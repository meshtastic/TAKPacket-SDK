package org.meshtastic.tak

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * Round-trip tests: CoT XML -> parse -> compress -> decompress -> build XML
 * Validates that all fields survive the full pipeline.
 */
class RoundTripTest {

    private val parser = CotXmlParser()
    private val builder = CotXmlBuilder()
    private val compressor = TakCompressor()

    private fun loadFixture(name: String): String = TestFixtures.loadFixture(name)

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.meshtastic.tak.TestFixtures#allFixtureFilenames")
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
            is TakPacketV2Data.Payload.DrawnShape -> {
                val d = decompressed.payload as TakPacketV2Data.Payload.DrawnShape
                assertEquals(p.kind, d.kind, "shape kind mismatch in $fixture")
                assertEquals(p.style, d.style, "shape style mismatch in $fixture")
                assertEquals(p.majorCm, d.majorCm, "shape majorCm mismatch in $fixture")
                assertEquals(p.minorCm, d.minorCm, "shape minorCm mismatch in $fixture")
                assertEquals(p.strokeArgb, d.strokeArgb, "shape strokeArgb mismatch in $fixture")
                assertEquals(p.fillArgb, d.fillArgb, "shape fillArgb mismatch in $fixture")
                assertEquals(p.vertices.size, d.vertices.size, "shape vertex count mismatch in $fixture")
                assertEquals(p.truncated, d.truncated, "shape truncated mismatch in $fixture")
            }
            is TakPacketV2Data.Payload.Marker -> {
                val d = decompressed.payload as TakPacketV2Data.Payload.Marker
                assertEquals(p.kind, d.kind, "marker kind mismatch in $fixture")
                assertEquals(p.colorArgb, d.colorArgb, "marker colorArgb mismatch in $fixture")
                assertEquals(p.iconset, d.iconset, "marker iconset mismatch in $fixture")
                assertEquals(p.parentUid, d.parentUid, "marker parentUid mismatch in $fixture")
            }
            is TakPacketV2Data.Payload.RangeAndBearing -> {
                val d = decompressed.payload as TakPacketV2Data.Payload.RangeAndBearing
                assertEquals(p.rangeCm, d.rangeCm, "rab rangeCm mismatch in $fixture")
                assertEquals(p.bearingCdeg, d.bearingCdeg, "rab bearingCdeg mismatch in $fixture")
                assertEquals(p.anchorLatI, d.anchorLatI, "rab anchorLatI mismatch in $fixture")
                assertEquals(p.anchorLonI, d.anchorLonI, "rab anchorLonI mismatch in $fixture")
            }
            is TakPacketV2Data.Payload.Route -> {
                val d = decompressed.payload as TakPacketV2Data.Payload.Route
                assertEquals(p.method, d.method, "route method mismatch in $fixture")
                assertEquals(p.direction, d.direction, "route direction mismatch in $fixture")
                assertEquals(p.prefix, d.prefix, "route prefix mismatch in $fixture")
                assertEquals(p.links.size, d.links.size, "route link count mismatch in $fixture")
                assertEquals(p.truncated, d.truncated, "route truncated mismatch in $fixture")
            }
            is TakPacketV2Data.Payload.CasevacReport -> {
                val d = decompressed.payload as TakPacketV2Data.Payload.CasevacReport
                assertEquals(p.precedence, d.precedence, "casevac precedence mismatch in $fixture")
                assertEquals(p.equipmentFlags, d.equipmentFlags, "casevac equipmentFlags mismatch in $fixture")
                assertEquals(p.litterPatients, d.litterPatients, "casevac litterPatients mismatch in $fixture")
                assertEquals(p.ambulatoryPatients, d.ambulatoryPatients, "casevac ambulatoryPatients mismatch in $fixture")
                assertEquals(p.security, d.security, "casevac security mismatch in $fixture")
                assertEquals(p.hlzMarking, d.hlzMarking, "casevac hlzMarking mismatch in $fixture")
                assertEquals(p.zoneMarker, d.zoneMarker, "casevac zoneMarker mismatch in $fixture")
                assertEquals(p.usMilitary, d.usMilitary, "casevac usMilitary mismatch in $fixture")
                assertEquals(p.usCivilian, d.usCivilian, "casevac usCivilian mismatch in $fixture")
                assertEquals(p.nonUsMilitary, d.nonUsMilitary, "casevac nonUsMilitary mismatch in $fixture")
                assertEquals(p.nonUsCivilian, d.nonUsCivilian, "casevac nonUsCivilian mismatch in $fixture")
                assertEquals(p.epw, d.epw, "casevac epw mismatch in $fixture")
                assertEquals(p.child, d.child, "casevac child mismatch in $fixture")
                assertEquals(p.terrainFlags, d.terrainFlags, "casevac terrainFlags mismatch in $fixture")
                assertEquals(p.frequency, d.frequency, "casevac frequency mismatch in $fixture")
            }
            is TakPacketV2Data.Payload.EmergencyAlert -> {
                val d = decompressed.payload as TakPacketV2Data.Payload.EmergencyAlert
                assertEquals(p.type, d.type, "emergency type mismatch in $fixture")
                assertEquals(p.authoringUid, d.authoringUid, "emergency authoringUid mismatch in $fixture")
                assertEquals(p.cancelReferenceUid, d.cancelReferenceUid, "emergency cancelReferenceUid mismatch in $fixture")
            }
            is TakPacketV2Data.Payload.TaskRequest -> {
                val d = decompressed.payload as TakPacketV2Data.Payload.TaskRequest
                assertEquals(p.taskType, d.taskType, "task type mismatch in $fixture")
                assertEquals(p.targetUid, d.targetUid, "task targetUid mismatch in $fixture")
                assertEquals(p.assigneeUid, d.assigneeUid, "task assigneeUid mismatch in $fixture")
                assertEquals(p.priority, d.priority, "task priority mismatch in $fixture")
                assertEquals(p.status, d.status, "task status mismatch in $fixture")
                assertEquals(p.note, d.note, "task note mismatch in $fixture")
            }
            else -> { /* PLI, None, RawDetail have no extra fields to check */ }
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
    fun `rectangle with space-after-comma link points preserves longitudes`() {
        // Regression: iTAK emits <link point="34.804, -92.468"/> with a space
        // after the comma. Kotlin's toDoubleOrNull() returns null for " -92.468"
        // (leading space), zeroing all longitudes and rendering flat shapes.
        val xml = loadFixture("drawing_rectangle_itak.xml")
        val packet = parser.parse(xml)
        assertTrue(packet.payload is TakPacketV2Data.Payload.DrawnShape,
            "Should be DrawnShape, got ${packet.payload}")
        val shape = packet.payload as TakPacketV2Data.Payload.DrawnShape
        assertEquals(4, shape.vertices.size, "Rectangle must have 4 vertices")
        // All longitudes must be non-zero (around 95.001-95.003)
        shape.vertices.forEachIndexed { i, v ->
            assertTrue(v.lonI != 0, "Vertex $i longitude must not be 0 (was ${v.lonI})")
            assertTrue(v.lonI > 950000000, "Vertex $i longitude should be ~95.00x (was ${v.lonI / 1e7})")
        }

        // Verify full round-trip preserves the coordinates
        val compressor = TakCompressor()
        val wire = compressor.compress(packet)
        val dec = compressor.decompress(wire)
        val decShape = dec.payload as TakPacketV2Data.Payload.DrawnShape
        assertEquals(4, decShape.vertices.size)
        decShape.vertices.forEachIndexed { i, v ->
            assertTrue(v.lonI != 0, "Decompressed vertex $i longitude must not be 0")
        }
    }

    @Test
    fun `PLI stationary clamps negative speed and course to zero`() {
        // Regression for an iOS crash where ATAK's <track speed="-1.0"
        // course="-1.0"/> sentinel for stationary / unknown targets tripped a
        // Double -> UInt32 conversion trap in the Swift parser. The proto
        // field is uint32 on all platforms, so the fix is to clamp negatives
        // to 0 rather than wrap them into huge unsigned values.
        val xml = loadFixture("pli_stationary.xml")
        val packet = parser.parse(xml)

        assertEquals(0, packet.speed, "Negative speed must clamp to 0")
        assertEquals(0, packet.course, "Negative course must clamp to 0")
        assertEquals("iPadTAKAware", packet.callsign)
        assertTrue(packet.payload is TakPacketV2Data.Payload.Pli)
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
    fun `drawing_circle extracts to DrawnShape Circle variant with StrokeAndFill`() {
        val packet = parser.parse(loadFixture("drawing_circle.xml"))
        val shape = packet.payload as TakPacketV2Data.Payload.DrawnShape
        assertEquals(CotXmlParser.SHAPE_KIND_CIRCLE, shape.kind)
        assertEquals(CotXmlParser.STYLE_STROKE_AND_FILL, shape.style)
        assertTrue(shape.majorCm > 0, "circle must have non-zero radius")
        assertNotEquals(0, shape.strokeArgb, "circle must preserve strokeArgb")
        assertNotEquals(0, shape.fillArgb, "circle must preserve fillArgb")
    }

    @Test
    fun `drawing_freeform preserves StrokeOnly style mode`() {
        val packet = parser.parse(loadFixture("drawing_freeform.xml"))
        val shape = packet.payload as TakPacketV2Data.Payload.DrawnShape
        assertEquals(CotXmlParser.SHAPE_KIND_FREEFORM, shape.kind)
        assertEquals(CotXmlParser.STYLE_STROKE_ONLY, shape.style)
        assertEquals(6, shape.vertices.size)
        assertNotEquals(0, shape.strokeArgb)
        assertEquals(0, shape.fillArgb, "freeform polyline must not gain a fill on parse")

        val rebuilt = builder.build(packet)
        assertTrue(rebuilt.contains("<strokeColor"))
        assertFalse(rebuilt.contains("<fillColor"), "StrokeOnly freeform must not emit fillColor")
    }

    @Test
    fun `drawing_rectangle extracts 4 vertices`() {
        val packet = parser.parse(loadFixture("drawing_rectangle.xml"))
        val shape = packet.payload as TakPacketV2Data.Payload.DrawnShape
        assertEquals(CotXmlParser.SHAPE_KIND_RECTANGLE, shape.kind)
        assertEquals(4, shape.vertices.size)
        assertEquals(CotXmlParser.STYLE_STROKE_AND_FILL, shape.style)
    }

    @Test
    fun `drawing_polygon extracts Polygon kind with 5 vertices`() {
        val packet = parser.parse(loadFixture("drawing_polygon.xml"))
        val shape = packet.payload as TakPacketV2Data.Payload.DrawnShape
        assertEquals(CotXmlParser.SHAPE_KIND_POLYGON, shape.kind)
        assertEquals(5, shape.vertices.size)
        assertTrue(shape.labelsOn)
    }

    @Test
    fun `drawing_telestration truncates to MAX_VERTICES`() {
        val packet = parser.parse(loadFixture("drawing_telestration.xml"))
        val shape = packet.payload as TakPacketV2Data.Payload.DrawnShape
        assertEquals(CotXmlParser.SHAPE_KIND_TELESTRATION, shape.kind)
        assertEquals(CotXmlParser.MAX_VERTICES, shape.vertices.size)
        assertTrue(shape.truncated, "40-vertex telestration must set truncated=true")
    }

    @Test
    fun `ranging_bullseye extracts bullseye fields`() {
        val packet = parser.parse(loadFixture("ranging_bullseye.xml"))
        val shape = packet.payload as TakPacketV2Data.Payload.DrawnShape
        assertEquals(CotXmlParser.SHAPE_KIND_BULLSEYE, shape.kind)
        assertTrue(shape.bullseyeDistanceDm > 0, "bullseye must preserve distance")
        assertEquals(1, shape.bullseyeBearingRef, "bullseye bearingRef=M must encode as 1")
        assertNotEquals(0, shape.bullseyeFlags and 0x01, "rangeRingVisible flag must be set")
        assertNotEquals(0, shape.bullseyeFlags and 0x02, "hasRangeRings flag must be set")
    }

    @Test
    fun `ranging_line extracts to RangeAndBearing variant`() {
        val packet = parser.parse(loadFixture("ranging_line.xml"))
        val rab = packet.payload as TakPacketV2Data.Payload.RangeAndBearing
        assertTrue(rab.rangeCm > 0, "ranging line must preserve range")
        assertTrue(rab.bearingCdeg > 0, "ranging line must preserve bearing")
        assertNotEquals(0, rab.anchorLatI, "ranging line must capture anchor lat")
    }

    @Test
    fun `marker_spot extracts to SpotMap marker with iconset`() {
        val packet = parser.parse(loadFixture("marker_spot.xml"))
        val marker = packet.payload as TakPacketV2Data.Payload.Marker
        assertEquals(CotXmlParser.MARKER_KIND_SPOT, marker.kind)
        assertTrue(marker.iconset.contains("COT_MAPPING_SPOTMAP"))
        assertNotEquals(0, marker.colorArgb)
    }

    @Test
    fun `marker_2525 extracts to 2525 symbol marker`() {
        val packet = parser.parse(loadFixture("marker_2525.xml"))
        val marker = packet.payload as TakPacketV2Data.Payload.Marker
        assertEquals(CotXmlParser.MARKER_KIND_SYMBOL_2525, marker.kind)
        assertTrue(marker.iconset.contains("COT_MAPPING_2525B"))
        // <color argb="-1"/> = white (0xFFFFFFFF) — must round-trip via palette
        assertEquals(1, marker.color, "white argb -1 must map to Team.White = 1")
    }

    @Test
    fun `marker_icon_set extracts to CustomIcon with full iconset path`() {
        val packet = parser.parse(loadFixture("marker_icon_set.xml"))
        val marker = packet.payload as TakPacketV2Data.Payload.Marker
        assertEquals(CotXmlParser.MARKER_KIND_CUSTOM_ICON, marker.kind)
        assertTrue(marker.iconset.endsWith(".png"))
    }

    @Test
    fun `waypoint extracts to Waypoint marker with parent`() {
        val packet = parser.parse(loadFixture("waypoint.xml"))
        val marker = packet.payload as TakPacketV2Data.Payload.Marker
        assertEquals(CotXmlParser.MARKER_KIND_WAYPOINT, marker.kind)
        assertTrue(marker.parentUid.isNotEmpty())
        assertEquals("a-f-G-U-C", marker.parentType)
    }

    @Test
    fun `route_3wp extracts 3 waypoints via typed Route variant`() {
        val packet = parser.parse(loadFixture("route_3wp.xml"))
        val route = packet.payload as TakPacketV2Data.Payload.Route
        assertEquals(3, route.links.size)
        assertEquals(1, route.method, "Driving must encode as 1")
        assertEquals(1, route.direction, "Infil must encode as 1")
        assertEquals("CP", route.prefix)
        assertFalse(route.truncated)
        assertEquals("CP1", route.links[0].callsign)
    }

    @Test
    fun `casevac_medline extracts full MEDEVAC report`() {
        val packet = parser.parse(loadFixture("casevac_medline.xml"))
        val c = packet.payload as TakPacketV2Data.Payload.CasevacReport
        assertEquals(CotXmlParser.PRECEDENCE_URGENT, c.precedence)
        // equipment_flags: hoist (0x02) + extraction_equipment (0x04) = 0x06
        assertEquals(0x06, c.equipmentFlags)
        assertEquals(2, c.litterPatients)
        assertEquals(1, c.ambulatoryPatients)
        assertEquals(CotXmlParser.SECURITY_NO_ENEMY, c.security)
        assertEquals(CotXmlParser.HLZ_MARKING_SMOKE, c.hlzMarking)
        assertEquals("Green smoke", c.zoneMarker)
        assertEquals(2, c.usMilitary)
        assertEquals(0, c.usCivilian)
        assertEquals(1, c.nonUsMilitary)
        assertEquals(0, c.nonUsCivilian)
        assertEquals(0, c.epw)
        assertEquals(0, c.child)
        // terrain_flags: slope (0x01) + loose (0x04) = 0x05
        assertEquals(0x05, c.terrainFlags)
        assertEquals("38.90", c.frequency)
    }

    @Test
    fun `emergency_911 extracts to Alert911 type`() {
        val packet = parser.parse(loadFixture("emergency_911.xml"))
        val e = packet.payload as TakPacketV2Data.Payload.EmergencyAlert
        assertEquals(CotXmlParser.EMERGENCY_TYPE_ALERT_911, e.type)
        assertTrue(e.authoringUid.isNotEmpty(), "emergency must capture authoring uid")
        assertEquals("", e.cancelReferenceUid)
    }

    @Test
    fun `emergency_cancel extracts to Cancel type`() {
        val packet = parser.parse(loadFixture("emergency_cancel.xml"))
        val e = packet.payload as TakPacketV2Data.Payload.EmergencyAlert
        assertEquals(CotXmlParser.EMERGENCY_TYPE_CANCEL, e.type)
    }

    @Test
    fun `task_engage extracts to TaskRequest with target and assignee`() {
        val packet = parser.parse(loadFixture("task_engage.xml"))
        val t = packet.payload as TakPacketV2Data.Payload.TaskRequest
        assertEquals("engage", t.taskType)
        assertEquals("target-01", t.targetUid)
        assertEquals("ANDROID-0000000000000005", t.assigneeUid)
        assertEquals(CotXmlParser.TASK_PRIORITY_HIGH, t.priority)
        assertEquals(CotXmlParser.TASK_STATUS_PENDING, t.status)
        assertEquals("cover by fire", t.note)
    }

    @Test
    fun `chat_receipt_delivered extracts as Chat with receiptType=Delivered`() {
        val packet = parser.parse(loadFixture("chat_receipt_delivered.xml"))
        val chat = packet.payload as TakPacketV2Data.Payload.Chat
        assertEquals(CotXmlParser.RECEIPT_TYPE_DELIVERED, chat.receiptType)
        assertTrue(chat.receiptForUid.isNotEmpty(), "delivered receipt must reference an original message uid")
        assertEquals("", chat.message, "receipt must not carry a text message")
    }

    @Test
    fun `chat_receipt_read extracts as Chat with receiptType=Read`() {
        val packet = parser.parse(loadFixture("chat_receipt_read.xml"))
        val chat = packet.payload as TakPacketV2Data.Payload.Chat
        assertEquals(CotXmlParser.RECEIPT_TYPE_READ, chat.receiptType)
        assertTrue(chat.receiptForUid.isNotEmpty())
    }

    @Test
    fun `drawing_ellipse extracts to Ellipse kind with distinct axes`() {
        val packet = parser.parse(loadFixture("drawing_ellipse.xml"))
        val shape = packet.payload as TakPacketV2Data.Payload.DrawnShape
        assertEquals(CotXmlParser.SHAPE_KIND_ELLIPSE, shape.kind)
        assertTrue(shape.majorCm > 0, "ellipse must have non-zero major axis")
        assertTrue(shape.minorCm > 0, "ellipse must have non-zero minor axis")
        assertTrue(shape.majorCm != shape.minorCm, "ellipse must have distinct major/minor axes")
        assertEquals(45, shape.angleDeg, "ellipse rotation angle must round-trip")
    }

    @Test
    fun `marker_goto extracts to GoToPoint kind`() {
        val packet = parser.parse(loadFixture("marker_goto.xml"))
        val marker = packet.payload as TakPacketV2Data.Payload.Marker
        assertEquals(CotXmlParser.MARKER_KIND_GO_TO_POINT, marker.kind)
        assertTrue(marker.parentUid.isNotEmpty())
    }

    @Test
    fun `marker_tank round-trips via 2525 iconset marker`() {
        val packet = parser.parse(loadFixture("marker_tank.xml"))
        // CotType enum has a-h-G-E-V-A-T at 105 now — verify the id was
        // resolved and the marker captured the 2525 iconset path.
        assertEquals(CotTypeMapper.COTTYPE_A_H_G_E_V_A_T, packet.cotTypeId)
        val marker = packet.payload as TakPacketV2Data.Payload.Marker
        assertEquals(CotXmlParser.MARKER_KIND_SYMBOL_2525, marker.kind)
        assertTrue(marker.iconset.contains("COT_MAPPING_2525B"))
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
