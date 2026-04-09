import XCTest
import Testing
@testable import MeshtasticTAK

// MARK: - Parameterized round-trip (Swift Testing)
//
// Uses the Swift Testing framework's native `@Test(arguments:)` so each
// fixture produces its own individually-counted test case. XCTest has no
// equivalent — an XCTest function that loops over fixtures internally
// counts as ONE test regardless of how many fixtures it exercises, which
// is why the earlier `XCTContext.runActivity`-based version reported only
// 32 total Swift tests while the other four platforms reported 100+.
// Swift Testing runs alongside XCTest in the same target, so the specific
// parse tests below keep using XCTestCase.

@Suite("RoundTrip")
struct RoundTripSuite {

    let parser = CotXmlParser()
    let builder = CotXmlBuilder()
    let compressor = TakCompressor()

    @Test("full round-trip preserves fields", arguments: TestFixtures.fixtureNames)
    func fullRoundTripPreservesFields(_ fixture: String) throws {
        let xml = try TestFixtures.loadFixture(fixture)
        let packet = parser.parse(xml)
        #expect(!packet.uid.isEmpty, "UID empty for \(fixture)")

        let wirePayload = try compressor.compress(packet)
        let decompressed = try compressor.decompress(wirePayload)

        #expect(packet.cotTypeID == decompressed.cotTypeID, "cotTypeId mismatch in \(fixture)")
        #expect(packet.how == decompressed.how, "how mismatch in \(fixture)")
        #expect(packet.callsign == decompressed.callsign, "callsign mismatch in \(fixture)")
        #expect(packet.team == decompressed.team, "team mismatch in \(fixture)")
        #expect(packet.latitudeI == decompressed.latitudeI, "lat mismatch in \(fixture)")
        #expect(packet.longitudeI == decompressed.longitudeI, "lon mismatch in \(fixture)")
        #expect(packet.altitude == decompressed.altitude, "alt mismatch in \(fixture)")
        #expect(packet.battery == decompressed.battery, "battery mismatch in \(fixture)")
        #expect(packet.uid == decompressed.uid, "uid mismatch in \(fixture)")
        #expect(packet.speed == decompressed.speed, "speed mismatch in \(fixture)")
        #expect(packet.course == decompressed.course, "course mismatch in \(fixture)")
        #expect(packet.role == decompressed.role, "role mismatch in \(fixture)")
        #expect(packet.deviceCallsign == decompressed.deviceCallsign, "deviceCallsign mismatch in \(fixture)")
        #expect(packet.takVersion == decompressed.takVersion, "takVersion mismatch in \(fixture)")
        #expect(packet.takPlatform == decompressed.takPlatform, "takPlatform mismatch in \(fixture)")
        #expect(packet.endpoint == decompressed.endpoint, "endpoint mismatch in \(fixture)")

        // Payload-specific field assertions
        switch packet.payloadVariant {
        case .chat(let origChat):
            guard case .chat(let decChat) = decompressed.payloadVariant else {
                Issue.record("Payload type mismatch in \(fixture)"); return
            }
            #expect(origChat.message == decChat.message, "chat.message mismatch in \(fixture)")
            #expect(origChat.to == decChat.to, "chat.to mismatch in \(fixture)")
        case .aircraft(let origAc):
            guard case .aircraft(let decAc) = decompressed.payloadVariant else {
                Issue.record("Payload type mismatch in \(fixture)"); return
            }
            #expect(origAc.icao == decAc.icao, "aircraft.icao mismatch in \(fixture)")
            #expect(origAc.registration == decAc.registration, "aircraft.registration mismatch in \(fixture)")
            #expect(origAc.flight == decAc.flight, "aircraft.flight mismatch in \(fixture)")
            #expect(origAc.squawk == decAc.squawk, "aircraft.squawk mismatch in \(fixture)")
        default:
            break
        }

        let rebuiltXml = builder.build(decompressed)
        #expect(rebuiltXml.contains("<event"), "Rebuilt XML missing <event> for \(fixture)")
    }
}

// MARK: - Specific parse tests (XCTest)

final class RoundTripTests: XCTestCase {

    let parser = CotXmlParser()
    let compressor = TakCompressor()

    func loadFixture(_ name: String) throws -> String {
        try TestFixtures.loadFixture(name)
    }

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
