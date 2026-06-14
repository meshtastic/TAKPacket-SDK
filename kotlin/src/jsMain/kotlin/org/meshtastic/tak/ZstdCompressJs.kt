@file:Suppress("unused")

package org.meshtastic.tak

import org.khronos.webgl.Uint8Array
import org.khronos.webgl.get

/**
 * js(IR) leaf actual for the web ZstdCodec compress bridge.
 *
 * Binds to `@bokuweb/zstd-wasm` (wasm-compiled libzstd, R5) via `@JsModule`
 * `external` declarations and compresses one independent frame per call with
 * `compressUsingDict(cctx, data, dict, level)` — a one-shot, dictionary-based
 * call (NEVER streaming), so the resilience invariant holds.
 *
 * The R3 spike proved these frames decode cross-binding (zstd-jni AND
 * PureZstdDecoder) to byte-identical proto bytes and stay within the
 * cross-binding size tolerance. NOTE (documented in the spike README): this
 * library's simple API cannot suppress dictID/contentSize/checksum, so the frame
 * embeds a dictID and is ~4 bytes larger than the JVM/native goldens — allowed,
 * because the SDK asserts decodability + size tolerance, not byte-identity.
 *
 * IMPORTANT — async wasm init: `@bokuweb/zstd-wasm` loads its wasm module
 * asynchronously and must be initialized before any compress call. The SDK's
 * compress SPI is synchronous, so the host application MUST `await` the
 * library's `init()` once at startup (e.g. before first `TakCompressor.compress`)
 * — see [ensureZstdWasmInitialized]. The `external` here is the synchronous call
 * path used after init has completed.
 */
@JsModule("@bokuweb/zstd-wasm")
@JsNonModule
private external object ZstdWasm {
    fun init(): kotlin.js.Promise<Unit>
    fun createCCtx(): Int
    fun freeCCtx(cctx: Int)
    fun compressUsingDict(cctx: Int, buf: Uint8Array, dict: Uint8Array, level: Int): Uint8Array
}

private fun ByteArray.toUint8Array(): Uint8Array {
    val arr = Uint8Array(size)
    for (i in indices) arr.asDynamic()[i] = this[i]
    return arr
}

private fun Uint8Array.toByteArray(): ByteArray {
    val out = ByteArray(length)
    for (i in 0 until length) out[i] = this[i]
    return out
}

internal actual fun zstdCompressWithDictWeb(data: ByteArray, dict: ByteArray, level: Int): ByteArray {
    val cctx = ZstdWasm.createCCtx()
    try {
        val frame = ZstdWasm.compressUsingDict(cctx, data.toUint8Array(), dict.toUint8Array(), level)
        return frame.toByteArray()
    } finally {
        ZstdWasm.freeCCtx(cctx)
    }
}

/**
 * Await the one-time async wasm-module initialization of `@bokuweb/zstd-wasm`.
 * Host apps that compress on js MUST call this (and let the returned Promise
 * settle) before the first synchronous [zstdCompressWithDictWeb] / compress.
 * Decompress does not need it (it uses the pure-Kotlin decoder).
 */
public fun ensureZstdWasmInitialized(): kotlin.js.Promise<Unit> = ZstdWasm.init()
