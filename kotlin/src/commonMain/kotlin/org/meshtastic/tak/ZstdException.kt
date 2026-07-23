package org.meshtastic.tak

/**
 * Thrown when the underlying zstd codec fails to compress or decompress a frame.
 *
 * The pure-Kotlin [ZstdCodec] wraps any lower-level failure (a malformed frame,
 * a missing dictionary, etc.) in this single, platform-independent type so
 * callers can catch one exception on every target. The original cause, when
 * available, is preserved in [cause].
 */
public class ZstdException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
