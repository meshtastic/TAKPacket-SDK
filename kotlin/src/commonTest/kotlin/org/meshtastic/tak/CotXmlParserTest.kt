package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
        val packet = parser.parse(InlinedFixtures.PLI_BASIC)

        assertEquals("testnode", packet.uid)
        assertEquals(CotTypeMapper.COTTYPE_A_F_G_U_C, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_M_G, packet.how)
        assertEquals("testnode", packet.callsign)
        assertEquals((37.7749 * 1e7).toInt(), packet.latitudeI)
        assertEquals((-122.4194 * 1e7).toInt(), packet.longitudeI)
    }

    @Test
    fun pliFullParsesAllFields() {
        val packet = parser.parse(InlinedFixtures.PLI_FULL)

        assertEquals(CotTypeMapper.COTTYPE_A_F_G_U_C, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_E, packet.how)
        assertEquals("VIPER", packet.callsign)
        assertNotEquals("", packet.takVersion)
        assertEquals("ATAK-CIV", packet.takPlatform)
        assertEquals("SAMSUNG GALAXY S24", packet.takDevice)
        assertTrue(packet.battery > 0, "Battery should be > 0")
        assertEquals(88, packet.battery)
        assertIs<TakPacketV2Data.Payload.Pli>(packet.payload)
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
        val packet = parser.parse(InlinedFixtures.PLI_WEBTAK)

        // WebTAK uses a-f-G-U-C-I which maps to OTHER since it's not in the 75-type table
        assertEquals("FALCON224", packet.callsign)
        assertEquals("WebTAK", packet.takPlatform)
        assertEquals("Chrome - 134", packet.takDevice)
        assertEquals(10, packet.team, "Team should be Cyan (10)")
        assertEquals(1, packet.role, "Role should be Team Member (1)")
    }

    @Test
    fun geoChatParsesMessageAndRecipients() {
        val packet = parser.parse(InlinedFixtures.GEOCHAT_SIMPLE)

        assertEquals(CotTypeMapper.COTTYPE_B_T_F, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_G_I_G_O, packet.how)
        assertIs<TakPacketV2Data.Payload.Chat>(packet.payload)
        val chat = packet.payload
        assertTrue(chat.message.isNotEmpty(), "Chat message should not be empty")
        assertEquals("Roger that, moving to rally point", chat.message)
    }

    @Test
    fun aircraftAdsbParsesAircraftFields() {
        val packet = parser.parse(InlinedFixtures.AIRCRAFT_ADSB)

        assertEquals(CotTypeMapper.COTTYPE_A_N_A_C_F, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_M_G, packet.how)
        assertIs<TakPacketV2Data.Payload.Aircraft>(packet.payload)
        val aircraft = packet.payload
        assertEquals("F1E2D3", aircraft.icao)
        assertEquals("N338DN", aircraft.registration)
        assertEquals("DAL417", aircraft.flight)
        assertEquals("A321", aircraft.aircraftType)
        assertEquals(3456, aircraft.squawk)
    }

    @Test
    fun aircraftHostileParsesAircotElement() {
        val packet = parser.parse(InlinedFixtures.AIRCRAFT_HOSTILE)

        assertEquals(CotTypeMapper.COTTYPE_A_H_A_M_F_F, packet.cotTypeId)
        assertIs<TakPacketV2Data.Payload.Aircraft>(packet.payload)
        val aircraft = packet.payload
        assertEquals("B7C8D9", aircraft.icao)
        assertEquals("N789ZZ", aircraft.registration)
        assertEquals("N789ZZ", aircraft.flight)
        assertEquals("A6", aircraft.category)
        assertEquals("cotbridge@example.takserver", aircraft.cotHostId)
    }

    @Test
    fun deleteEventParsesCorrectly() {
        val packet = parser.parse(InlinedFixtures.DELETE_EVENT)

        assertEquals(CotTypeMapper.COTTYPE_T_X_D_D, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_G_I_G_O, packet.how)
        assertEquals("a1b2c3d4-e5f6-7a8b-9c0d-e1f2a3b4c5d6", packet.uid)
    }

    @Test
    fun casevacParsesCorrectly() {
        val packet = parser.parse(InlinedFixtures.CASEVAC)

        assertEquals(CotTypeMapper.COTTYPE_B_R_F_H_C, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_E, packet.how)
        assertEquals("CASEVAC-1", packet.callsign)
    }

    @Test
    fun alertTicParsesCorrectly() {
        val packet = parser.parse(InlinedFixtures.ALERT_TIC)

        assertEquals(CotTypeMapper.COTTYPE_B_A_O_OPN, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_E, packet.how)
        assertEquals("ALPHA-6", packet.callsign)
    }

    // --- Shape / Marker / Route / Task payload tests ---

    @Test
    fun drawingCircleParsesShapePayload() {
        val packet = parser.parse(InlinedFixtures.DRAWING_CIRCLE)

        assertEquals(CotTypeMapper.COTTYPE_U_D_C_C, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_E, packet.how)
        assertEquals("Drawing Circle 1", packet.callsign)
        assertEquals("6d09b6f6-720a-4eef-a197-183012512316", packet.uid)
        assertIs<TakPacketV2Data.Payload.DrawnShape>(packet.payload)
        val shape = packet.payload
        // Ellipse 226.98m → cm
        assertEquals(22698, shape.majorCm, "major axis cm")
        assertEquals(22698, shape.minorCm, "minor axis cm")
        assertTrue(shape.labelsOn, "labels_on should be true")
    }

    @Test
    fun markerSpotParsesMarkerPayload() {
        val packet = parser.parse(InlinedFixtures.MARKER_SPOT)

        assertEquals(CotTypeMapper.COTTYPE_B_M_P_S_M, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_G_I_G_O, packet.how)
        assertEquals("R 1", packet.callsign)
        assertIs<TakPacketV2Data.Payload.Marker>(packet.payload)
        val marker = packet.payload
        assertTrue(marker.readiness, "Spot marker readiness should be true")
        assertEquals(-65536, marker.colorArgb, "color argb should be -65536 (red)")
        assertEquals("COT_MAPPING_SPOTMAP/b-m-p-s-m/-65536", marker.iconset)
        assertEquals("ANDROID-0000000000000001", marker.parentUid)
        assertEquals("SIM-01", marker.parentCallsign)
    }

    @Test
    fun route3wpParsesRoutePayload() {
        val packet = parser.parse(InlinedFixtures.ROUTE_3WP)

        assertEquals(CotTypeMapper.COTTYPE_B_M_R, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_E, packet.how)
        assertEquals("Route Alpha", packet.callsign)
        assertIs<TakPacketV2Data.Payload.Route>(packet.payload)
        val route = packet.payload
        assertEquals(3, route.links.size, "Route should have 3 waypoints")
        assertEquals("CP", route.prefix)
        assertEquals("CP1", route.links[0].callsign)
        assertEquals("CP2", route.links[1].callsign)
        assertEquals("CP3", route.links[2].callsign)
    }

    @Test
    fun taskEngageParsesTaskPayload() {
        val packet = parser.parse(InlinedFixtures.TASK_ENGAGE)

        assertEquals(CotTypeMapper.COTTYPE_T_S, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_E, packet.how)
        assertEquals("Task-Alpha", packet.callsign)
        assertIs<TakPacketV2Data.Payload.TaskRequest>(packet.payload)
        val task = packet.payload
        assertEquals("engage", task.taskType)
        assertEquals("ANDROID-0000000000000005", task.assigneeUid)
        assertEquals("cover by fire", task.note)
    }

    @Test
    fun emergency911ParsesEmergencyAlertPayload() {
        val packet = parser.parse(InlinedFixtures.EMERGENCY_911)

        assertEquals(CotTypeMapper.COTTYPE_B_A_O_TBL, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_E, packet.how)
        assertEquals("TESTNODE-04-Alert", packet.callsign)
        assertEquals("emergency-01", packet.uid)
        assertEquals(179995000, packet.latitudeI, "latitude")
        assertEquals(150, packet.altitude, "altitude")
        assertEquals(300, packet.staleSeconds, "stale should be 5 minutes")
        assertIs<TakPacketV2Data.Payload.EmergencyAlert>(packet.payload)
        val alert = packet.payload
        assertEquals(1, alert.type, "type should be 1 (911 Alert)")
        assertEquals("ANDROID-0000000000000004", alert.authoringUid)
        assertEquals("", alert.cancelReferenceUid)
    }

    @Test
    fun rangingLineParsesRangeAndBearingPayload() {
        val packet = parser.parse(InlinedFixtures.RANGING_LINE)

        assertEquals(CotTypeMapper.COTTYPE_U_RB_A, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_E, packet.how)
        assertEquals("RB Line 1", packet.callsign)
        assertEquals(86400, packet.staleSeconds, "stale should be 24 hours")
        assertIs<TakPacketV2Data.Payload.RangeAndBearing>(packet.payload)
        val rab = packet.payload
        assertEquals(99880000, rab.anchorLatI, "anchor lat")
        assertEquals(949950000, rab.anchorLonI, "anchor lon")
        assertEquals("anchor-1", rab.anchorUid)
        assertEquals(125050, rab.rangeCm, "range in cm")
        assertEquals(13500, rab.bearingCdeg, "bearing in centidegrees")
    }

    @Test
    fun casevacMedlineParsesStructuredCasevacPayload() {
        val packet = parser.parse(InlinedFixtures.CASEVAC_MEDLINE)

        assertEquals(CotTypeMapper.COTTYPE_B_R_F_H_C, packet.cotTypeId)
        assertEquals(CotTypeMapper.COTHOW_H_E, packet.how)
        assertEquals("Casevac-1", packet.callsign)
        assertEquals("medevac-01", packet.uid)
        assertEquals(600, packet.staleSeconds, "stale should be 10 minutes")
        assertIs<TakPacketV2Data.Payload.CasevacReport>(packet.payload)
        val report = packet.payload
        assertEquals(1, report.precedence, "Urgent = 1")
        assertEquals(6, report.equipmentFlags, "hoist(0x02) + extraction(0x04) = 6")
        assertEquals(2, report.litterPatients)
        assertEquals(1, report.ambulatoryPatients)
        assertEquals(1, report.security, "N / No Enemy = 1")
        assertEquals(3, report.hlzMarking, "Smoke = 3")
        assertEquals("Green smoke", report.zoneMarker)
        assertEquals(2, report.usMilitary)
        assertEquals(0, report.usCivilian)
        assertEquals(1, report.nonUsMilitary)
        assertEquals(0, report.nonUsCivilian)
        assertEquals(0, report.epw)
        assertEquals(0, report.child)
        assertEquals(5, report.terrainFlags, "slope(0x01) + loose(0x04) = 5")
        assertEquals("38.90", report.frequency)
    }

    // --- Cross-cutting tests across all fixtures ---

    @Test
    fun allFixturesParseWithoutErrors() {
        for ((name, xml) in InlinedFixtures.ALL) {
            val packet = parser.parse(xml)
            assertNotEquals("", packet.uid, "UID should not be empty for $name")
        }
    }

    @Test
    fun staleSecondsComputedCorrectly() {
        val packet = parser.parse(InlinedFixtures.PLI_BASIC)
        // stale is 45 seconds after time
        assertEquals(45, packet.staleSeconds, "PLI basic stale should be 45 seconds")

        val fullPacket = parser.parse(InlinedFixtures.PLI_FULL)
        assertEquals(45, fullPacket.staleSeconds, "PLI full stale should be 45 seconds")

        val chatPacket = parser.parse(InlinedFixtures.GEOCHAT_SIMPLE)
        assertEquals(60, chatPacket.staleSeconds, "GeoChat stale should be 60 seconds")
    }

    @Test
    fun latLonConversionIsAccurate() {
        val packet = parser.parse(InlinedFixtures.CASEVAC)
        // 47.6062 * 1e7 = 476062000
        assertEquals(476062000, packet.latitudeI, "CASEVAC latitude")
        // -122.3321 * 1e7 = -1223321000
        assertEquals(-1223321000, packet.longitudeI, "CASEVAC longitude")
        assertEquals(100, packet.altitude, "CASEVAC altitude")
    }

    @Test
    fun trackFieldsParsed() {
        val packet = parser.parse(InlinedFixtures.PLI_FULL)
        // speed 1.2 m/s * 100 = 120 cm/s
        assertEquals(120, packet.speed, "PLI full speed (cm/s)")
        // course 142.75 * 100 = 14275
        assertEquals(14275, packet.course, "PLI full course (deg*100)")
    }

    @Test
    fun endpointNormalized() {
        // Default TAK endpoints (*:-1:stcp, 0.0.0.0:4242:tcp) are
        // normalized to empty string to save wire bytes.
        val packet = parser.parse(InlinedFixtures.PLI_BASIC)
        assertEquals("", packet.endpoint)
    }
}
