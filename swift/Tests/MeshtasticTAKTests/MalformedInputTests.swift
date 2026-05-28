import XCTest
@testable import MeshtasticTAK

final class MalformedInputTests: XCTestCase {

    let compressor = TakCompressor()

    func loadMalformed(_ name: String) -> Data {
        (try? TestFixtures.loadMalformed(name)) ?? Data()
    }

    func testRejectsEmptyPayload() {
        XCTAssertThrowsError(try compressor.decompress(Data()))
    }

    func testRejectsSingleByte() {
        XCTAssertThrowsError(try compressor.decompress(Data([0x00])))
    }

    func testRejectsInvalidDictId() {
        XCTAssertThrowsError(try compressor.decompress(loadMalformed("invalid_dict_id.bin")))
    }

    func testRejectsTruncatedZstd() {
        XCTAssertThrowsError(try compressor.decompress(loadMalformed("truncated_zstd.bin")))
    }

    func testRejectsCorruptedZstd() {
        XCTAssertThrowsError(try compressor.decompress(loadMalformed("corrupted_zstd.bin")))
    }

    func testHandlesInvalidProtobufWithoutCrash() {
        // 0xFF + garbage bytes — no crash is the key assertion
        do {
            _ = try compressor.decompress(loadMalformed("invalid_protobuf.bin"))
        } catch {
            // Expected — either outcome is acceptable
        }
    }

    func testIgnoresReservedBitsInFlagsByte() throws {
        // 0xC0 has reserved bits set but dict ID = 0 (0xC0 & 0x3F = 0)
        let packet = try compressor.decompress(loadMalformed("reserved_bits_set.bin"))
        XCTAssertFalse(packet.uid.isEmpty, "Should decompress despite reserved bits")
    }

    // Security attack tests

    func testRejectsXmlWithDoctype() throws {
        let xml = try TestFixtures.loadMalformedXml("xml_doctype.xml")
        let parser = CotXmlParser()
        XCTAssertThrowsError(try parser.parse(xml)) { error in
            XCTAssertTrue(error is CotXmlParserError, "Expected CotXmlParserError")
        }
    }

    func testRejectsXmlWithEntityExpansion() throws {
        let xml = try TestFixtures.loadMalformedXml("xml_entity_expansion.xml")
        let parser = CotXmlParser()
        XCTAssertThrowsError(try parser.parse(xml)) { error in
            XCTAssertTrue(error is CotXmlParserError, "Expected CotXmlParserError")
        }
    }

    func testRejectsOversizedFields() {
        XCTAssertThrowsError(try compressor.decompress(loadMalformed("oversized_callsign.bin")))
    }

    func testRejectsDecompressionBomb() {
        XCTAssertThrowsError(try compressor.decompress(loadMalformed("decompression_bomb.bin")))
    }

    // MARK: - TAKTALK edge-case tests
    //
    // These confirm that anomalous-but-legal TAKTALK shapes — empty bodies,
    // missing children, surprise sibling elements — parse without crashing
    // and don't corrupt the surrounding envelope.

    func testMTTWithEmptyTextParsesWithoutCrashAndRoundTrips() throws {
        let xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="TAKTALK-MESSAGE-empty" type="m-t-t" how="null" \
        time="2026-05-27T01:36:25.023Z" start="2026-05-27T01:36:25.023Z" \
        stale="2026-05-27T01:42:26.251Z">
          <point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
          <detail>
            <callsign>ASPEN</callsign>
            <lang>English</lang>
            <text></text>
            <chatroom-id>1</chatroom-id>
          </detail>
        </event>
        """
        let parser = CotXmlParser()
        let packet = try parser.parse(xml)
        XCTAssertEqual(packet.cotTypeID, .mTT, "should classify as m-t-t even with empty text")
        guard case .taktalk(let tt) = packet.payloadVariant else {
            XCTFail("Expected taktalk payload"); return
        }
        XCTAssertEqual(tt.text, "", "empty text body should round-trip as empty string")
        XCTAssertEqual(tt.chatroomID, "1")
        XCTAssertEqual(tt.lang, "English")
        XCTAssertFalse(tt.fromVoice)
        // Full wire round-trip — compress + decompress shouldn't throw
        let wire = try compressor.compress(packet)
        let decompressed = try compressor.decompress(wire)
        XCTAssertEqual(decompressed.cotTypeID, .mTT)
    }

    func testYDashWithNoParticipantsParsesWithEmptyParticipantsList() throws {
        let xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="ROOM-DATA-no-roster" type="y-" how="null" \
        time="2026-05-27T02:09:08.426Z" start="2026-05-27T02:09:08.426Z" \
        stale="2026-05-27T02:15:09.699Z">
          <point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
          <detail>
            <sender-callsign>ASPEN</sender-callsign>
            <chatroom-id>30b2755c-c547-44ef-a0cc-cdbd8a15616f</chatroom-id>
            <chatroom-name>test-empty-room</chatroom-name>
          </detail>
        </event>
        """
        let parser = CotXmlParser()
        let packet = try parser.parse(xml)
        XCTAssertEqual(packet.cotTypeID, .y)
        // v0.3.2: <sender-callsign> routes into envelope packet.callsign,
        // not payload.senderCallsign (which is now deprecated).
        XCTAssertEqual(packet.callsign, "ASPEN")
        guard case .taktalkRoom(let room) = packet.payloadVariant else {
            XCTFail("Expected taktalkRoom payload"); return
        }
        XCTAssertEqual(room.roomID, "30b2755c-c547-44ef-a0cc-cdbd8a15616f")
        XCTAssertEqual(room.roomName, "test-empty-room")
        XCTAssertTrue(room.participants.isEmpty, "missing <chatroom-participants> should yield empty list, not crash")
        // Round-trip wire to confirm no crash on serialize/deserialize
        let wire = try compressor.compress(packet)
        let decompressed = try compressor.decompress(wire)
        XCTAssertEqual(decompressed.cotTypeID, .y)
        guard case .taktalkRoom(let roomBack) = decompressed.payloadVariant else {
            XCTFail("Expected taktalkRoom payload after round-trip"); return
        }
        XCTAssertTrue(roomBack.participants.isEmpty)
    }

    func testBTFWithEmptyEaElementDoesNotCorruptChatParsing() throws {
        // <Ea></Ea> with no body — chat message in <remarks> must NOT be
        // clobbered by the empty Ea text.
        let xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="GeoChat.ANDROID-aaa.ANDROID-bbb.malformed-ea" type="b-t-f" how="h-g-i-g-o" \
        time="2026-05-27T02:09:20.718Z" start="2026-05-27T02:09:20.718Z" \
        stale="2026-05-28T02:09:20.718Z">
          <point lat="38.8895" lon="-77.0353" hae="73.863" ce="6.9" le="9999999.0"/>
          <detail>
            <__chat parent="RootContactGroup" messageId="malformed-ea" chatroom="ETHEL" \
        id="ANDROID-bbb" senderCallsign="ASPEN">
              <chatgrp uid0="ANDROID-aaa" uid1="ANDROID-bbb" id="ANDROID-bbb"/>
            </__chat>
            <link uid="ANDROID-aaa" type="a-f-G-U-C" relation="p-p"/>
            <remarks source="BAO.F.ATAK.ANDROID-aaa" to="ANDROID-bbb" \
        time="2026-05-27T02:09:20.718Z">Real message body</remarks>
            <Ea></Ea>
            <roomId>30b2755c-c547-44ef-a0cc-cdbd8a15616f</roomId>
          </detail>
        </event>
        """
        let parser = CotXmlParser()
        let packet = try parser.parse(xml)
        guard case .chat(let chat) = packet.payloadVariant else {
            XCTFail("Expected chat payload"); return
        }
        // The critical assertion — chat message survives intact despite the
        // empty <Ea/> sibling sitting next to <remarks>.
        XCTAssertEqual(chat.message, "Real message body", "chat message must not be clobbered by empty <Ea/>")
        XCTAssertEqual(chat.lang, "", "empty <Ea/> yields empty lang, not garbage")
        XCTAssertEqual(chat.roomID, "30b2755c-c547-44ef-a0cc-cdbd8a15616f")
        // Round-trip should preserve message body
        let wire = try compressor.compress(packet)
        let decompressed = try compressor.decompress(wire)
        guard case .chat(let chatBack) = decompressed.payloadVariant else {
            XCTFail("Expected chat payload after round-trip"); return
        }
        XCTAssertEqual(chatBack.message, "Real message body")
    }
}
