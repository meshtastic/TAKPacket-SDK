package org.meshtastic.tak

/**
 * iOS stub for [ZstdCodec].
 *
 * TODO: Implement using Kotlin/Native C interop with the zstd C library.
 * See ZSTD_compress_usingCDict() and ZSTD_decompress_usingDDict() from zstd.h.
 */
actual object ZstdCodec {
    actual fun compressWithDict(data: ByteArray, dictId: Int, level: Int): ByteArray {
        throw UnsupportedOperationException(
            "Zstd compression is not yet implemented for iOS. " +
                "Requires native zstd C library via kotlinx.cinterop."
        )
    }

    actual fun decompressWithDict(data: ByteArray, dictId: Int, maxSize: Int): ByteArray {
        throw UnsupportedOperationException(
            "Zstd decompression is not yet implemented for iOS. " +
                "Requires native zstd C library via kotlinx.cinterop."
        )
    }
}
