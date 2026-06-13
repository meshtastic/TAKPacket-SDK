package org.meshtastic.tak

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import zstd.ZSTD_CCtx
import zstd.ZSTD_CDict
import zstd.ZSTD_DCtx
import zstd.ZSTD_DDict
import zstd.ZSTD_compressBound
import zstd.ZSTD_compress_usingCDict
import zstd.ZSTD_createCCtx
import zstd.ZSTD_createCDict
import zstd.ZSTD_createDCtx
import zstd.ZSTD_createDDict
import zstd.ZSTD_decompress_usingDDict
import zstd.ZSTD_freeCCtx
import zstd.ZSTD_freeCDict
import zstd.ZSTD_freeDCtx
import zstd.ZSTD_freeDDict
import zstd.ZSTD_getErrorName
import zstd.ZSTD_isError

/**
 * Kotlin/Native [ZstdCodec] actual, shared by ALL nine native targets
 * (iosArm64, iosSimulatorArm64, iosX64, macosArm64, tvosArm64,
 * tvosSimulatorArm64, linuxX64, linuxArm64, mingwX64) via `nativeMain`.
 *
 * Backed by libzstd through cinterop (`zstd.def`, statically linked per R6 so
 * consumer klibs are self-contained). Uses the dictionary-digest one-shot API
 * — `ZSTD_compress_usingCDict` / `ZSTD_decompress_usingDDict` — so every packet
 * is an independent frame with ZERO cross-packet state (the resilience
 * invariant). The streaming API is never used.
 *
 * Digested dictionaries are expensive to build, so [ZSTD_CDict] is cached per
 * `(dictId, level)` and [ZSTD_DDict] per `dictId`, mirroring the JVM actual.
 *
 * The codec exchanges FULL standard zstd frames (magic included). The wire
 * optimizations (magic strip / re-prepend, `0xFF` skip-compress) live above this
 * SPI in [TakCompressor]; frame fields stay at libzstd defaults for these
 * `usingCDict` calls (`dictID` is not embedded because the dict travels in
 * TakCompressor's flags byte). Failures surface as [ZstdException].
 *
 * All mutable state is guarded by [lock] (an atomicfu [SynchronizedObject],
 * which compiles on every native target — unlike Apple-only `NSLock`) so the
 * singleton is safe under concurrent `TakCompressor` use.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object ZstdCodec {
    private val lock = SynchronizedObject()
    private var cCtx: CPointer<ZSTD_CCtx>? = null
    private var dCtx: CPointer<ZSTD_DCtx>? = null
    private val cDicts = mutableMapOf<Pair<Int, Int>, CPointer<ZSTD_CDict>>()
    private val dDicts = mutableMapOf<Int, CPointer<ZSTD_DDict>>()

    private fun getOrCreateCCtx(): CPointer<ZSTD_CCtx> =
        cCtx ?: (ZSTD_createCCtx() ?: throw ZstdException("Failed to create ZSTD_CCtx")).also { cCtx = it }

    private fun getOrCreateDCtx(): CPointer<ZSTD_DCtx> =
        dCtx ?: (ZSTD_createDCtx() ?: throw ZstdException("Failed to create ZSTD_DCtx")).also { dCtx = it }

    private fun getOrCreateCDict(dictId: Int, level: Int): CPointer<ZSTD_CDict> =
        cDicts.getOrPut(dictId to level) {
            val dictBytes = DictionaryProvider.getDictionary(dictId)
                ?: throw ZstdException("No dictionary for ID $dictId")
            dictBytes.usePinned { pinned ->
                ZSTD_createCDict(
                    pinned.addressOf(0),
                    dictBytes.size.toULong(),
                    level,
                ) ?: throw ZstdException("Failed to create ZSTD_CDict for dictId=$dictId level=$level")
            }
        }

    private fun getOrCreateDDict(dictId: Int): CPointer<ZSTD_DDict> =
        dDicts.getOrPut(dictId) {
            val dictBytes = DictionaryProvider.getDictionary(dictId)
                ?: throw ZstdException("No dictionary for ID $dictId")
            dictBytes.usePinned { pinned ->
                ZSTD_createDDict(
                    pinned.addressOf(0),
                    dictBytes.size.toULong(),
                ) ?: throw ZstdException("Failed to create ZSTD_DDict for dictId=$dictId")
            }
        }

    actual fun compressWithDict(data: ByteArray, dictId: Int, level: Int): ByteArray = synchronized(lock) {
        val ctx = getOrCreateCCtx()
        val cDict = getOrCreateCDict(dictId, level)
        val maxSize = ZSTD_compressBound(data.size.toULong())
        val destBuffer = ByteArray(maxSize.toInt())

        // ByteArray of size 0 has no addressOf(0); compressBound is always >= 1
        // so destBuffer is non-empty, but an empty `data` still needs a valid
        // (unused) source pointer — pin a 1-byte scratch in that case.
        val compressedSize = destBuffer.usePinned { destPin ->
            if (data.isEmpty()) {
                ZSTD_compress_usingCDict(
                    ctx,
                    destPin.addressOf(0),
                    maxSize,
                    null,
                    0u,
                    cDict,
                )
            } else {
                data.usePinned { srcPin ->
                    ZSTD_compress_usingCDict(
                        ctx,
                        destPin.addressOf(0),
                        maxSize,
                        srcPin.addressOf(0),
                        data.size.toULong(),
                        cDict,
                    )
                }
            }
        }

        if (ZSTD_isError(compressedSize) != 0u) {
            val errorName = ZSTD_getErrorName(compressedSize)?.toKString() ?: "unknown error"
            throw ZstdException(
                "Zstd compression failed (dictId=$dictId, level=$level, size=${data.size}): $errorName",
            )
        }

        destBuffer.copyOf(compressedSize.toInt())
    }

    actual fun decompressWithDict(data: ByteArray, dictId: Int, maxSize: Int): ByteArray = synchronized(lock) {
        val ctx = getOrCreateDCtx()
        val dDict = getOrCreateDDict(dictId)
        val destBuffer = ByteArray(maxSize)

        val decompressedSize = destBuffer.usePinned { destPin ->
            data.usePinned { srcPin ->
                ZSTD_decompress_usingDDict(
                    ctx,
                    destPin.addressOf(0),
                    maxSize.toULong(),
                    srcPin.addressOf(0),
                    data.size.toULong(),
                    dDict,
                )
            }
        }

        if (ZSTD_isError(decompressedSize) != 0u) {
            val errorName = ZSTD_getErrorName(decompressedSize)?.toKString() ?: "unknown error"
            throw ZstdException(
                "Zstd decompression failed (dictId=$dictId, compressedSize=${data.size}): $errorName",
            )
        }

        destBuffer.copyOf(decompressedSize.toInt())
    }

    actual fun release(): Unit = synchronized(lock) {
        cCtx?.let { ZSTD_freeCCtx(it) }
        cCtx = null
        dCtx?.let { ZSTD_freeDCtx(it) }
        dCtx = null
        cDicts.values.forEach { ZSTD_freeCDict(it) }
        cDicts.clear()
        dDicts.values.forEach { ZSTD_freeDDict(it) }
        dDicts.clear()
    }
}
