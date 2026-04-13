package org.meshtastic.tak

/**
 * Platform-specific Zstd dictionary compression/decompression.
 *
 * JVM: uses zstd-jni.
 * iOS: uses native zstd C interop with libzstd.
 */
public expect object ZstdCodec {
    /**
     * Compress [data] using zstd with the dictionary identified by [dictId].
     * @param level compression level (1-22, default 19)
     */
    public fun compressWithDict(data: ByteArray, dictId: Int, level: Int = 19): ByteArray

    /**
     * Decompress [data] using zstd with the dictionary identified by [dictId].
     * @param maxSize maximum allowed decompressed size (prevents decompression bombs)
     */
    public fun decompressWithDict(data: ByteArray, dictId: Int, maxSize: Int): ByteArray

    /**
     * Release all cached native resources (contexts and dictionaries).
     * On JVM this is a no-op (zstd-jni manages lifecycle internally).
     * On iOS this frees C-level ZSTD contexts and dictionaries.
     */
    public fun release()
}
