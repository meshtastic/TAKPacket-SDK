package org.meshtastic.tak.internal.zstd

/**
 * Backward bit writer — the exact inverse of [ReverseBitReader].
 *
 * zstd FSE / Huffman bitstreams are consumed from the END toward the start:
 * [ReverseBitReader] finds the highest set bit of the last byte (the "stop bit"
 * sentinel), then reads bits MSB-first walking downward toward the stream start.
 * The first bit the decoder reads sits just below the stop bit (the highest
 * meaningful position); the last bit it reads sits at the very start.
 *
 * So the WRITER must lay bits down from the start upward: each [writeBits] call
 * appends a field whose low-order bit lands at the current (lower) global
 * position and whose high-order bit lands at the next (higher) position. Because
 * the decoder reads from the top down, the field's MSB is read first — matching
 * [ReverseBitReader.peekBits] returning bits MSB-first. The net effect: fields
 * written in order `f0, f1, … fk` are read back by the decoder in REVERSE order
 * `fk, … f1, f0`. Callers therefore write the entire bitstream in reverse of the
 * decode read order, then [finish] appends the stop bit at the very top.
 *
 * Pure common Kotlin — no `java.*`, no `expect/actual` — so it can back the
 * wasmWasi compress path.
 */
internal class ReverseBitWriter {
    // Bits accumulate LSB-first into `container`; whole bytes flush to `bytes`.
    private val bytes = ArrayList<Byte>(64)
    private var container: Int = 0
    private var bitsInContainer: Int = 0

    /** Total meaningful bits written so far (excluding the not-yet-added stop bit). */
    var bitCount: Int = 0
        private set

    /**
     * Append the low [n] bits of [value] (0..32 bits) to the stream. The bit at
     * position 0 of [value] is written FIRST (lands at the lower global
     * position); the decoder, reading top-down, sees the high bits first, so it
     * recovers [value] exactly when it [ReverseBitReader.readBits]s the same [n].
     */
    fun writeBits(value: Int, n: Int) {
        if (n == 0) return
        // Mask to n bits so stray high bits never corrupt neighbouring fields.
        val masked = if (n >= 32) value else value and ((1 shl n) - 1)
        container = container or (masked shl bitsInContainer)
        bitsInContainer += n
        bitCount += n
        while (bitsInContainer >= 8) {
            bytes.add((container and 0xFF).toByte())
            container = container ushr 8
            bitsInContainer -= 8
        }
    }

    /**
     * Finish the stream: append the single stop bit (a `1`) immediately above the
     * last meaningful bit, then flush. The decoder locates this highest set bit
     * of the final byte to anchor its backward read.
     */
    fun finish(): ByteArray {
        // The stop bit is one more `1` bit on top of everything written.
        container = container or (1 shl bitsInContainer)
        bitsInContainer += 1
        // Flush the final partial byte (the stop bit guarantees it is non-zero
        // if it is the high byte, and zstd zero-pads the rest of that byte).
        while (bitsInContainer > 0) {
            bytes.add((container and 0xFF).toByte())
            container = container ushr 8
            bitsInContainer -= 8
        }
        return ByteArray(bytes.size) { bytes[it] }
    }
}
