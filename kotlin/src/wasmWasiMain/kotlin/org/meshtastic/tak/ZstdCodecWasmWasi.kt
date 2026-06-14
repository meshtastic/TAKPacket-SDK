package org.meshtastic.tak

import org.meshtastic.tak.internal.zstd.PureZstdDecoder

/**
 * Kotlin/Wasm-WASI [ZstdCodec] actual.
 *
 * WASI has no JavaScript host, Kotlin/Wasm has no cinterop, and there is no
 * native libzstd to link, so the usual codec backends are all unavailable here.
 * BUT the SDK ships a proven, pure-Kotlin, dictionary-aware zstd DECODER in
 * `commonMain` ([PureZstdDecoder]), which makes wasmWasi a real,
 * **decode-capable** target:
 *
 *  - [decompressWithDict] delegates to [PureZstdDecoder.decode], loading the
 *    embedded dictionary for [dictId]. No JS dependency, no cinterop.
 *  - [compressWithDict] throws [ZstdException] — there is no pure-Kotlin encoder
 *    yet (R14b is deferred) and no libzstd to call. wasmWasi consumers can parse,
 *    build, serialize, and DECOMPRESS, but not compress.
 *  - [release] is a no-op: the decoder holds no native handles or caches.
 *
 * The codec exchanges FULL standard zstd frames (magic included); [TakCompressor]
 * owns the magic strip/re-prepend and the `0xFF` skip-compress path above this
 * SPI, exactly as on every other target.
 */
internal actual object ZstdCodec {

    /**
     * Compression is unavailable on wasmWasi (no JS host, no cinterop, no
     * pure-Kotlin encoder). Always throws [ZstdException].
     */
    actual fun compressWithDict(data: ByteArray, dictId: Int, level: Int): ByteArray =
        throw ZstdException("zstd compression unavailable on wasmWasi")

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
