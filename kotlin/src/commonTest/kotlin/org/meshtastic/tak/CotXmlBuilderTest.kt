package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Cross-platform XML builder tests.
 * Verifies that [CotXmlBuilder] produces valid CoT XML from [TakPacketV2Data].
 * Runs on JVM, iOS, and any future KMP target.
 */
class CotXmlBuilderTest {

    private val parser = CotXmlParser()
    private val builder = CotXmlBuilder()

    @Test
    fun buildProducesValidXmlFromPliData() {
        val packet = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
            how = CotTypeMapper.COTHOW_M_G,
            callsign = "TESTCALL",
            uid = "test-uid-123",
            latitudeI = 377749000,
            longitudeI = -1224194000,
            altitude = 50,
            payload = TakPacketV2Data.Payload.Pli(true),
        )

        val xml = builder.build(packet)
        assertTrue(xml.contains("<event"), "Should contain <event>")
        assertTrue(xml.contains("TESTCALL"), "Should contain callsign")
        assertTrue(xml.contains("test-uid-123"), "Should contain uid")
        assertTrue(xml.contains("a-f-G-U-C"), "Should contain CoT type")
        assertTrue(xml.contains("m-g"), "Should contain how")
    }

    @Test
    fun buildIncludesBatteryAndTrack() {
        val packet = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
            callsign = "VIPER",
            uid = "test-track",
            battery = 75,
            speed = 250, // 2.5 m/s
            course = 9000, // 90.0 degrees
            payload = TakPacketV2Data.Payload.Pli(true),
        )

        val xml = builder.build(packet)
        assertTrue(xml.contains("""battery="75""""), "Should include battery")
        assertTrue(xml.contains("<track"), "Should include track element")
        assertTrue(xml.contains("2.5"), "Speed should be 2.5 m/s")
        assertTrue(xml.contains("90.0"), "Course should be 90.0 degrees")
    }

    @Test
    fun buildIncludesTakvInfo() {
        val packet = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
            callsign = "VIPER",
            uid = "test-takv",
            takVersion = "4.12.0",
            takPlatform = "ATAK-CIV",
            takDevice = "Pixel 9",
            takOs = "15",
            payload = TakPacketV2Data.Payload.Pli(true),
        )

        val xml = builder.build(packet)
        assertTrue(xml.contains("<takv"), "Should include takv element")
        assertTrue(xml.contains("""version="4.12.0""""), "Should include version")
        assertTrue(xml.contains("""platform="ATAK-CIV""""), "Should include platform")
        assertTrue(xml.contains("""device="Pixel 9""""), "Should include device")
    }

    @Test
    fun buildIncludesGroupInfo() {
        val packet = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
            callsign = "VIPER",
            uid = "test-group",
            team = 10, // Cyan
            role = 1, // Team Member
            payload = TakPacketV2Data.Payload.Pli(true),
        )

        val xml = builder.build(packet)
        assertTrue(xml.contains("__group"), "Should include __group element")
        assertTrue(xml.contains("""name="Cyan""""), "Should include team name")
        assertTrue(xml.contains("""role="Team Member""""), "Should include role")
    }

    @Test
    fun buildIncludesChatRemarks() {
        val packet = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_B_T_F,
            how = CotTypeMapper.COTHOW_H_G_I_G_O,
            callsign = "VIPER",
            uid = "test-chat",
            payload = TakPacketV2Data.Payload.Chat(
                message = "Hello from test",
                to = "All Chat Rooms",
            ),
        )

        val xml = builder.build(packet)
        assertTrue(xml.contains("<remarks>"), "Should include remarks element")
        assertTrue(xml.contains("Hello from test"), "Should include chat message")
    }

    @Test
    fun buildIncludesAircraftData() {
        val packet = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_N_A_C_F,
            callsign = "DAL417",
            uid = "test-aircraft",
            payload = TakPacketV2Data.Payload.Aircraft(
                icao = "F1E2D3",
                registration = "N338DN",
                flight = "DAL417",
                category = "A3",
            ),
        )

        val xml = builder.build(packet)
        assertTrue(xml.contains("_aircot_"), "Should include _aircot_ element")
        assertTrue(xml.contains("""icao="F1E2D3""""), "Should include ICAO")
        assertTrue(xml.contains("""reg="N338DN""""), "Should include registration")
    }

    @Test
    fun buildEscapesSpecialCharacters() {
        val packet = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
            callsign = "Test<>&\"'Node",
            uid = "test-escape",
            payload = TakPacketV2Data.Payload.Pli(true),
        )

        val xml = builder.build(packet)
        assertTrue(xml.contains("&lt;"), "Should escape <")
        assertTrue(xml.contains("&gt;"), "Should escape >")
        assertTrue(xml.contains("&amp;"), "Should escape &")
        assertTrue(xml.contains("&quot;"), "Should escape \"")
    }

    @Test
    fun parseRoundTripPreservesFieldsForAllFixtures() {
        for ((name, xml) in TestFixtures.ALL) {
            val packet = parser.parse(xml)
            val rebuiltXml = builder.build(packet)

            // Verify the rebuilt XML is valid and contains key fields
            assertTrue(rebuiltXml.contains("<event"), "Rebuilt XML should contain <event> for $name")
            assertTrue(rebuiltXml.contains(packet.callsign),
                "Rebuilt XML should contain callsign '${packet.callsign}' for $name")

            // Re-parse the rebuilt XML and verify key fields survive
            val reparsed = parser.parse(rebuiltXml)
            assertEquals(packet.cotTypeId, reparsed.cotTypeId, "cotTypeId mismatch after rebuild for $name")
            assertEquals(packet.callsign, reparsed.callsign, "callsign mismatch after rebuild for $name")
            assertEquals(packet.latitudeI, reparsed.latitudeI, "latitudeI mismatch after rebuild for $name")
            assertEquals(packet.longitudeI, reparsed.longitudeI, "longitudeI mismatch after rebuild for $name")
        }
    }
}
