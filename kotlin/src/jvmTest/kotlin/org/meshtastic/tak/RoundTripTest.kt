package org.meshtastic.tak

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * JVM-only round-trip tests that require zstd compression (zstd-jni).
 * Individual parsing tests and uncompressed round-trip have moved to commonTest
 * (CotXmlParserTest, TakPacketV2SerializerTest) and run on all KMP targets.
 */
class RoundTripTest {

    private val parser = CotXmlParser()
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
    fun `full compressed round-trip preserves fields`(fixture: String) {
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
            else -> { /* PLI has no extra fields to check */ }
        }

        // Build XML from decompressed data and verify it's valid XML
        val builder = CotXmlBuilder()
        val rebuiltXml = builder.build(decompressed)
        assertTrue(rebuiltXml.contains("<event"), "Rebuilt XML should contain <event> for $fixture")
        assertTrue(rebuiltXml.contains(decompressed.callsign),
            "Rebuilt XML should contain callsign for $fixture")
    }
}
