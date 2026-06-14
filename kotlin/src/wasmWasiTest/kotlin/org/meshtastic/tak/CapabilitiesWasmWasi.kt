package org.meshtastic.tak

/** wasmWasi compresses via the pure-Kotlin [org.meshtastic.tak.internal.zstd.PureZstdEncoder]
 *  (R14b) and decompresses via the pure-Kotlin decoder, so the full
 *  compress→decompress pipeline runs here. */
internal actual val zstdCanCompress: Boolean = true
