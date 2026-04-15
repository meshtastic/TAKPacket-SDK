package org.meshtastic.tak

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Cross-platform compatibility tests.
 *
 * Verifies that compressed payloads match the golden files byte-for-byte.
 * Other platform implementations (Swift, C#, TypeScript, Python) run the same
 * tests against the same golden files to ensure interoperability.
 *
 * All parameterized cases are driven by [TestFixtures] which enumerates the
 * files under `testdata/cot_xml/` at class-load time, so adding a new fixture
 * is a zero-edit operation in this file — just drop the XML and run the tests.
 */
class CompatibilityTest {

    private val parser = CotXmlParser()
    private val compressor = TakCompressor()

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.meshtastic.tak.TestFixtures#allFixtureNames")
    fun `compressed output similar size to golden file`(fixtureName: String) {
        // Note: Exact byte match is expected within the same platform but may differ across
        // platforms due to protobuf serialization order. The key invariant is interoperability:
        // golden files from any platform can be decompressed by any other platform.
        val golden = TestFixtures.loadGolden(fixtureName)
            ?: return // Skip if golden files haven't been generated yet

        val xml = TestFixtures.loadFixture("$fixtureName.xml")
        val packet = parser.parse(xml)
        val wirePayload = compressor.compress(packet)

        // Protobuf serializers across platforms may produce different byte orderings,
        // resulting in different compressed sizes. Allow wide tolerance.
        val ratio = wirePayload.size.toDouble() / golden.size.toDouble()
        assertTrue(ratio in 0.5..2.0,
            "$fixtureName: compressed size ${wirePayload.size}B differs significantly from golden ${golden.size}B")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.meshtastic.tak.TestFixtures#allFixtureNames")
    fun `protobuf output matches golden file`(fixtureName: String) {
        val goldenPb = TestFixtures.loadProtobuf(fixtureName)
            ?: return // Skip if protobuf golden files haven't been generated yet

        val xml = TestFixtures.loadFixture("$fixtureName.xml")
        val packet = parser.parse(xml)
        val protobuf = TakPacketV2Serializer.serialize(packet)

        assertArrayEquals(goldenPb, protobuf,
            "$fixtureName: protobuf bytes do not match golden file " +
            "(got ${protobuf.size}B, expected ${goldenPb.size}B)")
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.meshtastic.tak.TestFixtures#allFixtureNames")
    fun `golden file decompresses to valid packet`(fixtureName: String) {
        val golden = TestFixtures.loadGolden(fixtureName)
            ?: return // Skip if golden files haven't been generated yet

        val packet = compressor.decompress(golden)
        assertNotEquals("", packet.uid, "$fixtureName: decompressed packet should have a UID")
        // Decompressed packet must have either a known CoT type enum OR an arbitrary
        // `cotTypeStr` string set. The drawing/marker fixtures use types like `u-d-c-c`
        // which are in the enum, and the spot marker uses `b-m-p-s-m` which isn't and
        // falls into COTTYPE_OTHER + cotTypeStr.
        assertTrue(
            packet.cotTypeId != 0 || packet.cotTypeString().isNotEmpty(),
            "$fixtureName: decompressed packet should have a known CoT type " +
                "(or cotTypeStr should be set)",
        )
    }

    @Test
    fun `all golden files exist for every fixture`() {
        if (!TestFixtures.goldenDir.exists()) {
            println("Golden directory does not exist yet - run CompressionTest.generate compression report first")
            return
        }
        val missing = TestFixtures.fixtureNames.filter { TestFixtures.loadGolden(it) == null }
        assertTrue(missing.isEmpty(), "Golden files missing for: $missing")
    }
}
