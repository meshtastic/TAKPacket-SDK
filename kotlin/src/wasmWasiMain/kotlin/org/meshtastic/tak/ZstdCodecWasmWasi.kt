package org.meshtastic.tak

import org.meshtastic.tak.internal.zstd.PureZstdDecoder
import org.meshtastic.tak.internal.zstd.PureZstdEncoder

/**
 * Kotlin/Wasm-WASI [ZstdCodec] actual.
 *
 * WASI has no JavaScript host, Kotlin/Wasm has no cinterop, and there is no
 * native libzstd to link, so the usual codec backends are all unavailable here.
 * BUT the SDK ships a proven, pure-Kotlin, dictionary-aware zstd codec in
 * `commonMain` ([PureZstdDecoder] + [PureZstdEncoder]), which makes wasmWasi a
 * fully **compress- AND decode-capable** target with zero native/JS deps:
 *
 *  - [compressWithDict] delegates to [PureZstdEncoder.encode] (R14b Phase 1).
 *    Its frames are decodable by real libzstd (and therefore by every other
 *    binding) and stay within the cross-binding size tolerance / 237 B MTU.
 *  - [decompressWithDict] delegates to [PureZstdDecoder.decode], loading the
 *    embedded dictionary for [dictId]. No JS dependency, no cinterop.
 *  - [release] is a no-op: the codec holds no native handles or caches.
 *
 * The codec exchanges FULL standard zstd frames (magic included); [TakCompressor]
 * owns the magic strip/re-prepend and the `0xFF` skip-compress path above this
 * SPI, exactly as on every other target.
 */
internal actual object ZstdCodec {

    /**
     * Compress a standard zstd frame (magic included) from [data] with the
     * dictionary identified by [dictId], via the pure-Kotlin [PureZstdEncoder].
     */
    actual fun compressWithDict(data: ByteArray, dictId: Int, level: Int): ByteArray {
        val dict = DictionaryProvider.getDictionary(dictId)
            ?: throw ZstdException("No dictionary for ID $dictId")
        return try {
            PureZstdEncoder.encode(data, dict, level)
        } catch (e: Exception) {
            throw ZstdException(
                "Zstd compression failed (dictId=$dictId, inputSize=${data.size}): " +
                    (e.message ?: e::class.simpleName ?: "unknown"),
                e,
            )
        }
    }

    /**
     * Decompress a standard zstd frame [data] (magic included) with the
     * dictionary identified by [dictId], via the pure-Kotlin [PureZstdDecoder].
     */
    actual fun decompressWithDict(data: ByteArray, dictId: Int, maxSize: Int): ByteArray {
        val dict = DictionaryProvider.getDictionary(dictId)
            ?: throw ZstdException("No dictionary for ID $dictId")
        return try {
            PureZstdDecoder.decode(data, dict, maxSize)
        } catch (e: Exception) {
            throw ZstdException(
                "Zstd decompression failed (dictId=$dictId, compressedSize=${data.size}): " +
                    (e.message ?: e::class.simpleName ?: "unknown"),
                e,
            )
        }
    }

    /** No cached native/JS resources to release. */
    actual fun release() {
        // No-op: PureZstdDecoder is stateless and holds no handles.
    }
}
