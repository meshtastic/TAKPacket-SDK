package org.meshtastic.tak

/**
 * Whether this target's [ZstdCodec] can COMPRESS synchronously inside a test.
 *
 * The common cross-platform suites need to know which targets can run the
 * compress half of the pipeline:
 *
 *  - **jvm + the 9 native targets** → `true`. zstd-jni / cinterop libzstd
 *    compress synchronously.
 *  - **js + wasmJs** → `false`. Compression goes through `@bokuweb/zstd-wasm`,
 *    whose wasm module must be `init()`-ed **asynchronously** before the first
 *    synchronous compress (see `ensureZstdWasmInitialized`). A `kotlin.test`
 *    body can't `await` that, so the compress path is exercised by the
 *    JVM/native suites + the dedicated R3 spike instead of here.
 *  - **wasmWasi** → `false`. Compression throws [ZstdException] unconditionally
 *    (no JS host, no cinterop, no pure-Kotlin encoder).
 *
 * DECOMPRESS is available on **every** target (the pure-Kotlin
 * [org.meshtastic.tak.internal.zstd.PureZstdDecoder] backs js/wasmJs/wasmWasi),
 * so the decode-side suites run everywhere unconditionally.
 */
internal expect val zstdCanCompress: Boolean
