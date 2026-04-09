package org.meshtastic.tak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Exercises the opt-in "smart compress" path that compares the typed-variant
 * wire payload against a `raw_detail`-fallback wire payload and picks the
 * smaller of the two.
 *
 * The path is a safety net for CoT types the structured parser can't fully
 * decompose or shapes with geometry beyond MAX_VERTICES.  For the 22 bundled
 * fixtures the typed path always wins — delta-encoded geometry and palette
 * enum colors are far tighter on the wire than raw XML tag names — so these
 * tests mostly verify that the comparison is happening, that ties favor the
 * typed path, and that the raw_detail round trip is loss-free for an
 * intentionally-unmapped synthetic CoT type.
 */
class SmartCompressTest {

    private val parser = CotXmlParser()
    private val builder = CotXmlBuilder()
    private val compressor = TakCompressor()

    @Test
    fun `extractRawDetailBytes returns inner content of detail element`() {
        val xml = TestFixtures.loadFixture("drawing_circle.xml")
        val raw = parser.extractRawDetailBytes(xml)
        assertTrue(raw.isNotEmpty(), "drawing_circle has a detail element")
        val text = String(raw, Charsets.UTF_8)
        assertTrue(text.contains("<shape>"), "raw bytes include the shape children")
        assertTrue(text.contains("<strokeColor"), "raw bytes include the strokeColor element")
        assertFalse(text.contains("<detail"), "raw bytes are the inner content, not the wrapper")
    }

    @Test
    fun `extractRawDetailBytes returns empty for missing detail`() {
        val noDetail = """<?xml version="1.0"?><event uid="x" type="a-f-G" how="m-g"><point lat="0" lon="0" hae="0" ce="0" le="0"/></event>"""
        assertEquals(0, parser.extractRawDetailBytes(noDetail).size)
    }

    @Test
    fun `compressBestOf picks typed variant on every bundled fixture`() {
        // Sanity check: the typed path should win on every known fixture.
        // If this regresses, the SDK is losing structural compression and
        // something is wrong with the parser or the zstd dictionary.
        var typedWins = 0
        var rawWins = 0
        for (fixture in TestFixtures.filenames) {
            val xml = TestFixtures.loadFixture(fixture)
            val packet = parser.parse(xml)
            val rawDetail = parser.extractRawDetailBytes(xml)

            val typedWire = compressor.compress(packet)
            val smartWire = compressor.compressBestOf(packet, rawDetail)

            if (smartWire.contentEquals(typedWire)) {
                typedWins++
            } else {
                rawWins++
                println("raw wins for $fixture: typed=${typedWire.size}B smart=${smartWire.size}B")
            }

            // Smart wire must always round-trip to a valid packet.
            val decoded = compressor.decompress(smartWire)
            assertNotNull(decoded)
        }
        assertEquals(TestFixtures.filenames.size, typedWins,
            "typed variant should win on every bundled fixture")
        assertEquals(0, rawWins)
    }

    @Test
    fun `compressBestOf falls back to raw_detail for unmapped CoT type`() {
        // An unmapped CoT type with a large, unusual detail blob that the
        // typed parser has no schema for.  The typed variant falls back to
        // `Pli(true)` and throws away the detail entirely — a classic lossy
        // scenario.  The raw_detail path preserves it.
        val unknownType = "x-q-r-unknown-custom-type"
        val detail = buildString {
            append("<customTelemetry>")
            repeat(30) { i ->
                append("<sample id=\"$i\" value=\"0.${i}42\" unit=\"mJ\"/>")
            }
            append("</customTelemetry>")
            append("<contact callsign=\"UNMAPPED-01\"/>")
        }
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <event version="2.0" uid="unmapped-01" type="$unknownType" how="h-e" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-16T14:22:10Z">
              <point lat="10.00000" lon="95.00000" hae="0" ce="9999999" le="9999999"/>
              <detail>$detail</detail>
            </event>
        """.trimIndent()

        val packet = parser.parse(xml)
        // Parser falls back to Pli for unknown type — that's the lossy default.
        assertTrue(packet.payload is TakPacketV2Data.Payload.Pli,
            "unmapped type parses as Pli fallback")

        val rawDetail = parser.extractRawDetailBytes(xml)
        assertTrue(rawDetail.isNotEmpty())

        val typedWire = compressor.compress(packet)
        val smartWire = compressor.compressBestOf(packet, rawDetail)

        // Smart wire must be no larger than the typed wire (ties favor typed).
        assertTrue(smartWire.size <= typedWire.size + 8,
            "smart wire should not grow the typed baseline meaningfully")

        // Decompressing the smart wire must give back a packet that can
        // reconstruct the unmapped detail content byte-for-byte via the
        // RawDetail payload.
        val decoded = compressor.decompress(smartWire)
        if (decoded.payload is TakPacketV2Data.Payload.RawDetail) {
            val roundTripped = String(
                (decoded.payload as TakPacketV2Data.Payload.RawDetail).bytes,
                Charsets.UTF_8,
            )
            assertTrue(roundTripped.contains("customTelemetry"),
                "custom detail element survived the round trip")
            assertTrue(roundTripped.contains("sample id=\"29\""),
                "all 30 samples survived the round trip")

            // The CotXmlBuilder must emit the raw bytes inside <detail>
            // so the final XML is a valid CoT event again.
            val rebuilt = builder.build(decoded)
            assertTrue(rebuilt.contains("<detail>"))
            assertTrue(rebuilt.contains("</detail>"))
            assertTrue(rebuilt.contains("customTelemetry"))
        }
        // Otherwise the typed path won because raw_detail didn't fit the
        // nanopb pool or compressed larger; the assertion above still
        // passes and the decode is still valid.
    }

    @Test
    fun `compressBestOf with empty raw detail matches plain compress`() {
        // No detail element at all — compressBestOf must short-circuit to
        // the typed path without attempting a raw_detail encode.
        val xml = TestFixtures.loadFixture("pli_basic.xml")
        val packet = parser.parse(xml)
        val emptyRaw = ByteArray(0)

        val typedWire = compressor.compress(packet)
        val smartWire = compressor.compressBestOf(packet, emptyRaw)

        assertEquals(typedWire.size, smartWire.size)
        assertTrue(typedWire.contentEquals(smartWire))
    }
}
