package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Cross-platform malformed input tests that don't require loading files from disk.
 * Tests that depend on testdata/malformed/ files remain in jvmTest.
 * Runs on JVM, iOS, and any future KMP target.
 */
class MalformedInputCommonTest {

    private val compressor = TakCompressor()
    private val parser = CotXmlParser()

    @Test
    fun rejectsEmptyPayload() {
        assertFailsWith<Exception> {
            compressor.decompress(byteArrayOf())
        }
    }

    @Test
    fun rejectsSingleByte() {
        assertFailsWith<Exception> {
            compressor.decompress(byteArrayOf(0x00))
        }
    }

    @Test
    fun rejectsXmlWithDoctypeDeclaration() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
            <event version="2.0" uid="test" type="a-f-G-U-C" how="m-g"
                   time="2026-01-01T00:00:00Z" start="2026-01-01T00:00:00Z" stale="2026-01-01T00:01:00Z">
              <point lat="0" lon="0" hae="0" ce="0" le="0"/>
              <detail><contact callsign="&xxe;"/></detail>
            </event>
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            parser.parse(xml)
        }
    }

    @Test
    fun rejectsXmlWithEntityExpansion() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!ENTITY bomb "BOOM">
            <event version="2.0" uid="test" type="a-f-G-U-C" how="m-g"
                   time="2026-01-01T00:00:00Z" start="2026-01-01T00:00:00Z" stale="2026-01-01T00:01:00Z">
              <point lat="0" lon="0" hae="0" ce="0" le="0"/>
              <detail><contact callsign="test"/></detail>
            </event>
        """.trimIndent()

        assertFailsWith<IllegalArgumentException> {
            parser.parse(xml)
        }
    }

    @Test
    fun rejectsEmptyXml() {
        assertFailsWith<Exception> {
            parser.parse("")
        }
    }

    @Test
    fun rejectsNonXmlString() {
        assertFailsWith<Exception> {
            parser.parse("this is not XML at all")
        }
    }

    @Test
    fun rejectsXmlMissingRequiredFields() {
        // Valid XML structure but missing required event attributes
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <event version="2.0">
              <point lat="0" lon="0" hae="0" ce="0" le="0"/>
              <detail/>
            </event>
        """.trimIndent()

        // Should either throw or return a degraded packet with empty uid
        try {
            val packet = parser.parse(xml)
            // If it doesn't throw, the uid should at least be empty
            assertTrue(packet.uid.isEmpty() || packet.uid == "",
                "Parsed packet from incomplete XML should have empty uid")
        } catch (_: Exception) {
            // Expected -- either outcome is acceptable
        }
    }

    @Test
    fun handlesMaxIntCoordinates() {
        val packet = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
            callsign = "EDGE",
            uid = "edge-test",
            latitudeI = Int.MAX_VALUE,
            longitudeI = Int.MIN_VALUE,
            altitude = Int.MAX_VALUE,
            payload = TakPacketV2Data.Payload.Pli(true),
        )

        val bytes = TakPacketV2Serializer.serialize(packet)
        val deserialized = TakPacketV2Serializer.deserialize(bytes)
        assertTrue(deserialized.latitudeI == Int.MAX_VALUE, "Should handle max lat")
        assertTrue(deserialized.longitudeI == Int.MIN_VALUE, "Should handle min lon")
    }
}
