package org.meshtastic.tak

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDictCompress
import com.github.luben.zstd.ZstdDictDecompress
import java.util.concurrent.ConcurrentHashMap

/**
 * JVM implementation of [ZstdCodec] using zstd-jni.
 *
 * Compressor and decompressor caches are thread-safe ([ConcurrentHashMap])
 * so the singleton can be used safely from multiple threads.
 */
internal actual object ZstdCodec {
    private val compressors = ConcurrentHashMap<Pair<Int, Int>, ZstdDictCompress>()
    private val decompressors = ConcurrentHashMap<Int, ZstdDictDecompress>()

    private fun ensureCompressor(dictId: Int, level: Int): ZstdDictCompress =
        compressors.computeIfAbsent(dictId to level) {
            ZstdDictCompress(DictionaryProvider.getDictionary(dictId), level)
        }

    private fun ensureDecompressor(dictId: Int): ZstdDictDecompress =
        decompressors.computeIfAbsent(dictId) {
            ZstdDictDecompress(DictionaryProvider.getDictionary(dictId))
        }

    actual fun compressWithDict(data: ByteArray, dictId: Int, level: Int): ByteArray {
        return Zstd.compress(data, ensureCompressor(dictId, level))
    }

    actual fun decompressWithDict(data: ByteArray, dictId: Int, maxSize: Int): ByteArray {
        return Zstd.decompress(data, ensureDecompressor(dictId), maxSize)
    }

    /**
     * Release all cached zstd-jni compressor and decompressor instances,
     * freeing the underlying native memory. The codec remains usable after
     * this call — new instances are lazily created on the next compress or
     * decompress operation.
     */
    actual fun release() {
        compressors.values.forEach { it.close() }
        compressors.clear()
        decompressors.values.forEach { it.close() }
        decompressors.clear()
    }
}
