package org.meshtastic.tak.internal.zstd

/**
 * Decompression output accumulator that treats the dictionary content as
 * read-only back-reference history WITHOUT copying it into the output buffer.
 *
 * A match in a dictionary-compressed frame may reference bytes that lie BEFORE
 * the frame's first output byte — those live in the dictionary content. Earlier
 * this class materialised one contiguous `[dict content][frame output]` array so
 * a match was a single contiguous lookup, but that copied the ~512 KB dictionary
 * into a fresh buffer on EVERY decode (the web/wasi per-packet hot path). Instead
 * we keep the dictionary array by reference and the frame output in its own
 * growing buffer; [copyMatch] resolves each source byte from whichever array it
 * falls in. Positions are still expressed against a virtual
 * `[dict content][frame output]` history (offset 0 = first dict byte), so the
 * decoder's match-offset arithmetic is unchanged and the output stays
 * byte-identical to the contiguous-copy version.
 *
 * [frameOutput] returns only the frame's own bytes. The [maxSize] cap is
 * enforced on the FRAME output length, matching the SDK's `MAX_DECOMPRESSED_SIZE`
 * guard.
 */
internal class OutputBuffer(private val dict: ByteArray, private val maxSize: Int) {

    private val dictLen = dict.size
    // Holds ONLY the frame's own output bytes (no dict prefix). `size` is the
    // virtual position in the `[dict][output]` history; frame bytes occupy
    // indices [0, frameLen) of `out`.
    private var out = ByteArray(minOf(maxSize, 4096).coerceAtLeast(64))
    private var size = dictLen

    /** Current number of frame-output bytes produced so far. */
    private val frameLen: Int get() = size - dictLen

    private fun ensure(extra: Int) {
        if (frameLen + extra > maxSize) {
            throw ZstdFormatException("decompressed size exceeds limit $maxSize")
        }
        val needed = frameLen + extra
        if (needed > out.size) {
            var newCap = out.size * 2
            while (newCap < needed) newCap *= 2
            out = out.copyOf(newCap)
        }
    }

    fun appendByte(b: Int) {
        ensure(1)
        out[frameLen] = b.toByte()
        size++
    }

    fun appendBytes(src: ByteArray, offset: Int, length: Int) {
        if (length == 0) return
        ensure(length)
        src.copyInto(out, frameLen, offset, offset + length)
        size += length
    }

    /**
     * Resolve a byte at virtual history position [pos] (0 = first dict byte) from
     * either the dictionary prefix or the frame output.
     */
    private fun byteAt(pos: Int): Byte =
        if (pos < dictLen) dict[pos] else out[pos - dictLen]

    /**
     * Copy a match of [length] bytes from [offset] bytes before the current
     * write position. Overlapping copies (offset < length) are handled
     * byte-by-byte, which is the LZ-correct semantics (the copied region grows
     * as it is written). An offset reaching before the dictionary prefix is a
     * corrupt frame. The source may straddle the dict→output boundary, which the
     * per-byte [byteAt] lookup handles transparently.
     */
    fun copyMatch(offset: Int, length: Int) {
        if (offset <= 0) throw ZstdFormatException("non-positive match offset $offset")
        var s = size - offset
        if (s < 0) throw ZstdFormatException("match offset $offset reaches before dictionary start")
        ensure(length)
        var d = frameLen
        for (i in 0 until length) {
            out[d++] = byteAt(s++)
        }
        size += length
    }

    /** The frame's own output bytes (excluding the dictionary prefix). */
    fun frameOutput(): ByteArray = out.copyOfRange(0, frameLen)
}
