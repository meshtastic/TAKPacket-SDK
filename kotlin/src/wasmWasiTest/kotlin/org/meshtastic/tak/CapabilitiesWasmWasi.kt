package org.meshtastic.tak

/** Compression throws [ZstdException] on wasmWasi (no encoder), so the compress
 *  half of the pipeline is skipped here; decompress (pure-Kotlin decoder) runs. */
internal actual val zstdCanCompress: Boolean = false
