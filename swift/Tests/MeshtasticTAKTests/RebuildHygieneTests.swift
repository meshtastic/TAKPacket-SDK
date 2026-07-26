import XCTest
@testable import MeshtasticTAK

/// Locks two rebuild invariants: the XML `CotXmlBuilder` emits always parses
/// as exactly ONE `<event>` document, and a contact endpoint never travels
/// over the mesh in either direction.
///
/// Uses XCTest (not Swift Testing) so it builds even where the Testing module
/// is unavailable, same as `ResilienceTests`.
final class RebuildHygieneTests: XCTestCase {

    private let parser = CotXmlParser()
    private let builder = CotXmlBuilder()

    /// Count of non-overlapping `needle` occurrences in `haystack`.
    private func occurrences(of needle: String, in haystack: String) -> Int {
        haystack.components(separatedBy: needle).count - 1
    }

    /// A minimal PLI-shaped packet — callsign present so `<contact>` is emitted.
    private func basePacket() -> TAKPacketV2 {
        var packet = TAKPacketV2()
        packet.uid = "ALPHA-1"
        packet.cotTypeID = .aFGUC
        packet.how = .mG
        packet.callsign = "ALPHA-1"
        packet.latitudeI = 331284000
        packet.longitudeI = -1072528000
        packet.altitude = 100
        return packet
    }

    func testRawDetailFragmentThatWouldUnbalanceTheWrapperIsDropped() {
        var packet = basePacket()
        // Inner content of ONE <detail> can't legitimately hold these tag
        // tokens; echoing them back would split the rebuild into two events.
        packet.rawDetail = Data("<remarks>ping</remarks></detail></event><event uid=\"ALPHA-2\">".utf8)

        let xml = builder.build(packet)

        XCTAssertFalse(xml.contains("</detail></event><event "), "malformed fragment must not be re-emitted")
        XCTAssertEqual(occurrences(of: "<event", in: xml), 1, "rebuilt XML must carry exactly one <event")
        XCTAssertEqual(occurrences(of: "</event>", in: xml), 1, "rebuilt XML must carry exactly one </event>")
        // Only the fragment is skipped — the rest of the event still builds.
        XCTAssertTrue(xml.contains("callsign=\"ALPHA-1\""), "remaining event content must still be built")
    }

    func testRawDetailFragmentWithMixedCaseWrapperTokensIsDropped() {
        var packet = basePacket()
        // XML element names are case-sensitive, but the tag tokens that would
        // unbalance the wrapper have to be recognized whatever case they are
        // typed in — `</DETAIL></Event><Event …>` splits the rebuild into two
        // documents exactly like its lowercase spelling does.
        let fragment = "<remarks>ALPHA-1 checking in</remarks></DETAIL></Event><Event version=\"2.0\" uid=\"SECOND\">"
        packet.rawDetail = Data(fragment.utf8)

        let xml = builder.build(packet)

        XCTAssertFalse(xml.contains(fragment), "mixed-case fragment must not be re-emitted")
        XCTAssertFalse(xml.contains("uid=\"SECOND\""), "the fragment's second event must not reach the rebuilt XML")
        // Case-insensitive counts: a re-emitted fragment would add `<Event`
        // and `</Event>`, which a case-sensitive count would miss entirely.
        let lowered = xml.lowercased()
        XCTAssertEqual(occurrences(of: "<event", in: lowered), 1, "rebuilt XML must carry exactly one event element")
        XCTAssertEqual(occurrences(of: "</event>", in: lowered), 1, "rebuilt XML must carry exactly one event close tag")
        // Only the fragment is skipped — the rest of the event still builds.
        XCTAssertTrue(xml.contains("callsign=\"ALPHA-1\""), "remaining event content must still be built")
    }

    func testBenignRawDetailFragmentStillRoundTripsVerbatim() {
        var packet = basePacket()
        packet.rawDetail = Data("<foo bar=\"1\"/>".utf8)

        let xml = builder.build(packet)

        XCTAssertTrue(xml.contains("<foo bar=\"1\"/>"), "well-formed fragment must be re-emitted verbatim")
        XCTAssertEqual(occurrences(of: "<event", in: xml), 1, "rebuilt XML must carry exactly one <event")
        XCTAssertEqual(occurrences(of: "</event>", in: xml), 1, "rebuilt XML must carry exactly one </event>")
    }

    func testBuilderAlwaysEmitsTheServerStreamEndpoint() {
        var packet = basePacket()
        // Older senders may still put a concrete endpoint on the packet.
        packet.endpoint = "192.0.2.1:4242:tcp"

        let xml = builder.build(packet)

        XCTAssertTrue(xml.contains("endpoint=\"*:-1:stcp\""), "contact must reply via the server stream")
        XCTAssertFalse(xml.contains("192.0.2.1"), "a concrete endpoint must never reach the rebuilt XML")
    }

    func testParserNeverStoresAContactEndpoint() throws {
        let xml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="ALPHA-1" type="a-f-G-U-C" how="m-g" \
        time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-15T14:27:10Z">
          <point lat="33.1284" lon="-107.2528" hae="1234.0" ce="9.5" le="9999999.0"/>
          <detail>
            <contact callsign="ALPHA-1" endpoint="192.0.2.1:4242:tcp"/>
            <__group name="Cyan" role="Team Member"/>
          </detail>
        </event>
        """

        let packet = try parser.parse(xml)

        XCTAssertEqual(packet.callsign, "ALPHA-1", "callsign still parses")
        XCTAssertTrue(packet.endpoint.isEmpty, "a contact endpoint must never be stored on the packet")
    }
}
