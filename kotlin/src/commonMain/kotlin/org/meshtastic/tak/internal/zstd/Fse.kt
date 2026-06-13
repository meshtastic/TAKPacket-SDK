package org.meshtastic.tak.internal.zstd

/**
 * Finite State Entropy (FSE) decoding tables and bitstream header parsing,
 * per RFC 8878 §4.1.1 ("FSE").
 *
 * An [FseTable] is built from a normalized symbol-count distribution. Decoding
 * walks a state: each step emits the symbol at the current state, then reads
 * `nbBits` from the (backward) bitstream and adds them to `baseline` to form the
 * next state. The same machinery serves Huffman-weight decoding and the three
 * sequence symbol streams (literal-length, offset, match-length).
 */
internal class FseTable(
    val tableLog: Int,
    /** symbol emitted when entering state i */
    val symbol: IntArray,
    /** number of bits to read when transitioning out of state i */
    val nbBits: IntArray,
    /** baseline added to the freshly-read bits to form the next state */
    val newState: IntArray,
) {
    val size: Int get() = symbol.size

    companion object {
        const val FSE_MAX_TABLELOG = 15

        /**
         * Build an FSE decoding table from a normalized count distribution.
         * [normalizedCounts] holds, for each symbol 0..maxSymbol, its count;
         * a count of -1 denotes a "less than 1" probability symbol that still
         * occupies one table cell. The counts sum to `1 << tableLog`.
         *
         * This is the canonical zstd table-build (RFC 8878 §4.1.1, "From
         * normalized distribution to decoding tables"): symbols are spread
         * across the table with the step `(size>>1) + (size>>3) + 3` skipping
         * cells already claimed by low-probability symbols, then each cell's
         * `nbBits`/`newState`/`symbol` are derived.
         */
        fun build(normalizedCounts: IntArray, maxSymbol: Int, tableLog: Int): FseTable {
            val tableSize = 1 shl tableLog
            val tableMask = tableSize - 1
            val symbolTable = IntArray(tableSize)
            val nbBitsTable = IntArray(tableSize)
            val newStateTable = IntArray(tableSize)

            // Per-symbol next-state counters; "less than 1" probability symbols
            // are placed at the high end of the table first.
            val symbolNext = IntArray(maxSymbol + 1)
            var highThreshold = tableSize - 1

            for (s in 0..maxSymbol) {
                when {
                    normalizedCounts[s] == -1 -> {
                        // Low-probability symbol: claim a cell at the top, counter = 1.
                        symbolTable[highThreshold] = s
                        highThreshold--
                        symbolNext[s] = 1
                    }
                    else -> symbolNext[s] = normalizedCounts[s]
                }
            }

            // Spread the remaining (probability >= 1) symbols across the table.
            val step = (tableSize shr 1) + (tableSize shr 3) + 3
            var position = 0
            for (s in 0..maxSymbol) {
                val count = normalizedCounts[s]
                if (count <= 0) continue // skip absent (0) and low-prob (-1, already placed)
                for (i in 0 until count) {
                    symbolTable[position] = s
                    position = (position + step) and tableMask
                    // Skip cells already reserved for low-probability symbols.
                    while (position > highThreshold) {
                        position = (position + step) and tableMask
                    }
                }
            }
            if (position != 0) {
                throw ZstdFormatException("FSE table spread did not return to 0 (corrupt distribution)")
            }

            // Build the per-cell transition info.
            for (i in 0 until tableSize) {
                val s = symbolTable[i]
                val next = symbolNext[s]
                symbolNext[s] = next + 1
                // nbBits = tableLog - floor(log2(next)). newState lays the symbol's
                // occurrences contiguously at the bottom of the next-state range.
                val nbBits = tableLog - highBit(next)
                nbBitsTable[i] = nbBits
                newStateTable[i] = ((next shl nbBits) - tableSize)
            }

            return FseTable(tableLog, symbolTable, nbBitsTable, newStateTable)
        }

        /** Floor(log2(v)) for v >= 1. */
        private fun highBit(v: Int): Int {
            var n = 0
            var x = v
            while (x > 1) { x = x shr 1; n++ }
            return n
        }
    }
}

/** A running FSE decode state. */
internal class FseState(private val table: FseTable) {
    var state: Int = 0
        private set

    /** Initialize the state by reading [FseTable.tableLog] bits. */
    fun init(br: ReverseBitReader) {
        state = br.readBits(table.tableLog)
    }

    /** The symbol the current state decodes to (read it BEFORE [update]). */
    fun symbol(): Int = table.symbol[state]

    /** Advance to the next state by consuming this state's `nbBits` bits. */
    fun update(br: ReverseBitReader) {
        val nb = table.nbBits[state]
        val rest = br.readBits(nb)
        state = table.newState[state] + rest
    }
}

/**
 * Parse an FSE table description from the forward byte stream (RFC 8878
 * §4.1.1, "FSE Table Description"). Returns the built table and advances the
 * reader past the consumed header bytes. [maxLog] / [maxSymbol] bound the table.
 *
 * The header is itself a tiny variable-bit-width stream read FORWARD by bits
 * (LSB-first within each byte): a 4-bit `Accuracy_Log` bias, then a sequence of
 * per-symbol counts encoded with a shrinking bit width derived from the
 * remaining probability budget, with a run-length escape for streaks of zero
 * counts.
 */
internal fun parseFseTable(
    reader: ForwardByteReader,
    maxLog: Int,
    maxSymbol: Int,
): FseTable {
    val bits = ForwardBitReader(reader)

    // This mirrors the reference `FSE_readNCount`. `remaining` tracks the
    // probability budget left (starts at tableSize+1); `threshold`/`nbBits`
    // shrink as the budget drains so common small counts cost fewer bits.
    val tableLog = bits.read(4) + 5
    if (tableLog > maxLog) throw ZstdFormatException("FSE tableLog $tableLog > max $maxLog")
    val tableSize = 1 shl tableLog

    val normalizedCounts = IntArray(maxSymbol + 1)
    var remaining = tableSize + 1
    var threshold = tableSize
    var nbBits = tableLog + 1
    var symbol = 0
    var previousIsZero = false

    while (remaining > 1 && symbol <= maxSymbol) {
        if (previousIsZero) {
            // Run-length of extra zero-probability symbols. Each group is 2 bits;
            // a group value of 3 means "skip 3 and continue", any smaller value
            // ends the run after adding that many zeros.
            var n0 = symbol
            while (true) {
                val twoBits = bits.read(2)
                n0 += twoBits
                if (twoBits != 3) break
            }
            while (symbol < n0 && symbol <= maxSymbol) normalizedCounts[symbol++] = 0
            previousIsZero = false
            continue
        }

        val max = (2 * threshold - 1) - remaining
        var count: Int
        var value = bits.read(nbBits - 1)
        if (value < max) {
            count = value
        } else {
            val extra = bits.read(1)
            value += extra shl (nbBits - 1)
            count = if (value >= threshold) value - max else value
        }
        // Encoded value is (probability + 1); decode to the signed probability.
        count -= 1
        remaining -= if (count < 0) -count else count
        normalizedCounts[symbol] = count
        symbol++
        previousIsZero = (count == 0)

        // Shrink the field width as the budget drains.
        while (remaining < threshold) {
            nbBits--
            threshold = threshold shr 1
        }
    }

    // Any trailing symbols up to maxSymbol implicitly have count 0.
    while (symbol <= maxSymbol) normalizedCounts[symbol++] = 0

    if (remaining != 1) {
        throw ZstdFormatException("FSE distribution did not sum to table size (remaining=$remaining)")
    }

    bits.alignToByte()

    return FseTable.build(normalizedCounts, maxSymbol, tableLog)
}

/**
 * Forward, LSB-first bit reader used only for FSE table headers. Bits are taken
 * from the low end of the current byte first; bytes are consumed in order from
 * the underlying [ForwardByteReader]. After the header, [alignToByte] advances
 * the byte reader past any partially-consumed byte.
 */
internal class ForwardBitReader(private val reader: ForwardByteReader) {
    private var container: Int = 0
    private var bitsInContainer: Int = 0
    private var bytesConsumed: Int = 0

    fun read(n: Int): Int {
        if (n == 0) return 0
        while (bitsInContainer < n) {
            val b = reader.readByte()
            bytesConsumed++
            container = container or (b shl bitsInContainer)
            bitsInContainer += 8
        }
        val v = container and ((1 shl n) - 1)
        container = container ushr n
        bitsInContainer -= n
        return v
    }

    /**
     * Rewind the underlying byte reader so the next forward byte read begins at
     * the first byte NOT (even partially) needed by the bit stream. A partially
     * consumed byte still counts as consumed (FSE headers are byte-aligned at
     * their end).
     */
    fun alignToByte() {
        // bitsInContainer holds leftover whole+partial bits from the last fetched
        // byte(s). Whole unused bytes must be returned to the reader.
        val wholeUnusedBytes = bitsInContainer / 8
        reader.pos -= wholeUnusedBytes
    }
}
