import Foundation
import Testing
@testable import MeshtasticTAK

// MARK: - The `start` timestamp
//
// Mirrors the Kotlin, Python, TypeScript and C# bindings. The shared fixtures
// cannot provide this: every one is stamped months in the past, so the parser
// correctly refuses to encode its `start` and the builder falls back to the decode
// instant, which makes the whole corpus pass whether this works or not. So the
// timestamps here are minted at run time rather than committed: a fixture written
// today would age out of the bound within the hour and start passing vacuously.

private let boundary: Int64 = StartEpoch.modulus * 109_000

// Default options deliberately: no fractional seconds, matching what CotXmlBuilder
// emits (its own formatter is unconfigured, and RebuildHygieneTests pins the shape as
// `2026-03-15T14:22:10Z`). With `.withFractionalSeconds` every equality below would
// compare `...:10.000Z` against `...:10Z` and fail.
private let isoFmt = ISO8601DateFormatter()

private func iso(_ epochSeconds: Int64) -> String {
    isoFmt.string(from: Date(timeIntervalSince1970: TimeInterval(epochSeconds)))
}

private func cot(start: String, stale: String) -> String {
    """
    <?xml version="1.0" encoding="UTF-8"?>\
    <event version="2.0" uid="ATOM-START-RT" type="a-f-G-U-C" how="m-g" \
    time="\(start)" start="\(start)" stale="\(stale)">\
    <point lat="38.8895" lon="-77.0353" hae="0.0" ce="9999999.0" le="9999999.0"/>\
    <detail><contact callsign="ALPHA-1"/></detail></event>
    """
}

private func attr(_ xml: String, _ name: String) -> String? {
    guard let r = xml.range(of: "\(name)=\"[^\"]+\"", options: .regularExpression) else { return nil }
    let piece = xml[r]
    guard let open = piece.firstIndex(of: "\""), let close = piece.lastIndex(of: "\""), open < close
    else { return nil }
    return String(piece[piece.index(after: open)..<close])
}

private func nowSec() -> Int64 { Int64(Date().timeIntervalSince1970.rounded(.down)) }

// MARK: Pure arithmetic

@Test func roundTripsAStartEqualToNow() {
    let now = boundary + 1234
    #expect(StartEpoch.reconstruct(Int64(StartEpoch.truncate(now)), now) == now)
}

@Test func roundTripsAStartAFewSecondsOld() {
    let now = boundary + 1234
    let start = now - 40
    #expect(StartEpoch.reconstruct(Int64(StartEpoch.truncate(start)), now) == start)
}

@Test func isExactAtABoundaryWithTheSenderAheadOfTheReceiver() {
    // start = B, receiver still at B - 1. The naive splice yields B - 16384.
    #expect(StartEpoch.reconstruct(Int64(StartEpoch.truncate(boundary)), boundary - 1) == boundary)
}

@Test func isExactAtABoundaryWithThePacketCrossingInFlight() {
    // start = B - 1, receiver already at B. The naive splice yields B + 16383.
    #expect(StartEpoch.reconstruct(Int64(StartEpoch.truncate(boundary - 1)), boundary) == boundary - 1)
}

@Test func rejectsAStartImplausiblyFarInTheFuture() {
    let now = boundary + 1234
    let future = now + StartEpoch.futureToleranceSeconds + 10
    #expect(StartEpoch.reconstruct(Int64(StartEpoch.truncate(future)), now) == nil)
}

@Test func rejectsAStartBeyondThePastLimit() {
    let now = boundary + 8000
    let tooOld = now - StartEpoch.pastLimitSeconds - 10
    #expect(StartEpoch.reconstruct(Int64(StartEpoch.truncate(tooOld)), now) == nil)
}

@Test func acceptsAStartJustInsideThePastLimit() {
    let now = boundary + 8000
    let old = now - StartEpoch.pastLimitSeconds
    #expect(StartEpoch.reconstruct(Int64(StartEpoch.truncate(old)), now) == old)
}

@Test func rejectsAValueOutsideTheFieldRange() {
    #expect(StartEpoch.reconstruct(-1, boundary) == nil)
    #expect(StartEpoch.reconstruct(StartEpoch.modulus, boundary) == nil)
}

@Test func truncatesToTheLow14Bits() {
    #expect(StartEpoch.truncate(boundary) == 0)
    #expect(StartEpoch.truncate(boundary + 1) == 1)
    #expect(StartEpoch.truncate(boundary - 1) == UInt32(StartEpoch.modulus - 1))
}

@Test func documentsTheBandWhereReconstructionIsWronglyAccepted() {
    // Not a bug report: the band cannot be closed from the receiver alone, and the
    // sender gate is what keeps it out of reach. Pinned so a bound change has to
    // confront it rather than silently widen it.
    let now = boundary + 8000
    let low = StartEpoch.modulus - StartEpoch.futureToleranceSeconds
    let high = StartEpoch.modulus + StartEpoch.pastLimitSeconds
    #expect(low == 12784)
    #expect(high == 19984)
    for age in [low, (low + high) / 2, high] {
        let trueStart = now - age
        let got = StartEpoch.reconstruct(Int64(StartEpoch.truncate(trueStart)), now)
        #expect(got == trueStart + StartEpoch.modulus, "age \(age) should be wrongly accepted")
    }
    // Just outside the band is rejected rather than wrong, on BOTH sides. The far edge
    // is the one a widened bound would walk into, so pinning only the near edge leaves
    // the comment above unenforced.
    #expect(StartEpoch.reconstruct(Int64(StartEpoch.truncate(now - (low - 1))), now) == nil)
    #expect(StartEpoch.reconstruct(Int64(StartEpoch.truncate(now - (high + 1))), now) == nil)
}

// MARK: Round trip

@Test func aRecentStartSurvivesTheRoundTrip() throws {
    // 90 s old: inside the bound, and far enough from now that a fallback to the
    // decode instant is unambiguous rather than a rounding tie.
    let start = nowSec() - 90
    let packet = try CotXmlParser().parse(cot(start: iso(start), stale: iso(start + 300)))
    #expect(packet.hasStartEpochLo14, "parser did not capture start")
    #expect(packet.startEpochLo14 == StartEpoch.truncate(start))

    // The rebuilt event must carry the ORIGINAL instant, not the decode instant.
    let rebuilt = CotXmlBuilder().build(packet)
    #expect(attr(rebuilt, "start") == iso(start))
    #expect(attr(rebuilt, "time") == iso(start))
}

@Test func aLo14OfZeroStaysPresentRatherThanAbsent() throws {
    // `start` mod 16384 is 0 for one second in every 16384, i.e. once every 4.55 h,
    // and that second is the only one where a genuine value collides with proto3's
    // default. Explicit presence is what separates them. Lose it and a real start
    // reads as absent once every 4.55 hours, silently, and no fixture can catch it.
    var zero = TAKPacketV2()
    zero.uid = "ATOM-LO14-ZERO"
    zero.cotTypeStr = "a-f-G-U-C"
    zero.startEpochLo14 = 0
    #expect(zero.hasStartEpochLo14)
    let rt = try TAKPacketV2(serializedBytes: try zero.serializedData())
    #expect(rt.hasStartEpochLo14, "a lo14 of 0 came back absent: presence was lost")
    #expect(rt.startEpochLo14 == 0)

    // The other direction, so this pins presence rather than just non-zeroness.
    var absent = TAKPacketV2()
    absent.uid = "ATOM-LO14-ABSENT"
    absent.cotTypeStr = "a-f-G-U-C"
    let rtAbsent = try TAKPacketV2(serializedBytes: try absent.serializedData())
    #expect(!rtAbsent.hasStartEpochLo14, "an absent start came back as 0")
}

@Test func anAbsentStartFallsBackToTheDecodeInstant() {
    var packet = TAKPacketV2()
    packet.uid = "ATOM-NO-START"
    packet.cotTypeStr = "a-f-G-U-C"
    #expect(!packet.hasStartEpochLo14)
    let before = nowSec()
    let emitted = attr(CotXmlBuilder().build(packet), "start")
    let parsed = emitted.flatMap { isoFmt.date(from: $0) }
    #expect(parsed != nil)
    let delta = Int64(parsed!.timeIntervalSince1970.rounded(.down)) - before
    #expect(delta >= 0 && delta <= 10)
}

@Test func aStartPastTheSenderGateIsNotEncoded() throws {
    // Well inside the 8192 s half-window, so this exercises the sender gate and not
    // the wrap. That gate is the only thing keeping a receiver out of the band above.
    let tooOld = nowSec() - StartEpoch.pastLimitSeconds - 60
    let packet = try CotXmlParser().parse(cot(start: iso(tooOld), stale: iso(tooOld + 300)))
    #expect(!packet.hasStartEpochLo14)
}

@Test func aPresentButOutOfRangeStartFallsBackRatherThanBeingUsed() {
    // The receiver-side counterpart to the sender gate: present, refused, and
    // travelling through the builder. 7200 s is inside the half-window, so this is the
    // bound rather than the wrap. Not dropped: uid, type and position are fine.
    let now = nowSec()
    var packet = TAKPacketV2()
    packet.uid = "ATOM-OUT-OF-RANGE"
    packet.cotTypeStr = "a-f-G-U-C"
    packet.startEpochLo14 = StartEpoch.truncate(now - 7200)
    let emitted = attr(CotXmlBuilder().build(packet), "start")
    let parsed = emitted.flatMap { isoFmt.date(from: $0) }
    #expect(parsed != nil, "the event must still be built")
    let delta = Int64(parsed!.timeIntervalSince1970.rounded(.down)) - now
    #expect(delta >= 0 && delta <= 10, "out-of-range start was used: \(emitted ?? "nil")")
}

@Test func theRebuiltTimestampsAreUtc() throws {
    let start = nowSec() - 90
    let rebuilt = CotXmlBuilder().build(
        try CotXmlParser().parse(cot(start: iso(start), stale: iso(start + 300))))
    for name in ["time", "start", "stale"] {
        #expect(attr(rebuilt, name)?.hasSuffix("Z") == true, "\(name) is not UTC")
    }
}

@Test func anOffsetBearingStartIsConvertedRatherThanReadPositionally() throws {
    // Same instant written with a +04:00 offset. Both must encode the same bits,
    // which holds because we resolve the instant instead of reading fixed character
    // positions the way ATAK's fast path does.
    let start = nowSec() - 90
    let offsetFmt = ISO8601DateFormatter()
    offsetFmt.timeZone = TimeZone(secondsFromGMT: 4 * 3600)
    let asOffset = offsetFmt.string(from: Date(timeIntervalSince1970: TimeInterval(start)))

    let fromZ = try CotXmlParser().parse(cot(start: iso(start), stale: iso(start + 300)))
    let fromOffset = try CotXmlParser().parse(cot(start: asOffset, stale: iso(start + 300)))
    #expect(fromOffset.hasStartEpochLo14, "offset form was not encoded: \(asOffset)")
    #expect(fromOffset.startEpochLo14 == fromZ.startEpochLo14)
}

@Test func aStartWithNoZoneDesignatorIsNotGuessedAt() throws {
    // ATAK's fixed-position parser would read this as GMT. We refuse instead, because
    // a producer that omits the designator has told us nothing trustworthy.
    let start = nowSec() - 90
    let noZone = String(iso(start).dropLast())  // drop the trailing Z
    let packet = try CotXmlParser().parse(cot(start: noZone, stale: iso(start + 300)))
    #expect(!packet.hasStartEpochLo14)
}

@Test func staleIsMeasuredFromArrivalRatherThanTheRecoveredStart() throws {
    // An honest `start` must not shorten an event's life. Ten minutes in transit
    // against a 60 s window would otherwise arrive already expired.
    let start = nowSec() - 600
    let rebuilt = CotXmlBuilder().build(
        try CotXmlParser().parse(cot(start: iso(start), stale: iso(start + 60))))
    let stale = attr(rebuilt, "stale").flatMap { isoFmt.date(from: $0) }
    #expect(stale != nil)
    #expect(stale! > Date(), "stale is already in the past: \(stale!)")
}
