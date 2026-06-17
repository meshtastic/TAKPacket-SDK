package org.meshtastic.tak.internal.zstd

/**
 * Pure-Kotlin, dictionary-aware zstd frame ENCODER (RFC 8878) — the inverse of
 * [PureZstdDecoder].
 *
 * [PureZstdEncoder.encode] takes raw bytes + a trained dictionary and returns a
 * COMPLETE standard zstd frame (magic included). The frame is decodable by real
 * libzstd (zstd-jni and every other binding) AND by [PureZstdDecoder]. It is the
 * compress half that lets `wasmWasi` — which has no JS host, no cinterop, and no
 * native libzstd — both compress and decompress in pure Kotlin.
 *
 * ## Strategy (the simplest VALID path that still benefits from the dict)
 *
 *  - **Frame header:** single-segment, dictID OFF, contentSize OFF, checksum
 *    OFF — byte-for-byte the descriptor the SDK's decoder (and libzstd) accept,
 *    with a window large enough to cover `dict.content + input`.
 *  - **One Compressed_Block** with Last_Block set. If the compressed block would
 *    not be smaller than the input, a Raw_Block is emitted instead (a valid
 *    fallback; the SDK's own `0xFF` skip-compress also covers incompressible
 *    payloads above this layer).
 *  - **Matching:** a 4-byte hash-chain matcher over `[dict.content ++ input]`, so
 *    matches can back-reference the dictionary content. The dict index is built
 *    once per dictionary identity and cached, so the 512 KB dict is hashed once,
 *    not per packet. Lazy (1-step lookahead) matching for a better ratio.
 *  - **Literals:** RAW literals block (litType 0) — simplest valid option and a
 *    good fit because a trained dict turns most bytes into matches.
 *  - **Sequences:** FSE-coded with the PREDEFINED LL/ML/OF tables (mode 0) — no
 *    custom-table emission, and the predefined tables are exactly what
 *    [PureZstdDecoder] / libzstd build from the spec's default distributions.
 *  - **Offsets:** emitted as explicit literal offsets (`offset_code = distance +
 *    3`), avoiding repeat-offset bookkeeping. Valid and only marginally larger.
 *
 * Pure common Kotlin: no `java.*`, no `expect/actual`.
 */
internal object PureZstdEncoder {

    private val FRAME_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    private const val MIN_MATCH = 3
    private const val HASH_LOG = 17 // 128 K buckets — enough to index a 512 KB dict
    private const val HASH_SIZE = 1 shl HASH_LOG

    /**
     * Encode [data] into a complete zstd frame using [dictionary] as match
     * history. [level] is accepted for API symmetry; the pure encoder uses a
     * single fixed greedy/lazy strategy (it does not implement zstd's 22 levels).
     */
    fun encode(data: ByteArray, dictionary: ByteArray, @Suppress("UNUSED_PARAMETER") level: Int = 19): ByteArray {
        val dict = ZstdDictionary.parseCached(dictionary)
        val index = MatchIndex.forDict(dict.content)

        // Build the literal+sequence program by matching `data` against the
        // combined [dictContent ++ data] history.
        val program = buildSequences(data, dict, index)

        // Encode the single block. If it does not beat raw, fall back to a Raw
        // block (still a valid frame).
        val compressedBlock = encodeCompressedBlock(program, data)
        val out = ArrayList<Byte>(data.size + 16)
        FRAME_MAGIC.forEach { out.add(it) }
        out.add(frameHeaderDescriptor())
        out.add(windowDescriptor(dict.content.size + data.size))

        if (compressedBlock != null && compressedBlock.size < data.size) {
            // Block_Header (3 bytes LE): last=1, type=2 (Compressed), size=blockSize
            writeBlockHeader(out, lastBlock = true, blockType = 2, blockSize = compressedBlock.size)
            compressedBlock.forEach { out.add(it) }
        } else {
            // Raw_Block fallback: the literal bytes are the block.
            writeBlockHeader(out, lastBlock = true, blockType = 0, blockSize = data.size)
            data.forEach { out.add(it) }
        }
        return ByteArray(out.size) { out[it] }
    }

    // ── Frame header ───────────────────────────────────────────────────────────

    /**
     * Frame_Header_Descriptor: Frame_Content_Size flag 0, Single_Segment 0,
     * Content_Checksum 0, Dictionary_ID 0. (Single_Segment 0 means a
     * Window_Descriptor byte follows, which is how the SDK's frames are shaped
     * and what its decoder skips.)
     */
    private fun frameHeaderDescriptor(): Byte = 0

    /**
     * Window_Descriptor byte. The window must cover the full back-reference
     * history (dict content + input). We pick the smallest exponent whose window
     * size >= history, encoded as zstd's `(exponent-10)<<3 | mantissa`. Using
     * mantissa 0 keeps it simple: windowSize = 1 << exponent.
     */
    private fun windowDescriptor(historySize: Int): Byte {
        var exponent = 10 // minimum window log
        while ((1L shl exponent) < historySize.toLong() && exponent < 31) exponent++
        val windowLog = exponent - 10
        return (windowLog shl 3).toByte()
    }

    private fun writeBlockHeader(out: ArrayList<Byte>, lastBlock: Boolean, blockType: Int, blockSize: Int) {
        val header = (if (lastBlock) 1 else 0) or (blockType shl 1) or (blockSize shl 3)
        out.add((header and 0xFF).toByte())
        out.add(((header ushr 8) and 0xFF).toByte())
        out.add(((header ushr 16) and 0xFF).toByte())
    }

    // ── Sequence building (the LZ matcher) ───────────────────────────────────────

    /** One zstd sequence: literalLength literals, then a matchLength match at `offset`. */
    private class Seq(val litLen: Int, val matchLen: Int, val offset: Int)

    private class Program(val sequences: List<Seq>, val literals: ByteArray)

    /**
     * Greedy/lazy LZ over `data`, referencing the dictionary content as history.
     * Positions are expressed against the virtual `[dictContent ++ data]` array:
     * a match at distance `d` from input position `i` copies bytes that may lie
     * in the dict content (when `d > i`) or earlier in `data`.
     */
    private fun buildSequences(data: ByteArray, dict: ZstdDictionary, index: MatchIndex): Program {
        val dictContent = dict.content
        val dictLen = dictContent.size
        val n = data.size

        // Combined-history accessor: position p in [0, dictLen) is dict content,
        // p in [dictLen, dictLen+n) is data[p-dictLen].
        fun histByte(p: Int): Int =
            if (p < dictLen) dictContent[p].toInt() and 0xFF else data[p - dictLen].toInt() and 0xFF

        // Per-input hash chain (continues the dict's chain). head/prev index the
        // combined history. We only INSERT input positions here; dict positions
        // live in the cached index.
        val head = IntArray(HASH_SIZE) { -1 }
        val prev = IntArray(n) { -1 }

        fun hashAt(p: Int): Int {
            // 4-byte hash; guarded so the last <4 bytes are never hashed.
            val b0 = histByte(p)
            val b1 = histByte(p + 1)
            val b2 = histByte(p + 2)
            val b3 = histByte(p + 3)
            val v = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
            return ((v * -1640531527) ushr (32 - HASH_LOG)) and (HASH_SIZE - 1)
        }

        fun insert(inputPos: Int) {
            // inputPos is a `data` index; its combined-history position is
            // dictLen+inputPos. The 4-byte hash needs 4 bytes available.
            if (inputPos + 4 > n) return
            val h = hashAt(dictLen + inputPos)
            prev[inputPos] = head[h]
            head[h] = inputPos
            // Also chain to the dict's most-recent occurrence for this hash so
            // matches can reach into the dictionary. We thread that via the
            // MatchIndex lookup in findMatch instead of merging arrays.
        }

        // Length of the common run between combined-history positions a and b
        // (a < b), capped at `limit` data-bytes available from b.
        fun matchLength(a: Int, b: Int, limit: Int): Int {
            var l = 0
            while (l < limit && histByte(a + l) == histByte(b + l)) l++
            return l
        }

        // Find the best match for input position `i`. Searches the input chain
        // and the dict index; returns (length, distance) or null.
        val maxChain = 64
        fun findMatch(i: Int): Long? {
            // The 4-byte hash + prefix check needs 4 bytes available at `i`.
            if (i + 4 > n) return null
            val cur = dictLen + i
            val available = n - i
            var bestLen = MIN_MATCH - 1
            var bestDist = 0

            // 1) Input chain (recent `data` positions with the same 4-byte hash).
            var p = head[hashAt(cur)]
            var chain = 0
            while (p >= 0 && chain < maxChain) {
                val candHist = dictLen + p
                val l = matchLength(candHist, cur, available)
                if (l > bestLen) {
                    bestLen = l
                    bestDist = cur - candHist
                }
                p = prev[p]
                chain++
            }

            // 2) Dictionary chain (positions inside the dict content sharing the
            // 4-byte prefix at `cur`). Distances here are large (cur - dictPos).
            val key = first4(::histByte, cur)
            index.forEachCandidate(key) { dictPos ->
                val l = matchLength(dictPos, cur, available)
                if (l > bestLen) {
                    bestLen = l
                    bestDist = cur - dictPos
                }
            }

            if (bestLen >= MIN_MATCH && bestDist > 0) {
                return (bestLen.toLong() shl 32) or (bestDist.toLong() and 0xFFFFFFFFL)
            }
            return null
        }

        val sequences = ArrayList<Seq>()
        val literals = ArrayList<Byte>(n)
        var i = 0
        var litStart = 0
        while (i < n) {
            val m = findMatch(i)
            if (m == null) {
                insert(i)
                i++
                continue
            }
            var len = (m ushr 32).toInt()
            var dist = (m and 0xFFFFFFFFL).toInt()

            // Lazy: peek i+1 for a strictly longer match; if found, emit a single
            // literal and take the better match next round.
            if (i + 1 < n) {
                insert(i)
                val m2 = findMatch(i + 1)
                if (m2 != null) {
                    val len2 = (m2 ushr 32).toInt()
                    if (len2 > len) {
                        i++
                        continue
                    }
                }
                // Emit the match at i. Flush pending literals as this sequence's
                // literal run.
                val litLen = i - litStart
                for (k in litStart until i) literals.add(data[k])
                sequences.add(Seq(litLen, len, dist))
                // Insert all covered positions into the chain for future matches.
                var q = i + 1
                val matchEnd = i + len
                while (q < matchEnd && q + MIN_MATCH <= n) { insert(q); q++ }
                i = matchEnd
                litStart = i
            } else {
                val litLen = i - litStart
                for (k in litStart until i) literals.add(data[k])
                sequences.add(Seq(litLen, len, dist))
                i += len
                litStart = i
            }
        }
        // Trailing literals after the last match (or the whole input if no match).
        for (k in litStart until n) literals.add(data[k])

        return Program(sequences, ByteArray(literals.size) { literals[it] })
    }

    private inline fun first4(histByte: (Int) -> Int, p: Int): Int {
        val b0 = histByte(p)
        val b1 = histByte(p + 1)
        val b2 = histByte(p + 2)
        val b3 = histByte(p + 3)
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    // ── Compressed block encoding ────────────────────────────────────────────────

    /**
     * Encode a single Compressed_Block body: a RAW literals section, then the
     * FSE-coded sequences section. Returns null only if it could not be built
     * (it always can for our inputs).
     */
    private fun encodeCompressedBlock(program: Program, data: ByteArray): ByteArray? {
        val out = ArrayList<Byte>(data.size + 16)

        // Literals_Section_Header (Raw, litType 0). Regenerated_Size = literals
        // length, encoded with the 1/2/3-byte size_format variants.
        writeRawLiteralsHeader(out, program.literals.size)
        program.literals.forEach { out.add(it) }

        // Sequences_Section.
        writeSequences(out, program.sequences)

        return ByteArray(out.size) { out[it] }
    }

    private fun writeRawLiteralsHeader(out: ArrayList<Byte>, size: Int) {
        // litType=0 (Raw). size_format selects the Regenerated_Size width:
        //  - size <  32      : 1-byte header, 5-bit size  (size_format bit0 = 0)
        //  - size <  4096    : 2-byte header, 12-bit size (size_format = 0b01)
        //  - else            : 3-byte header, 20-bit size (size_format = 0b11)
        when {
            size < 32 -> {
                out.add(((size shl 3) or (0 shl 2) or 0).toByte()) // [size:5][00][00]
            }
            size < 4096 -> {
                val b0 = (0) or (0b01 shl 2) or ((size and 0xF) shl 4)
                val b1 = (size ushr 4) and 0xFF
                out.add(b0.toByte())
                out.add(b1.toByte())
            }
            else -> {
                val b0 = (0) or (0b11 shl 2) or ((size and 0xF) shl 4)
                val b1 = (size ushr 4) and 0xFF
                val b2 = (size ushr 12) and 0xFF
                out.add(b0.toByte())
                out.add(b1.toByte())
                out.add(b2.toByte())
            }
        }
    }

    private fun writeSequences(out: ArrayList<Byte>, sequences: List<Seq>) {
        val nbSeq = sequences.size
        // Number_of_Sequences.
        when {
            nbSeq == 0 -> { out.add(0); return }
            nbSeq < 128 -> out.add(nbSeq.toByte())
            nbSeq < 0x7F00 -> {
                out.add((((nbSeq ushr 8) + 128) and 0xFF).toByte())
                out.add((nbSeq and 0xFF).toByte())
            }
            else -> {
                out.add(0xFF.toByte())
                val v = nbSeq - 0x7F00
                out.add((v and 0xFF).toByte())
                out.add(((v ushr 8) and 0xFF).toByte())
            }
        }

        // Symbol_Compression_Modes: predefined (0) for LL, OF, ML; low 2 bits 0.
        out.add(0)

        val llTable = predefinedLiteralLengthEnc
        val ofTable = predefinedOffsetEnc
        val mlTable = predefinedMatchLengthEnc

        // Translate each Seq into (code, extraValue) triples.
        val codes = Array(nbSeq) { computeCodes(sequences[it]) }

        val bw = ReverseBitWriter()

        // Encode in REVERSE sequence order so the backward bitstream reads
        // forward (see FseEncTable). The decoder read order is:
        //   init LL, OF, ML;
        //   per seq: symbols (from state), extra bits OF/ML/LL, then update LL/ML/OF.
        // The reverse-order writes below reproduce exactly that on decode.

        // Initial states from the LAST sequence's symbols.
        val last = codes[nbSeq - 1]
        var llSt = llTable.initialState(last.llCode)
        var ofSt = ofTable.initialState(last.ofCode)
        var mlSt = mlTable.initialState(last.mlCode)

        // Last sequence's extra bits (encoder order LL, ML, OF).
        writeExtra(bw, last)

        for (i in nbSeq - 2 downTo 0) {
            val c = codes[i]
            // Update transitions for seq i (encoder order OF, ML, LL).
            ofSt = ofTable.encode(bw, ofSt, c.ofCode)
            mlSt = mlTable.encode(bw, mlSt, c.mlCode)
            llSt = llTable.encode(bw, llSt, c.llCode)
            // Seq i extra bits (encoder order LL, ML, OF).
            writeExtra(bw, c)
        }

        // Flush the initial states (encoder order ML, OF, LL).
        mlTable.flushState(bw, mlSt)
        ofTable.flushState(bw, ofSt)
        llTable.flushState(bw, llSt)

        val stream = bw.finish()
        stream.forEach { out.add(it) }
    }

    /** Per-sequence FSE symbols + extra-bit payloads. */
    private class Codes(
        val llCode: Int, val llExtra: Int,
        val mlCode: Int, val mlExtra: Int,
        val ofCode: Int, val ofExtra: Int,
    )

    private fun writeExtra(bw: ReverseBitWriter, c: Codes) {
        // Decoder reads OF extra, then ML extra, then LL extra (chronological).
        // Reversed for the backward writer: LL, ML, OF.
        bw.writeBits(c.llExtra, LL_EXTRA_BITS[c.llCode])
        bw.writeBits(c.mlExtra, ML_EXTRA_BITS[c.mlCode])
        bw.writeBits(c.ofExtra, c.ofCode)
    }

    private fun computeCodes(seq: Seq): Codes {
        val llCode = literalLengthCode(seq.litLen)
        val llExtra = seq.litLen - LL_BASELINE[llCode]
        val mlCode = matchLengthCode(seq.matchLen)
        val mlExtra = seq.matchLen - ML_BASELINE[mlCode]
        // Explicit literal offset: offsetValue = distance + 3. ofCode = number of
        // extra bits = highBit(offsetValue); ofExtra = offsetValue - (1<<ofCode).
        val offsetValue = seq.offset + 3
        val ofCode = highBit(offsetValue)
        val ofExtra = offsetValue - (1 shl ofCode)
        return Codes(llCode, llExtra, mlCode, mlExtra, ofCode, ofExtra)
    }

    /** Map a literal length to its FSE code (largest baseline <= length). */
    private fun literalLengthCode(len: Int): Int {
        var c = LL_BASELINE.size - 1
        while (c > 0 && LL_BASELINE[c] > len) c--
        return c
    }

    private fun matchLengthCode(len: Int): Int {
        var c = ML_BASELINE.size - 1
        while (c > 0 && ML_BASELINE[c] > len) c--
        return c
    }

    private fun highBit(v: Int): Int {
        var n = 0
        var x = v
        while (x > 1) { x = x shr 1; n++ }
        return n
    }

    // Predefined FSE encoding tables (built once).
    private val predefinedLiteralLengthEnc: FseEncTable by lazy {
        FseEncTable.build(LL_DEFAULT_DISTRIBUTION, LL_DEFAULT_DISTRIBUTION.size - 1, LL_DEFAULT_LOG)
    }
    private val predefinedMatchLengthEnc: FseEncTable by lazy {
        FseEncTable.build(ML_DEFAULT_DISTRIBUTION, ML_DEFAULT_DISTRIBUTION.size - 1, ML_DEFAULT_LOG)
    }
    private val predefinedOffsetEnc: FseEncTable by lazy {
        FseEncTable.build(OF_DEFAULT_DISTRIBUTION, OF_DEFAULT_DISTRIBUTION.size - 1, OF_DEFAULT_LOG)
    }
}
