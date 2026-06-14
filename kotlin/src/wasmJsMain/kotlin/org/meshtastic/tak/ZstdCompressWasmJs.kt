@file:Suppress("unused")
@file:OptIn(kotlin.js.ExperimentalWasmJsInterop::class)

package org.meshtastic.tak

import kotlin.js.Promise

/**
 * wasmJs leaf actual for the web ZstdCodec compress bridge.
 *
 * Mirrors the js(IR) leaf but uses Kotlin/Wasm's JS-interop ABI, which is NOT
 * shared with js(IR) — hence the split. Two differences drive the split:
 *  - Kotlin/Wasm has no `org.khronos.webgl.Uint8Array` (that package is a
 *    JS-IR-only binding); JS typed arrays cross the wasm boundary as opaque
 *    [JsAny] references, converted via small `js("…")` glue functions.
 *  - `@JsModule` externs in Wasm don't take `@JsNonModule`.
 *
 * Binds to `@bokuweb/zstd-wasm` (wasm-compiled libzstd, R5) and compresses one
 * independent frame per call via `compressUsingDict` (one-shot, never streaming).
 *
 * See the js(IR) leaf and the R3 spike README for the cross-binding decode
 * proof, the documented dictID/size delta, and the async-init requirement
 * ([ensureZstdWasmInitialized] must be awaited before the first synchronous
 * compress).
 */
@JsModule("@bokuweb/zstd-wasm")
private external object ZstdWasm {
    fun init(): Promise<JsAny?>
    fun createCCtx(): Int
    fun freeCCtx(cctx: Int)
    fun compressUsingDict(cctx: Int, buf: JsAny, dict: JsAny, level: Int): JsAny
}

// JS glue: build a Uint8Array of length `len`, set one byte, read one byte, read
// length. ByteArray bytes are signed; mask to 0..255 when writing and re-sign
// (via toByte()) when reading. Indexing a Uint8Array in JS is trivial, so the
// per-element work stays on the JS side of the boundary.
private fun jsNewUint8Array(len: Int): JsAny = js("new Uint8Array(len)")
private fun jsSetByte(arr: JsAny, index: Int, value: Int) { js("arr[index] = value") }
private fun jsGetByte(arr: JsAny, index: Int): Int = js("arr[index]")
private fun jsLength(arr: JsAny): Int = js("arr.length")

private fun ByteArray.toUint8Array(): JsAny {
    val arr = jsNewUint8Array(size)
    for (i in indices) jsSetByte(arr, i, this[i].toInt() and 0xFF)
    return arr
}

private fun JsAny.toByteArray(): ByteArray {
    val n = jsLength(this)
    val out = ByteArray(n)
    for (i in 0 until n) out[i] = jsGetByte(this, i).toByte()
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
 * Host apps that compress on wasmJs MUST let the returned Promise settle before
 * the first synchronous compress. Decompress does not need it (pure-Kotlin
 * decoder).
 */
public fun ensureZstdWasmInitialized(): Promise<JsAny?> = ZstdWasm.init()
