package org.meshtastic.tak.internal.zstd

/**
 * Decompression output accumulator with the dictionary content prepended as
 * back-reference history.
 *
 * A match in a dictionary-compressed frame may reference bytes that lie BEFORE
 * the frame's first output byte — those live in the dictionary content. We hold
 * one contiguous byte array `[dict content][frame output]`; a match offset `d`
 * from the current write position therefore naturally indexes into either the
 * already-produced frame output or the dictionary prefix, with no special case.
 *
 * [frameOutput] returns only the frame's own bytes (the suffix after the dict
 * prefix). The [maxSize] cap is enforced on the FRAME output length, matching
 * the SDK's `MAX_DECOMPRESSED_SIZE` guard.
 */
internal class OutputBuffer(dictContent: ByteArray, private val maxSize: Int) {

    private val dictLen = dictContent.size
    private var buf = ByteArray(dictLen + minOf(maxSize, 4096).coerceAtLeast(64))
    private var size = dictLen

    init {
        if (dictLen > buf.size) buf = dictContent.copyOf(dictLen + 64)
        dictContent.copyInto(buf, 0)
    }

    /** Current number of frame-output bytes produced so far. */
    private val frameLen: Int get() = size - dictLen

    private fun ensure(extra: Int) {
        if (frameLen + extra > maxSize) {
            throw ZstdFormatException("decompressed size exceeds limit $maxSize")
        }
        if (size + extra > buf.size) {
            var newCap = buf.size * 2
            while (newCap < size + extra) newCap *= 2
            buf = buf.copyOf(newCap)
        }
    }

    fun appendByte(b: Int) {
        ensure(1)
        buf[size++] = b.toByte()
    }

    fun appendBytes(src: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        ensure(length)
        src.copyInto(buf, size, offset, offset + length)
        size += length
    }

    /**
     * Copy a match of [length] bytes from [offset] bytes before the current
     * write position. Overlapping copies (offset < length) are handled
     * byte-by-byte, which is the LZ-correct semantics (the copied region grows
     * as it is written). An offset reaching before the dictionary prefix is a
     * corrupt frame.
     */
    fun copyMatch(offset: Int, length: Int) {
        if (offset <= 0) throw ZstdFormatException("non-positive match offset $offset")
        val from = size - offset
        if (from < 0) throw ZstdFormatException("match offset $offset reaches before dictionary start")
        ensure(length)
        var s = from
        var d = size
        for (i in 0 until length) {
            buf[d++] = buf[s++]
        }
        size += length
    }

    /** The frame's own output bytes (excluding the dictionary prefix). */
    fun frameOutput(): ByteArray = buf.copyOfRange(dictLen, size)
}
