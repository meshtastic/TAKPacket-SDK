package org.meshtastic.tak

/**
 * Platform-specific Zstd dictionary compression/decompression.
 *
 * JVM: uses zstd-jni.
 * iOS: not yet implemented (requires native zstd C interop).
 */
expect object ZstdCodec {
    /**
     * Compress [data] using zstd with the dictionary identified by [dictId].
     * @param level compression level (1-22, default 19)
     */
    fun compressWithDict(data: ByteArray, dictId: Int, level: Int = 19): ByteArray

    /**
     * Decompress [data] using zstd with the dictionary identified by [dictId].
     * @param maxSize maximum allowed decompressed size (prevents decompression bombs)
     */
    fun decompressWithDict(data: ByteArray, dictId: Int, maxSize: Int): ByteArray
}
