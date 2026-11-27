import Foundation

/// The `<__detection>` CoT detail element that carries ``TAKDetection``, shared by
/// ``CotXmlParser`` and ``CotXmlBuilder``. The contract is Kotlin's `DetectionXml`.
///
/// One self-closing element, with each attribute left out at its default:
///
/// ```
/// <__detection kind="uas" sources="remote_id rf" confidence="87" state="active" trend="closing"
///     count="2" sensorAnchored="true" cep="12" rssi="-71" uasId="..." uasIdType="serial_number"
///     uasStatus="airborne"/>
/// ```
///
/// An enum attribute carries the value's name in lowercase snake_case (`uasOperator` is
/// `uas_operator`), and `sources` is a space-separated list of them. Reading ignores case,
/// takes an unknown token as unspecified and skips one in `sources`. `confidence` is clamped
/// to 0-100, `count` and `cep` to zero or more, and `uasId` is cut to 20 code points and kept
/// only when the kind is `uas`.
///
/// `cep` is also the event's `<point ce>`: the builder writes it there, and the parser reads
/// `<point ce>` when the element has no `cep`. An event without the element keeps
/// `ce="9999999"`.
enum DetectionXml {
    static let element = "__detection"

    /// CoT's "unknown" circular error, written to `<point ce>` when there is no cep to carry.
    static let unknownCe: Int32 = 9_999_999

    private static let uasIdMaxScalars = 20
    private static let confidenceMax: Int32 = 100

    // Spelled out, because the generated case names are not the XML tokens (`remoteID`).
    private static let kinds: [(value: TAKDetection.Kind, token: String)] = [
        (.unspecified, "unspecified"), (.uas, "uas"), (.uasOperator, "uas_operator"),
        (.uasHome, "uas_home"), (.person, "person"), (.vehicle, "vehicle"), (.other, "other"),
    ]
    // In ascending bit order, the order `sources` is written in.
    private static let sources: [(value: TAKDetection.Source, token: String)] = [
        (.unspecified, "unspecified"), (.remoteID, "remote_id"), (.rf, "rf"), (.acoustic, "acoustic"),
        (.radar, "radar"), (.optical, "optical"), (.thermal, "thermal"), (.seismic, "seismic"),
        (.magnetic, "magnetic"),
    ]
    private static let states: [(value: TAKDetection.State, token: String)] = [
        (.unspecified, "unspecified"), (.new, "new"), (.active, "active"), (.coasting, "coasting"),
        (.lost, "lost"),
    ]
    private static let trends: [(value: TAKDetection.Trend, token: String)] = [
        (.unspecified, "unspecified"), (.closing, "closing"), (.steady, "steady"), (.opening, "opening"),
    ]
    private static let uasIdTypes: [(value: TAKDetection.UasIdType, token: String)] = [
        (.unspecified, "unspecified"), (.serialNumber, "serial_number"),
        (.caaRegistration, "caa_registration"), (.utmAssigned, "utm_assigned"),
        (.specificSession, "specific_session"),
    ]
    private static let uasStatuses: [(value: TAKDetection.UasStatus, token: String)] = [
        (.unspecified, "unspecified"), (.ground, "ground"), (.airborne, "airborne"),
        (.emergency, "emergency"), (.systemFailure, "system_failure"),
    ]

    /// What Kotlin's `trim()` removes: Unicode space separators (Zs, Zl, Zp) and the ASCII
    /// controls Java counts as whitespace. `.whitespacesAndNewlines` would also take U+0085.
    private static let whitespace = CharacterSet.whitespaces
        .union(CharacterSet(charactersIn: "\n\u{0B}\u{0C}\r\u{1C}\u{1D}\u{1E}\u{1F}\u{2028}\u{2029}"))

    /// What `sources` is split on: runs of these, as Kotlin's `\s+` (ASCII only).
    private static let separators = CharacterSet(charactersIn: " \t\n\u{0B}\u{0C}\r")

    /// The ``TAKDetection`` for one element's `attributes`. `pointCe` is the event's
    /// `<point ce>`, used when the element has no `cep`.
    static func parse(_ attributes: [String: String], pointCe: String?) -> TAKDetection {
        var d = TAKDetection()
        d.kind = lookup(attributes["kind"], in: kinds) ?? .unspecified
        d.sources = attributes["sources"].map(sourceBits) ?? 0
        d.confidence = UInt32(min(max(number(attributes["confidence"]) ?? 0, 0), confidenceMax))
        d.state = lookup(attributes["state"], in: states) ?? .unspecified
        d.trend = lookup(attributes["trend"], in: trends) ?? .unspecified
        d.count = UInt32(max(number(attributes["count"]) ?? 0, 0))
        d.sensorAnchored = attributes["sensorAnchored"].map { trim($0).lowercased() == "true" } ?? false
        let cep = number(attributes["cep"]) ?? number(pointCe).flatMap { $0 < unknownCe ? $0 : nil }
        d.cepM = UInt32(max(cep ?? 0, 0))
        d.rssiDbm = number(attributes["rssi"]) ?? 0
        if d.kind == .uas { d.uasID = uasIdPrefix(attributes["uasId"] ?? "") }
        d.uasIDType = lookup(attributes["uasIdType"], in: uasIdTypes) ?? .unspecified
        d.uasStatus = lookup(attributes["uasStatus"], in: uasStatuses) ?? .unspecified
        return d
    }

    /// The `ce` for the event's `<point>`: the detection's cep when it has one, else unknown.
    static func pointCe(_ packet: TAKPacketV2) -> Int32 {
        let cep = Int32(bitPattern: packet.detection.cepM)
        return packet.hasDetection && cep > 0 ? cep : unknownCe
    }

    /// The element as a `<detail>` child line, escaping `uasId` with `esc`. Unsigned fields
    /// are read as Int32, as Kotlin's model holds them, so an out-of-range wire value is
    /// clamped or left out.
    static func emit(_ detection: TAKDetection, esc: (String) -> String) -> String {
        var s = "    <\(element)"
        func attr(_ name: String, _ value: String) { s += " \(name)=\"\(value)\"" }

        if detection.kind != .unspecified, let t = token(detection.kind, in: kinds) { attr("kind", t) }
        // Bits without a token still travel on the wire; XML can only name the known ones.
        let named = sources.filter { detection.sources & UInt32($0.value.rawValue) != 0 }
        if !named.isEmpty { attr("sources", named.map(\.token).joined(separator: " ")) }
        let confidence = min(max(Int32(bitPattern: detection.confidence), 0), confidenceMax)
        if confidence > 0 { attr("confidence", "\(confidence)") }
        if detection.state != .unspecified, let t = token(detection.state, in: states) { attr("state", t) }
        if detection.trend != .unspecified, let t = token(detection.trend, in: trends) { attr("trend", t) }
        let count = Int32(bitPattern: detection.count)
        if count > 0 { attr("count", "\(count)") }
        if detection.sensorAnchored { attr("sensorAnchored", "true") }
        let cep = Int32(bitPattern: detection.cepM)
        if cep > 0 { attr("cep", "\(cep)") }
        if detection.rssiDbm != 0 { attr("rssi", "\(detection.rssiDbm)") }
        let uasId = detection.kind == .uas ? uasIdPrefix(detection.uasID) : ""
        if !uasId.isEmpty { attr("uasId", esc(uasId)) }
        if detection.uasIDType != .unspecified, let t = token(detection.uasIDType, in: uasIdTypes) {
            attr("uasIdType", t)
        }
        if detection.uasStatus != .unspecified, let t = token(detection.uasStatus, in: uasStatuses) {
            attr("uasStatus", t)
        }
        return s + "/>\n"
    }

    private static func trim(_ s: String) -> String { s.trimmingCharacters(in: whitespace) }

    private static func lookup<E>(_ raw: String?, in table: [(value: E, token: String)]) -> E? {
        guard let raw = raw else { return nil }
        let t = trim(raw).lowercased()
        return table.first { $0.token == t }?.value
    }

    private static func token<E: Equatable>(_ value: E, in table: [(value: E, token: String)]) -> String? {
        table.first { $0.value == value }?.token
    }

    /// The OR of the bits `raw` names. Tokens are not trimmed one by one, as in Kotlin.
    private static func sourceBits(_ raw: String) -> UInt32 {
        var bits: UInt32 = 0
        for scalars in trim(raw).unicodeScalars.split(whereSeparator: { separators.contains($0) }) {
            let t = String(String.UnicodeScalarView(scalars)).lowercased()
            if let source = sources.first(where: { $0.token == t })?.value { bits |= UInt32(source.rawValue) }
        }
        return bits
    }

    /// A plain decimal rounded the way the parser rounds (Java's `Math.round`), or nil.
    private static func number(_ raw: String?) -> Int32? {
        raw.flatMap { CotXmlParser.parseDouble(trim($0)) }.map(CotXmlParser.roundToInt)
    }

    /// The first 20 code points: a scalar outside the BMP counts once and is never split.
    private static func uasIdPrefix(_ s: String) -> String {
        String(String.UnicodeScalarView(s.unicodeScalars.prefix(uasIdMaxScalars)))
    }
}
