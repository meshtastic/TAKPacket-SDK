package org.meshtastic.tak

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * Round-trip tests: CoT XML -> parse -> compress -> decompress -> build XML
 * Validates that all fields survive the full pipeline.
 */
class RoundTripTest {

    private val parser = CotXmlParser()
    private val builder = CotXmlBuilder()
    private val compressor = TakCompressor()

    private fun loadFixture(name: String): String {
        val path = File("../testdata/cot_xml/$name")
        assertTrue(path.exists(), "Test fixture not found: ${path.absolutePath}")
        return path.readText()
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "pli_basic.xml",
        "pli_full.xml",
        "pli_webtak.xml",
        "geochat_simple.xml",
        "aircraft_adsb.xml",
        "aircraft_hostile.xml",
        "delete_event.xml",
        "casevac.xml",
        "alert_tic.xml",
    ])
    fun `full round-trip preserves fields`(fixture: String) {
        val xml = loadFixture(fixture)

        // Parse
        val packet = parser.parse(xml)
        assertNotEquals("", packet.uid, "UID should not be empty for $fixture")

        // Compress -> decompress
        val wirePayload = compressor.compress(packet)
        val decompressed = compressor.decompress(wirePayload)

        // Verify all fields match after round-trip through compression
        assertEquals(packet.cotTypeId, decompressed.cotTypeId, "cotTypeId mismatch in $fixture")
        assertEquals(packet.cotTypeStr, decompressed.cotTypeStr, "cotTypeStr mismatch in $fixture")
        assertEquals(packet.how, decompressed.how, "how mismatch in $fixture")
        assertEquals(packet.callsign, decompressed.callsign, "callsign mismatch in $fixture")
        assertEquals(packet.team, decompressed.team, "team mismatch in $fixture")
        assertEquals(packet.role, decompressed.role, "role mismatch in $fixture")
        assertEquals(packet.latitudeI, decompressed.latitudeI, "latitudeI mismatch in $fixture")
        assertEquals(packet.longitudeI, decompressed.longitudeI, "longitudeI mismatch in $fixture")
        assertEquals(packet.altitude, decompressed.altitude, "altitude mismatch in $fixture")
        assertEquals(packet.speed, decompressed.speed, "speed mismatch in $fixture")
        assertEquals(packet.course, decompressed.course, "course mismatch in $fixture")
        assertEquals(packet.battery, decompressed.battery, "battery mismatch in $fixture")
        assertEquals(packet.uid, decompressed.uid, "uid mismatch in $fixture")
        assertEquals(packet.deviceCallsign, decompressed.deviceCallsign, "deviceCallsign mismatch in $fixture")
        assertEquals(packet.takVersion, decompressed.takVersion, "takVersion mismatch in $fixture")
        assertEquals(packet.takPlatform, decompressed.takPlatform, "takPlatform mismatch in $fixture")
        assertEquals(packet.endpoint, decompressed.endpoint, "endpoint mismatch in $fixture")

        // Verify payload type matches
        assertEquals(packet.payload::class, decompressed.payload::class,
            "payload type mismatch in $fixture")

        // Verify payload-specific fields
        when (val p = packet.payload) {
            is TakPacketV2Data.Payload.Chat -> {
                val d = decompressed.payload as TakPacketV2Data.Payload.Chat
                assertEquals(p.message, d.message, "chat message mismatch in $fixture")
                assertEquals(p.to, d.to, "chat to mismatch in $fixture")
            }
            is TakPacketV2Data.Payload.Aircraft -> {
                val d = decompressed.payload as TakPacketV2Data.Payload.Aircraft
                assertEquals(p.icao, d.icao, "icao mismatch in $fixture")
                assertEquals(p.registration, d.registration, "registration mismatch in $fixture")
                assertEquals(p.flight, d.flight, "flight mismatch in $fixture")
                assertEquals(p.squawk, d.squawk, "squawk mismatch in $fixture")
            }
            else -> { /* PLI has no extra fields to check */ }
        }

        // Build XML from decompressed data and verify it's valid XML
        val rebuiltXml = builder.build(decompressed)
        assertTrue(rebuiltXml.contains("<event"), "Rebuilt XML should contain <event> for $fixture")
        assertTrue(rebuiltXml.contains(decompressed.callsign),
            "Rebuilt XML should contain callsign for $fixture")
    }

    @Test
    fun `PLI basic parses correctly`() {
        val xml = loadFixture("pli_basic.xml")
        val packet = parser.parse(xml)

        assertEquals("testnode", packet.uid)
        assertEquals(CotTypeMapper.COTTYPE_A_F_G_U_C, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_M_G, packet.how)
        assertEquals("testnode", packet.callsign)
        assertEquals((37.7749 * 1e7).toInt(), packet.latitudeI)
        assertEquals((-122.4194 * 1e7).toInt(), packet.longitudeI)
    }

    @Test
    fun `PLI full parses all fields`() {
        val xml = loadFixture("pli_full.xml")
        val packet = parser.parse(xml)

        assertEquals(CotTypeMapper.COTTYPE_A_F_G_U_C, packet.cotTypeId)
        assertNotEquals("", packet.callsign)
        assertNotEquals("", packet.takVersion)
        assertNotEquals("", packet.takPlatform)
        assertTrue(packet.battery > 0, "Battery should be > 0")
        assertTrue(packet.payload is TakPacketV2Data.Payload.Pli)
    }

    @Test
    fun `GeoChat parses message and recipients`() {
        val xml = loadFixture("geochat_simple.xml")
        val packet = parser.parse(xml)

        assertEquals(CotTypeMapper.COTTYPE_B_T_F, packet.cotTypeId)
        assertTrue(packet.payload is TakPacketV2Data.Payload.Chat)
        val chat = packet.payload as TakPacketV2Data.Payload.Chat
        assertTrue(chat.message.isNotEmpty(), "Chat message should not be empty")
    }

    @Test
    fun `Aircraft ADS-B parses aircraft fields`() {
        val xml = loadFixture("aircraft_adsb.xml")
        val packet = parser.parse(xml)

        assertEquals(CotTypeMapper.COTTYPE_A_N_A_C_F, packet.cotTypeId)
        assertTrue(packet.payload is TakPacketV2Data.Payload.Aircraft)
        val aircraft = packet.payload as TakPacketV2Data.Payload.Aircraft
        assertTrue(aircraft.icao.isNotEmpty(), "ICAO should not be empty")
    }

    @Test
    fun `Delete event parses correctly`() {
        val xml = loadFixture("delete_event.xml")
        val packet = parser.parse(xml)

        assertEquals(CotTypeMapper.COTTYPE_T_X_D_D, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_G_I_G_O, packet.how)
    }

    @Test
    fun `CASEVAC parses correctly`() {
        val xml = loadFixture("casevac.xml")
        val packet = parser.parse(xml)

        assertEquals(CotTypeMapper.COTTYPE_B_R_F_H_C, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_E, packet.how)
        assertEquals("CASEVAC-1", packet.callsign)
    }

    @Test
    fun `Alert TIC parses correctly`() {
        val xml = loadFixture("alert_tic.xml")
        val packet = parser.parse(xml)

        assertEquals(CotTypeMapper.COTTYPE_B_A_O_OPN, packet.cotTypeId)
        assertEquals("ALPHA-6", packet.callsign)
    }

    @Test
    fun `uncompressed payload (0xFF flag) round-trips`() {
        val packet = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
            how = CotTypeMapper.COTHOW_M_G,
            callsign = "TEST",
            latitudeI = 340522000,
            longitudeI = -1182437000,
            altitude = 100,
            payload = TakPacketV2Data.Payload.Pli(true),
        )

        // Simulate firmware TAK_TRACKER: flags=0xFF + raw protobuf
        val protobuf = TakPacketV2Serializer.serialize(packet)
        val wirePayload = ByteArray(1 + protobuf.size)
        wirePayload[0] = 0xFF.toByte()
        System.arraycopy(protobuf, 0, wirePayload, 1, protobuf.size)

        val decompressed = compressor.decompress(wirePayload)
        assertEquals(packet.cotTypeId, decompressed.cotTypeId)
        assertEquals(packet.callsign, decompressed.callsign)
        assertEquals(packet.latitudeI, decompressed.latitudeI)
    }
}
