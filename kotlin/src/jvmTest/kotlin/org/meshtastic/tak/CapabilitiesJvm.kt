package org.meshtastic.tak

/** zstd-jni compresses synchronously on the JVM. */
internal actual val zstdCanCompress: Boolean = true
