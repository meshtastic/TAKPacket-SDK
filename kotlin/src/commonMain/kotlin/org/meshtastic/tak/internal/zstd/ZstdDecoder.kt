package org.meshtastic.tak.internal.zstd

/**
 * Pure-Kotlin, dictionary-aware zstd frame decoder (RFC 8878).
 *
 * Entry point [PureZstdDecoder.decode] takes a complete standard zstd frame
 * (magic included), a trained dictionary, and a decompressed-size cap, and
 * returns the original bytes. It implements the subset of zstd the SDK's wire
 * frames exercise — and, defensively, the full block/literals/sequences grammar
 * so it stays correct for any conformant small frame:
 *
 *  - Frame header with optional content-size / dictionary-id / checksum (all
 *    OFF in our frames, but parsed if present).
 *  - Block loop: Raw, RLE, and Compressed blocks; the Last_Block flag.
 *  - Literals section: Raw, RLE, Compressed (fresh Huffman), and Treeless
 *    (repeat the dictionary's / previous block's Huffman table); 1-stream and
 *    4-stream layouts.
 *  - Sequences section: literal-length / offset / match-length FSE with
 *    Predefined, RLE, FSE-compressed, and Repeat modes (Repeat references the
 *    dictionary's FSE tables).
 *  - Sequence execution: literal copy + match copy with the three repeat-offset
 *    slots, copying from current output OR from the dictionary content for
 *    offsets that reach before the frame's first output byte.
 *
 * No `java.*`, no `expect/actual`: pure common Kotlin, so it can later back the
 * wasmWasi `decompress` actual.
 */
internal object PureZstdDecoder {

    private const val FRAME_MAGIC = 0xFD2FB528.toInt()

    /**
     * Decode a full zstd [frame] (magic-prefixed) using [dictionary], rejecting
     * output larger than [maxSize].
     */
    fun decode(frame: ByteArray, dictionary: ByteArray, maxSize: Int): ByteArray {
        val dict = ZstdDictionary.parse(dictionary)
        val reader = ForwardByteReader(frame, 0, frame.size)

        val magic = reader.readLEInt(4)
        if (magic != FRAME_MAGIC) throw ZstdFormatException("bad frame magic ${magic.toString(16)}")

        parseFrameHeader(reader) // window/content-size/dict-id/checksum descriptors

        // Output buffer prefixed (conceptually) by the dictionary content: a
        // match offset that reaches before the frame start indexes into the
        // dict content. We materialise dict content + output in one array so
        // match copies are a single contiguous-history lookup.
        val out = OutputBuffer(dict.content, maxSize)

        // Per-frame decoding state carried across blocks ("repeat" entropy and
        // the three repeat offsets), seeded from the dictionary.
        val state = DecodeState(
            huffman = dict.literalsHuffman,
            litLenFse = dict.literalLengthFse,
            offsetFse = dict.offsetFse,
            matchLenFse = dict.matchLengthFse,
            repeatOffsets = dict.repeatOffsets.copyOf(),
        )

        while (true) {
            val header = reader.readLEInt(3)
            val lastBlock = (header and 1) == 1
            val blockType = (header ushr 1) and 0x3
            val blockSize = header ushr 3

            when (blockType) {
                0 -> { // Raw_Block: literal bytes copied verbatim.
                    for (i in 0 until blockSize) out.appendByte(reader.readByte())
                }
                1 -> { // RLE_Block: one byte repeated blockSize times.
                    val b = reader.readByte()
                    for (i in 0 until blockSize) out.appendByte(b)
                }
                2 -> { // Compressed_Block.
                    decodeCompressedBlock(reader, blockSize, out, state, maxSize)
                }
                else -> throw ZstdFormatException("reserved block type")
            }

            if (lastBlock) break
        }

        return out.frameOutput()
    }

    // ── Frame header ─────────────────────────────────────────────────────────

    private fun parseFrameHeader(reader: ForwardByteReader) {
        val descriptor = reader.readByte()
        val frameContentSizeFlag = (descriptor ushr 6) and 0x3
        val singleSegment = (descriptor ushr 5) and 0x1
        val contentChecksum = (descriptor ushr 2) and 0x1
        val dictIdFlag = descriptor and 0x3

        if (singleSegment == 0) {
            // Window_Descriptor byte (we don't enforce window size for <=4KB).
            reader.readByte()
        }

        // Dictionary_ID field, 0/1/2/4 bytes.
        val dictIdBytes = when (dictIdFlag) {
            0 -> 0; 1 -> 1; 2 -> 2; else -> 4
        }
        if (dictIdBytes > 0) reader.readLEInt(dictIdBytes)

        // Frame_Content_Size field, 0/1/2/4/8 bytes (presence/size depends on
        // the flag and single-segment bit). We don't need the value.
        val fcsBytes = when (frameContentSizeFlag) {
            0 -> if (singleSegment == 1) 1 else 0
            1 -> 2
            2 -> 4
            else -> 8
        }
        if (fcsBytes > 0) reader.readLELong(fcsBytes)

        // Content checksum (if any) is a trailing 4-byte field consumed by the
        // block loop's end; our frames set it OFF. Record nothing here.
        @Suppress("UNUSED_EXPRESSION")
        contentChecksum
    }

    // ── Compressed block ───────────────────────────────────────────────────────

    private fun decodeCompressedBlock(
        reader: ForwardByteReader,
        blockSize: Int,
        out: OutputBuffer,
        state: DecodeState,
        maxSize: Int,
    ) {
        val blockStart = reader.pos
        val blockEnd = blockStart + blockSize

        val literals = decodeLiteralsSection(reader, blockEnd, state, maxSize)

        // Sequences section occupies the rest of the block.
        decodeSequences(reader, blockEnd, literals, out, state)
    }

    // ── Literals section (RFC 8878 §3.1.1.3.1) ─────────────────────────────────

    private fun decodeLiteralsSection(
        reader: ForwardByteReader,
        blockEnd: Int,
        state: DecodeState,
        maxSize: Int,
    ): ByteArray {
        val first = reader.readByte()
        val litType = first and 0x3
        val sizeFormat = (first ushr 2) and 0x3

        return when (litType) {
            0, 1 -> decodeRawOrRleLiterals(reader, first, litType, sizeFormat, maxSize)
            2, 3 -> decodeHuffmanLiterals(reader, first, litType, sizeFormat, blockEnd, state, maxSize)
            else -> throw ZstdFormatException("bad literals type")
        }
    }

    private fun decodeRawOrRleLiterals(
        reader: ForwardByteReader,
        first: Int,
        litType: Int,
        sizeFormat: Int,
        maxSize: Int,
    ): ByteArray {
        // Regenerated_Size width depends on size_format.
        val regenSize: Int = when (sizeFormat) {
            0, 2 -> first ushr 3 // 5-bit size, low form (sizeFormat bit0 == 0)
            1 -> (first ushr 4) or (reader.readByte() shl 4) // 12-bit
            else -> (first ushr 4) or (reader.readByte() shl 4) or (reader.readByte() shl 12) // 20-bit
        }
        // A 20-bit Regenerated_Size can request ~1 MB; reject before allocating
        // so a crafted header can't force a large allocation past the cap.
        if (regenSize > maxSize) {
            throw ZstdFormatException("literals regen size $regenSize exceeds limit $maxSize")
        }
        return if (litType == 0) { // Raw
            ByteArray(regenSize) { reader.readByte().toByte() }
        } else { // RLE: one byte repeated regenSize times
            val b = reader.readByte().toByte()
            ByteArray(regenSize) { b }
        }
    }

    private fun decodeHuffmanLiterals(
        reader: ForwardByteReader,
        first: Int,
        litType: Int, // 2 = Compressed (new tree), 3 = Treeless (repeat tree)
        sizeFormat: Int,
        blockEnd: Int,
        state: DecodeState,
        maxSize: Int,
    ): ByteArray {
        // Regenerated_Size and Compressed_Size, plus the 4-stream flag, are
        // packed per size_format (RFC 8878 §3.1.1.3.1.1).
        val regenSize: Int
        val compressedSize: Int
        val fourStreams: Boolean
        when (sizeFormat) {
            0 -> { // single stream, 10-bit sizes
                val b1 = reader.readByte()
                val b2 = reader.readByte()
                regenSize = (first ushr 4) or ((b1 and 0x3F) shl 4)
                compressedSize = (b1 ushr 6) or (b2 shl 2)
                fourStreams = false
            }
            1 -> { // 4 streams, 10-bit sizes
                val b1 = reader.readByte()
                val b2 = reader.readByte()
                regenSize = (first ushr 4) or ((b1 and 0x3F) shl 4)
                compressedSize = (b1 ushr 6) or (b2 shl 2)
                fourStreams = true
            }
            2 -> { // 4 streams, 14-bit sizes
                val b1 = reader.readByte()
                val b2 = reader.readByte()
                val b3 = reader.readByte()
                regenSize = (first ushr 4) or (b1 shl 4) or ((b2 and 0x3) shl 12)
                compressedSize = (b2 ushr 2) or (b3 shl 6)
                fourStreams = true
            }
            else -> { // 4 streams, 18-bit sizes
                val b1 = reader.readByte()
                val b2 = reader.readByte()
                val b3 = reader.readByte()
                val b4 = reader.readByte()
                regenSize = (first ushr 4) or (b1 shl 4) or ((b2 and 0x3F) shl 12)
                compressedSize = (b2 ushr 6) or (b3 shl 2) or (b4 shl 10)
                fourStreams = true
            }
        }

        // An 18-bit Regenerated_Size can request ~256 KB; reject before
        // huffmanDecodeStreams allocates ByteArray(regenSize), so a crafted
        // header can't force a large allocation past the decompressed-size cap.
        if (regenSize > maxSize) {
            throw ZstdFormatException("literals regen size $regenSize exceeds limit $maxSize")
        }

        // Huffman table: fresh for type 2 (Compressed), reuse for type 3
        // (Treeless → the dictionary's / previous block's table).
        val streamsStart: Int
        if (litType == 2) {
            val tableStart = reader.pos
            val huff = parseHuffmanTable(reader)
            state.huffman = huff
            streamsStart = reader.pos
            // compressedSize counts the Huffman-tree description + the stream(s).
            val streamsLen = compressedSize - (streamsStart - tableStart)
            return huffmanDecodeStreams(reader, streamsStart, streamsLen, regenSize, fourStreams, state.huffman!!)
        } else {
            val huff = state.huffman
                ?: throw ZstdFormatException("treeless literals but no prior/dict Huffman table")
            streamsStart = reader.pos
            return huffmanDecodeStreams(reader, streamsStart, compressedSize, regenSize, fourStreams, huff)
        }
    }

    private fun huffmanDecodeStreams(
        reader: ForwardByteReader,
        streamsStart: Int,
        streamsLen: Int,
        regenSize: Int,
        fourStreams: Boolean,
        huff: HuffmanTable,
    ): ByteArray {
        val out = ByteArray(regenSize)
        val streamsEnd = streamsStart + streamsLen
        if (streamsEnd > reader.endPos) throw ZstdFormatException("Huffman streams overrun input")

        if (!fourStreams) {
            val br = ReverseBitReader(reader.backingArray, streamsStart, streamsEnd)
            for (i in 0 until regenSize) out[i] = huff.decode(br).toByte()
            reader.pos = streamsEnd
            return out
        }

        // 4-stream layout: a 6-byte jump table (three 2-byte little-endian
        // sizes for streams 1..3; stream 4 fills the remainder), then the four
        // streams back to back. Each stream decodes an (almost) equal quarter
        // of the output; the last takes the remainder.
        val jumpBase = streamsStart
        val size1 = leShort(reader.backingArray, jumpBase)
        val size2 = leShort(reader.backingArray, jumpBase + 2)
        val size3 = leShort(reader.backingArray, jumpBase + 4)
        val s1Start = jumpBase + 6
        val s2Start = s1Start + size1
        val s3Start = s2Start + size2
        val s4Start = s3Start + size3
        val s4End = streamsEnd
        if (s4Start > s4End) throw ZstdFormatException("Huffman 4-stream jump table overruns")

        val segment = (regenSize + 3) / 4
        val starts = intArrayOf(s1Start, s2Start, s3Start, s4Start)
        val ends = intArrayOf(s2Start, s3Start, s4Start, s4End)
        var written = 0
        for (k in 0 until 4) {
            val count = if (k < 3) segment else (regenSize - written)
            val br = ReverseBitReader(reader.backingArray, starts[k], ends[k])
            for (i in 0 until count) out[written + i] = huff.decode(br).toByte()
            written += count
        }
        reader.pos = streamsEnd
        return out
    }

    private fun leShort(b: ByteArray, off: Int): Int =
        (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)

    // ── Sequences section (RFC 8878 §3.1.1.3.2) ─────────────────────────────────

    private fun decodeSequences(
        reader: ForwardByteReader,
        blockEnd: Int,
        literals: ByteArray,
        out: OutputBuffer,
        state: DecodeState,
    ) {
        // Number_of_Sequences: 0, 1, or 2-3 byte varint-ish encoding.
        var nbSeq = reader.readByte()
        when {
            nbSeq == 0 -> {
                // No sequences: the whole literals buffer is the block output.
                out.appendBytes(literals, 0, literals.size)
                reader.pos = blockEnd
                return
            }
            nbSeq < 128 -> { /* value is nbSeq */ }
            nbSeq < 255 -> nbSeq = ((nbSeq - 128) shl 8) + reader.readByte()
            else -> nbSeq = reader.readByte() + (reader.readByte() shl 8) + 0x7F00
        }

        // Symbol_Compression_Modes byte: 2 bits each for LL, OF, ML (in that
        // order from high bits), low 2 bits reserved.
        val modes = reader.readByte()
        val llMode = (modes ushr 6) and 0x3
        val ofMode = (modes ushr 4) and 0x3
        val mlMode = (modes ushr 2) and 0x3

        val llTable = resolveTable(reader, llMode, SeqKind.LITERAL_LENGTH, state)
        val ofTable = resolveTable(reader, ofMode, SeqKind.OFFSET, state)
        val mlTable = resolveTable(reader, mlMode, SeqKind.MATCH_LENGTH, state)
        state.litLenFse = llTable
        state.offsetFse = ofTable
        state.matchLenFse = mlTable

        // The sequence bitstream is the remainder of the block, read backward.
        val br = ReverseBitReader(reader.backingArray, reader.pos, blockEnd)
        reader.pos = blockEnd

        val llState = FseState(llTable)
        val ofState = FseState(ofTable)
        val mlState = FseState(mlTable)
        // Initial state read order: literal-length, offset, match-length.
        llState.init(br)
        ofState.init(br)
        mlState.init(br)

        var litPos = 0
        for (seqIdx in 0 until nbSeq) {
            // Read order within a sequence: offset code's extra bits, then
            // match-length extra, then literal-length extra; but symbols are
            // emitted before reading their extra bits in the order OF, ML, LL
            // per the reference. We follow the reference bit read order exactly.
            val ofCode = ofState.symbol()
            val mlCode = mlState.symbol()
            val llCode = llState.symbol()

            // A corrupt FSE table can emit a symbol outside the baseline/extra
            // tables' range; reject before indexing them (a bare
            // IndexOutOfBoundsException would otherwise escape untyped).
            if (llCode !in LL_BASELINE.indices) {
                throw ZstdFormatException("literal-length code $llCode out of range")
            }
            if (mlCode !in ML_BASELINE.indices) {
                throw ZstdFormatException("match-length code $mlCode out of range")
            }

            // Extra bits are read in the order: offset (most), then match
            // length, then literal length (RFC 8878 §3.1.1.3.2.1.1, "the
            // bitstream ... offset, then match length, then literal length").
            val offsetExtra = if (ofCode > 0) br.readBits(ofCode) else 0
            val mlExtra = if (ML_EXTRA_BITS[mlCode] > 0) br.readBits(ML_EXTRA_BITS[mlCode]) else 0
            val llExtra = if (LL_EXTRA_BITS[llCode] > 0) br.readBits(LL_EXTRA_BITS[llCode]) else 0

            val literalLength = LL_BASELINE[llCode] + llExtra
            val matchLength = ML_BASELINE[mlCode] + mlExtra
            val offsetValue = (1 shl ofCode) + offsetExtra

            // The literal copy must stay within the decoded literals buffer; a
            // corrupt literalLength would otherwise throw a bare
            // IndexOutOfBoundsException inside appendBytes.
            if (literalLength < 0 || litPos + literalLength > literals.size) {
                throw ZstdFormatException("sequence literal length $literalLength overruns literals")
            }

            val actualOffset = applyOffset(offsetValue, literalLength, state.repeatOffsets)

            // Emit: copy `literalLength` literals, then a `matchLength` match.
            out.appendBytes(literals, litPos, literalLength)
            litPos += literalLength
            out.copyMatch(actualOffset, matchLength)

            // Update FSE states for the NEXT sequence (skipped after the last).
            if (seqIdx < nbSeq - 1) {
                llState.update(br)
                mlState.update(br)
                ofState.update(br)
            }
        }

        // Any literals not consumed by a sequence are appended verbatim.
        if (litPos < literals.size) {
            out.appendBytes(literals, litPos, literals.size - litPos)
        }
    }

    /**
     * Resolve the FSE table for one sequence symbol stream given its 2-bit mode:
     * 0 = Predefined, 1 = RLE (single repeated symbol), 2 = FSE_Compressed
     * (parse a table here), 3 = Repeat (reuse the dict's / previous block's
     * table). The reader is advanced for RLE and FSE_Compressed modes.
     */
    private fun resolveTable(
        reader: ForwardByteReader,
        mode: Int,
        kind: SeqKind,
        state: DecodeState,
    ): FseTable = when (mode) {
        0 -> kind.predefined()
        1 -> {
            // RLE: a single byte is the only symbol (probability 1.0), tableLog 0.
            val symbol = reader.readByte()
            rleTable(symbol)
        }
        2 -> parseFseTable(reader, kind.maxLog, kind.maxSymbol)
        else -> kind.repeat(state)
            ?: throw ZstdFormatException("repeat mode for ${kind.name} but no prior/dict table")
    }

    private fun rleTable(symbol: Int): FseTable {
        // One-state table: always emits `symbol`, consumes 0 bits, stays in
        // state 0.
        return FseTable(
            tableLog = 0,
            symbol = intArrayOf(symbol),
            nbBits = intArrayOf(0),
            newState = intArrayOf(0),
        )
    }

    /**
     * Apply zstd's repeat-offset machinery (RFC 8878 §3.1.1.3.2.1.1). Offset
     * codes 1..3 reference the three repeat-offset slots (with a literal-length
     * == 0 special case for code 1→slot[0]); codes >3 are literal offsets
     * (value - 3) that shift the slots. Returns the actual back-reference
     * distance and mutates [rep].
     */
    private fun applyOffset(offsetValue: Int, literalLength: Int, rep: IntArray): Int {
        if (offsetValue > 3) {
            val actual = offsetValue - 3
            rep[2] = rep[1]
            rep[1] = rep[0]
            rep[0] = actual
            return actual
        }
        // Repeat offset reference.
        val repCode = if (literalLength == 0) offsetValue else offsetValue - 1
        return when (repCode) {
            0 -> rep[0]
            1 -> {
                val v = rep[1]
                rep[1] = rep[0]; rep[0] = v
                v
            }
            2 -> {
                val v = rep[2]
                rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = v
                v
            }
            else -> {
                // repCode == 3 only reachable when literalLength == 0 and
                // offsetValue == 3: actual = rep[0] - 1.
                val v = rep[0] - 1
                rep[2] = rep[1]; rep[1] = rep[0]; rep[0] = v
                v
            }
        }
    }

    private enum class SeqKind {
        LITERAL_LENGTH, OFFSET, MATCH_LENGTH;

        val maxLog: Int get() = when (this) {
            LITERAL_LENGTH -> LITERAL_LENGTH_MAX_LOG
            OFFSET -> OFFSET_MAX_LOG
            MATCH_LENGTH -> MATCH_LENGTH_MAX_LOG
        }
        val maxSymbol: Int get() = when (this) {
            LITERAL_LENGTH -> LITERAL_LENGTH_MAX_SYMBOL
            OFFSET -> OFFSET_MAX_SYMBOL
            MATCH_LENGTH -> MATCH_LENGTH_MAX_SYMBOL
        }
        fun predefined(): FseTable = when (this) {
            LITERAL_LENGTH -> predefinedLiteralLengthTable()
            OFFSET -> predefinedOffsetTable()
            MATCH_LENGTH -> predefinedMatchLengthTable()
        }
        fun repeat(state: DecodeState): FseTable? = when (this) {
            LITERAL_LENGTH -> state.litLenFse
            OFFSET -> state.offsetFse
            MATCH_LENGTH -> state.matchLenFse
        }
    }

    /** Mutable per-frame entropy + repeat-offset state, seeded from the dict. */
    private class DecodeState(
        var huffman: HuffmanTable?,
        var litLenFse: FseTable?,
        var offsetFse: FseTable?,
        var matchLenFse: FseTable?,
        val repeatOffsets: IntArray,
    )
}
