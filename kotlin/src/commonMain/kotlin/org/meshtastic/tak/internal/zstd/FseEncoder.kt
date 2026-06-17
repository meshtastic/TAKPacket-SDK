package org.meshtastic.tak.internal.zstd

/**
 * Finite State Entropy (FSE) ENCODING table — the inverse of the decode-side
 * [FseTable]. It is derived **from the very decode table the [FseTable.build]
 * routine produces**, so an encoded stream is provably consumable by this
 * decoder (and by libzstd, which builds the identical decode table from the same
 * normalized distribution).
 *
 * ## How encoding mirrors decoding
 *
 * The decoder keeps a state in `[0, tableSize)`. In decode-state `ds` it emits
 * `symbol[ds]`, then transitions: `next = newState[ds] + readBits(nbBits[ds])`.
 * The set of `next` values reachable from all decode-states that emit a given
 * symbol `s` tiles `[0, tableSize)` exactly (the FSE invariant).
 *
 * Encoding runs the symbol stream in REVERSE (last symbol first). The encoder
 * holds a `state` that equals the decode-state which will emit the symbol it is
 * about to encode. To encode symbol `s` while currently at `state = t` (where
 * `t` is the decode-state that emits the NEXT, already-encoded symbol), it:
 *   1. finds the unique decode-state `ds` with `symbol[ds] == s` whose output
 *      range `[newState[ds], newState[ds] + 2^nbBits[ds])` contains `t`,
 *   2. emits the low `nbBits[ds]` bits of `t - newState[ds]`,
 *   3. sets `state = ds`.
 * Because the writer is the backward [ReverseBitWriter], emitting in reverse
 * symbol order yields a stream the decoder reads in forward order.
 *
 * The very first thing the decoder reads is the initial state (`tableLog` bits),
 * which equals the encoder's FINAL `state` after the whole (reversed) stream —
 * so the encoder writes that state last.
 *
 * This table is built once per (distribution, tableLog); for the predefined
 * tables the SDK uses, it is cached.
 */
internal class FseEncTable private constructor(
    val tableLog: Int,
    private val tableSize: Int,
    // For each decode-state index `ds`: the symbol it emits, the bits it
    // consumes on transition, and the base of its output-state range. Encoding
    // searches, per symbol, the decode-states emitting that symbol.
    private val symbolStates: Array<IntArray>, // symbolStates[symbol] = sorted decode-states emitting it
    private val nbBits: IntArray, // nbBits[ds]
    private val newStateBase: IntArray, // newState[ds]  (== range base)
) {

    /**
     * Encode one [symbol] given the current [state], writing the transition bits
     * to [bw], and return the new encoder state. The first symbol of a stream
     * (in reverse order, i.e. the LAST output symbol) is encoded from a chosen
     * initial state via [initialState].
     */
    fun encode(bw: ReverseBitWriter, state: Int, symbol: Int): Int {
        val states = symbolStates[symbol]
        // Find the decode-state `ds` emitting `symbol` whose output range
        // [base, base + 2^nb) contains the target `state`. Ranges partition
        // [0,tableSize), so exactly one matches. The encoder emits `state - base`
        // in `nb` bits and moves to `ds`.
        for (ds in states) {
            val nb = nbBits[ds]
            val base = newStateBase[ds]
            val hi = base + (1 shl nb)
            if (state in base until hi) {
                bw.writeBits(state - base, nb)
                return ds
            }
        }
        // The FSE invariant guarantees a match; reaching here means a corrupt
        // table or an out-of-range symbol the caller failed to bound.
        throw ZstdFormatException("FSE encode: no transition for symbol $symbol from state $state")
    }

    /**
     * Pick the initial encoder state for [symbol] (the LAST output symbol, which
     * the encoder processes first). Any decode-state that emits [symbol] is a
     * valid starting state; choosing the smallest keeps the final flushed state
     * small and deterministic.
     */
    fun initialState(symbol: Int): Int = symbolStates[symbol].first()

    /** Write the final [state] (the decoder's initial-state read) as `tableLog` bits. */
    fun flushState(bw: ReverseBitWriter, state: Int) {
        bw.writeBits(state, tableLog)
    }

    companion object {
        /**
         * Build the encoding table by first building the decode table (so the two
         * are guaranteed consistent) and then indexing decode-states by symbol.
         */
        fun build(normalizedCounts: IntArray, maxSymbol: Int, tableLog: Int): FseEncTable {
            val decode = FseTable.build(normalizedCounts, maxSymbol, tableLog)
            val tableSize = 1 shl tableLog

            // Group decode-state indices by the symbol they emit. RLE tables
            // (tableLog 0, tableSize 1) collapse to a single state.
            val perSymbolLists = Array(maxSymbol + 1) { ArrayList<Int>() }
            for (ds in 0 until tableSize) {
                val s = decode.symbol[ds]
                if (s in 0..maxSymbol) perSymbolLists[s].add(ds)
            }
            val symbolStates = Array(maxSymbol + 1) { s ->
                IntArray(perSymbolLists[s].size) { perSymbolLists[s][it] }
            }

            return FseEncTable(
                tableLog = tableLog,
                tableSize = tableSize,
                symbolStates = symbolStates,
                nbBits = decode.nbBits,
                newStateBase = decode.newState,
            )
        }
    }
}
