package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * wasmWasi-specific codec contract (`kotlin.test`).
 *
 * wasmWasi has no JS host, no cinterop, and no native libzstd, yet it is a
 * FULLY capable target via the pure-Kotlin `org.meshtastic.kzstd` codec: it
 * compresses and decompresses through kzstd on this target like every other.
 * This pins both halves of that contract:
 *
 *  - [TakCompressor.compress] produces a wire payload that [TakCompressor.decompress]
 *    round-trips back to the same packet (codec on this target = pure Kotlin).
 *  - The codec-level frame is a standard zstd frame (with magic), decodable by
 *    the pure-Kotlin decoder.
 *  - A golden frame produced by another binding still decodes here.
 *
 * The shared common suites (RoundTrip / Resilience / Decode) also run on
 * wasmWasi — this locks the wasmWasi-only specifics.
 */
class WasmWasiCodecTest {

    @Test
    fun codecCompressRoundTrips() {
        // The codec's compress actual now uses the pure-Kotlin encoder; the
        // pure-Kotlin decoder must reproduce the input exactly.
        val input = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 1, 2, 3, 4, 5)
        val frame = ZstdCodec.compressWithDict(input, DictionaryProvider.DICT_ID_NON_AIRCRAFT, 19)
        val back = ZstdCodec.decompressWithDict(
            frame, DictionaryProvider.DICT_ID_NON_AIRCRAFT, TakCompressor.MAX_DECOMPRESSED_SIZE,
        )
        assertContentEquals(input, back)
    }

    @Test
    fun takCompressorParseCompressDecompressRoundTrips() {
        val parser = CotXmlParser()
        val compressor = TakCompressor()

        // Parse works on wasmWasi (pure-Kotlin xmlutil parser).
        val packet = parser.parse(InlinedFixtures.xml.getValue("pli_basic"))
        assertEquals("testnode", packet.uid)

        // Compress now works on wasmWasi (pure-Kotlin encoder), under the MTU.
        val wire = compressor.compress(packet)
        assertTrue(wire.size <= 237, "wire payload ${wire.size}B exceeds MTU 237")

        // And decompress round-trips the key fields.
        val decoded = compressor.decompress(wire)
        assertEquals(packet.cotTypeId, decoded.cotTypeId)
        assertEquals(packet.uid, decoded.uid)
        assertEquals(packet.callsign, decoded.callsign)

        // A golden frame from another binding still decodes here too.
        val golden = InlinedFixtures.goldenWire.getValue("pli_basic")
        val fromGolden = compressor.decompress(golden)
        assertEquals(packet.uid, fromGolden.uid)
    }

    @Test
    fun decompressionGuardsTheMaxSize() {
        // A real over-cap guard: an uncompressed (0xFF) payload whose body
        // exceeds MAX_DECOMPRESSED_SIZE must be REJECTED, not returned. The 0xFF
        // path enforces the cap itself (no zstd pass to do it), so this confirms
        // the size guard actually trips on oversized input rather than just
        // asserting a small valid frame stays small.
        val tooBig = ByteArray(1 + TakCompressor.MAX_DECOMPRESSED_SIZE + 1)
        tooBig[0] = DictionaryProvider.DICT_ID_UNCOMPRESSED.toByte()
        assertFailsWith<IllegalArgumentException> { TakCompressor().decompress(tooBig) }
    }
}
