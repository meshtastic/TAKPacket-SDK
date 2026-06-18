package org.meshtastic.tak

import kotlin.concurrent.Volatile
import org.meshtastic.kzstd.Zstd
import org.meshtastic.kzstd.ZstdDictionary as KzstdDictionary
import org.meshtastic.kzstd.ZstdException as KzstdException

/**
 * Internal codec for dictionary-based zstd compression — a thin adapter over the
 * standalone pure-Kotlin [org.meshtastic.kzstd] library, which runs on every
 * target (jvm, the 9 native targets, js, wasmJs, wasmWasi).
 *
 * The engine was extracted from this SDK's former `internal.zstd` package into
 * kzstd so it lives and is tested in exactly one place (killing the shared-lineage
 * drift between two near-identical copies). kzstd produces and reads **full,
 * standard zstd frames** (magic number included) that real libzstd — and therefore
 * every other language binding (Swift/Python/TypeScript/C#) — interoperates with
 * in BOTH directions; that interop gate is proven by kzstd's own test suite.
 *
 * This adapter adds the two things kzstd deliberately leaves to its host:
 *  - it digests each shipped dictionary into a reusable [KzstdDictionary] ONCE and
 *    holds it (kzstd's dictionary is immutable, so two `@Volatile` holders give
 *    safe publication lock-free; a benign double-build race only wastes work), and
 *  - it normalizes kzstd's [KzstdException] into this SDK's public [ZstdException]
 *    so callers catch one type.
 *
 * The wire-format optimizations live ABOVE this codec, in [TakCompressor] (it
 * strips the 4-byte zstd magic on encode and re-prepends it on decode, plus the
 * `0xFF` skip-compress path), so this object stays a plain zstd codec and the
 * framing logic exists in exactly one place.
 */
internal object ZstdCodec {

    // Digested dictionaries (parsed entropy tables + match index), built once per
    // dict ID from the static shipped bytes and reused for every packet. Holding
    // them is the SDK's only "cache" — it carries ZERO cross-packet state, so
    // [release] can drop it at any time and the next call transparently rebuilds.
    @Volatile private var nonAircraftDigest: KzstdDictionary? = null

    @Volatile private var aircraftDigest: KzstdDictionary? = null

    /**
     * Compress [data] with the dictionary identified by [dictId] at the given
     * [level], returning a standard zstd frame (with magic).
     */
    fun compressWithDict(data: ByteArray, dictId: Int, level: Int = Zstd.DEFAULT_LEVEL): ByteArray =
        try {
            Zstd.compress(data, digestFor(dictId), level)
        } catch (e: ZstdException) {
            throw e
        } catch (e: Exception) {
            throw ZstdException(
                "Zstd compression failed (dictId=$dictId, level=$level, size=${data.size}): " +
                    (e.message ?: e::class.simpleName ?: "unknown"),
                e,
            )
        }

    /**
     * Decompress a standard zstd frame [data] (with magic) using the dictionary
     * identified by [dictId], rejecting output larger than [maxSize] bytes.
     */
    fun decompressWithDict(data: ByteArray, dictId: Int, maxSize: Int): ByteArray =
        try {
            Zstd.decompress(data, digestFor(dictId), maxSize)
        } catch (e: ZstdException) {
            throw e
        } catch (e: Exception) {
            throw ZstdException(
                "Zstd decompression failed (dictId=$dictId, compressedSize=${data.size}): " +
                    (e.message ?: e::class.simpleName ?: "unknown"),
                e,
            )
        }

    /**
     * Drop the held digested dictionaries. They hold ONLY the static shipped
     * dictionary's derived tables (zero cross-packet state), so clearing them is
     * purely an optimization reset — the next compress / decompress rebuilds them.
     */
    fun release() {
        nonAircraftDigest = null
        aircraftDigest = null
    }

    /** Return the digested dictionary for [dictId], building and caching it on first use. */
    private fun digestFor(dictId: Int): KzstdDictionary = when (dictId) {
        DictionaryProvider.DICT_ID_NON_AIRCRAFT ->
            nonAircraftDigest ?: buildDigest(dictId).also { nonAircraftDigest = it }
        DictionaryProvider.DICT_ID_AIRCRAFT ->
            aircraftDigest ?: buildDigest(dictId).also { aircraftDigest = it }
        else -> throw ZstdException("No dictionary for ID $dictId")
    }

    private fun buildDigest(dictId: Int): KzstdDictionary {
        val bytes = DictionaryProvider.getDictionary(dictId)
            ?: throw ZstdException("No dictionary for ID $dictId")
        return KzstdDictionary(bytes)
    }
}
