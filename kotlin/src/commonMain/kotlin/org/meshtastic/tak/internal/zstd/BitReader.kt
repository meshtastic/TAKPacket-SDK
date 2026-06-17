package org.meshtastic.tak.internal.zstd

/**
 * Forward little-endian byte reader over a [ByteArray] window.
 *
 * Used for zstd structural headers (frame header, block headers, the literals
 * and sequences section headers, FSE/Huffman table descriptors) which are read
 * front-to-back. Multi-byte integers in zstd are little-endian.
 *
 * This is pure Kotlin/common — no `java.*`, no `expect/actual` — so the whole
 * decoder can later serve as the wasmWasi `decompress` actual.
 */
internal class ForwardByteReader(
    val backingArray: ByteArray,
    var pos: Int,
    private val end: Int,
) {

    val remaining: Int get() = end - pos
    val endPos: Int get() = end

    fun readByte(): Int {
        if (pos >= end) throw ZstdFormatException("read past end of buffer")
        return backingArray[pos++].toInt() and 0xFF
    }

    /** Read [n] bytes as a little-endian unsigned integer (n in 0..4). */
    fun readLEInt(n: Int): Int {
        var v = 0
        for (i in 0 until n) {
            v = v or (readByte() shl (8 * i))
        }
        return v
    }

    /** Read [n] bytes as a little-endian unsigned long (n in 0..8). */
    fun readLELong(n: Int): Long {
        var v = 0L
        for (i in 0 until n) {
            v = v or ((readByte().toLong() and 0xFF) shl (8 * i))
        }
        return v
    }
}

/**
 * Backward bit reader for zstd FSE / Huffman bitstreams (RFC 8878 §4.1.1).
 *
 * Zstd writes these streams to be consumed from the END toward the start: the
 * last byte's highest set bit is a stop-bit sentinel marking the true end of
 * the meaningful bits. Reading begins at the bit just below the stop bit and
 * proceeds MSB-to-LSB, walking backward through the bytes. The first bit
 * consumed is the most-significant bit of the returned field.
 *
 * This is a deliberately simple bit-at-a-time model: it precomputes the global
 * bit index of the bit immediately below the stop bit, then serves bits by
 * decrementing that index. zstd payloads here are <= 4096 bytes, so the
 * per-bit cost is irrelevant and correctness is maximised. The interface is
 * **peek + skip** so FSE (fixed-width transitions) and Huffman (variable-width
 * canonical codes) can share one cursor when interleaved.
 *
 * Reads past the stream start return zero bits (zstd's defined zero-padding)
 * and set [overflowed]; callers use that to terminate symbol decode loops.
 */
internal class ReverseBitReader(private val buf: ByteArray, private val start: Int, endExclusive: Int) {

    // Global bit index of the NEXT bit to be consumed, counting bit 0 as the
    // least-significant bit of byte `start`. We move this downward as bits are
    // consumed. It begins at the bit just below the stop bit of the last byte.
    private var bitPos: Int

    var overflowed: Boolean = false
        private set

    /**
     * Global index of the next bit to be consumed (decreases as bits are read).
     * Exposed so interleaved decode loops can detect a transition that consumed
     * no bits (a non-advancing, malformed self-loop).
     */
    val bitPosition: Int get() = bitPos

    init {
        if (endExclusive <= start) throw ZstdFormatException("empty bitstream")
        // A corrupt/truncated frame can declare a stream window (block size,
        // Huffman compressed size, jump-table offsets) that runs past the actual
        // input. Reject it here so the cursor can never index outside `buf`
        // (which would otherwise leak an untyped IndexOutOfBoundsException).
        if (start < 0 || endExclusive > buf.size) {
            throw ZstdFormatException("bitstream window [$start,$endExclusive) outside input ${buf.size}")
        }
        val lastIdx = endExclusive - 1
        val lastByte = buf[lastIdx].toInt() and 0xFF
        if (lastByte == 0) throw ZstdFormatException("bitstream last byte is zero (no stop bit)")
        // Position of the highest set bit within the last byte (0..7).
        val stopBit = 7 - leadingZeros8(lastByte)
        // Global index of the stop bit; the first readable bit is one below it.
        val stopGlobal = (lastIdx - start) * 8 + stopBit
        bitPos = stopGlobal - 1
    }

    private fun leadingZeros8(v: Int): Int {
        var n = 0
        var x = v and 0xFF
        if (x and 0xF0 == 0) { n += 4; x = x shl 4 }
        if (x and 0xC0 == 0) { n += 2; x = x shl 2 }
        if (x and 0x80 == 0) { n += 1; x = x shl 1 }
        return n
    }

    /** Read the single bit at global index [g], or 0 (with overflow) if g < 0. */
    private fun bitAt(g: Int): Int {
        if (g < 0) { overflowed = true; return 0 }
        val byteIndex = start + (g ushr 3)
        val bitInByte = g and 7
        return (buf[byteIndex].toInt() ushr bitInByte) and 1
    }

    /**
     * Peek the next [n] bits (0..32) MSB-first without consuming them. The
     * first bit returned occupies the high-order position of the field.
     */
    fun peekBits(n: Int): Int {
        if (n == 0) return 0
        var v = 0
        var g = bitPos
        for (i in 0 until n) {
            v = (v shl 1) or bitAt(g)
            g--
        }
        return v
    }

    /** Consume [n] bits previously peeked. */
    fun skipBits(n: Int) {
        bitPos -= n
    }

    /** Peek then consume [n] bits. */
    fun readBits(n: Int): Int {
        val v = peekBits(n)
        skipBits(n)
        return v
    }

    /**
     * True once every meaningful bit has been consumed (the cursor sits below
     * the stream start). After the final symbol, a well-formed stream lands
     * exactly here.
     */
    fun isFinished(): Boolean = bitPos < 0
}
