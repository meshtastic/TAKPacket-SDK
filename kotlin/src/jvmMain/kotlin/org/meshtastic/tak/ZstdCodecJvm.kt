package org.meshtastic.tak

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdCompressCtx
import com.github.luben.zstd.ZstdDictCompress
import com.github.luben.zstd.ZstdDictDecompress
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM [ZstdCodec] actual, backed by zstd-jni.
 *
 * Digested dictionaries ([ZstdDictCompress] / [ZstdDictDecompress]) are
 * expensive to build, so they are cached. The compress cache is keyed by
 * `dictId` AND `level` (a digested compression dictionary is level-specific);
 * the decompress cache is keyed by `dictId` alone.
 *
 * The compress path replicates the historical [TakCompressor] settings EXACTLY —
 * `ZstdCompressCtx` with `dictID` / `contentSize` / `checksum` all OFF and an
 * explicit `loadDict` — because any change to the frame fields would diverge from
 * the frozen wire goldens. This codec returns the FULL standard frame (magic
 * included); the magic-strip / re-prepend lives in [TakCompressor], above the SPI.
 */
internal actual object ZstdCodec {

    private val compressors = ConcurrentHashMap<Long, ZstdDictCompress>()
    private val decompressors = ConcurrentHashMap<Int, ZstdDictDecompress>()

    private fun compressDict(dictId: Int, level: Int): ZstdDictCompress {
        val key = (dictId.toLong() shl 32) or (level.toLong() and 0xFFFFFFFFL)
        return compressors.getOrPut(key) {
            val dict = DictionaryProvider.getDictionary(dictId)
                ?: throw ZstdException("No dictionary for ID $dictId")
            ZstdDictCompress(dict, level)
        }
    }

    private fun decompressDict(dictId: Int): ZstdDictDecompress =
        decompressors.getOrPut(dictId) {
            val dict = DictionaryProvider.getDictionary(dictId)
                ?: throw ZstdException("No dictionary for ID $dictId")
            ZstdDictDecompress(dict)
        }

    actual fun compressWithDict(data: ByteArray, dictId: Int, level: Int): ByteArray {
        val compressor = compressDict(dictId, level)
        return try {
            // Compress exactly one independent frame per call via the one-shot
            // context API (NEVER the streaming API) so every packet decodes in
            // isolation. dictID / contentSize / checksum are all OFF: the dict
            // ID already travels in TakCompressor's flags byte, and content-size
            // / checksum are dead weight on these tiny dict-compressed payloads.
            // This MUST match the historical call path byte-for-byte.
            ZstdCompressCtx().use { ctx ->
                ctx.setDictID(false)
                ctx.setContentSize(false)
                ctx.setChecksum(false)
                ctx.loadDict(compressor)
                ctx.compress(data)
            }
        } catch (e: Exception) {
            throw ZstdException(
                "Zstd compression failed (dictId=$dictId, level=$level, size=${data.size}): " +
                    (e.message ?: e::class.simpleName ?: "unknown"),
                e,
            )
        }
    }

    actual fun decompressWithDict(data: ByteArray, dictId: Int, maxSize: Int): ByteArray {
        val decompressor = decompressDict(dictId)
        return try {
            // Zstd.decompress with a size limit already guards the cap — a bomb
            // that expands past maxSize throws inside the zstd library.
            Zstd.decompress(data, decompressor, maxSize)
        } catch (e: Exception) {
            throw ZstdException(
                "Zstd decompression failed (dictId=$dictId, compressedSize=${data.size}): " +
                    (e.message ?: e::class.simpleName ?: "unknown"),
                e,
            )
        }
    }

    actual fun release() {
        compressors.clear()
        decompressors.clear()
    }
}
