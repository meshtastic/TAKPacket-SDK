package org.meshtastic.tak

/**
 * Compression on js + wasmJs needs an async `@bokuweb/zstd-wasm` `init()` that a
 * synchronous `kotlin.test` body can't await, so the compress half of the
 * pipeline is exercised by the JVM/native suites + the R3 spike, not here.
 * Decompress (pure-Kotlin decoder) still runs in the common decode suites.
 */
internal actual val zstdCanCompress: Boolean = false
