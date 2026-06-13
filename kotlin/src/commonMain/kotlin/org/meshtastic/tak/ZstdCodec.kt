package org.meshtastic.tak

/**
 * Internal SPI for dictionary-based zstd compression, satisfied by a per-target
 * `actual object`.
 *
 * The codec exchanges **full, standard zstd frames** — magic number included.
 * The wire-format optimizations ([TakCompressor] strips the 4-byte magic on
 * encode and re-prepends it on decode, plus the `0xFF` skip-compress path) live
 * ABOVE this SPI, in [TakCompressor], so every actual stays a plain zstd
 * codec and the framing logic exists in exactly one place.
 *
 * Actuals MUST replicate the historical JVM frame-field settings exactly to keep
 * the wire bytes byte-identical to the frozen goldens: `dictID`, `contentSize`,
 * and `checksum` are all OFF on compress.
 *
 * Failures are surfaced as [ZstdException].
 */
internal expect object ZstdCodec {
    /**
     * Compress [data] with the dictionary identified by [dictId] at the given
     * [level], returning a standard zstd frame (with magic).
     */
    fun compressWithDict(data: ByteArray, dictId: Int, level: Int = 19): ByteArray

    /**
     * Decompress a standard zstd frame [data] (with magic) using the dictionary
     * identified by [dictId], rejecting output larger than [maxSize] bytes.
     */
    fun decompressWithDict(data: ByteArray, dictId: Int, maxSize: Int): ByteArray

    /** Release any cached native/dictionary resources held by the codec. */
    fun release()
}
