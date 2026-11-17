package org.meshtastic.tak

/**
 * Arithmetic in GF(2^8) with the reduction polynomial 0x11D (the usual
 * Reed–Solomon field). Addition is XOR; multiplication and inversion go through
 * log/exp tables built once at load.
 */
internal object Gf256 {
    private const val POLY: Int = 0x11D
    private const val FIELD_SIZE: Int = 256
    private const val ORDER: Int = FIELD_SIZE - 1
    private const val OVERFLOW_BIT: Int = 0x100

    /** exp table, doubled so `EXP[LOG[a] + LOG[b]]` never needs a modulo. */
    val EXP: IntArray = IntArray(2 * FIELD_SIZE)

    /** log table; `LOG[0]` is unused (zero has no logarithm). */
    val LOG: IntArray = IntArray(FIELD_SIZE)

    init {
        var x = 1
        for (i in 0 until ORDER) {
            EXP[i] = x
            LOG[x] = i
            x = x shl 1
            if (x and OVERFLOW_BIT != 0) x = x xor POLY
        }
        for (i in ORDER until EXP.size) EXP[i] = EXP[i - ORDER]
    }

    fun mul(
        a: Int,
        b: Int,
    ): Int = if (a == 0 || b == 0) 0 else EXP[LOG[a] + LOG[b]]

    fun inv(a: Int): Int {
        require(a != 0) { "zero has no inverse in GF(256)" }
        return EXP[ORDER - LOG[a]]
    }
}

/**
 * Systematic MDS erasure code over GF(256), built from a column-scaled Cauchy
 * matrix. This is the codec behind fragment `code` 0 (design doc §5.3), and it is
 * kept separate from the fragment framing so a future file-transfer protocol can
 * reuse it unchanged (§5.5).
 *
 * A block has `k` source symbols of equal length `s`. Encoding symbol ID (ESI)
 * `e < k` is source symbol `e` itself; `e >= k` is a repair symbol:
 *
 * ```
 * coef(e, j) = (k xor j) * inv(e xor j)        for e >= k
 * symbol(e)  = sum over j of coef(e, j) * source[j]
 * ```
 *
 * The repair rows are a Cauchy matrix `1 / (x_e + y_j)` with `x_e = e` in
 * `[k, 255]` and `y_j = j` in `[0, k)`, each column scaled by a nonzero constant,
 * so every square submatrix is invertible and ANY `k` distinct symbols recover
 * the block. The scaling makes row `e = k` all ones: the first repair symbol is
 * the plain XOR of the sources.
 */
internal object TakFec {
    /** ESIs are one byte, so a block plus its repair symbols holds at most 256 symbols. */
    const val MAX_SYMBOLS: Int = 256

    /** Generator coefficient for ESI [esi] and source column [j] of a [k]-symbol block. */
    fun coef(
        esi: Int,
        j: Int,
        k: Int,
    ): Int =
        when {
            esi >= k -> Gf256.mul(k xor j, Gf256.inv(esi xor j))
            esi == j -> 1
            else -> 0
        }

    /** Repair symbol [esi] (which must be >= k) for [source], whose symbols all have the same length. */
    fun encodeRepair(
        source: List<ByteArray>,
        esi: Int,
    ): ByteArray {
        val k = source.size
        require(k in 1 until MAX_SYMBOLS) { "block size $k out of range" }
        require(esi in k until MAX_SYMBOLS) { "repair ESI $esi out of range for k=$k" }
        val s = source[0].size
        val out = ByteArray(s)
        for (j in 0 until k) {
            val c = coef(esi, j, k)
            val src = source[j]
            require(src.size == s) { "source symbols must all have length $s" }
            for (t in 0 until s) {
                out[t] = (out[t].toInt() xor Gf256.mul(c, src[t].toInt() and BYTE_MASK)).toByte()
            }
        }
        return out
    }

    /**
     * Recover the [k] source symbols from exactly [k] distinct (ESI, symbol)
     * pairs, every symbol of length [s]. Returns null if the ESIs are not
     * distinct or out of range; with valid ESIs the code is MDS, so the solve
     * always succeeds.
     */
    fun decode(
        k: Int,
        s: Int,
        symbols: List<Pair<Int, ByteArray>>,
    ): List<ByteArray>? {
        val esis = symbols.map { it.first }
        val valid = symbols.size == k && esis.toSet().size == k && esis.all { it in 0 until MAX_SYMBOLS }
        return when {
            !valid -> null
            esis.all { it < k } -> sourcesInOrder(k, symbols)
            else -> gaussJordan(k, s, symbols)
        }
    }

    /** Every source symbol is present: nothing to solve, just put them in ESI order. */
    private fun sourcesInOrder(
        k: Int,
        symbols: List<Pair<Int, ByteArray>>,
    ): List<ByteArray> {
        val out = arrayOfNulls<ByteArray>(k)
        for ((e, sym) in symbols) out[e] = sym
        return out.map { it!! }
    }

    /** Gauss–Jordan elimination over GF(256) on rows of [coefficients | symbol bytes]. */
    private fun gaussJordan(
        k: Int,
        s: Int,
        symbols: List<Pair<Int, ByteArray>>,
    ): List<ByteArray> {
        val coefs = Array(k) { r -> IntArray(k) { j -> coef(symbols[r].first, j, k) } }
        val data = Array(k) { r -> IntArray(s) { t -> symbols[r].second[t].toInt() and BYTE_MASK } }
        for (col in 0 until k) {
            // MDS: some remaining row always has a nonzero entry in this column.
            val pivot = (col until k).first { coefs[it][col] != 0 }
            swapRows(coefs, data, pivot, col)
            scaleRow(coefs[col], data[col], Gf256.inv(coefs[col][col]))
            for (r in 0 until k) {
                val f = coefs[r][col]
                if (r != col && f != 0) eliminate(coefs[r], data[r], coefs[col], data[col], f)
            }
        }
        return data.map { row -> ByteArray(s) { row[it].toByte() } }
    }

    private fun swapRows(
        coefs: Array<IntArray>,
        data: Array<IntArray>,
        a: Int,
        b: Int,
    ) {
        if (a == b) return
        coefs[a] = coefs[b].also { coefs[b] = coefs[a] }
        data[a] = data[b].also { data[b] = data[a] }
    }

    private fun scaleRow(
        coefRow: IntArray,
        dataRow: IntArray,
        factor: Int,
    ) {
        if (factor == 1) return
        for (j in coefRow.indices) coefRow[j] = Gf256.mul(factor, coefRow[j])
        for (t in dataRow.indices) dataRow[t] = Gf256.mul(factor, dataRow[t])
    }

    /** row -= factor * pivotRow, for both the coefficient and the data parts. */
    private fun eliminate(
        coefRow: IntArray,
        dataRow: IntArray,
        pivotCoefs: IntArray,
        pivotData: IntArray,
        factor: Int,
    ) {
        for (j in coefRow.indices) coefRow[j] = coefRow[j] xor Gf256.mul(factor, pivotCoefs[j])
        for (t in dataRow.indices) dataRow[t] = dataRow[t] xor Gf256.mul(factor, pivotData[t])
    }

    /**
     * Balanced split of an object into source blocks for bulk transfer: the
     * fewest blocks of at most [maxBlockK] symbols of length [s], with block
     * sizes differing by at most one (larger blocks first). TAK messages are
     * always a single block; this exists so the file-transfer path shares the
     * same codec and tests.
     */
    fun partition(
        objectLength: Int,
        s: Int,
        maxBlockK: Int,
    ): List<Int> {
        require(objectLength >= 0 && s > 0 && maxBlockK in 1 until MAX_SYMBOLS)
        val totalSymbols = maxOf(1, (objectLength + s - 1) / s)
        val blocks = (totalSymbols + maxBlockK - 1) / maxBlockK
        val base = totalSymbols / blocks
        val extra = totalSymbols % blocks
        return List(blocks) { if (it < extra) base + 1 else base }
    }
}
