package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Cross-platform protobuf serialization tests.
 * Verifies that [TakPacketV2Serializer] correctly round-trips [TakPacketV2Data]
 * through protobuf wire format. Runs on JVM, iOS, and any future KMP target.
 */
class TakPacketV2SerializerTest {

    @Test
    fun pliRoundTrip() {
        val original = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
            how = CotTypeMapper.COTHOW_M_G,
            callsign = "TESTNODE",
            uid = "test-uid-123",
            latitudeI = 377749000,
            longitudeI = -1224194000,
            altitude = 50,
            speed = 120,
            course = 14275,
            battery = 88,
            team = 10,
            role = 1,
            payload = TakPacketV2Data.Payload.Pli(true),
        )

        val bytes = TakPacketV2Serializer.serialize(original)
        assertTrue(bytes.isNotEmpty(), "Serialized bytes should not be empty")

        val deserialized = TakPacketV2Serializer.deserialize(bytes)
        assertPacketEquals(original, deserialized)
    }

    @Test
    fun chatRoundTrip() {
        val original = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_B_T_F,
            how = CotTypeMapper.COTHOW_H_G_I_G_O,
            callsign = "VIPER",
            uid = "GeoChat.test.chat.1234",
            latitudeI = 423601000,
            longitudeI = -710589000,
            payload = TakPacketV2Data.Payload.Chat(
                message = "Hello from test",
                to = "All Chat Rooms",
                toCallsign = "VIPER",
            ),
        )

        val bytes = TakPacketV2Serializer.serialize(original)
        val deserialized = TakPacketV2Serializer.deserialize(bytes)
        assertPacketEquals(original, deserialized)

        val chat = deserialized.payload as TakPacketV2Data.Payload.Chat
        assertEquals("Hello from test", chat.message)
        assertEquals("All Chat Rooms", chat.to)
        assertEquals("VIPER", chat.toCallsign)
    }

    @Test
    fun aircraftRoundTrip() {
        val original = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_N_A_C_F,
            how = CotTypeMapper.COTHOW_M_G,
            callsign = "DAL417-N338DN-A3",
            uid = "ICAOF1E2D3",
            latitudeI = 398561000,
            longitudeI = -1046737000,
            altitude = 3048,
            speed = 22358,
            course = 7616,
            payload = TakPacketV2Data.Payload.Aircraft(
                icao = "F1E2D3",
                registration = "N338DN",
                flight = "DAL417",
                aircraftType = "A321",
                squawk = 3456,
                category = "A3",
                rssiX10 = -194,
                gps = true,
                cotHostId = "",
            ),
        )

        val bytes = TakPacketV2Serializer.serialize(original)
        val deserialized = TakPacketV2Serializer.deserialize(bytes)
        assertPacketEquals(original, deserialized)

        val aircraft = deserialized.payload as TakPacketV2Data.Payload.Aircraft
        assertEquals("F1E2D3", aircraft.icao)
        assertEquals("N338DN", aircraft.registration)
        assertEquals("DAL417", aircraft.flight)
        assertEquals("A321", aircraft.aircraftType)
        assertEquals(3456, aircraft.squawk)
    }

    @Test
    fun emptyPacketRoundTrip() {
        val original = TakPacketV2Data()
        val bytes = TakPacketV2Serializer.serialize(original)
        val deserialized = TakPacketV2Serializer.deserialize(bytes)
        assertEquals(CotTypeMapper.COTTYPE_OTHER, deserialized.cotTypeId)
        assertEquals("", deserialized.callsign)
    }

    @Test
    fun fullFieldCoverageRoundTrip() {
        val original = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
            cotTypeStr = null,
            how = CotTypeMapper.COTHOW_H_E,
            callsign = "FULLTEST",
            team = 5, // Red
            role = 2, // Team Lead
            latitudeI = 388977000,
            longitudeI = -770365000,
            altitude = 100,
            speed = 350,
            course = 27000,
            battery = 95,
            geoSrc = CotXmlParser.GEOSRC_GPS,
            altSrc = CotXmlParser.GEOSRC_GPS,
            uid = "full-coverage-test",
            deviceCallsign = "FULLTEST-DEVICE",
            staleSeconds = 120,
            takVersion = "4.12.0",
            takDevice = "Test Device",
            takPlatform = "ATAK-CIV",
            takOs = "15",
            endpoint = "*:-1:stcp",
            phone = "+15551234567",
            payload = TakPacketV2Data.Payload.Pli(true),
        )

        val bytes = TakPacketV2Serializer.serialize(original)
        val deserialized = TakPacketV2Serializer.deserialize(bytes)

        assertEquals(original.cotTypeId, deserialized.cotTypeId)
        assertEquals(original.how, deserialized.how)
        assertEquals(original.callsign, deserialized.callsign)
        assertEquals(original.team, deserialized.team)
        assertEquals(original.role, deserialized.role)
        assertEquals(original.latitudeI, deserialized.latitudeI)
        assertEquals(original.longitudeI, deserialized.longitudeI)
        assertEquals(original.altitude, deserialized.altitude)
        assertEquals(original.speed, deserialized.speed)
        assertEquals(original.course, deserialized.course)
        assertEquals(original.battery, deserialized.battery)
        assertEquals(original.geoSrc, deserialized.geoSrc)
        assertEquals(original.altSrc, deserialized.altSrc)
        assertEquals(original.uid, deserialized.uid)
        assertEquals(original.deviceCallsign, deserialized.deviceCallsign)
        assertEquals(original.staleSeconds, deserialized.staleSeconds)
        assertEquals(original.takVersion, deserialized.takVersion)
        assertEquals(original.takDevice, deserialized.takDevice)
        assertEquals(original.takPlatform, deserialized.takPlatform)
        assertEquals(original.takOs, deserialized.takOs)
        assertEquals(original.endpoint, deserialized.endpoint)
        assertEquals(original.phone, deserialized.phone)
    }

    @Test
    fun serializeIsDeterministic() {
        val packet = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
            callsign = "DETERMINISM",
            uid = "det-test",
            latitudeI = 100000000,
            payload = TakPacketV2Data.Payload.Pli(true),
        )

        val bytes1 = TakPacketV2Serializer.serialize(packet)
        val bytes2 = TakPacketV2Serializer.serialize(packet)
        assertContentEquals(bytes1, bytes2, "Repeated serialization should be deterministic")
    }

    @Test
    fun uncompressedPayloadRoundTrips() {
        val packet = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
            how = CotTypeMapper.COTHOW_M_G,
            callsign = "TEST",
            latitudeI = 340522000,
            longitudeI = -1182437000,
            altitude = 100,
            payload = TakPacketV2Data.Payload.Pli(true),
        )

        // Simulate firmware TAK_TRACKER: flags=0xFF + raw protobuf (no compression)
        val protobuf = TakPacketV2Serializer.serialize(packet)
        val wirePayload = ByteArray(1 + protobuf.size)
        wirePayload[0] = 0xFF.toByte()
        protobuf.copyInto(wirePayload, destinationOffset = 1)

        // Decompress should handle 0xFF flag = raw protobuf
        val decompressed = TakCompressor().decompress(wirePayload)
        assertEquals(packet.cotTypeId, decompressed.cotTypeId)
        assertEquals(packet.callsign, decompressed.callsign)
        assertEquals(packet.latitudeI, decompressed.latitudeI)
    }

    @Test
    fun parseToProtobufRoundTripForAllFixtures() {
        val parser = CotXmlParser()
        for ((name, xml) in TestFixtures.ALL) {
            val packet = parser.parse(xml)
            val bytes = TakPacketV2Serializer.serialize(packet)
            val deserialized = TakPacketV2Serializer.deserialize(bytes)

            assertEquals(packet.cotTypeId, deserialized.cotTypeId, "cotTypeId mismatch in $name")
            assertEquals(packet.callsign, deserialized.callsign, "callsign mismatch in $name")
            assertEquals(packet.latitudeI, deserialized.latitudeI, "latitudeI mismatch in $name")
            assertEquals(packet.longitudeI, deserialized.longitudeI, "longitudeI mismatch in $name")
            assertEquals(packet.payload::class, deserialized.payload::class, "payload type mismatch in $name")
        }
    }

    // Helper to assert key fields match between two packets
    private fun assertPacketEquals(expected: TakPacketV2Data, actual: TakPacketV2Data) {
        assertEquals(expected.cotTypeId, actual.cotTypeId, "cotTypeId")
        assertEquals(expected.how, actual.how, "how")
        assertEquals(expected.callsign, actual.callsign, "callsign")
        assertEquals(expected.team, actual.team, "team")
        assertEquals(expected.role, actual.role, "role")
        assertEquals(expected.latitudeI, actual.latitudeI, "latitudeI")
        assertEquals(expected.longitudeI, actual.longitudeI, "longitudeI")
        assertEquals(expected.altitude, actual.altitude, "altitude")
        assertEquals(expected.speed, actual.speed, "speed")
        assertEquals(expected.course, actual.course, "course")
        assertEquals(expected.battery, actual.battery, "battery")
        assertEquals(expected.uid, actual.uid, "uid")
        assertEquals(expected.payload::class, actual.payload::class, "payload type")
    }
}
