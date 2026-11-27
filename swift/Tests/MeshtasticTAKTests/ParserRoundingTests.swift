import Foundation
import Testing
@testable import MeshtasticTAK

// MARK: - Rounding scaled values like Kotlin
//
// The parser rounds every scaled value as Kotlin's `roundToInt()` does (Java's
// `Math.round`: a half goes toward positive infinity), so each field gets the same
// integer in both bindings. Every expected value below is what the Kotlin parser
// produces for the same XML. Most inputs land exactly on a half once scaled (0.025 m/s
// is 2.5 cm/s), where `rounded()` takes a negative half away from zero and an
// `Int32(_:)` conversion truncates; the rest are ordinary fractions, which a
// truncating conversion gets wrong too.

// Washington Monument, the repo's public-landmark placeholder position.
private let monumentPoint = #"lat="38.8895" lon="-77.0353" hae="0""#

private func event(_ type: String, _ point: String, _ detail: String) -> String {
    #"<event version="2.0" uid="ROUNDING-1" type="\#(type)" how="m-g" "#
        + #"time="2026-01-01T00:00:00Z" start="2026-01-01T00:00:00Z" stale="2026-01-01T00:10:00Z">"#
        + #"<point \#(point) ce="9999999" le="9999999"/><detail>\#(detail)</detail></event>"#
}

private let trackCases: [(speed: String, course: String, speedCmS: UInt32, courseCdeg: UInt32)] = [
    ("0.025", "0.125", 3, 13),    // 2.5 and 12.5
    ("0.005", "0.375", 1, 38),    // 0.5 and 37.5
    ("-0.025", "-0.125", 0, 0),   // -2.5 and -12.5 round to -2 and -12, then clamp to 0
]

private let pointCases: [(lat: String, lon: String, hae: String, latI: Int32, lonI: Int32, altitude: Int32)] = [
    // 388895000.5 and -770353001.5 after scaling: rounded() gives ...001 and ...002.
    ("38.88950005", "-77.03530015", "2.5", 388_895_001, -770_353_001, 3),
    // 388895002.5 and -770353003.5: rounded() gives ...003 and ...004.
    ("38.88950025", "-77.03530035", "-3.5", 388_895_003, -770_353_003, -3),
    ("38.8895", "-77.0353", "0.5", 388_895_000, -770_353_000, 1),
    ("38.8895", "-77.0353", "-0.5", 388_895_000, -770_353_000, 0),
    ("38.8895", "-77.0353", "-2.5", 388_895_000, -770_353_000, -2),
    // Not ties: a truncating conversion gives -29 and 73.
    ("38.8895", "-77.0353", "-29.667", 388_895_000, -770_353_000, -30),
    ("38.8895", "-77.0353", "73.963", 388_895_000, -770_353_000, 74),
]

private let outOfRangeHaeCases: [(hae: String, altitude: Int32)] = [
    ("1e10", .max),       // Kotlin clamps to the Int32 range; Int32(_:) traps
    ("-1e10", .min),
    ("Infinity", .max),
    ("NaN", 0),           // Int32(_:) traps; Java's Math.round gives 0
]

private let rssiCases: [(rssi: String, rssiX10: Int32)] = [
    ("0.25", 3),        // 2.5: a truncating conversion gives 2
    ("-0.25", -2),      // -2.5 goes toward positive infinity
    ("-19.35", -193),   // -193.5: rounded() gives -194
    ("-19.47", -195),   // -194.7: a truncating conversion gives -194
]

private let shapeCases: [(major: String, minor: String, strokeWeight: String,
                          majorCm: UInt32, minorCm: UInt32, strokeWeightX10: UInt32)] = [
    // 12.5 cm, 0.29 m (just short of 29 cm as a double) and 2.5: truncation gives 12, 28 and 2.
    ("0.125", "0.29", "0.25", 13, 29, 3),
    ("0.375", "0.125", "0.35", 38, 13, 4),
]

private let bullseyeCases: [(distance: String, distanceDm: UInt32)] = [
    ("0.25", 3),  // 2.5 dm: truncation gives 2
    ("0.35", 4),  // 3.5 dm: truncation gives 3
]

private let rangeAndBearingCases: [(range: String, bearing: String, rangeCm: UInt32, bearingCdeg: UInt32)] = [
    ("0.29", "0.125", 29, 13),   // truncation gives 28 and 12
    ("0.125", "0.375", 13, 38),  // truncation gives 12 and 37
]

@Suite("Parser rounding")
struct ParserRoundingSuite {

    let parser = CotXmlParser()

    @Test("track speed and course round a half up like Kotlin", arguments: trackCases)
    func trackSpeedAndCourse(
        _ c: (speed: String, course: String, speedCmS: UInt32, courseCdeg: UInt32)
    ) throws {
        let packet = try parser.parse(
            event("a-f-G-U-C", monumentPoint, #"<track speed="\#(c.speed)" course="\#(c.course)"/>"#))
        #expect(packet.speed == c.speedCmS)
        #expect(packet.course == c.courseCdeg)
    }

    @Test("point lat, lon and hae round a half up like Kotlin", arguments: pointCases)
    func pointLatLonAndHae(
        _ c: (lat: String, lon: String, hae: String, latI: Int32, lonI: Int32, altitude: Int32)
    ) throws {
        let packet = try parser.parse(
            event("a-f-G-U-C", #"lat="\#(c.lat)" lon="\#(c.lon)" hae="\#(c.hae)""#, ""))
        #expect(packet.latitudeI == c.latI)
        #expect(packet.longitudeI == c.lonI)
        #expect(packet.altitude == c.altitude)
    }

    @Test("the largest double below 0.5 rounds down like Kotlin")
    func largestDoubleBelowHalf() throws {
        // (d + 0.5).rounded(.down) gives 1 here, because the sum itself rounds up to 1.0.
        let justBelowHalf = (0.5).nextDown.description
        let packet = try parser.parse(
            event("a-f-G-U-C", #"lat="38.8895" lon="-77.0353" hae="\#(justBelowHalf)""#, ""))
        #expect(packet.altitude == 0)
    }

    @Test("an out-of-range or NaN hae clamps instead of trapping", arguments: outOfRangeHaeCases)
    func outOfRangeHae(_ c: (hae: String, altitude: Int32)) throws {
        let packet = try parser.parse(
            event("a-f-G-U-C", #"lat="38.8895" lon="-77.0353" hae="\#(c.hae)""#, ""))
        #expect(packet.altitude == c.altitude)
    }

    @Test("out-of-range speed and course clamp like Kotlin instead of trapping")
    func outOfRangeSpeedAndCourse() throws {
        // 1e12 cm/s does not fit in UInt32; Kotlin clamps to Int32 first.
        let packet = try parser.parse(
            event("a-f-G-U-C", monumentPoint, #"<track speed="1e10" course="1e10"/>"#))
        #expect(packet.speed == UInt32(Int32.max))
        #expect(packet.course == UInt32(Int32.max))
    }

    @Test("aircraft rssi rounds like Kotlin", arguments: rssiCases)
    func aircraftRssi(_ c: (rssi: String, rssiX10: Int32)) throws {
        let packet = try parser.parse(event(
            "a-n-A-C-F", #"lat="38.8895" lon="-77.0353" hae="3048""#,
            #"<_aircot_ icao="000001"/><_radio rssi="\#(c.rssi)" gps="true"/>"#))
        guard case .aircraft(let aircraft) = packet.payloadVariant else {
            Issue.record("expected an aircraft payload, got \(String(describing: packet.payloadVariant))")
            return
        }
        #expect(aircraft.rssiX10 == c.rssiX10)
    }

    @Test("shape radii and stroke weight round like Kotlin", arguments: shapeCases)
    func shapeRadiiAndStrokeWeight(
        _ c: (major: String, minor: String, strokeWeight: String,
              majorCm: UInt32, minorCm: UInt32, strokeWeightX10: UInt32)
    ) throws {
        let packet = try parser.parse(event(
            "u-d-c-c", monumentPoint,
            #"<shape><ellipse major="\#(c.major)" minor="\#(c.minor)" angle="360"/></shape>"#
                + #"<strokeColor value="-1"/><strokeWeight value="\#(c.strokeWeight)"/>"#
                + #"<labels_on value="false"/>"#))
        guard case .shape(let shape) = packet.payloadVariant else {
            Issue.record("expected a shape payload, got \(String(describing: packet.payloadVariant))")
            return
        }
        #expect(shape.majorCm == c.majorCm)
        #expect(shape.minorCm == c.minorCm)
        #expect(shape.strokeWeightX10 == c.strokeWeightX10)
    }

    @Test("bullseye distance rounds like Kotlin", arguments: bullseyeCases)
    func bullseyeDistance(_ c: (distance: String, distanceDm: UInt32)) throws {
        let packet = try parser.parse(event(
            "u-r-b-bullseye", monumentPoint,
            #"<shape><ellipse major="500.0" minor="500.0" angle="360"/></shape>"#
                + #"<bullseye distance="\#(c.distance)" bearingRef="M"/><strokeColor value="-65536"/>"#))
        guard case .shape(let shape) = packet.payloadVariant else {
            Issue.record("expected a shape payload, got \(String(describing: packet.payloadVariant))")
            return
        }
        #expect(shape.bullseyeDistanceDm == c.distanceDm)
    }

    @Test("range and bearing round like Kotlin", arguments: rangeAndBearingCases)
    func rangeAndBearing(
        _ c: (range: String, bearing: String, rangeCm: UInt32, bearingCdeg: UInt32)
    ) throws {
        let packet = try parser.parse(event(
            "u-rb-a", monumentPoint,
            #"<link uid="anchor-1" relation="p-p" type="b-m-p-w" point="38.8895,-77.0353"/>"#
                + #"<range value="\#(c.range)"/><bearing value="\#(c.bearing)"/>"#))
        guard case .rab(let rab) = packet.payloadVariant else {
            Issue.record("expected a range-and-bearing payload, got \(String(describing: packet.payloadVariant))")
            return
        }
        #expect(rab.rangeCm == c.rangeCm)
        #expect(rab.bearingCdeg == c.bearingCdeg)
    }

    /// Kotlin wrote `testdata/protobuf/*.pb` from the same fixtures, so every value this
    /// parser rounds must equal the golden's. Whole packets are not compared: a few
    /// fixtures differ for reasons that have nothing to do with rounding.
    @Test("rounded values match the Kotlin golden", arguments: TestFixtures.fixtureNames)
    func roundedValuesMatchKotlinGolden(_ name: String) throws {
        let golden = try #require(TestFixtures.loadProtobuf(name), "\(name): no .pb golden")
        let kotlin = try TAKPacketV2(serializedBytes: golden)
        let swift = try parser.parse(try TestFixtures.loadFixture(name))
        #expect(roundedValues(swift) == roundedValues(kotlin), "\(name)")
    }
}

/// Every field the parser fills by rounding a scaled double.
private func roundedValues(_ p: TAKPacketV2) -> [String: String] {
    func point(_ g: CotGeoPoint) -> String { "\(g.latDeltaI)/\(g.lonDeltaI)" }
    func deltas(_ d: [Int32]) -> String { d.map { "\($0)" }.joined(separator: ",") }

    var v: [String: String] = [
        "latitude_i": "\(p.latitudeI)",
        "longitude_i": "\(p.longitudeI)",
        "altitude": "\(p.altitude)",
        "speed": "\(p.speed)",
        "course": "\(p.course)",
    ]
    switch p.payloadVariant {
    case .shape(let s):
        v["shape.major_cm"] = "\(s.majorCm)"
        v["shape.minor_cm"] = "\(s.minorCm)"
        v["shape.stroke_weight_x10"] = "\(s.strokeWeightX10)"
        v["shape.bullseye_distance_dm"] = "\(s.bullseyeDistanceDm)"
        v["shape.vertex_lat_deltas"] = deltas(s.vertexLatDeltas)
        v["shape.vertex_lon_deltas"] = deltas(s.vertexLonDeltas)
    case .rab(let r):
        v["rab.range_cm"] = "\(r.rangeCm)"
        v["rab.bearing_cdeg"] = "\(r.bearingCdeg)"
        v["rab.stroke_weight_x10"] = "\(r.strokeWeightX10)"
        v["rab.anchor"] = point(r.anchor)
    case .aircraft(let a):
        v["aircraft.rssi_x10"] = "\(a.rssiX10)"
    case .route(let route):
        v["route.points"] = route.links.map { point($0.point) }.joined(separator: ",")
    default:
        break
    }
    return v
}
