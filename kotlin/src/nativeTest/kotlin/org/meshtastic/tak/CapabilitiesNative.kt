package org.meshtastic.tak

/** cinterop libzstd compresses synchronously on every native target. */
internal actual val zstdCanCompress: Boolean = true
