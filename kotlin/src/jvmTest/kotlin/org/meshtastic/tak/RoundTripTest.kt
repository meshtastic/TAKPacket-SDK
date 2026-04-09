package org.meshtastic.tak

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * JVM-only round-trip tests that require zstd compression (zstd-jni).
 * Individual parsing tests and uncompressed round-trip have moved to commonTest
 * (CotXmlParserTest, TakPacketV2SerializerTest) and run on all KMP targets.
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

        // Compress -> decompress (requires zstd-jni)
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
        protobuf.copyInto(wirePayload, destinationOffset = 1)

        val decompressed = compressor.decompress(wirePayload)
        assertEquals(packet.cotTypeId, decompressed.cotTypeId)
        assertEquals(packet.callsign, decompressed.callsign)
        assertEquals(packet.latitudeI, decompressed.latitudeI)
    }
}
