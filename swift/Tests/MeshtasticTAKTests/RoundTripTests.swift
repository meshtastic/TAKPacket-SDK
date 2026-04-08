import XCTest
@testable import MeshtasticTAK

final class RoundTripTests: XCTestCase {

    let parser = CotXmlParser()
    let builder = CotXmlBuilder()
    let compressor = TakCompressor()

    func loadFixture(_ name: String) throws -> String {
        let url = Bundle.module.url(forResource: "Fixtures/cot_xml/\(name)", withExtension: "xml")!
        return try String(contentsOf: url, encoding: .utf8)
    }

    // MARK: - Helper for round-trip validation

    func assertRoundTrip(_ fixture: String, file: StaticString = #file, line: UInt = #line) throws {
        let xml = try loadFixture(fixture)
        let packet = parser.parse(xml)
        XCTAssertFalse(packet.uid.isEmpty, "UID empty for \(fixture)", file: file, line: line)

        let wirePayload = try compressor.compress(packet)
        let decompressed = try compressor.decompress(wirePayload)

        XCTAssertEqual(packet.cotTypeID, decompressed.cotTypeID, "cotTypeId mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.how, decompressed.how, "how mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.callsign, decompressed.callsign, "callsign mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.team, decompressed.team, "team mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.latitudeI, decompressed.latitudeI, "lat mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.longitudeI, decompressed.longitudeI, "lon mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.altitude, decompressed.altitude, "alt mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.battery, decompressed.battery, "battery mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.uid, decompressed.uid, "uid mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.speed, decompressed.speed, "speed mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.course, decompressed.course, "course mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.role, decompressed.role, "role mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.deviceCallsign, decompressed.deviceCallsign, "deviceCallsign mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.takVersion, decompressed.takVersion, "takVersion mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.takPlatform, decompressed.takPlatform, "takPlatform mismatch in \(fixture)", file: file, line: line)
        XCTAssertEqual(packet.endpoint, decompressed.endpoint, "endpoint mismatch in \(fixture)", file: file, line: line)

        // Payload-specific field assertions
        switch packet.payloadVariant {
        case .chat(let origChat):
            guard case .chat(let decChat) = decompressed.payloadVariant else {
                XCTFail("Payload type mismatch in \(fixture)", file: file, line: line); return
            }
            XCTAssertEqual(origChat.message, decChat.message, "chat.message mismatch in \(fixture)", file: file, line: line)
            XCTAssertEqual(origChat.to, decChat.to, "chat.to mismatch in \(fixture)", file: file, line: line)
        case .aircraft(let origAc):
            guard case .aircraft(let decAc) = decompressed.payloadVariant else {
                XCTFail("Payload type mismatch in \(fixture)", file: file, line: line); return
            }
            XCTAssertEqual(origAc.icao, decAc.icao, "aircraft.icao mismatch in \(fixture)", file: file, line: line)
            XCTAssertEqual(origAc.registration, decAc.registration, "aircraft.registration mismatch in \(fixture)", file: file, line: line)
            XCTAssertEqual(origAc.flight, decAc.flight, "aircraft.flight mismatch in \(fixture)", file: file, line: line)
            XCTAssertEqual(origAc.squawk, decAc.squawk, "aircraft.squawk mismatch in \(fixture)", file: file, line: line)
        default:
            break
        }

        let rebuiltXml = builder.build(decompressed)
        XCTAssertTrue(rebuiltXml.contains("<event"), "Rebuilt XML missing <event> for \(fixture)", file: file, line: line)
    }

    // MARK: - Per-fixture round-trip tests

    func testRoundTrip_pliBasic() throws { try assertRoundTrip("pli_basic") }
    func testRoundTrip_pliFull() throws { try assertRoundTrip("pli_full") }
    func testRoundTrip_pliWebtak() throws { try assertRoundTrip("pli_webtak") }
    func testRoundTrip_geochatSimple() throws { try assertRoundTrip("geochat_simple") }
    func testRoundTrip_aircraftAdsb() throws { try assertRoundTrip("aircraft_adsb") }
    func testRoundTrip_aircraftHostile() throws { try assertRoundTrip("aircraft_hostile") }
    func testRoundTrip_deleteEvent() throws { try assertRoundTrip("delete_event") }
    func testRoundTrip_casevac() throws { try assertRoundTrip("casevac") }
    func testRoundTrip_alertTic() throws { try assertRoundTrip("alert_tic") }

    // MARK: - Specific parsing tests

    func testPliBasicParsesCorrectly() throws {
        let xml = try loadFixture("pli_basic")
        let packet = parser.parse(xml)
        XCTAssertEqual(packet.uid, "testnode")
        XCTAssertEqual(packet.cotTypeID, .aFGUC)
        XCTAssertEqual(packet.how, .mG)
        XCTAssertEqual(packet.callsign, "testnode")
        XCTAssertEqual(packet.latitudeI, Int32(37.7749 * 1e7))
        XCTAssertEqual(packet.longitudeI, Int32(-122.4194 * 1e7))
    }

    func testAircraftAdsbParsesIcao() throws {
        let xml = try loadFixture("aircraft_adsb")
        let packet = parser.parse(xml)
        XCTAssertEqual(packet.cotTypeID, .aNACF)
        guard case .aircraft(let ac) = packet.payloadVariant else {
            XCTFail("Expected aircraft payload"); return
        }
        XCTAssertFalse(ac.icao.isEmpty, "ICAO should not be empty")
    }

    func testDeleteEventParsesCorrectly() throws {
        let xml = try loadFixture("delete_event")
        let packet = parser.parse(xml)
        XCTAssertEqual(packet.cotTypeID, .tXDD)
        XCTAssertEqual(packet.how, .hGIGO)
    }

    func testGeochatParsesMessage() throws {
        let xml = try loadFixture("geochat_simple")
        let packet = parser.parse(xml)
        XCTAssertEqual(packet.cotTypeID, .bTF)
        guard case .chat(let chat) = packet.payloadVariant else {
            XCTFail("Expected chat payload"); return
        }
        XCTAssertFalse(chat.message.isEmpty, "Chat message should not be empty")
    }

    func testCasevacParsesCorrectly() throws {
        let xml = try loadFixture("casevac")
        let packet = parser.parse(xml)
        XCTAssertEqual(packet.cotTypeID, .bRFHC)
        XCTAssertEqual(packet.callsign, "CASEVAC-1")
    }

    func testAlertTicParsesCorrectly() throws {
        let xml = try loadFixture("alert_tic")
        let packet = parser.parse(xml)
        XCTAssertEqual(packet.cotTypeID, .bAOOpn)
        XCTAssertEqual(packet.callsign, "ALPHA-6")
    }

    func testPliFullParsesAllFields() throws {
        let xml = try loadFixture("pli_full")
        let packet = parser.parse(xml)
        XCTAssertEqual(packet.cotTypeID, .aFGUC)
        XCTAssertFalse(packet.callsign.isEmpty)
        XCTAssertFalse(packet.takVersion.isEmpty, "takVersion should not be empty")
        XCTAssertFalse(packet.takPlatform.isEmpty, "takPlatform should not be empty")
        XCTAssertGreaterThan(packet.battery, 0, "Battery should be > 0")
        guard case .pli = packet.payloadVariant else {
            XCTFail("Expected PLI payload"); return
        }
    }

    func testUncompressed0xFFRoundTrip() throws {
        var packet = TAKPacketV2()
        packet.cotTypeID = .aFGUC
        packet.how = .mG
        packet.callsign = "TEST"
        packet.latitudeI = 340522000
        packet.longitudeI = -1182437000
        packet.altitude = 100
        packet.pli = true

        let proto = try packet.serializedData()
        var wire = Data([0xFF])
        wire.append(proto)

        let decompressed = try compressor.decompress(wire)
        XCTAssertEqual(decompressed.cotTypeID, .aFGUC)
        XCTAssertEqual(decompressed.callsign, "TEST")
        XCTAssertEqual(decompressed.latitudeI, 340522000)
    }
}
