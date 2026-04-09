package org.meshtastic.tak

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDictCompress
import com.github.luben.zstd.ZstdDictDecompress

/**
 * JVM implementation of [ZstdCodec] using zstd-jni.
 */
actual object ZstdCodec {
    private val compressors = mutableMapOf<Int, ZstdDictCompress>()
    private val decompressors = mutableMapOf<Int, ZstdDictDecompress>()

    private fun ensureDict(dictId: Int, level: Int) {
        if (dictId !in compressors) {
            val dictBytes = DictionaryProvider.getDictionary(dictId)
                ?: throw IllegalArgumentException("Unknown dictionary ID: $dictId")
            compressors[dictId] = ZstdDictCompress(dictBytes, level)
            decompressors[dictId] = ZstdDictDecompress(dictBytes)
        }
    }

    actual fun compressWithDict(data: ByteArray, dictId: Int, level: Int): ByteArray {
        ensureDict(dictId, level)
        return Zstd.compress(data, compressors[dictId]!!)
    }

    actual fun decompressWithDict(data: ByteArray, dictId: Int, maxSize: Int): ByteArray {
        ensureDict(dictId, 19) // level only matters for compression, but needed for init
        return Zstd.decompress(data, decompressors[dictId]!!, maxSize)
    }
}
