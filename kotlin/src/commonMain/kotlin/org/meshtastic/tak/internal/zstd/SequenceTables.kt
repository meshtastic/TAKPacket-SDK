package org.meshtastic.tak.internal.zstd

// ── Symbol-space bounds for the three sequence FSE tables (RFC 8878 §3.1.1.3.2)
internal const val LITERAL_LENGTH_MAX_SYMBOL = 35
internal const val MATCH_LENGTH_MAX_SYMBOL = 52
internal const val OFFSET_MAX_SYMBOL = 31 // offset codes; trained dicts cap well below this

internal const val LITERAL_LENGTH_MAX_LOG = 9
internal const val MATCH_LENGTH_MAX_LOG = 9
internal const val OFFSET_MAX_LOG = 8

/**
 * Literal-length code → (baseline, number of extra bits) (RFC 8878 §3.1.1.3.2.1).
 * Codes 0..15 are literal values 0..15 (0 extra bits); 16..35 add extra bits.
 */
internal val LL_BASELINE = intArrayOf(
    0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15,
    16, 18, 20, 22, 24, 28, 32, 40, 48, 64, 128, 256, 512, 1024,
    2048, 4096, 8192, 16384, 32768, 65536,
)
internal val LL_EXTRA_BITS = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    1, 1, 1, 1, 2, 2, 3, 3, 4, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
)

/**
 * Match-length code → (baseline, number of extra bits) (RFC 8878 §3.1.1.3.2.2).
 * The minimum match length is 3, so code 0 maps to baseline 3.
 */
internal val ML_BASELINE = intArrayOf(
    3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
    19, 20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34,
    35, 37, 39, 41, 43, 47, 51, 59, 67, 83, 99, 131, 259, 515, 1027, 2051,
    4099, 8195, 16387, 32771, 65539,
)
internal val ML_EXTRA_BITS = intArrayOf(
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
    1, 1, 1, 1, 2, 2, 3, 3, 4, 4, 5, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16,
)

// ── Predefined FSE distributions (RFC 8878 §3.1.1.3.2.2, "Default Distributions")
// A value of -1 denotes a "less than 1" probability symbol (one table cell).

internal val LL_DEFAULT_DISTRIBUTION = intArrayOf(
    4, 3, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 1, 1, 1,
    2, 2, 2, 2, 2, 2, 2, 2, 2, 3, 2, 1, 1, 1, 1, 1,
    -1, -1, -1, -1,
)
internal const val LL_DEFAULT_LOG = 6

// Exactly zstd's `ML_defaultNorm` (53 entries, sum to 64): counts of 1 run from
// index 9 through 45 (37 ones), then seven low-probability (-1) symbols at
// indices 46..52. (An earlier transcription stopped the run of 1s too early —
// 33 ones + 11 -1s — which spread the predefined ML table differently and made
// the first match-length of every predefined-ML sequence decode one symbol off.)
internal val ML_DEFAULT_DISTRIBUTION = intArrayOf(
    1, 4, 3, 2, 2, 2, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, -1, -1,
    -1, -1, -1, -1, -1,
)
internal const val ML_DEFAULT_LOG = 6

internal val OF_DEFAULT_DISTRIBUTION = intArrayOf(
    1, 1, 1, 1, 1, 1, 2, 2, 2, 1, 1, 1, 1, 1, 1, 1,
    1, 1, 1, 1, 1, 1, 1, 1, -1, -1, -1, -1, -1,
)
internal const val OF_DEFAULT_LOG = 5

/** Build the three predefined FSE tables from their default distributions. */
internal fun predefinedLiteralLengthTable(): FseTable =
    FseTable.build(LL_DEFAULT_DISTRIBUTION, LL_DEFAULT_DISTRIBUTION.size - 1, LL_DEFAULT_LOG)

internal fun predefinedMatchLengthTable(): FseTable =
    FseTable.build(ML_DEFAULT_DISTRIBUTION, ML_DEFAULT_DISTRIBUTION.size - 1, ML_DEFAULT_LOG)

internal fun predefinedOffsetTable(): FseTable =
    FseTable.build(OF_DEFAULT_DISTRIBUTION, OF_DEFAULT_DISTRIBUTION.size - 1, OF_DEFAULT_LOG)
