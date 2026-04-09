package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Cross-platform XML parsing tests using inlined fixtures.
 * These run on JVM, iOS, and any future KMP target.
 */
class CotXmlParserTest {

    private val parser = CotXmlParser()

    // --- Individual fixture parsing tests ---

    @Test
    fun pliBasicParsesCorrectly() {
        val packet = parser.parse(TestFixtures.PLI_BASIC)

        assertEquals("testnode", packet.uid)
        assertEquals(CotTypeMapper.COTTYPE_A_F_G_U_C, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_M_G, packet.how)
        assertEquals("testnode", packet.callsign)
        assertEquals((37.7749 * 1e7).toInt(), packet.latitudeI)
        assertEquals((-122.4194 * 1e7).toInt(), packet.longitudeI)
    }

    @Test
    fun pliFullParsesAllFields() {
        val packet = parser.parse(TestFixtures.PLI_FULL)

        assertEquals(CotTypeMapper.COTTYPE_A_F_G_U_C, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_E, packet.how)
        assertEquals("VIPER", packet.callsign)
        assertNotEquals("", packet.takVersion)
        assertEquals("ATAK-CIV", packet.takPlatform)
        assertEquals("SAMSUNG GALAXY S24", packet.takDevice)
        assertTrue(packet.battery > 0, "Battery should be > 0")
        assertEquals(88, packet.battery)
        assertTrue(packet.payload is TakPacketV2Data.Payload.Pli)
        // Team and role
        assertEquals(10, packet.team, "Team should be Cyan (10)")
        assertEquals(1, packet.role, "Role should be Team Member (1)")
        // Precision location
        assertEquals(CotXmlParser.GEOSRC_GPS, packet.geoSrc)
        assertEquals(CotXmlParser.GEOSRC_GPS, packet.altSrc)
        // Phone
        assertEquals("+15551234567", packet.phone)
    }

    @Test
    fun pliWebtakParsesCorrectly() {
        val packet = parser.parse(TestFixtures.PLI_WEBTAK)

        // WebTAK uses a-f-G-U-C-I which maps to OTHER since it's not in the 75-type table
        assertEquals("FALCON224", packet.callsign)
        assertEquals("WebTAK", packet.takPlatform)
        assertEquals("Chrome - 134", packet.takDevice)
        assertEquals(10, packet.team, "Team should be Cyan (10)")
        assertEquals(1, packet.role, "Role should be Team Member (1)")
    }

    @Test
    fun geoChatParsesMessageAndRecipients() {
        val packet = parser.parse(TestFixtures.GEOCHAT_SIMPLE)

        assertEquals(CotTypeMapper.COTTYPE_B_T_F, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_G_I_G_O, packet.how)
        assertTrue(packet.payload is TakPacketV2Data.Payload.Chat)
        val chat = packet.payload as TakPacketV2Data.Payload.Chat
        assertTrue(chat.message.isNotEmpty(), "Chat message should not be empty")
        assertEquals("Roger that, moving to rally point", chat.message)
    }

    @Test
    fun aircraftAdsbParsesAircraftFields() {
        val packet = parser.parse(TestFixtures.AIRCRAFT_ADSB)

        assertEquals(CotTypeMapper.COTTYPE_A_N_A_C_F, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_M_G, packet.how)
        assertTrue(packet.payload is TakPacketV2Data.Payload.Aircraft)
        val aircraft = packet.payload as TakPacketV2Data.Payload.Aircraft
        assertEquals("F1E2D3", aircraft.icao)
        assertEquals("N338DN", aircraft.registration)
        assertEquals("DAL417", aircraft.flight)
        assertEquals("A321", aircraft.aircraftType)
        assertEquals(3456, aircraft.squawk)
    }

    @Test
    fun aircraftHostileParsesAircotElement() {
        val packet = parser.parse(TestFixtures.AIRCRAFT_HOSTILE)

        assertEquals(CotTypeMapper.COTTYPE_A_H_A_M_F_F, packet.cotTypeId)
        assertTrue(packet.payload is TakPacketV2Data.Payload.Aircraft)
        val aircraft = packet.payload as TakPacketV2Data.Payload.Aircraft
        assertEquals("B7C8D9", aircraft.icao)
        assertEquals("N789ZZ", aircraft.registration)
        assertEquals("N789ZZ", aircraft.flight)
        assertEquals("A6", aircraft.category)
        assertEquals("cotbridge@example.takserver", aircraft.cotHostId)
    }

    @Test
    fun deleteEventParsesCorrectly() {
        val packet = parser.parse(TestFixtures.DELETE_EVENT)

        assertEquals(CotTypeMapper.COTTYPE_T_X_D_D, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_G_I_G_O, packet.how)
        assertEquals("a1b2c3d4-e5f6-7a8b-9c0d-e1f2a3b4c5d6", packet.uid)
    }

    @Test
    fun casevacParsesCorrectly() {
        val packet = parser.parse(TestFixtures.CASEVAC)

        assertEquals(CotTypeMapper.COTTYPE_B_R_F_H_C, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_E, packet.how)
        assertEquals("CASEVAC-1", packet.callsign)
    }

    @Test
    fun alertTicParsesCorrectly() {
        val packet = parser.parse(TestFixtures.ALERT_TIC)

        assertEquals(CotTypeMapper.COTTYPE_B_A_O_OPN, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_E, packet.how)
        assertEquals("ALPHA-6", packet.callsign)
    }

    // --- Cross-cutting tests across all fixtures ---

    @Test
    fun allFixturesParseWithoutErrors() {
        for ((name, xml) in TestFixtures.ALL) {
            val packet = parser.parse(xml)
            assertNotEquals("", packet.uid, "UID should not be empty for $name")
        }
    }

    @Test
    fun staleSecondsComputedCorrectly() {
        val packet = parser.parse(TestFixtures.PLI_BASIC)
        // stale is 45 seconds after time
        assertEquals(45, packet.staleSeconds, "PLI basic stale should be 45 seconds")

        val fullPacket = parser.parse(TestFixtures.PLI_FULL)
        assertEquals(45, fullPacket.staleSeconds, "PLI full stale should be 45 seconds")

        val chatPacket = parser.parse(TestFixtures.GEOCHAT_SIMPLE)
        assertEquals(60, chatPacket.staleSeconds, "GeoChat stale should be 60 seconds")
    }

    @Test
    fun latLonConversionIsAccurate() {
        val packet = parser.parse(TestFixtures.CASEVAC)
        // 47.6062 * 1e7 = 476062000
        assertEquals(476062000, packet.latitudeI, "CASEVAC latitude")
        // -122.3321 * 1e7 = -1223321000
        assertEquals(-1223321000, packet.longitudeI, "CASEVAC longitude")
        assertEquals(100, packet.altitude, "CASEVAC altitude")
    }

    @Test
    fun trackFieldsParsed() {
        val packet = parser.parse(TestFixtures.PLI_FULL)
        // speed 1.2 m/s * 100 = 120 cm/s
        assertEquals(120, packet.speed, "PLI full speed (cm/s)")
        // course 142.75 * 100 = 14275
        assertEquals(14275, packet.course, "PLI full course (deg*100)")
    }

    @Test
    fun endpointParsed() {
        val packet = parser.parse(TestFixtures.PLI_BASIC)
        assertEquals("*:-1:stcp", packet.endpoint)
    }
}
