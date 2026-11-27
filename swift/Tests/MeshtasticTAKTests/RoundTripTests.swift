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
        let packet = try parser.parse(xml)
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
        // Directed-routing recipients (v0.3.2). Empty list = broadcast
        // (default for PLI / situational-awareness); populated for TAKTALK
        // m-t-t and directed b-t-f DMs. Dropping the list silently breaks
        // TAKTALK voice TTS on the receiver, so this regression-guards
        // the actual end-to-end behavior that motivated the v0.3.2 release.
        #expect(
            Array(packet.marti.destCallsign) == Array(decompressed.marti.destCallsign),
            "marti mismatch in \(fixture)"
        )

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
        let packet = try parser.parse(xml)
        XCTAssertEqual(packet.uid, "testnode")
        XCTAssertEqual(packet.cotTypeID, .aFGUC)
        XCTAssertEqual(packet.how, .mG)
        XCTAssertEqual(packet.callsign, "testnode")
        XCTAssertEqual(packet.latitudeI, Int32(round(33.1284 * 1e7)))
        XCTAssertEqual(packet.longitudeI, Int32(round(-107.2528 * 1e7)))
    }

    func testAircraftAdsbParsesIcao() throws {
        let xml = try loadFixture("aircraft_adsb")
        let packet = try parser.parse(xml)
        XCTAssertEqual(packet.cotTypeID, .aNACF)
        guard case .aircraft(let ac) = packet.payloadVariant else {
            XCTFail("Expected aircraft payload"); return
        }
        XCTAssertFalse(ac.icao.isEmpty, "ICAO should not be empty")
    }

    func testDeleteEventParsesCorrectly() throws {
        let xml = try loadFixture("delete_event")
        let packet = try parser.parse(xml)
        XCTAssertEqual(packet.cotTypeID, .tXDD)
        XCTAssertEqual(packet.how, .hGIGO)
    }

    func testGeochatParsesMessage() throws {
        let xml = try loadFixture("geochat_simple")
        let packet = try parser.parse(xml)
        XCTAssertEqual(packet.cotTypeID, .bTF)
        guard case .chat(let chat) = packet.payloadVariant else {
            XCTFail("Expected chat payload"); return
        }
        XCTAssertFalse(chat.message.isEmpty, "Chat message should not be empty")
    }

    func testCasevacParsesCorrectly() throws {
        let xml = try loadFixture("casevac")
        let packet = try parser.parse(xml)
        XCTAssertEqual(packet.cotTypeID, .bRFHC)
        XCTAssertEqual(packet.callsign, "CASEVAC-1")
    }

    func testAlertTicParsesCorrectly() throws {
        let xml = try loadFixture("alert_tic")
        let packet = try parser.parse(xml)
        XCTAssertEqual(packet.cotTypeID, .bAOOpn)
        XCTAssertEqual(packet.callsign, "ALPHA-6")
    }

    func testPliFullParsesAllFields() throws {
        let xml = try loadFixture("pli_full")
        let packet = try parser.parse(xml)
        XCTAssertEqual(packet.cotTypeID, .aFGUC)
        XCTAssertFalse(packet.callsign.isEmpty)
        XCTAssertFalse(packet.takVersion.isEmpty, "takVersion should not be empty")
        XCTAssertFalse(packet.takPlatform.isEmpty, "takPlatform should not be empty")
        XCTAssertGreaterThan(packet.battery, 0, "Battery should be > 0")
        // PLI is the implicit payload since v0.4.0 — no payload variant is set.
        XCTAssertNil(packet.payloadVariant, "Expected implicit PLI (no payload variant)")
    }

    func testPliStationaryClampsNegativeSpeedAndCourse() throws {
        // Regression for an iOS crash where ATAK's <track speed="-1.0"
        // course="-1.0"/> sentinel for stationary / unknown targets tripped a
        // Double -> UInt32 conversion trap in the Swift parser. The proto
        // field is uint32 on all platforms, so the fix is to clamp negatives
        // to 0 rather than wrap them into huge unsigned values.
        let xml = try loadFixture("pli_stationary")
        let packet = try parser.parse(xml)
        XCTAssertEqual(packet.speed, 0, "Negative speed must clamp to 0")
        XCTAssertEqual(packet.course, 0, "Negative course must clamp to 0")
        XCTAssertEqual(packet.callsign, "iPadTAKAware")
        // PLI is the implicit payload since v0.4.0 — no payload variant is set.
        XCTAssertNil(packet.payloadVariant, "Expected implicit PLI (no payload variant)")
    }

    func testUncompressed0xFFRoundTrip() throws {
        var packet = TAKPacketV2()
        packet.cotTypeID = .aFGUC
        packet.how = .mG
        packet.callsign = "TEST"
        packet.latitudeI = 340522000
        packet.longitudeI = -1182437000
        packet.altitude = 100
        // PLI is the implicit payload since v0.4.0 — no payload variant set.

        let proto = try packet.serializedData()
        var wire = Data([0xFF])
        wire.append(proto)

        let decompressed = try compressor.decompress(wire)
        XCTAssertEqual(decompressed.cotTypeID, .aFGUC)
        XCTAssertEqual(decompressed.callsign, "TEST")
        XCTAssertEqual(decompressed.latitudeI, 340522000)
    }
}

// MARK: - <environment> and <sensor> annotations (Swift Testing)
//
// Mirrors Kotlin's behavior after a wire round trip, which is what a mesh
// receiver sees. Kotlin converts units in its serializer; this binding's
// parser writes the wire values directly, so they are asserted in wire units.

@Suite("SensorEnvironment")
struct SensorEnvironmentSuite {

    let parser = CotXmlParser()
    let builder = CotXmlBuilder()
    let compressor = TakCompressor()

    /// A PLI whose `<detail>` holds just `detail`.
    private func event(_ detail: String) -> String {
        """
        <event version="2.0" uid="ALPHA-1" type="a-f-G-U-C" how="m-g" \
        time="2026-03-15T16:12:00Z" start="2026-03-15T16:12:00Z" stale="2026-03-15T16:12:45Z">\
        <point lat="38.8895" lon="-77.0353" hae="0.0" ce="9999999.0" le="9999999.0"/>\
        <detail>\(detail)</detail></event>
        """
    }

    private func overTheWire(_ packet: TAKPacketV2) throws -> TAKPacketV2 {
        try compressor.decompress(try compressor.compress(packet))
    }

    /// The annotations `pli_with_sensor.xml` carries, in wire units.
    private static let fixtureEnvironment: TAKEnvironment = {
        var env = TAKEnvironment()
        env.temperatureCX10 = 225  // 22.5 °C
        env.windDirectionDeg = 270
        env.windSpeedCmS = 1200  // 12.0 m/s
        return env
    }()

    private static let fixtureSensor: SensorFov = {
        var fov = SensorFov()
        fov.type = .thermal
        fov.azimuthDeg = 45
        fov.rangeM = 2500
        fov.fovHorizontalDeg = 45
        fov.fovVerticalDeg = 30
        fov.elevationDeg = -5
        fov.rollDeg = 0
        fov.model = "FLIR-Boson-640"
        return fov
    }()

    private func expectFixtureAnnotations(
        _ packet: TAKPacketV2, _ stage: String, sourceLocation: SourceLocation = #_sourceLocation
    ) {
        #expect(packet.hasEnvironment, "\(stage): environment missing", sourceLocation: sourceLocation)
        #expect(packet.environment == Self.fixtureEnvironment, "\(stage)", sourceLocation: sourceLocation)
        #expect(packet.hasSensorFov, "\(stage): sensor_fov missing", sourceLocation: sourceLocation)
        #expect(packet.sensorFov.hasRangeM, "\(stage): range_m missing", sourceLocation: sourceLocation)
        #expect(packet.sensorFov == Self.fixtureSensor, "\(stage)", sourceLocation: sourceLocation)
    }

    @Test("pli_with_sensor keeps both annotations through parse, wire, build and re-parse")
    func fixtureRoundTrip() throws {
        let packet = try parser.parse(try TestFixtures.loadFixture("pli_with_sensor"))
        expectFixtureAnnotations(packet, "parse")

        let received = try overTheWire(packet)
        expectFixtureAnnotations(received, "wire")

        let xml = builder.build(received)
        #expect(xml.contains(#"<environment temperature="22.5" windDirection="270" windSpeed="12.0"/>"#), "\(xml)")
        #expect(
            xml.contains(#"<sensor azimuth="45" range="2500" fov="45" vfov="30" elevation="-5" model="FLIR-Boson-640"/>"#),
            "\(xml)"
        )
        let sensorTag = xml.range(of: "<sensor[^>]*>", options: .regularExpression).map { String(xml[$0]) }
        #expect(sensorTag?.contains(" type=") == false, "<sensor> must not carry a type attribute")

        expectFixtureAnnotations(try parser.parse(xml), "re-parse")
    }

    @Test("pli_with_sensor serializes to the bytes of Kotlin's golden .pb")
    func matchesKotlinProtobufGolden() throws {
        let golden = try #require(TestFixtures.loadProtobuf("pli_with_sensor"))
        let packet = try parser.parse(try TestFixtures.loadFixture("pli_with_sensor"))
        #expect(try packet.serializedData() == golden)
    }

    @Test("a bare <sensor/> is present with ATAK-CIV's defaults and no range")
    func bareSensor() throws {
        let packet = try parser.parse(event("<sensor/>"))
        #expect(packet.hasSensorFov)
        let fov = packet.sensorFov
        #expect(fov.azimuthDeg == 270)
        #expect(fov.fovHorizontalDeg == 45)
        #expect(!fov.hasRangeM)
        #expect(fov.fovVerticalDeg == 0)
        #expect(fov.elevationDeg == 0)
        #expect(fov.rollDeg == 0)
        #expect(fov.model.isEmpty)
        #expect(fov.type == .unspecified)
        #expect(builder.build(try overTheWire(packet)).contains(#"<sensor azimuth="270" fov="45" elevation="0"/>"#))
    }

    @Test("<environment> needs one parseable attribute, and the rest default to 0")
    func environmentPartials() throws {
        let packet = try parser.parse(event(#"<environment temperature="-3"/>"#))
        #expect(packet.hasEnvironment)
        #expect(packet.environment.temperatureCX10 == -30)
        #expect(packet.environment.windDirectionDeg == 0)
        #expect(packet.environment.windSpeedCmS == 0)
        #expect(builder.build(try overTheWire(packet)).contains(#"<environment temperature="-3.0"/>"#))

        // windDirection is an Int attribute, so "270.5" does not parse.
        for detail in [#"<environment windDirection="270.5"/>"#, "<environment/>", #"<environment temperature="abc"/>"#] {
            let unset = try parser.parse(event(detail))
            #expect(!unset.hasEnvironment, "\(detail) should leave environment unset")
        }
    }

    @Test("rounding is Kotlin's roundToInt applied after the double multiply")
    func rounding() throws {
        let fov = try parser.parse(event(#"<sensor azimuth="44.5" elevation="-2.5"/>"#)).sensorFov
        #expect(fov.azimuthDeg == 45)
        #expect(fov.elevationDeg == -2, "a tie goes up, not away from zero")
        // Java's Math.round gives 0 here; floor(d + 0.5) would give 1.
        #expect(try parser.parse(event(#"<sensor roll="0.49999999999999994"/>"#)).sensorFov.rollDeg == 0)

        // 22.45 * 10 is exactly 224.5 as a double, so it ties up to 225, as Kotlin does.
        #expect(try parser.parse(event(#"<environment temperature="22.45"/>"#)).environment.temperatureCX10 == 225)
        // 1.005 * 100 is 100.49999999999999, just under the tie, so it rounds down.
        #expect(try parser.parse(event(#"<environment windSpeed="1.005"/>"#)).environment.windSpeedCmS == 100)
    }

    @Test("inferSensorType follows Kotlin's keyword order", arguments: [
        (nil, .unspecified), ("", .unspecified),
        ("FLIR-Boson-640", .thermal), ("thermal cam", .thermal), ("Boson", .thermal),
        ("LRF-3000", .laser), ("laser", .laser),
        ("NVG-21", .nvg), ("night vision", .nvg),
        ("radar", .rf), ("rf-detector", .rf), ("RF-Scanner", .rf),
        ("rfscanner", .other), ("rf", .other),
        ("EO-IR", .camera), ("camera", .camera), ("optic", .camera),
        ("widget", .other),
        ("FLIR radar", .thermal),
    ] as [(String?, SensorFov.SensorType)])
    func infersSensorType(_ model: String?, _ expected: SensorFov.SensorType) {
        #expect(CotXmlParser.inferSensorType(model) == expected)
    }

    @Test("range=\"0\" stays present with value 0 and is rebuilt")
    func zeroRangeStaysPresent() throws {
        let packet = try parser.parse(event(#"<sensor range="0"/>"#))
        #expect(packet.sensorFov.hasRangeM)
        #expect(packet.sensorFov.rangeM == 0)
        let received = try overTheWire(packet)
        #expect(received.sensorFov.hasRangeM, "range_m presence lost on the wire")
        #expect(builder.build(received).contains(#" range="0""#))
    }

    @Test("numbers parse like Kotlin's toDoubleOrNull and toIntOrNull, except NaN and Infinity")
    func numberGrammar() throws {
        // Doubles take surrounding whitespace, a sign, a bare fraction and an exponent.
        let fov = try parser.parse(event(#"<sensor azimuth=" 45 " range="1e3" fov="+30" vfov=".5"/>"#)).sensorFov
        #expect(fov.azimuthDeg == 45)
        #expect(fov.rangeM == 1000)
        #expect(fov.fovHorizontalDeg == 30)
        #expect(fov.fovVerticalDeg == 1)

        // NaN and Infinity read as absent, where Kotlin throws or clamps.
        let odd = try parser.parse(event(#"<sensor azimuth="NaN" range="Infinity" fov="1e400"/>"#)).sensorFov
        #expect(odd.azimuthDeg == 270)
        #expect(!odd.hasRangeM)
        #expect(odd.fovHorizontalDeg == 45)
        let infinite = try parser.parse(event(#"<environment temperature="-Infinity" windSpeed="1e400"/>"#))
        #expect(!infinite.hasEnvironment)

        // windDirection takes a sign but no whitespace.
        #expect(try parser.parse(event(#"<environment windDirection="+270"/>"#)).environment.windDirectionDeg == 270)
        let spaced = try parser.parse(event(#"<environment windDirection=" 270"/>"#))
        #expect(!spaced.hasEnvironment)
    }

    @Test("a negative value in an unsigned field keeps its Int32 bits and rebuilds as negative")
    func negativeUnsignedValues() throws {
        let detail = #"<environment windDirection="-10" windSpeed="-1.5"/><sensor azimuth="-90"/>"#
        let packet = try overTheWire(try parser.parse(event(detail)))
        #expect(packet.environment.windDirectionDeg == UInt32(truncatingIfNeeded: Int32(-10)))
        #expect(packet.environment.windSpeedCmS == UInt32(truncatingIfNeeded: Int32(-150)))
        #expect(packet.sensorFov.azimuthDeg == UInt32(truncatingIfNeeded: Int32(-90)))
        let xml = builder.build(packet)
        #expect(xml.contains(#"<environment temperature="0.0" windDirection="-10" windSpeed="-1.5"/>"#), "\(xml)")
        #expect(xml.contains(#"<sensor azimuth="-90" fov="45" elevation="0"/>"#), "\(xml)")
    }

    @Test("only a parseable <environment> replaces an earlier one, and the last <sensor> wins")
    func laterElements() throws {
        let detail = #"<environment temperature="10"/><environment windSpeed="2"/><environment temperature="x"/>"#
            + #"<sensor azimuth="10"/><sensor/>"#
        let packet = try parser.parse(event(detail))
        #expect(packet.environment.temperatureCX10 == 0)
        #expect(packet.environment.windSpeedCmS == 200)
        #expect(packet.sensorFov.azimuthDeg == 270)
    }
}
