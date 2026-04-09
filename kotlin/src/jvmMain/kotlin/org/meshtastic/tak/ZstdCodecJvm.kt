package org.meshtastic.tak

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDictCompress
import com.github.luben.zstd.ZstdDictDecompress

/**
 * JVM implementation of [ZstdCodec] using zstd-jni.
 */
actual object ZstdCodec {
    private val compressors = mutableMapOf<Pair<Int, Int>, ZstdDictCompress>()
    private val decompressors = mutableMapOf<Int, ZstdDictDecompress>()

    private fun ensureCompressor(dictId: Int, level: Int) {
        val key = dictId to level
        if (key !in compressors) {
            val dictBytes = DictionaryProvider.getDictionary(dictId)
                ?: throw IllegalArgumentException("Unknown dictionary ID: $dictId")
            compressors[key] = ZstdDictCompress(dictBytes, level)
        }
    }

    private fun ensureDecompressor(dictId: Int) {
        if (dictId !in decompressors) {
            val dictBytes = DictionaryProvider.getDictionary(dictId)
                ?: throw IllegalArgumentException("Unknown dictionary ID: $dictId")
            decompressors[dictId] = ZstdDictDecompress(dictBytes)
        }
    }

    actual fun compressWithDict(data: ByteArray, dictId: Int, level: Int): ByteArray {
        ensureCompressor(dictId, level)
        return Zstd.compress(data, compressors[dictId to level]!!)
    }

    actual fun decompressWithDict(data: ByteArray, dictId: Int, maxSize: Int): ByteArray {
        ensureDecompressor(dictId)
        return Zstd.decompress(data, decompressors[dictId]!!, maxSize)
    }
}
