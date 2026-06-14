package org.meshtastic.tak

import org.meshtastic.tak.internal.zstd.PureZstdDecoder

/**
 * Web ([js] + [wasmJs]) [ZstdCodec] actual, shared via the `jsCommonMain`
 * source set.
 *
 * The split of work across the SPI:
 *  - **decompress** → the proven pure-Kotlin [PureZstdDecoder]. No JS library is
 *    needed on either web target; the same decoder that validates every golden
 *    on the JVM runs here against the embedded dictionaries.
 *  - **compress** → @bokuweb/zstd-wasm (wasm-compiled libzstd, R5). The actual
 *    JS interop differs in ABI between js(IR) and wasmJs, so the narrow call is
 *    a leaf `internal expect fun` ([zstdCompressWithDictWeb]) whose `actual`s
 *    live in `jsMain` / `wasmJsMain`. All the shared logic (dict lookup, error
 *    wrapping) stays here.
 *
 * The compressor MUST produce a standard zstd frame (magic included) with
 * `dictID` / `contentSize` / `checksum` all OFF, at level 19, so [TakCompressor]
 * can strip the magic and the frame stays byte-compatible with the other
 * bindings' goldens (within the cross-binding size tolerance). See the R3 spike.
 *
 * Failures surface as [ZstdException].
 */
internal actual object ZstdCodec {

    actual fun compressWithDict(data: ByteArray, dictId: Int, level: Int): ByteArray {
        val dict = DictionaryProvider.getDictionary(dictId)
            ?: throw ZstdException("No dictionary for ID $dictId")
        return try {
            zstdCompressWithDictWeb(data, dict, level)
        } catch (e: Throwable) {
            throw ZstdException(
                "Zstd compression failed (dictId=$dictId, level=$level, size=${data.size}): " +
                    (e.message ?: "unknown"),
                e,
            )
        }
    }

    actual fun decompressWithDict(data: ByteArray, dictId: Int, maxSize: Int): ByteArray {
        val dict = DictionaryProvider.getDictionary(dictId)
            ?: throw ZstdException("No dictionary for ID $dictId")
        return try {
            PureZstdDecoder.decode(data, dict, maxSize)
        } catch (e: Throwable) {
            throw ZstdException(
                "Zstd decompression failed (dictId=$dictId, compressedSize=${data.size}): " +
                    (e.message ?: "unknown"),
                e,
            )
        }
    }

    /** No persistent native/JS handles to release (the wasm lib is module-cached). */
    actual fun release() {
        // No-op.
    }
}

/**
 * Leaf-provided bridge to the wasm-compiled libzstd compressor. Splits per web
 * target because the `external`/JS-interop ABI is not shared between js(IR) and
 * wasmJs. Returns a standard zstd frame (magic included) compressed with [dict]
 * at [level], with `dictID` / `contentSize` / `checksum` all OFF.
 */
internal expect fun zstdCompressWithDictWeb(data: ByteArray, dict: ByteArray, level: Int): ByteArray
