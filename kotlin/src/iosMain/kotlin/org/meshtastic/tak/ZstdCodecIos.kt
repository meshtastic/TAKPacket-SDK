package org.meshtastic.tak

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
import zstd.ZSTD_getErrorName
import zstd.ZSTD_isError

/**
 * iOS implementation of [ZstdCodec] using Kotlin/Native C interop with libzstd.
 *
 * Uses ZSTD_compress_usingCDict / ZSTD_decompress_usingDDict for dictionary-based
 * compression and decompression. Contexts and dictionaries are lazily created and cached.
 *
 * The consuming iOS app must link against libzstd (e.g. via CocoaPods, SPM, or vendored xcframework).
 */
@OptIn(ExperimentalForeignApi::class)
actual object ZstdCodec {
    private var cCtx: CPointer<ZSTD_CCtx>? = null
    private var dCtx: CPointer<ZSTD_DCtx>? = null
    private val cDicts = mutableMapOf<Int, CPointer<ZSTD_CDict>>()
    private val dDicts = mutableMapOf<Int, CPointer<ZSTD_DDict>>()

    private fun getOrCreateCCtx(): CPointer<ZSTD_CCtx> =
        cCtx ?: (ZSTD_createCCtx() ?: error("Failed to create ZSTD_CCtx")).also { cCtx = it }

    private fun getOrCreateDCtx(): CPointer<ZSTD_DCtx> =
        dCtx ?: (ZSTD_createDCtx() ?: error("Failed to create ZSTD_DCtx")).also { dCtx = it }

    private fun getOrCreateCDict(dictId: Int, level: Int): CPointer<ZSTD_CDict> =
        cDicts.getOrPut(dictId) {
            val dictBytes = DictionaryProvider.getDictionary(dictId)
                ?: throw IllegalArgumentException("Unknown dictionary ID: $dictId")
            dictBytes.usePinned { pinned ->
                ZSTD_createCDict(
                    pinned.addressOf(0),
                    dictBytes.size.toULong(),
                    level,
                ) ?: error("Failed to create ZSTD_CDict for dictId=$dictId")
            }
        }

    private fun getOrCreateDDict(dictId: Int): CPointer<ZSTD_DDict> =
        dDicts.getOrPut(dictId) {
            val dictBytes = DictionaryProvider.getDictionary(dictId)
                ?: throw IllegalArgumentException("Unknown dictionary ID: $dictId")
            dictBytes.usePinned { pinned ->
                ZSTD_createDDict(
                    pinned.addressOf(0),
                    dictBytes.size.toULong(),
                ) ?: error("Failed to create ZSTD_DDict for dictId=$dictId")
            }
        }

    actual fun compressWithDict(data: ByteArray, dictId: Int, level: Int): ByteArray {
        val ctx = getOrCreateCCtx()
        val cDict = getOrCreateCDict(dictId, level)
        val maxSize = ZSTD_compressBound(data.size.toULong())
        val destBuffer = ByteArray(maxSize.toInt())

        val compressedSize = destBuffer.usePinned { destPin ->
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

        if (ZSTD_isError(compressedSize) != 0u) {
            val errorName = ZSTD_getErrorName(compressedSize)?.toKString() ?: "unknown error"
            error("Zstd compression failed: $errorName")
        }

        return destBuffer.copyOf(compressedSize.toInt())
    }

    actual fun decompressWithDict(data: ByteArray, dictId: Int, maxSize: Int): ByteArray {
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
            error("Zstd decompression failed: $errorName")
        }

        return destBuffer.copyOf(decompressedSize.toInt())
    }
}
