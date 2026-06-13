package org.meshtastic.tak.internal.zstd

/**
 * Thrown by the pure-Kotlin zstd decoder when the input is malformed, uses an
 * unsupported feature, or would exceed a safety bound (e.g. the decompressed
 * size cap). Kept distinct from the public `ZstdException` so [PureZstdDecoder]
 * stays self-contained in `internal.zstd` with no dependency on the SDK's outer
 * codec types.
 */
internal class ZstdFormatException(message: String, cause: Throwable? = null) :
    RuntimeException(message, cause)
