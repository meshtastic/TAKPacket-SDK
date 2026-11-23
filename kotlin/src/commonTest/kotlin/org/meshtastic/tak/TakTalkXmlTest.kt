package org.meshtastic.tak

import kotlin.io.encoding.Base64
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The TAKTALK payloads as CoT XML, in both directions: `<voice>` on a message, and the
 * voice profile (44), voice profile request (45) and room request (46) `y-` events the
 * plugin routes by UID prefix.
 */
class TakTalkXmlTest {
    private val builder = CotXmlBuilder()
    private val parser = CotXmlParser()
    private val compressor = TakCompressor()

    private val profileId = "6f1c2a54-0d3b-4f6e-9a77-2c8e5b1d4f90"
    private val roomId = "30b2755c-c547-44ef-a0cc-cdbd8a15616f"

    @AfterTest
    fun restoreLogger() {
        TakPacketSdk.logger = NoOpLogger
    }

    private fun packet(
        type: String,
        uid: String,
        payload: TakPacketV2Data.Payload,
    ): TakPacketV2Data =
        TakPacketV2Data
            .Builder()
            .cotType(type)
            .uid(uid)
            .callsign("ALPHA-1")
            .addMarti("BRAVO-2")
            .payload(payload)
            .build()

    /** The plugin's profile: 256 float32 values, big-endian. */
    private fun modelBytes(): ByteArray {
        val out = ByteArray(1_024)
        for (i in 0 until 256) {
            val bits = (i * 0.125f - 16f).toRawBits()
            for (b in 0 until 4) out[i * 4 + b] = (bits ushr (24 - 8 * b)).toByte()
        }
        return out
    }

    /** XML → packet → wire → packet → XML → packet; returns the final packet. */
    private fun throughTheMesh(xml: String): TakPacketV2Data {
        val parsed = parser.parse(xml)
        val result = compressor.compressForMesh(parsed, TakFragmenter(maxSourceFragments = 16), fragmentIfOversize = true)
        val rx = TakMeshReceiver(TakReassembler(frameAirtimeMillis = 2_200L))
        val received = result.frames!!.mapNotNull { rx.onPayload(it, 0L) }.single()
        assertEquals(parsed, received)
        return parser.parse(builder.build(received))
    }

    // --- <voice> on a TAKTALK message ------------------------------------

    @Test
    fun voiceCarriesTheProfileIdAndReadsBack() {
        val talk = TakPacketV2Data.Payload.TakTalk(text = "Moving to the north gate", chatroomId = "1", voiceProfileId = profileId)
        val xml = builder.build(packet("m-t-t", "ANDROID-0000000000000001", talk))
        assertTrue(xml.contains("<voice>$profileId</voice>"), xml)

        val back = parser.parse(xml).payload as TakPacketV2Data.Payload.TakTalk
        assertEquals(profileId, back.voiceProfileId)
        assertEquals(talk, back)
    }

    @Test
    fun voiceIsAlwaysWrittenEvenWithoutAnId() {
        for (fromVoice in listOf(false, true)) {
            val talk = TakPacketV2Data.Payload.TakTalk(text = "Copy", fromVoice = fromVoice)
            val xml = builder.build(packet("m-t-t", "ANDROID-0000000000000001", talk))
            assertTrue(xml.contains("<voice/>"), "fromVoice=$fromVoice: $xml")
            assertEquals("", (parser.parse(xml).payload as TakPacketV2Data.Payload.TakTalk).voiceProfileId)
        }
    }

    @Test
    fun fromVoiceIsNotInferredFromTheElement() {
        val xml =
            """<event version="2.0" uid="a8d3f0c2-1b4e-4c77-9d21-5e6f7a8b9c0d" type="m-t-t" how="null" """ +
                """time="2026-05-27T02:09:08Z" start="2026-05-27T02:09:08Z" stale="2026-05-27T02:11:08Z">""" +
                """<point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>""" +
                """<detail><callsign>ALPHA-1</callsign><text>Testing 123</text><chatroom-id>1</chatroom-id>""" +
                """<voice>$profileId</voice></detail></event>"""
        val talk = parser.parse(xml).payload as TakPacketV2Data.Payload.TakTalk
        assertEquals(profileId, talk.voiceProfileId)
        assertFalse(talk.fromVoice)
    }

    @Test
    fun fromVoiceIsLoggedAsNotRendered() {
        val logged = mutableListOf<String>()
        TakPacketSdk.logger = Logger { logged += it }
        builder.build(packet("m-t-t", "ANDROID-0000000000000001", TakPacketV2Data.Payload.TakTalk(text = "Copy", fromVoice = true)))
        assertTrue(logged.any { "fromVoice" in it }, logged.toString())
    }

    // --- VoiceProfile (tag 44) -------------------------------------------

    @Test
    fun voiceProfileIsWrittenAsThePluginWritesIt() {
        val data = modelBytes()
        val profile = TakPacketV2Data.Payload.VoiceProfile(profileId, "Alpha Lead", data)
        val xml = builder.build(packet("a-f-G-U-C", "ANDROID-0000000000000001", profile))

        assertTrue(xml.contains("""uid="TAKTALK-RESPONSE-ANDROID-0000000000000001""""), xml)
        assertTrue(xml.contains("""type="y-""""), xml)
        assertTrue(xml.contains("<sender-callsign>ALPHA-1</sender-callsign>"), xml)
        assertTrue(xml.contains("<voice-id>$profileId</voice-id>"), xml)
        assertTrue(xml.contains("<voice-name>Alpha Lead</voice-name>"), xml)
        assertFalse(xml.contains("<contact"), xml)

        // Android Base64.DEFAULT: 76-character lines, a newline after each, the last included.
        val body = xml.substringAfter("<voice-data>").substringBefore("</voice-data>")
        val lines = body.split("\n")
        assertEquals("", lines.last(), "trailing newline")
        assertTrue(lines.dropLast(2).all { it.length == 76 }, body)
        assertContentEquals(data, Base64.Default.decode(lines.joinToString("")))

        // Sent already stale, as the plugin does, so ATAK doesn't keep it.
        val time = xml.substringAfter("""time="""").substringBefore('"')
        val stale = xml.substringAfter("""stale="""").substringBefore('"')
        assertTrue(stale < time, "stale $stale should be before time $time")
    }

    @Test
    fun voiceProfileRoundTripsThroughXmlAndTheMesh() {
        val profile = TakPacketV2Data.Payload.VoiceProfile(profileId, "Alpha Lead", modelBytes())
        val xml = builder.build(packet("y-", "TAKTALK-RESPONSE-$profileId", profile))
        val back = throughTheMesh(xml)
        assertEquals(profile, back.payload)
        assertEquals("ALPHA-1", back.callsign)
        assertEquals("TAKTALK-RESPONSE-$profileId", back.uid)
        assertEquals(listOf("BRAVO-2"), back.marti)
    }

    @Test
    fun voiceDataWithSerializedNewlineEntitiesDecodesExactly() {
        // ATAK's CoT serializer turns the newlines into &#10;, whose digits are themselves
        // Base64 characters. They must be resolved, not decoded as data.
        val data = modelBytes()
        val wrapped =
            Base64.Default
                .encode(data)
                .chunked(76)
                .joinToString("&#10;", postfix = "&#10;")
        val xml = voiceProfileXml(wrapped)
        val profile = parser.parse(xml).payload as TakPacketV2Data.Payload.VoiceProfile
        assertContentEquals(data, profile.profileData)
        assertEquals(profileId, profile.voiceProfileId)
        assertEquals("Alpha Lead", profile.voiceProfileName)
    }

    @Test
    fun characterReferencesInVoiceDataAreResolvedNotDropped() {
        // &#65; is "A", a Base64 character: dropping it would shift every later byte.
        val data = modelBytes()
        val encoded = Base64.Default.encode(data)
        val firstA = encoded.indexOf('A')
        assertTrue(firstA >= 0, encoded)
        val withRef = encoded.substring(0, firstA) + "&#65;" + encoded.substring(firstA + 1)
        assertContentEquals(data, (parser.parse(voiceProfileXml(withRef)).payload as TakPacketV2Data.Payload.VoiceProfile).profileData)
    }

    @Test
    fun escapedTextSurvivesTheRoundTrip() {
        val profile = TakPacketV2Data.Payload.VoiceProfile(profileId, "Alpha & Bravo <Lead>", ByteArray(8) { it.toByte() })
        val talk = TakPacketV2Data.Payload.TakTalk(text = "Tom & Jerry", voiceProfileId = "a&b")
        val sentProfile = packet("y-", "TAKTALK-RESPONSE-1", profile).toBuilder().callsign("A & B").build()
        val back = parser.parse(builder.build(sentProfile))
        assertEquals(profile, back.payload)
        assertEquals("A & B", back.callsign)
        assertEquals(talk, parser.parse(builder.build(packet("m-t-t", "u-1", talk))).payload)
    }

    @Test
    fun anUnresolvedEntityIsAnErrorNotCorruptBytes() {
        // A double-escaped &#10; reaches the decoder as the literal text "&#10;".
        val wrapped =
            Base64.Default
                .encode(modelBytes())
                .chunked(76)
                .joinToString("&amp;#10;")
        assertFailsWith<IllegalArgumentException> { parser.parse(voiceProfileXml(wrapped)) }
    }

    private fun voiceProfileXml(voiceData: String) =
        """<event version="2.0" uid="TAKTALK-RESPONSE-$profileId" type="y-" how="h-g-i-g-o" """ +
            """time="2026-05-27T02:09:08Z" start="2026-05-27T02:09:08Z" stale="2026-05-27T01:45:08Z">""" +
            """<point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>""" +
            """<detail><sender-callsign>ALPHA-1</sender-callsign><voice-data>$voiceData</voice-data>""" +
            """<voice-id>$profileId</voice-id><voice-name>Alpha Lead</voice-name></detail></event>"""

    // --- VoiceProfileRequest (tag 45) ------------------------------------

    @Test
    fun voiceProfileRequestIsTheSenderCallsignOnly() {
        val xml = builder.build(packet("y-", "ANDROID-0000000000000001", TakPacketV2Data.Payload.VoiceProfileRequest()))
        assertTrue(xml.contains("""uid="TAKTALK-REQUEST-ANDROID-0000000000000001""""), xml)
        val detail = xml.substringAfter("<detail>").substringBefore("</detail>")
        assertTrue(detail.contains("<sender-callsign>ALPHA-1</sender-callsign>"), detail)
        assertFalse(detail.contains("<chatroom-id>") || detail.contains("<voice"), detail)

        val back = throughTheMesh(xml)
        assertEquals(TakPacketV2Data.Payload.VoiceProfileRequest(), back.payload)
        assertEquals("ALPHA-1", back.callsign)
    }

    @Test
    fun aRequestedProfileIdIsLoggedAsDropped() {
        val logged = mutableListOf<String>()
        TakPacketSdk.logger = Logger { logged += it }
        builder.build(packet("y-", "TAKTALK-REQUEST-1", TakPacketV2Data.Payload.VoiceProfileRequest(profileId)))
        assertTrue(logged.any { "VoiceProfileRequest" in it && profileId in it }, logged.toString())
    }

    // --- TakTalkRoomRequest (tag 46) --------------------------------------

    @Test
    fun roomRequestUsesThePrefixWithoutAHyphen() {
        val xml = builder.build(packet("y-", "", TakPacketV2Data.Payload.TakTalkRoomRequest(roomId)))
        val uid = xml.substringAfter("""uid="""").substringBefore('"')
        // A version 4, RFC 4122 variant UUID straight after the prefix.
        assertTrue(Regex("ROOM-REQUEST[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}").matches(uid), uid)
        assertTrue(xml.contains("<chatroom-id>$roomId</chatroom-id>"), xml)
        assertFalse(xml.contains("<chatroom-name>"), xml)

        val back = throughTheMesh(xml)
        assertEquals(TakPacketV2Data.Payload.TakTalkRoomRequest(roomId), back.payload)
        assertEquals(uid, back.uid)
    }

    @Test
    fun senderCallsignIsWrittenEvenWhenEmpty() {
        // The plugin's receiver reads <sender-callsign> unguarded: leaving it out crashes it.
        val payloads =
            listOf(
                TakPacketV2Data.Payload.VoiceProfile(profileId, "Alpha Lead", ByteArray(4)),
                TakPacketV2Data.Payload.VoiceProfileRequest(),
                TakPacketV2Data.Payload.TakTalkRoomRequest(roomId),
                TakPacketV2Data.Payload.TakTalkRoom(roomId, "Gate team", listOf("BRAVO-2")),
            )
        for (payload in payloads) {
            val noCallsign = packet("y-", "ROOM-DATA-$roomId", payload).toBuilder().callsign("").build()
            val xml = builder.build(noCallsign)
            assertTrue(xml.contains("<sender-callsign/>"), "${payload::class.simpleName}: $xml")
            val back = parser.parse(xml)
            assertEquals("", back.callsign)
            assertEquals(payload, back.payload)
        }
    }

    @Test
    fun aRoomBroadcastIsStillARoom() {
        // Same y- type and <chatroom-id>; only the UID prefix tells them apart.
        val room = TakPacketV2Data.Payload.TakTalkRoom(roomId, "Gate team", listOf("ALPHA-1", "BRAVO-2"))
        val back = parser.parse(builder.build(packet("y-", "ROOM-DATA-$roomId", room)))
        assertEquals(room, back.payload)
    }

    @Test
    fun aPrefixedUidIsKept() {
        for (
        (uid, payload) in listOf(
            "TAKTALK-RESPONSE-x" to TakPacketV2Data.Payload.VoiceProfile(profileId, "A", ByteArray(4)),
            "TAKTALK-REQUEST-x" to TakPacketV2Data.Payload.VoiceProfileRequest(),
            "ROOM-REQUESTx" to TakPacketV2Data.Payload.TakTalkRoomRequest(roomId),
        )
        ) {
            val xml = builder.build(packet("y-", uid, payload))
            assertTrue(xml.contains("""uid="$uid""""), xml)
        }
    }
}
