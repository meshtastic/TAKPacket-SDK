import Foundation
import Testing
@testable import MeshtasticTAK

// MARK: - The <__detection> annotation (TAKPacketV2 tag 47)
//
// Mirrors Kotlin's DetectionXmlTest: the passive-sensor detection annotation as
// <__detection> and on the wire.

// A synthetic CTA-2063-A serial number: the placeholder manufacturer code ZZZZ,
// length code 8, then the eight-character serial.
private let uasId = "ZZZZ8TEST0001"

private let fullElement =
    #"<__detection kind="uas" sources="remote_id rf" confidence="87" state="active" trend="closing" count="2" "#
    + #"sensorAnchored="true" cep="12" rssi="-71" uasId="ZZZZ8TEST0001" uasIdType="serial_number" uasStatus="airborne"/>"#

private func bit(_ source: TAKDetection.Source) -> UInt32 { UInt32(source.rawValue) }

@Suite("DetectionXml")
struct DetectionXmlSuite {

    let parser = CotXmlParser()
    let builder = CotXmlBuilder()
    let compressor = TakCompressor()

    private func event(_ detail: String, type: String = "a-u-A-M-H-Q", ce: String = "9999999") -> String {
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <event version="2.0" uid="UAS-1" type="\(type)" how="m-r" \
        time="2026-11-20T15:00:00Z" start="2026-11-20T15:00:00Z" stale="2026-11-20T15:01:00Z">
          <point lat="33.1286" lon="-107.2470" hae="1452.0" ce="\(ce)" le="9999999"/>
          <detail><contact callsign="UAS-1"/>\(detail)</detail>
        </event>
        """
    }

    /// The detection `element` parses to, or nil when the packet has none.
    private func detection(_ element: String, ce: String = "9999999") throws -> TAKDetection? {
        try detection(fromXml: event(element, ce: ce))
    }

    private func detection(fromXml xml: String) throws -> TAKDetection? {
        let packet = try parser.parse(xml)
        return packet.hasDetection ? packet.detection : nil
    }

    private func packet(_ detection: TAKDetection) -> TAKPacketV2 {
        var packet = TAKPacketV2()
        packet.cotTypeStr = "a-u-A-M-H-Q"
        packet.uid = "UAS-1"
        packet.callsign = "UAS-1"
        packet.detection = detection
        return packet
    }

    private func overTheWire(_ packet: TAKPacketV2) throws -> TAKPacketV2 {
        try compressor.decompress(try compressor.compress(packet))
    }

    // MARK: Tokens

    // Spelled out rather than derived, so a renamed enum value can't quietly change the XML.
    private static let kinds: KeyValuePairs<TAKDetection.Kind, String> = [
        .unspecified: "unspecified", .uas: "uas", .uasOperator: "uas_operator", .uasHome: "uas_home",
        .person: "person", .vehicle: "vehicle", .other: "other",
    ]
    private static let states: KeyValuePairs<TAKDetection.State, String> = [
        .unspecified: "unspecified", .new: "new", .active: "active", .coasting: "coasting", .lost: "lost",
    ]
    private static let trends: KeyValuePairs<TAKDetection.Trend, String> = [
        .unspecified: "unspecified", .closing: "closing", .steady: "steady", .opening: "opening",
    ]
    private static let idTypes: KeyValuePairs<TAKDetection.UasIdType, String> = [
        .unspecified: "unspecified", .serialNumber: "serial_number", .caaRegistration: "caa_registration",
        .utmAssigned: "utm_assigned", .specificSession: "specific_session",
    ]
    private static let statuses: KeyValuePairs<TAKDetection.UasStatus, String> = [
        .unspecified: "unspecified", .ground: "ground", .airborne: "airborne", .emergency: "emergency",
        .systemFailure: "system_failure",
    ]
    private static let sources: KeyValuePairs<TAKDetection.Source, String> = [
        .unspecified: "unspecified", .remoteID: "remote_id", .rf: "rf", .acoustic: "acoustic",
        .radar: "radar", .optical: "optical", .thermal: "thermal", .seismic: "seismic",
        .magnetic: "magnetic",
    ]

    /// The line building `detection` must produce for one attribute: unspecified is left out.
    private func expectBuilds(
        _ detection: TAKDetection, _ attribute: String, _ token: String,
        sourceLocation: SourceLocation = #_sourceLocation
    ) {
        let xml = builder.build(packet(detection))
        let line = token == "unspecified" ? "    <__detection/>\n" : #"<__detection \#(attribute)="\#(token)"/>"#
        #expect(xml.contains(line), "\(attribute)=\(token) built \(xml)", sourceLocation: sourceLocation)
    }

    /// Every row of `table`, both ways, for the enum attribute stored at `field`.
    private func expectTokens<E: CaseIterable & Hashable>(
        _ attribute: String, _ field: WritableKeyPath<TAKDetection, E>, _ table: KeyValuePairs<E, String>,
        sourceLocation: SourceLocation = #_sourceLocation
    ) throws {
        // Every value is in the table, so a value added later without a token fails here.
        #expect(table.count == E.allCases.count, "\(attribute) table", sourceLocation: sourceLocation)
        #expect(Set(table.map(\.key)) == Set(E.allCases), "\(attribute) table", sourceLocation: sourceLocation)
        for (value, token) in table {
            let parsed = try detection(#"<__detection \#(attribute)="\#(token)"/>"#)
            #expect(parsed?[keyPath: field] == value, "\(attribute)=\(token)", sourceLocation: sourceLocation)
            var built = TAKDetection()
            built[keyPath: field] = value
            expectBuilds(built, attribute, token, sourceLocation: sourceLocation)
        }
    }

    @Test("every enum value has a token that reads back")
    func everyEnumValueHasATokenThatReadsBack() throws {
        try expectTokens("kind", \.kind, Self.kinds)
        try expectTokens("state", \.state, Self.states)
        try expectTokens("trend", \.trend, Self.trends)
        try expectTokens("uasIdType", \.uasIDType, Self.idTypes)
        try expectTokens("uasStatus", \.uasStatus, Self.statuses)

        #expect(Self.sources.count == TAKDetection.Source.allCases.count)
        #expect(Set(Self.sources.map(\.key)) == Set(TAKDetection.Source.allCases))
        for (source, token) in Self.sources {
            #expect(try detection(#"<__detection sources="\#(token)"/>"#)?.sources == bit(source), "sources=\(token)")
            expectBuilds(.with { $0.sources = bit(source) }, "sources", token)
        }
    }

    @Test("sources are written in bit order and read as a bitmask")
    func sourcesAreWrittenInBitOrderAndReadAsABitmask() throws {
        let all = TAKDetection.Source.allCases.reduce(UInt32(0)) { $0 | bit($1) }
        let xml = builder.build(packet(.with { $0.sources = all }))
        #expect(xml.contains(#"sources="remote_id rf acoustic radar optical thermal seismic magnetic""#), "\(xml)")
        #expect(try parser.parse(xml).detection.sources == all)
    }

    // MARK: The element

    private static let full = TAKDetection.with {
        $0.kind = .uas
        $0.sources = bit(.remoteID) | bit(.rf)
        $0.confidence = 87
        $0.state = .active
        $0.trend = .closing
        $0.count = 2
        $0.sensorAnchored = true
        $0.cepM = 12
        $0.rssiDbm = -71
        $0.uasID = Data(uasId.utf8)
        $0.uasIDType = .serialNumber
        $0.uasStatus = .airborne
    }

    @Test("the full element survives the mesh")
    func theFullElementSurvivesTheMesh() throws {
        let parsed = try parser.parse(event(fullElement))
        #expect(parsed.hasDetection)
        #expect(parsed.detection == Self.full)

        let received = try overTheWire(parsed)
        #expect(received == parsed)

        let rebuilt = builder.build(received)
        #expect(rebuilt.contains("    \(fullElement)\n"), "\(rebuilt)")
        #expect(rebuilt.contains(#"ce="12" le="9999999""#), "\(rebuilt)")
        #expect(try detection(fromXml: rebuilt) == Self.full)
    }

    @Test("a bare element is present with every default")
    func aBareElementIsPresentWithEveryDefault() throws {
        #expect(try detection("<__detection/>") == TAKDetection())
        // The last element wins, even a bare one.
        #expect(try detection(#"<__detection kind="uas" confidence="87"/><__detection/>"#) == TAKDetection())
        let xml = builder.build(packet(TAKDetection()))
        #expect(xml.contains("    <__detection/>\n"), "\(xml)")
        #expect(xml.contains(#"ce="9999999""#), "\(xml)")
    }

    @Test("an event without the element has no detection and keeps the unknown ce")
    func anEventWithoutTheElementHasNoDetection() throws {
        let parsed = try parser.parse(event("", ce: "12.0"))
        #expect(!parsed.hasDetection)
        let received = try overTheWire(parsed)
        #expect(!received.hasDetection)
        let xml = builder.build(parsed)
        #expect(!xml.contains("__detection"), "\(xml)")
        #expect(xml.contains(#"ce="9999999""#), "\(xml)")
    }

    @Test("unknown tokens read as unspecified, and case is ignored")
    func unknownTokensReadAsUnspecifiedAndCaseIsIgnored() throws {
        let d = try #require(try detection(
            #"<__detection kind="balloon" sources=" RF bogus  ACOUSTIC " state="LOST" trend="sideways" "#
                + #"uasIdType="mystery" uasStatus="Ground" sensorAnchored="TRUE"/>"#))
        #expect(d.kind == .unspecified)
        #expect(d.sources == bit(.rf) | bit(.acoustic))
        #expect(d.state == .lost)
        #expect(d.trend == .unspecified)
        #expect(d.uasIDType == .unspecified)
        #expect(d.uasStatus == .ground)
        #expect(d.sensorAnchored)
        #expect(try detection(#"<__detection sensorAnchored="yes"/>"#)?.sensorAnchored == false)
    }

    @Test("values are trimmed of Unicode spaces, as Kotlin's trim() does, but sources split on ASCII ones")
    func valuesAreTrimmedLikeKotlin() throws {
        let d = try #require(try detection("<__detection kind=\"\u{A0}uas\u{2003}\" cep=\"\u{A0}12\"/>"))
        #expect(d.kind == .uas)
        #expect(d.cepM == 12)
        #expect(try detection("<__detection sources=\"rf\u{A0}acoustic\"/>")?.sources == 0)
    }

    @Test("numbers are rounded half up and clamped")
    func numbersAreRoundedHalfUpAndClamped() throws {
        #expect(try detection(#"<__detection confidence="250"/>"#)?.confidence == 100)
        #expect(try detection(#"<__detection confidence="-5"/>"#)?.confidence == 0)
        #expect(try detection(#"<__detection confidence="87.5"/>"#)?.confidence == 88)
        #expect(try detection(#"<__detection count="-3"/>"#)?.count == 0)
        #expect(try detection(#"<__detection cep="-1"/>"#)?.cepM == 0)
        #expect(try detection(#"<__detection cep=" 12 "/>"#)?.cepM == 12)
        #expect(try detection(#"<__detection rssi="-70.5"/>"#)?.rssiDbm == -70)
        #expect(try detection(#"<__detection rssi="-70.6"/>"#)?.rssiDbm == -71)
        #expect(try detection(#"<__detection count="1.5e3"/>"#)?.count == 1500)
        // Not plain decimals, or too large to be finite: read as absent.
        #expect(
            try detection(#"<__detection confidence="NaN" count="Infinity" cep="0x10" rssi="1e400"/>"#)
                == TAKDetection()
        )
        #expect(try detection(#"<__detection count="2f"/>"#)?.count == 0)

        // The builder clamps a value that arrived out of range on the wire.
        let outOfRange = TAKDetection.with {
            $0.confidence = 150
            $0.count = UInt32(bitPattern: -2)
            $0.cepM = UInt32(bitPattern: -4)
        }
        let xml = builder.build(packet(outOfRange))
        #expect(xml.contains(#"<__detection confidence="100"/>"#), "\(xml)")
        #expect(xml.contains(#"ce="9999999""#), "\(xml)")
    }

    @Test("the UAS ID is cut to 20 bytes and kept only for a UAS")
    func theUasIdIsCutTo20AndKeptOnlyForAUas() throws {
        let long = "ZZZZFTEST000000000001EXTRA"
        #expect(long.utf8.count == 26)
        #expect(try detection(#"<__detection kind="uas" uasId="\#(long)"/>"#)?.uasID == Data(long.utf8.prefix(20)))

        // Text beyond ASCII counts in UTF-8 bytes.
        let accented = String(repeating: "A", count: 19) + "\u{E9}"
        #expect(try detection(#"<__detection kind="uas" uasId="\#(accented)"/>"#)?.uasID == Data(accented.utf8.prefix(20)))

        for kind in ["uas_operator", "uas_home", "person", "vehicle", "other", "unspecified"] {
            #expect(try detection(#"<__detection kind="\#(kind)" uasId="\#(uasId)"/>"#)?.uasID == Data(), "\(kind)")
        }
        #expect(try detection(#"<__detection uasId="\#(uasId)"/>"#)?.uasID == Data())

        // The builder holds a packet built in code to the same rule.
        let person = builder.build(packet(.with { $0.kind = .person; $0.uasID = Data(uasId.utf8) }))
        #expect(!person.contains(uasId), "\(person)")
        let cut = builder.build(packet(.with { $0.kind = .uas; $0.uasID = Data(long.utf8) }))
        #expect(cut.contains(#"uasId="\#(long.prefix(20))""#), "\(cut)")
    }

    @Test("a binary UAS ID travels as hex")
    func aBinaryUasIdTravelsAsHex() throws {
        // A UTM-assigned UUID is 16 raw bytes, most of them outside the text set.
        let uuid = Data([0x12, 0x3E, 0x45, 0x67, 0xE8, 0x9B, 0x42, 0xD3, 0xA4, 0x56, 0x42, 0x66, 0x14, 0x17, 0x40, 0x00])
        let d = TAKDetection.with { $0.kind = .uas; $0.uasID = uuid; $0.uasIDType = .utmAssigned }
        let xml = builder.build(packet(d))
        #expect(xml.contains(#"uasId="hex:123e4567e89b42d3a456426614174000""#), "\(xml)")
        #expect(try detection(fromXml: xml) == d)
        #expect(try overTheWire(packet(d)).detection == d)

        // Hex reads in any case; a value that is not whole hex digits is dropped.
        #expect(try detection(#"<__detection kind="uas" uasId="HEX:123E4567E89B42D3A456426614174000"/>"#)?.uasID == uuid)
        for bad in ["hex:123", "hex:12zz", "hex:+1", "hex:"] {
            #expect(try detection(#"<__detection kind="uas" uasId="\#(bad)"/>"#)?.uasID == Data(), "\(bad)")
        }
        // Hex longer than 20 bytes is cut like text.
        let longHex = String(repeating: "11", count: 25)
        #expect(try detection(#"<__detection kind="uas" uasId="hex:\#(longHex)"/>"#)?.uasID == Data(repeating: 0x11, count: 20))

        // One byte outside letters, digits, '-', '.' and '_' sends the whole ID as hex.
        let spaced = builder.build(packet(.with { $0.kind = .uas; $0.uasID = Data("ZZZZ8 TEST".utf8) }))
        #expect(spaced.contains(#"uasId="hex:5a5a5a5a382054455354""#), "\(spaced)")
        let quoted = Data("ZZZZ8\"&<".utf8)
        let quotedXml = builder.build(packet(.with { $0.kind = .uas; $0.uasID = quoted }))
        #expect(quotedXml.contains(#"uasId="hex:5a5a5a5a3822263c""#), "\(quotedXml)")
        #expect(try detection(fromXml: quotedXml)?.uasID == quoted)
    }

    @Test("cep falls back to the point's ce")
    func cepFallsBackToThePointCe() throws {
        #expect(try detection("<__detection/>", ce: "15.0")?.cepM == 15)
        #expect(try detection(#"<__detection cep="12"/>"#, ce: "15.0")?.cepM == 12)
        #expect(try detection(#"<__detection cep="unknown"/>"#, ce: "15.0")?.cepM == 15)
        #expect(try detection("<__detection/>", ce: "9999999")?.cepM == 0)
        #expect(try detection("<__detection/>", ce: "9999999.0")?.cepM == 0)
        #expect(try detection("<__detection/>", ce: "-3")?.cepM == 0)
    }

    @Test("the annotation leaves the payload variant alone")
    func theAnnotationLeavesThePayloadVariantAlone() throws {
        let parentLink = #"<link uid="ANDROID-0000000000000012" type="a-f-G-E-S" parent_callsign="SENSOR-1" relation="p-p"/>"#
        let annotated = try parser.parse(event(parentLink + fullElement))
        guard case .marker(let marker) = annotated.payloadVariant else {
            Issue.record("expected a marker, got \(String(describing: annotated.payloadVariant))")
            return
        }
        #expect(marker.parentCallsign == "SENSOR-1")
        #expect(try parser.parse(event(parentLink)).payloadVariant == annotated.payloadVariant)
        // On a-f-G-U-C it stays a plain position report, with no payload variant.
        #expect(try parser.parse(event(fullElement, type: "a-f-G-U-C")).payloadVariant == nil)
    }

    @Test("source bits without a token survive the wire")
    func sourceBitsWithoutATokenSurviveTheWire() throws {
        let d = TAKDetection.with {
            $0.kind = .uas
            $0.sources = bit(.radar) | (1 << 9)
        }
        #expect(try overTheWire(packet(d)).detection == d)
        // CoT XML can only name the bits it knows.
        let xml = builder.build(packet(d))
        #expect(xml.contains(#"<__detection kind="uas" sources="radar"/>"#), "\(xml)")
    }

    // MARK: Fixtures

    /// The detection each shared fixture carries.
    static let fixtures: [(String, TAKDetection)] = [
        ("detection_uas_remoteid", .with {
            $0.kind = .uas
            $0.sources = 3
            $0.confidence = 87
            $0.state = .active
            $0.trend = .closing
            $0.cepM = 12
            $0.rssiDbm = -71
            $0.uasID = Data(uasId.utf8)
            $0.uasIDType = .serialNumber
            $0.uasStatus = .airborne
        }),
        ("detection_uas_sensor_anchored", .with {
            $0.kind = .uas
            $0.sources = 2
            $0.confidence = 60
            $0.state = .new
            $0.sensorAnchored = true
            $0.rssiDbm = -84
        }),
        ("detection_uas_operator", .with {
            $0.kind = .uasOperator
            $0.sources = 1
            $0.confidence = 87
            $0.state = .active
            $0.cepM = 15  // from <point ce="15.0">
        }),
        ("detection_ground_vehicle", .with {
            $0.kind = .vehicle
            $0.sources = 68
            $0.confidence = 72
            $0.state = .active
            $0.trend = .opening
            $0.count = 2
            $0.cepM = 25
        }),
    ]

    @Test("each detection fixture parses as expected and equals Kotlin's goldens", arguments: fixtures)
    func fixtureMatchesKotlin(_ name: String, _ expected: TAKDetection) throws {
        let parsed = try parser.parse(try TestFixtures.loadFixture(name))
        #expect(parsed.hasDetection)
        #expect(parsed.detection == expected)

        // Byte for byte: Kotlin writes tag 47 itself, after every other field, which is where
        // SwiftProtobuf's field-number order puts it.
        let pb = try #require(TestFixtures.loadProtobuf(name), "missing \(name).pb")
        #expect(try parsed.serializedData() == pb)

        let bin = try #require(TestFixtures.loadGolden(name), "missing \(name).bin")
        let fromBin = try compressor.decompress(bin)
        #expect(fromBin.hasDetection)
        #expect(fromBin.detection == parsed.detection)
    }

    @Test("detection fixtures keep their payload and sensor cone")
    func fixturesKeepTheirPayloadAndSensorCone() throws {
        let remote = try parser.parse(try TestFixtures.loadFixture("detection_uas_remoteid"))
        guard case .marker(let marker) = remote.payloadVariant else {
            Issue.record("expected a marker, got \(String(describing: remote.payloadVariant))")
            return
        }
        #expect(marker.parentUid == "ANDROID-0000000000000012")

        let anchored = try parser.parse(try TestFixtures.loadFixture("detection_uas_sensor_anchored"))
        #expect(anchored.hasSensorFov)
        #expect(anchored.sensorFov.azimuthDeg == 0)
        #expect(anchored.sensorFov.fovHorizontalDeg == 360)
        #expect(anchored.sensorFov.hasRangeM && anchored.sensorFov.rangeM == 2000)
        #expect(anchored.sensorFov.type == .rf)
    }

    // MARK: Dictionary
    // selectDictId picks by CoT type, which would send an a-u-A-... UAS marker with the
    // 4 KB aircraft dictionary. The compressor sends a detection that is not an aircraft
    // track with the non-aircraft dictionary instead.

    private func wireDictId(_ packet: TAKPacketV2) throws -> Int {
        guard let flags = try compressor.compress(packet).first else { return -1 }
        return Int(flags)
    }

    @Test("a detection marker uses the non-aircraft dictionary whatever its type")
    func aDetectionMarkerUsesTheNonAircraftDictionary() throws {
        let uas = try parser.parse(try TestFixtures.loadFixture("detection_uas_remoteid"))
        let byType = DictionaryProvider.selectDictId(
            cotTypeId: uas.cotTypeID,
            cotTypeStr: uas.cotTypeStr.isEmpty ? nil : uas.cotTypeStr
        )
        #expect(byType == DictionaryProvider.DICT_ID_AIRCRAFT)
        #expect(try wireDictId(uas) == DictionaryProvider.DICT_ID_NON_AIRCRAFT)
    }

    @Test("the same marker without its detection keeps the aircraft dictionary")
    func theSameMarkerWithoutItsDetectionKeepsTheAircraftDictionary() throws {
        var uas = try parser.parse(try TestFixtures.loadFixture("detection_uas_remoteid"))
        uas.clearDetection()
        #expect(try wireDictId(uas) == DictionaryProvider.DICT_ID_AIRCRAFT)
    }

    @Test("an aircraft track keeps the aircraft dictionary with a detection")
    func anAircraftTrackKeepsTheAircraftDictionaryWithADetection() throws {
        let detection = try parser.parse(try TestFixtures.loadFixture("detection_uas_remoteid")).detection
        var track = try parser.parse(try TestFixtures.loadFixture("aircraft_adsb"))
        var isTrack = false
        if case .aircraft(_)? = track.payloadVariant { isTrack = true }
        #expect(isTrack)
        track.detection = detection
        #expect(try wireDictId(track) == DictionaryProvider.DICT_ID_AIRCRAFT)
    }
}
