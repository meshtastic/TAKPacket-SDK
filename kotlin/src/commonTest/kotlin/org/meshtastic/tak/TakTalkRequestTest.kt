package org.meshtastic.tak

import org.meshtastic.proto.TAKPacketV2
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The TAKTALK request payloads, `VoiceProfileRequest` (tag 45) and
 * `TakTalkRoomRequest` (tag 46). They are packet-level only: built directly,
 * never parsed from CoT XML.
 */
class TakTalkRequestTest {
    private val compressor = TakCompressor()

    private fun request(payload: TakPacketV2Data.Payload): TakPacketV2Data =
        TakPacketV2Data
            .Builder()
            .cotType("y-")
            .uid("ANDROID-0000000000000001")
            .callsign("ALPHA-1")
            .addMarti("BRAVO-2")
            .payload(payload)
            .build()

    private val voiceRequest = request(TakPacketV2Data.Payload.VoiceProfileRequest("6f1c2a54-0d3b-4f6e-9a77-2c8e5b1d4f90"))
    private val roomRequest = request(TakPacketV2Data.Payload.TakTalkRoomRequest("30b2755c-c547-44ef-a0cc-cdbd8a15616f"))

    @Test
    fun voiceProfileRequestRoundTrips() {
        assertEquals(voiceRequest, TakPacketV2Serializer.deserialize(TakPacketV2Serializer.serialize(voiceRequest)))
        assertEquals(voiceRequest, compressor.decompress(compressor.compress(voiceRequest)))
    }

    @Test
    fun takTalkRoomRequestRoundTrips() {
        assertEquals(roomRequest, TakPacketV2Serializer.deserialize(TakPacketV2Serializer.serialize(roomRequest)))
        assertEquals(roomRequest, compressor.decompress(compressor.compress(roomRequest)))
    }

    @Test
    fun eachRequestUsesItsOwnOneofArm() {
        val voice = TAKPacketV2.ADAPTER.decode(TakPacketV2Serializer.serialize(voiceRequest))
        assertEquals("6f1c2a54-0d3b-4f6e-9a77-2c8e5b1d4f90", voice.voice_profile_request?.voice_profile_id)
        assertNull(voice.taktalk_room_request)
        assertNull(voice.taktalk_room)

        val room = TAKPacketV2.ADAPTER.decode(TakPacketV2Serializer.serialize(roomRequest))
        assertEquals("30b2755c-c547-44ef-a0cc-cdbd8a15616f", room.taktalk_room_request?.room_id)
        assertNull(room.voice_profile_request)
        assertNull(room.taktalk_room)
        assertEquals(listOf("BRAVO-2"), room.marti?.dest_callsign)
    }

    @Test
    fun anEmptyRequestIsStillARequestNotAPli() {
        // An empty voice_profile_id means "whichever profile you are using". The arm must
        // still be present on the wire, or the receiver would decode an implicit PLI.
        for (payload in listOf(TakPacketV2Data.Payload.VoiceProfileRequest(), TakPacketV2Data.Payload.TakTalkRoomRequest())) {
            val packet = request(payload)
            val decoded = compressor.decompress(compressor.compress(packet))
            assertEquals(payload, decoded.payload)
        }
    }

    @Test
    fun xmlRendersTheEnvelopeOnly() {
        val xml = CotXmlBuilder().build(roomRequest)
        assertTrue(xml.contains("""type="y-""""), xml)
        assertTrue(xml.contains("BRAVO-2"), "marti must survive so the request reaches its addressee: $xml")
        assertFalse(xml.contains("30b2755c-c547-44ef-a0cc-cdbd8a15616f"), xml)
        assertNotNull(CotXmlParser().parse(xml))
    }
}
