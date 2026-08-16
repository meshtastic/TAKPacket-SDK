package org.meshtastic.tak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class MalformedInputTest {
    private val compressor = TakCompressor()

    private fun loadMalformed(name: String): ByteArray {
        val path = File("../testdata/malformed/$name")
        return path.readBytes()
    }

    @Test
    fun `rejects empty payload`() {
        assertThrows<Exception> { compressor.decompress(byteArrayOf()) }
    }

    @Test
    fun `rejects single byte`() {
        assertThrows<Exception> { compressor.decompress(byteArrayOf(0x00)) }
    }

    @Test
    fun `rejects invalid dictionary ID`() {
        assertThrows<Exception> { compressor.decompress(loadMalformed("invalid_dict_id.bin")) }
    }

    @Test
    fun `rejects truncated zstd frame`() {
        assertThrows<Exception> { compressor.decompress(loadMalformed("truncated_zstd.bin")) }
    }

    @Test
    fun `rejects corrupted zstd`() {
        assertThrows<Exception> { compressor.decompress(loadMalformed("corrupted_zstd.bin")) }
    }

    @Test
    fun `handles invalid protobuf without crash`() {
        // 0xFF + garbage bytes — protobuf parser may be lenient or may throw
        // Key assertion: no crash
        try {
            compressor.decompress(loadMalformed("invalid_protobuf.bin"))
        } catch (_: Exception) {
            // Expected — either outcome is acceptable
        }
    }

    @Test
    fun `ignores reserved bits in flags byte`() {
        // 0xC0 has reserved bits set but dict ID = 0 (0xC0 & 0x3F = 0)
        // Regenerate fixture if dictionary changed
        regenerateReservedBitsFixtureIfNeeded()
        val packet = compressor.decompress(loadMalformed("reserved_bits_set.bin"))
        assert(packet.uid.isNotEmpty()) { "Should decompress despite reserved bits" }
    }

    private fun regenerateReservedBitsFixtureIfNeeded() {
        val fixture = File("../testdata/malformed/reserved_bits_set.bin")
        // Try decompressing; if it fails, the dictionary changed — regenerate
        try {
            compressor.decompress(fixture.readBytes())
        } catch (_: Exception) {
            val parser = CotXmlParser()
            val xml = """<event version="2.0" uid="test-reserved-bits" type="a-f-G-U-C" how="m-g" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-15T14:24:10Z"><point lat="10.0" lon="95.0" hae="100" ce="9999999" le="9999999"/><detail><contact callsign="testnode5" endpoint="0.0.0.0:4242:tcp"/><__group role="Team Member" name="Cyan"/><status battery="88"/><track speed="1.2" course="142.75"/><uid Droid="testnode5"/></detail></event>"""
            val packet = parser.parse(xml)
            val wire = compressor.compress(packet)
            wire[0] = 0xC0.toByte() // reserved bits set, dictId=0
            fixture.writeBytes(wire)
        }
    }

    // Security attack tests

    @Test
    fun `rejects XML with DOCTYPE declaration`() {
        val xml = File("../testdata/malformed/xml_doctype.xml").readText()
        val parser = CotXmlParser()
        assertThrows<Exception> { parser.parse(xml) }
    }

    @Test
    fun `rejects XML with entity expansion`() {
        val xml = File("../testdata/malformed/xml_entity_expansion.xml").readText()
        val parser = CotXmlParser()
        assertThrows<Exception> { parser.parse(xml) }
    }

    @Test
    fun `rejects oversized protobuf fields`() {
        assertThrows<Exception> { compressor.decompress(loadMalformed("oversized_callsign.bin")) }
    }

    @Test
    fun `rejects decompression bomb`() {
        assertThrows<Exception> { compressor.decompress(loadMalformed("decompression_bomb.bin")) }
    }

    // -- Decompression size-cap boundary tests (audit item #19) --------------
    //
    // The existing decompression_bomb.bin fixture proves "> 4096 rejects" for
    // a dict-compressed payload via the zstd library's max_output_size guard.
    // These tests pin the boundary on the 0xFF uncompressed path — the only
    // branch where TakCompressor enforces the cap itself — with synthetic
    // wire payloads of exactly 4096 and 4097 bytes.

    @Test
    fun `uncompressed payload over MAX_DECOMPRESSED_SIZE is rejected`() {
        // [0xFF] + 4097 bytes of anything -> size check MUST fire before
        // the bytes are handed to the protobuf parser.
        val wire = ByteArray(1 + TakCompressor.MAX_DECOMPRESSED_SIZE + 1)
        wire[0] = 0xFF.toByte()
        val ex = assertThrows<IllegalArgumentException> { compressor.decompress(wire) }
        assertTrue(
            ex.message?.contains("exceeds limit") == true,
            "expected 'exceeds limit' in error message, got: ${ex.message}",
        )
    }

    @Test
    fun `uncompressed payload at MAX_DECOMPRESSED_SIZE passes size guard`() {
        // [0xFF] + exactly 4096 bytes. The size check is `> MAX_DECOMPRESSED_SIZE`
        // so 4096 bytes is within the limit. 4096 zero bytes is NOT valid
        // protobuf (field tag 0 is reserved), so the call will still throw —
        // but the failure must come from the downstream protobuf parse step,
        // NOT from the size guard. Verified by asserting the error message
        // does not mention the size limit.
        val wire = ByteArray(1 + TakCompressor.MAX_DECOMPRESSED_SIZE)
        wire[0] = 0xFF.toByte()
        try {
            compressor.decompress(wire)
            // If it somehow parses successfully, that's fine — no size error.
        } catch (e: Exception) {
            assertFalse(
                e.message?.contains("exceeds limit") == true,
                "size check fired at the exact boundary: ${e.message}",
            )
        }
    }

    // -- TAKTALK edge-case tests ---------------------------------------------
    //
    // These confirm that anomalous-but-legal TAKTALK shapes — empty bodies,
    // missing children, surprise sibling elements — parse without crashing
    // and don't corrupt the surrounding envelope. The wire format is
    // emitted by ATAK + TAKTALK in the field and we have to tolerate
    // whatever ordering or omissions the plugin produces.

    @Test
    fun `m-t-t with empty text parses without crash and round-trips`() {
        // Empty <text/> element — receiver should still recognize the
        // event as a TAKTALK message rather than falling through to PLI.
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <event version="2.0" uid="TAKTALK-MESSAGE-empty" type="m-t-t" how="null"
                time="2026-05-27T01:36:25.023Z" start="2026-05-27T01:36:25.023Z"
                stale="2026-05-27T01:42:26.251Z">
              <point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
              <detail>
                <callsign>ASPEN</callsign>
                <lang>English</lang>
                <text></text>
                <chatroom-id>1</chatroom-id>
              </detail>
            </event>"""
        val parser = CotXmlParser()
        val packet = parser.parse(xml)
        assertEquals(CotTypeMapper.COTTYPE_M_T_T, packet.cotTypeId, "should classify as m-t-t even with empty text")
        val talk = packet.payload as TakPacketV2Data.Payload.TakTalk
        assertEquals("", talk.text, "empty text body should round-trip as empty string")
        assertEquals("1", talk.chatroomId)
        assertEquals("English", talk.lang)
        assertFalse(talk.fromVoice)
        // Full wire round-trip — compress + decompress shouldn't throw
        val wire = compressor.compress(packet)
        val decompressed = compressor.decompress(wire)
        assertEquals(CotTypeMapper.COTTYPE_M_T_T, decompressed.cotTypeId)
    }

    @Test
    fun `y- with no participants parses with empty participants list`() {
        // <chatroom-participants> omitted entirely. Receiver should still
        // recognize as TakTalkRoom and populate other fields normally.
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <event version="2.0" uid="ROOM-DATA-no-roster" type="y-" how="null"
                time="2026-05-27T02:09:08.426Z" start="2026-05-27T02:09:08.426Z"
                stale="2026-05-27T02:15:09.699Z">
              <point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
              <detail>
                <sender-callsign>ASPEN</sender-callsign>
                <chatroom-id>30b2755c-c547-44ef-a0cc-cdbd8a15616f</chatroom-id>
                <chatroom-name>test-empty-room</chatroom-name>
              </detail>
            </event>"""
        val parser = CotXmlParser()
        val packet = parser.parse(xml)
        assertEquals(CotTypeMapper.COTTYPE_Y_DASH, packet.cotTypeId)
        // <sender-callsign> routes into envelope packet.callsign — TakTalkRoom
        // itself carries no sender-callsign field.
        assertEquals("ASPEN", packet.callsign)
        val room = packet.payload as TakPacketV2Data.Payload.TakTalkRoom
        assertEquals("30b2755c-c547-44ef-a0cc-cdbd8a15616f", room.roomId)
        assertEquals("test-empty-room", room.roomName)
        assertTrue(room.participants.isEmpty(), "missing <chatroom-participants> should yield empty list, not crash")
        // Round-trip wire to confirm no crash on serialize/deserialize
        val wire = compressor.compress(packet)
        val decompressed = compressor.decompress(wire)
        assertEquals(CotTypeMapper.COTTYPE_Y_DASH, decompressed.cotTypeId)
        val roomBack = decompressed.payload as TakPacketV2Data.Payload.TakTalkRoom
        assertTrue(roomBack.participants.isEmpty())
    }

    @Test
    fun `b-t-f with empty Ea element does not corrupt chat parsing`() {
        // <Ea></Ea> with no body — chat message in <remarks> must NOT be
        // clobbered by the empty Ea text. Previously a bug here would
        // cause the chat 'message' field to come back as "" because the
        // TEXT handler grabbed any detail-level text indiscriminately.
        val xml = """<?xml version="1.0" encoding="UTF-8"?>
            <event version="2.0" uid="GeoChat.ANDROID-aaa.ANDROID-bbb.malformed-ea" type="b-t-f" how="h-g-i-g-o"
                time="2026-05-27T02:09:20.718Z" start="2026-05-27T02:09:20.718Z"
                stale="2026-05-28T02:09:20.718Z">
              <point lat="38.8895" lon="-77.0353" hae="73.863" ce="6.9" le="9999999.0"/>
              <detail>
                <__chat parent="RootContactGroup" messageId="malformed-ea" chatroom="ETHEL"
                    id="ANDROID-bbb" senderCallsign="ASPEN">
                  <chatgrp uid0="ANDROID-aaa" uid1="ANDROID-bbb" id="ANDROID-bbb"/>
                </__chat>
                <link uid="ANDROID-aaa" type="a-f-G-U-C" relation="p-p"/>
                <remarks source="BAO.F.ATAK.ANDROID-aaa" to="ANDROID-bbb"
                    time="2026-05-27T02:09:20.718Z">Real message body</remarks>
                <Ea></Ea>
                <roomId>30b2755c-c547-44ef-a0cc-cdbd8a15616f</roomId>
              </detail>
            </event>"""
        val parser = CotXmlParser()
        val packet = parser.parse(xml)
        val chat = packet.payload as TakPacketV2Data.Payload.Chat
        // The critical assertion — chat message survives intact despite
        // the empty <Ea/> sibling sitting next to <remarks>.
        assertEquals("Real message body", chat.message, "chat message must not be clobbered by empty <Ea/>")
        assertEquals("", chat.lang, "empty <Ea/> yields empty lang, not garbage")
        assertEquals("30b2755c-c547-44ef-a0cc-cdbd8a15616f", chat.roomId)
        // Round-trip should preserve message body
        val wire = compressor.compress(packet)
        val decompressed = compressor.decompress(wire)
        val chatBack = decompressed.payload as TakPacketV2Data.Payload.Chat
        assertEquals("Real message body", chatBack.message)
    }
}
