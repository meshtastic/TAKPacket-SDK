import Foundation
import Testing
@testable import MeshtasticTAK

// MARK: - stale_seconds like Kotlin
//
// `stale_seconds` is computed as Kotlin's parser computes it,
// `stale.epochSeconds - time.epochSeconds`: each timestamp is rounded down to whole
// epoch seconds before subtracting. Every expected value below is what the Kotlin
// parser produces for the same XML, as Kotlin's own StaleSecondsTest checks.
// Truncating or rounding the difference instead disagrees with Kotlin whenever the
// two timestamps carry different fractions of a second.

private func event(time: String, stale: String) -> String {
    #"<event version="2.0" uid="STALE-1" type="a-f-G-U-C" how="m-g" "#
        + #"time="\#(time)" start="\#(time)" stale="\#(stale)">"#
        + #"<point lat="38.8895" lon="-77.0353" hae="0" ce="9999999" le="9999999"/>"#
        + "<detail></detail></event>"
}

private let staleCases: [(time: String, stale: String, staleSeconds: UInt32)] = [
    // 1.2 s and 0.5 s apart but 2 and 1 whole seconds: truncating the difference
    // gives 1 and 0.
    ("2026-01-01T00:00:00.900Z", "2026-01-01T00:00:02.100Z", 2),
    ("2026-01-01T00:00:00.500Z", "2026-01-01T00:00:01.000Z", 1),
    // 1.8 s and 0.999 s apart: rounding the difference gives 2 and 1.
    ("2026-01-01T00:00:00.100Z", "2026-01-01T00:00:01.900Z", 1),
    ("2026-01-01T00:00:00.000Z", "2026-01-01T00:00:00.999Z", 0),
    ("2026-01-01T00:00:00Z", "2026-01-01T00:10:00Z", 600),
    ("2026-01-01T00:00:02.100Z", "2026-01-01T00:00:00.900Z", 0),  // stale before time
    ("2026-01-01T00:00:00.900Z", "2026-01-01T00:00:00.900Z", 0),
    // Rounded down, not toward zero: -2 and -1 whole seconds.
    ("1969-12-31T23:59:58.500Z", "1969-12-31T23:59:59.400Z", 1),
    ("2026-01-01T01:00:00.900+01:00", "2026-01-01T00:00:02.100Z", 2),
    // Past 68 years every binding caps at Int32.max, because the Kotlin binding reads a
    // larger value back as a negative Int. UInt32(_:) trapped on the 174 years here.
    ("2026-01-01T00:00:00Z", "2100-01-01T00:00:00Z", UInt32(Int32.max)),
    ("2026-01-01T00:00:00Z", "2200-01-01T00:00:00Z", UInt32(Int32.max)),
]

@Suite("Stale seconds")
struct StaleSecondsSuite {

    let parser = CotXmlParser()

    @Test("stale seconds subtract whole epoch seconds like Kotlin", arguments: staleCases)
    func staleSeconds(_ c: (time: String, stale: String, staleSeconds: UInt32)) throws {
        let packet = try parser.parse(event(time: c.time, stale: c.stale))
        #expect(packet.staleSeconds == c.staleSeconds)
    }
}
