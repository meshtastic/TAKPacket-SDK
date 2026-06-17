package org.meshtastic.tak

import org.meshtastic.tak.internal.zstd.MatchIndex
import org.meshtastic.tak.internal.zstd.PureZstdDecoder
import org.meshtastic.tak.internal.zstd.PureZstdEncoder
import org.meshtastic.tak.internal.zstd.ZstdDictionary

/**
 * Internal codec for dictionary-based zstd compression — ONE pure-Kotlin
 * implementation on every target (jvm, the 9 native targets, js, wasmJs,
 * wasmWasi).
 *
 * As of v0.6.0 there is no more `expect/actual` split and no per-target backend
 * (zstd-jni / cinterop libzstd / @bokuweb): every binding routes through the
 * validated pure-Kotlin [PureZstdEncoder] / [PureZstdDecoder] in
 * `internal.zstd`. This codec produces and reads **full, standard zstd frames**
 * (magic number included) that real libzstd — and therefore every other
 * language binding (Swift/Python/TypeScript/C#) — interoperates with in BOTH
 * directions:
 *  - our decoder reads real libzstd frames (proven by the golden decode), and
 *  - our encoder's frames are read by libzstd (proven by zstd-jni cross-decode).
 *
 * The wire-format optimizations live ABOVE this codec, in [TakCompressor] (it
 * strips the 4-byte zstd magic on encode and re-prepends it on decode, plus the
 * `0xFF` skip-compress path), so this object stays a plain zstd codec and the
 * framing logic exists in exactly one place. Failures surface as [ZstdException].
 */
internal object ZstdCodec {
    /**
     * Compress [data] with the dictionary identified by [dictId] at the given
     * [level], returning a standard zstd frame (with magic).
     */
    fun compressWithDict(data: ByteArray, dictId: Int, level: Int = 19): ByteArray {
        val dict = DictionaryProvider.getDictionary(dictId)
            ?: throw ZstdException("No dictionary for ID $dictId")
        return try {
            PureZstdEncoder.encode(data, dict, level)
        } catch (e: ZstdException) {
            throw e
        } catch (e: Exception) {
            throw ZstdException(
                "Zstd compression failed (dictId=$dictId, level=$level, size=${data.size}): " +
                    (e.message ?: e::class.simpleName ?: "unknown"),
                e,
            )
        }
    }

    /**
     * Decompress a standard zstd frame [data] (with magic) using the dictionary
     * identified by [dictId], rejecting output larger than [maxSize] bytes.
     */
    fun decompressWithDict(data: ByteArray, dictId: Int, maxSize: Int): ByteArray {
        val dict = DictionaryProvider.getDictionary(dictId)
            ?: throw ZstdException("No dictionary for ID $dictId")
        return try {
            PureZstdDecoder.decode(data, dict, maxSize)
        } catch (e: ZstdException) {
            throw e
        } catch (e: Exception) {
            throw ZstdException(
                "Zstd decompression failed (dictId=$dictId, compressedSize=${data.size}): " +
                    (e.message ?: e::class.simpleName ?: "unknown"),
                e,
            )
        }
    }

    /**
     * Drop the codec's parsed-dictionary and match-index caches. These hold ONLY
     * the static shipped dictionary's derived tables (zero cross-packet state),
     * so clearing them is purely an optimization reset — the next compress /
     * decompress transparently rebuilds them.
     */
    fun release() {
        ZstdDictionary.clearCache()
        MatchIndex.clearCache()
    }
}
