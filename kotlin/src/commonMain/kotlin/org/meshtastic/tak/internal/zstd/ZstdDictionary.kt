package org.meshtastic.tak.internal.zstd

/**
 * A parsed zstd trained dictionary (RFC 8878 §5, "Dictionary Format").
 *
 * Layout:
 * ```
 * Magic_Number   4 bytes  = 0x37 0xA4 0x30 0xEC (little-endian 0xEC30A437)
 * Dictionary_ID  4 bytes  (little-endian)
 * Entropy_Tables          Huffman table for literals, then 3 FSE tables in the
 *                         order offset, match-length, literal-length, then the
 *                         3 default repeat-offset values (3 × 4-byte LE)
 * Content                 the remaining bytes — the back-reference history a
 *                         frame's first matches may copy from
 * ```
 *
 * A dictionary-compressed frame may, in its first block, request "repeat"
 * (treeless) entropy: literals using the dict's Huffman table, and any of the
 * three sequence FSE tables using the dict's corresponding table. The frame's
 * three repeat-offset slots are also seeded from the dictionary's stored
 * offsets. All of that lives here so the decoder can reference it.
 */
internal class ZstdDictionary private constructor(
    val literalsHuffman: HuffmanTable?,
    val offsetFse: FseTable?,
    val matchLengthFse: FseTable?,
    val literalLengthFse: FseTable?,
    val repeatOffsets: IntArray,
    val content: ByteArray,
) {
    companion object {
        private const val DICT_MAGIC = 0xEC30A437.toInt()

        /** Repeat-offset defaults for a frame with NO dictionary (RFC 8878 §3.1.1.3.3). */
        val DEFAULT_REPEAT_OFFSETS = intArrayOf(1, 4, 8)

        /**
         * Parsed-dictionary cache keyed by the source byte array's *reference*
         * identity (a plain [HashMap] keys [ByteArray] by reference, not content).
         *
         * Parsing a trained dictionary re-reads its full entropy tables and
         * copies its ~512 KB content into [content] — pure work that yields the
         * same immutable result for the same bytes. On the web/wasi decode path
         * this runs once per packet, so without a cache every received frame
         * re-parsed the static shipped dictionary. The SDK always hands the same
         * `lazy`-held dictionary array ([DictionaryProvider.getDictionary]) to
         * the decoder, so the identity key hits on the second and every later
         * decode.
         *
         * This caches the STATIC shipped dictionary only; it holds zero
         * cross-packet decoded state, so it does not affect the resilience
         * invariant. The web/wasm/wasi production decode paths are
         * single-threaded; on the JVM the cache is touched only by tests.
         */
        private val parseCache = HashMap<ByteArray, ZstdDictionary>()

        /**
         * Parse [bytes], reusing a previously parsed result for the same array
         * reference. See [parseCache]. This is the entry point the decoder uses
         * on its per-packet hot path so the static dictionary is parsed once.
         */
        fun parseCached(bytes: ByteArray): ZstdDictionary =
            parseCache.getOrPut(bytes) { parse(bytes) }

        /**
         * Parse [bytes]. A "raw content" dictionary (anything that does NOT
         * start with the trained-dict magic, including the empty dictionary) is
         * treated as pure content with default entropy/offsets — the decoder
         * then uses predefined FSE tables and must Huffman-decode every block
         * fresh. Our shipped dictionaries are trained, so the full entropy path
         * is exercised; this fallback keeps the decoder total.
         */
        fun parse(bytes: ByteArray): ZstdDictionary {
            if (bytes.size < 8 || leInt(bytes, 0) != DICT_MAGIC) {
                return ZstdDictionary(
                    literalsHuffman = null,
                    offsetFse = null,
                    matchLengthFse = null,
                    literalLengthFse = null,
                    repeatOffsets = DEFAULT_REPEAT_OFFSETS.copyOf(),
                    content = bytes,
                )
            }

            // Skip the 4-byte Magic_Number + 4-byte Dictionary_ID (offset 8): the
            // SDK selects the dictionary out-of-band via the wire flags byte, so
            // the frame-embedded Dictionary_ID is not needed here.
            val reader = ForwardByteReader(bytes, 8, bytes.size)

            // Entropy tables, in the dictionary's defined order.
            val huffman = parseHuffmanTable(reader)
            val offsetFse = parseFseTable(reader, maxLog = OFFSET_MAX_LOG, maxSymbol = OFFSET_MAX_SYMBOL)
            val matchFse = parseFseTable(reader, maxLog = MATCH_LENGTH_MAX_LOG, maxSymbol = MATCH_LENGTH_MAX_SYMBOL)
            val litLenFse = parseFseTable(reader, maxLog = LITERAL_LENGTH_MAX_LOG, maxSymbol = LITERAL_LENGTH_MAX_SYMBOL)

            // Three 4-byte little-endian repeat offsets.
            val rep = IntArray(3)
            for (i in 0 until 3) rep[i] = reader.readLEInt(4)

            val contentStart = reader.pos
            val content = bytes.copyOfRange(contentStart, bytes.size)

            return ZstdDictionary(
                literalsHuffman = huffman,
                offsetFse = offsetFse,
                matchLengthFse = matchFse,
                literalLengthFse = litLenFse,
                repeatOffsets = rep,
                content = content,
            )
        }

        private fun leInt(b: ByteArray, off: Int): Int =
            (b[off].toInt() and 0xFF) or
                ((b[off + 1].toInt() and 0xFF) shl 8) or
                ((b[off + 2].toInt() and 0xFF) shl 16) or
                ((b[off + 3].toInt() and 0xFF) shl 24)
    }
}
