package org.meshtastic.tak

/**
 * Thrown when the underlying zstd codec fails to compress or decompress a frame.
 *
 * Per-target [ZstdCodec] actuals wrap their platform-specific failures (zstd-jni
 * exceptions on JVM, error codes on native, etc.) in this common type so callers
 * can catch a single, platform-independent exception. The original cause, when
 * available, is preserved in [cause].
 */
public class ZstdException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
