package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Cross-platform full-pipeline coverage (`kotlin.test`, runs on every target).
 *
 * For each of the 47 inlined fixtures this exercises the SDK's actual code on
 * the current platform — the xmlutil [CotXmlParser], [TakPacketV2Serializer],
 * the [ZstdCodec] actual, and the pure-Kotlin decoder:
 *
 *  - **Parse** runs on EVERY target. It is the bulk of the pipeline (the
 *    1400-line common parser) and the part most likely to diverge per platform
 *    (regex flavor, entity decoding, number parsing), so it is asserted
 *    unconditionally.
 *  - **Compress → decompress** runs on EVERY target too. As of v0.6.0 the codec
 *    is the pure-Kotlin encoder/decoder everywhere, so the full round trip is
 *    exercised uniformly (no per-target capability gate).
 *  - **Decode of the golden wire frame** runs on EVERY target, covering the
 *    decode + dictionary-load + deserialize path. The decoded packet must match
 *    the freshly parsed packet's key fields.
 *
 * The JVM file-based [RoundTripTest] remains the comprehensive golden oracle;
 * this is ADDITIVE cross-platform coverage of the same pipeline.
 */
class RoundTripCommonTest {
    private val parser = CotXmlParser()
    private val builder = CotXmlBuilder()
    private val compressor = TakCompressor()

    /** Assert the load-bearing envelope + payload fields match between two packets. */
    private fun assertKeyFields(
        expected: TakPacketV2Data,
        actual: TakPacketV2Data,
        ctx: String,
    ) {
        assertEquals(expected.cotTypeId, actual.cotTypeId, "cotTypeId mismatch ($ctx)")
        assertEquals(expected.cotTypeStr, actual.cotTypeStr, "cotTypeStr mismatch ($ctx)")
        assertEquals(expected.callsign, actual.callsign, "callsign mismatch ($ctx)")
        assertEquals(expected.latitudeI, actual.latitudeI, "latitudeI mismatch ($ctx)")
        assertEquals(expected.longitudeI, actual.longitudeI, "longitudeI mismatch ($ctx)")
        assertEquals(expected.altitude, actual.altitude, "altitude mismatch ($ctx)")
        assertEquals(expected.uid, actual.uid, "uid mismatch ($ctx)")
        // marti gates TAKTALK voice routing — a regression here silently breaks
        // directed voice/text end-to-end (see CLAUDE.md + jvm RoundTripTest).
        assertEquals(expected.marti, actual.marti, "marti mismatch ($ctx)")
        // Payload variant identity is the single most important invariant: PLI is
        // implicit (no payload + a-f-* type), so a spurious/absent payload flips
        // the packet's meaning.
        assertEquals(
            expected.payload::class.simpleName,
            actual.payload::class.simpleName,
            "payload type mismatch ($ctx)",
        )
    }

    @Test
    fun everyFixtureParsesAndRoundTrips() {
        var parsed = 0
        var compressed = 0
        var decodedGolden = 0

        for (name in InlinedFixtures.names) {
            val xml = InlinedFixtures.xml.getValue(name)
            val packet = parser.parse(xml)
            parsed++

            // delete_event uses uid="delete-..."; every fixture has a non-empty uid.
            assertNotEquals("", packet.uid, "uid should not be empty for $name")

            // Building XML from the parsed packet must produce something
            // well-formed enough to contain the event root — runs everywhere.
            val rebuilt = builder.build(packet)
            assertTrue(rebuilt.contains("<event"), "rebuilt XML must contain <event> for $name")

            // ── compress → decompress (runs on EVERY target) ──
            // The pure-Kotlin codec compresses on every target as of v0.6.0.
            val wire = compressor.compress(packet)
            assertTrue(wire.size >= 2, "wire payload too short for $name")
            val rt = compressor.decompress(wire)
            assertKeyFields(packet, rt, "$name [compress→decompress]")
            compressed++

            // ── decode the golden wire frame (runs on EVERY target) ──
            // Covers the decode + dictionary-load + deserialize path against the
            // canonical goldens. 0xFF (uncompressed) frames decode via the raw path.
            val golden = InlinedFixtures.goldenWire[name]
            if (golden != null) {
                val decoded = compressor.decompress(golden)
                assertKeyFields(packet, decoded, "$name [decode golden]")
                decodedGolden++
            }
        }

        // Sanity: we actually iterated the full fixture set and the decode path
        // ran on every target.
        assertEquals(InlinedFixtures.names.size, parsed, "every fixture must parse")
        assertEquals(InlinedFixtures.names.size, decodedGolden, "every fixture's golden frame must decode")
        assertEquals(InlinedFixtures.names.size, compressed, "every fixture must compress")
    }
}
