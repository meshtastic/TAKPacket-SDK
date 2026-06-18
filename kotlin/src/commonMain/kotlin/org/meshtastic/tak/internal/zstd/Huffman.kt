package org.meshtastic.tak.internal.zstd

/**
 * Huffman decoding table for the literals section (RFC 8878 §4.2.1).
 *
 * Zstd Huffman is canonical and decoded MSB-first via a flat lookup table of
 * `1 << maxBits` entries: the top [maxBits] bits of the (backward) bitstream
 * index the table, yielding the symbol and how many bits it actually consumed.
 *
 * A table can be parsed fresh from a block ("compressed" literals) or reused
 * from the dictionary / a previous block ("treeless"/repeat literals). The
 * dictionary's Huffman table (parsed from its entropy-tables section) is exactly
 * such a reusable table, so [HuffmanTable] is also what [ZstdDictionary] hands
 * to a treeless literals block.
 */
internal class HuffmanTable(
    val maxBits: Int,
    /** symbol for each of the `1 << maxBits` table slots */
    val symbols: ByteArray,
    /** number of bits the symbol in that slot consumes */
    val numBits: ByteArray,
) {
    companion object {
        const val HUF_MAX_TABLELOG = 12
        const val MAX_SYMBOL = 255

        /**
         * Build a canonical Huffman decode table from per-symbol weights.
         *
         * Weight `w > 0` ⇒ code length `maxBits + 1 - w`; weight 0 ⇒ absent.
         * The stored weights cover all symbols EXCEPT the last present one,
         * whose weight is implied: the total of `2^(w-1)` over all symbols must
         * be a power of two (`2^maxBits`), so the final symbol takes whatever
         * weight rounds the running total up to the next power of two. [weights]
         * here already contains ONLY the explicitly-coded weights ([numSymbols]
         * of them); this routine appends the implied final weight.
         */
        fun fromWeights(weights: IntArray, numSymbols: Int): HuffmanTable {
            var weightSum = 0
            for (i in 0 until numSymbols) {
                val w = weights[i]
                if (w > 0) {
                    if (w > HUF_MAX_TABLELOG) throw ZstdFormatException("Huffman: weight $w too large")
                    weightSum += (1 shl (w - 1))
                }
            }
            if (weightSum == 0) throw ZstdFormatException("Huffman: zero total weight")

            // maxBits = floor(log2(weightSum)) + 1; the leftover up to 2^maxBits
            // is the implied final symbol's contribution.
            val maxBits = highBit(weightSum) + 1
            if (maxBits > HUF_MAX_TABLELOG) {
                throw ZstdFormatException("Huffman: maxBits $maxBits > $HUF_MAX_TABLELOG")
            }
            val total = 1 shl maxBits
            val leftover = total - weightSum
            if (leftover <= 0 || (leftover and (leftover - 1)) != 0) {
                throw ZstdFormatException("Huffman: invalid weight total (leftover=$leftover)")
            }
            val lastWeight = highBit(leftover) + 1

            val symbolCount = numSymbols + 1
            val fullWeights = IntArray(symbolCount)
            for (i in 0 until numSymbols) fullWeights[i] = weights[i]
            fullWeights[numSymbols] = lastWeight

            val tableSize = total
            val symbols = ByteArray(tableSize)
            val numBitsArr = ByteArray(tableSize)

            // Count symbols per weight, then assign contiguous slot ranges in
            // canonical order: shortest code (highest weight) first, and within
            // a weight by ascending symbol index.
            val rankCount = IntArray(maxBits + 2)
            for (i in 0 until symbolCount) {
                val w = fullWeights[i]
                if (w > 0) rankCount[w]++
            }
            // Slot ranges per weight, ASCENDING weight (weight 1 = the longest
            // code = the LOWEST table indices), matching the reference
            // `HUF_readDTableX1` `rankVal` accumulation. Getting this order
            // wrong silently produces a self-consistent but WRONG table that
            // decodes literals to garbage.
            val rankStart = IntArray(maxBits + 2)
            var slot = 0
            for (w in 1..maxBits) {
                rankStart[w] = slot
                slot += rankCount[w] * (1 shl (w - 1))
            }
            if (slot != tableSize) {
                throw ZstdFormatException("Huffman: filled $slot != table size $tableSize")
            }

            for (sym in 0 until symbolCount) {
                val w = fullWeights[sym]
                if (w == 0) continue
                val length = maxBits + 1 - w
                val span = 1 shl (w - 1)
                val start = rankStart[w]
                for (j in 0 until span) {
                    symbols[start + j] = sym.toByte()
                    numBitsArr[start + j] = length.toByte()
                }
                rankStart[w] = start + span
            }

            return HuffmanTable(maxBits, symbols, numBitsArr)
        }

        private fun highBit(v: Int): Int {
            var n = 0
            var x = v
            while (x > 1) { x = x shr 1; n++ }
            return n
        }
    }

    /**
     * Decode one symbol from the backward [br], consuming exactly its code
     * length. Peeks [maxBits] (the bit reader zero-pads beyond the stream end),
     * looks the slot up, and skips only the real code length.
     */
    fun decode(br: ReverseBitReader): Int {
        val index = br.peekBits(maxBits)
        val sym = symbols[index].toInt() and 0xFF
        br.skipBits(numBits[index].toInt())
        return sym
    }
}

/**
 * Parse a Huffman table description (RFC 8878 §4.2.1, "Huffman Tree
 * Description") from the forward stream, returning the built [HuffmanTable] and
 * leaving [reader] positioned just after the description.
 *
 * The first byte selects the encoding:
 *  - `< 128`: weights are FSE-compressed; the byte is the count of compressed
 *    bytes that follow (an FSE table header + a 2-state weight bitstream).
 *  - `>= 128`: weights are stored directly, 4 bits each; `byte - 127` weights
 *    total, packed two nibbles per byte (high nibble first).
 */
internal fun parseHuffmanTable(reader: ForwardByteReader): HuffmanTable {
    val headerByte = reader.readByte()

    if (headerByte >= 128) {
        val numWeights = headerByte - 127
        val weights = IntArray(numWeights)
        var i = 0
        while (i < numWeights) {
            val b = reader.readByte()
            weights[i] = b ushr 4
            if (i + 1 < numWeights) weights[i + 1] = b and 0x0F
            i += 2
        }
        return HuffmanTable.fromWeights(weights, numWeights)
    }

    // FSE-compressed weights. `headerByte` = number of bytes in the FSE block
    // (table header + weight bitstream), which immediately follows.
    val compressedSize = headerByte
    val blockStart = reader.pos
    val blockEnd = blockStart + compressedSize
    if (blockEnd > reader.endPos) throw ZstdFormatException("Huffman: weight FSE block overruns input")

    val tableReader = ForwardByteReader(reader.backingArray, blockStart, blockEnd)
    // Weight values are 0..HUF_MAX_TABLELOG; the weight FSE uses accuracy log
    // <= 6 and max symbol = HUF_MAX_TABLELOG.
    val fseTable = parseFseTable(tableReader, maxLog = 6, maxSymbol = HuffmanTable.HUF_MAX_TABLELOG)
    val bitstreamStart = tableReader.pos
    val br = ReverseBitReader(reader.backingArray, bitstreamStart, blockEnd)

    val weights = decodeWeightStream(fseTable, br)
    reader.pos = blockEnd
    return HuffmanTable.fromWeights(weights, weights.size)
}

/**
 * Decode the FSE-compressed Huffman-weight bitstream into a weight per symbol.
 *
 * Two FSE states alternate (the writer interleaves them). Each step emits the
 * symbol of the current state, THEN consumes that state's transition bits. The
 * stream is sized exactly to the weights, so we stop the instant a transition
 * would read past the start: the symbol read just before that over-read is the
 * final one. We detect the over-read via [ReverseBitReader.overflowed], which
 * is set only when a read dipped below bit 0 (true padding).
 */
internal fun decodeWeightStream(fseTable: FseTable, br: ReverseBitReader): IntArray {
    val collected = ArrayList<Int>()
    val s1 = FseState(fseTable)
    val s2 = FseState(fseTable)
    s1.init(br)
    s2.init(br)
    var current = s1
    var other = s2
    // Huffman descriptions cover at most MAX_SYMBOL+1 (256) weights. A crafted
    // FSE weight table can encode a 0-bit self-loop transition that never sets
    // `overflowed`, so the loop must be hard-bounded: without this cap a
    // malicious frame spins forever and grows `collected` without limit
    // (bypasses the 4096 decompressed-size bomb guard).
    val maxWeights = HuffmanTable.MAX_SYMBOL + 1
    while (true) {
        // Emit the current state's symbol; it is always valid (the state was
        // produced by a previous in-range transition or by init).
        collected.add(current.symbol())
        if (collected.size > maxWeights) {
            throw ZstdFormatException("Huffman: weight stream exceeds $maxWeights symbols (malformed)")
        }
        // Attempt the transition. zstd interleaves two states and the stream is
        // sized exactly to the weights, so once a transition reads past the
        // stream start (overflow) BOTH states have one final pending symbol: the
        // one just emitted, and the OTHER state's current symbol. Emit that
        // trailing symbol and stop. (Stopping without it drops the last weight
        // and makes the implied-symbol total come up short.)
        current.update(br)
        if (br.overflowed) {
            collected.add(other.symbol())
            break
        }
        // A 0-bit (nbBits == 0) FSE transition is LEGAL — high-probability
        // weight symbols transition without consuming bits — so the bit position
        // legitimately does not advance on those steps. Termination is bounded by
        // the `maxWeights` cap above (and by `overflowed`), NOT by requiring every
        // transition to advance. (An earlier guard threw on any non-advancing
        // transition; that was a false positive — it rejected valid trained
        // dictionaries whose weight FSE table contains 0-bit states.)
        val t = current; current = other; other = t
    }
    return IntArray(collected.size) { collected[it] }
}
