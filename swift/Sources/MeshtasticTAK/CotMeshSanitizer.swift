import Foundation

/// Stateless CoT-XML hygiene for LoRa-mesh transport.
///
/// Centralized here so every consumer (Meshtastic-Android `takserver`,
/// Meshtastic-Apple `AccessoryManager`, …) shares ONE golden-tested
/// implementation instead of each maintaining its own regex list. Those lists
/// had drifted and silently broken features — most recently TAK-Talk `<voice>`
/// and `<marti>` were re-added to one side's strip set, so directed/voice
/// TAK-Talk stopped surfacing end-to-end.
///
/// Pure string transforms — no platform, protobuf, or compression dependencies.
/// This is the Swift binding of the canonical Kotlin `CotMeshSanitizer`
/// (`kotlin/src/commonMain/kotlin/org/meshtastic/tak/CotMeshSanitizer.kt`).
///
/// Regexes use `[\s\S]` rather than the DOTALL / `.dotMatchesLineSeparators`
/// flag so behaviour is byte-for-byte identical across all five language
/// bindings. The cross-binding fixtures under `testdata/sanitizer/` lock that
/// parity.
public enum CotMeshSanitizer {

    // Display-only / receiver-rederivable elements that add ~100–200 wire bytes.
    //
    // DELIBERATELY ABSENT: <voice> and <marti>. They are TAK-Talk essentials —
    // <voice/> marks a push-to-talk (voice) message and <marti><dest
    // callsign="…"/></marti> carries the directed-routing recipients. Stripping
    // either breaks TAK-Talk: the receiving ATAK plugin can neither play nor
    // route the m-t-t. The SDK carries both compactly (voice→bool,
    // marti→repeated string) and re-emits them on rebuild, omitting an empty
    // marti — so there is nothing to gain by stripping and a feature to lose.
    private static let stripElements: [NSRegularExpression] = [
        "<takv[^>]*/>",
        "<takv[^>]*>[\\s\\S]*?</takv>",
        "<__geofence[^>]*/>",
        "<__geofence[^>]*>[\\s\\S]*?</__geofence>",
        "<tog[^>]*/>",
        "<archive[^>]*/>",
        "<__shapeExtras[^>]*/>",
        "<__shapeExtras[^>]*>[\\s\\S]*?</__shapeExtras>",
        "<creator[^>]*/>",
        "<creator[^>]*>[\\s\\S]*?</creator>",
        "<remarks[^>]*/>",
        "<remarks[^>]*></remarks>",
        "<strokeStyle[^>]*/>",
        "<precisionlocation[^>]*/>",
        "<precisionlocation[^>]*>[\\s\\S]*?</precisionlocation>",
        "<precisionLocation[^>]*/>",
        "<precisionLocation[^>]*>[\\s\\S]*?</precisionLocation>",
    ].map { try! NSRegularExpression(pattern: $0) }

    // Strip any attribute whose value is the literal placeholder "???".
    private static let unknownAttr = try! NSRegularExpression(pattern: "\\s+\\w+\\s*=\\s*\"\\?{3}\"")

    // Display-only attributes the SDK doesn't carry. Empty callsign/phone only
    // (a populated callsign — e.g. <contact>, <dest> — is preserved).
    private static let stripAttrs: [NSRegularExpression] = [
        "\\s+routetype\\s*=\\s*\"[^\"]*\"",
        "\\s+order\\s*=\\s*\"[^\"]*\"",
        "\\s+color\\s*=\\s*\"[^\"]*\"",
        "\\s+access\\s*=\\s*\"[^\"]*\"",
        "\\s+callsign\\s*=\\s*\"\"",
        "\\s+phone\\s*=\\s*\"\"",
    ].map { try! NSRegularExpression(pattern: $0) }

    // Route-waypoint / shape-vertex <link> elements carry full 36-char UUIDs
    // (~40 wire bytes each) the receiver re-derives. Strip uid ONLY from <link>
    // elements that have a point= attribute, never from other elements.
    private static let routeLink = try! NSRegularExpression(pattern: "<link\\s[^>]*\\bpoint=\"[^\"]*\"[^>]*/>")
    private static let linkUid = try! NSRegularExpression(pattern: "\\s+uid=\"[^\"]*\"")

    private static let xmlDecl = try! NSRegularExpression(pattern: "<\\?xml[^>]*\\?>")
    private static let interTagWs = try! NSRegularExpression(pattern: ">\\s+<")

    /// Strip display-only CoT `<detail>` content to fit the LoRa MTU, preserving
    /// everything the receiver needs to render/route — including TAK-Talk
    /// `<voice>` and `<marti>`. Safe to run on any CoT XML; a no-op when there is
    /// nothing to strip.
    public static func stripNonEssentialForMesh(_ xml: String) -> String {
        var result = xml
        for re in stripElements { result = replaceAll(re, in: result, with: "") }
        result = replaceAll(unknownAttr, in: result, with: "")
        for re in stripAttrs { result = replaceAll(re, in: result, with: "") }
        // For each <link …point=…/> match, strip its uid= attribute. Iterate the
        // matches in reverse so each replacement keeps the earlier ranges valid
        // (mirrors Kotlin's `ROUTE_LINK.replace(result) { LINK_UID.replace(it.value, "") }`).
        let matches = routeLink.matches(in: result, range: nsRange(of: result))
        for match in matches.reversed() {
            if let range = Range(match.range, in: result) {
                let linkStr = String(result[range])
                let stripped = replaceAll(linkUid, in: linkStr, with: "")
                result.replaceSubrange(range, with: stripped)
            }
        }
        return result
    }

    /// Normalize CoT XML for the TAK TCP stream: drop the `<?xml …?>` declaration
    /// and collapse inter-tag whitespace (`>   <` → `><`). TAK clients read a
    /// continuous stream of single-line events and choke on a pretty-printed,
    /// multi-line document with a prologue. Whitespace inside text nodes is left
    /// intact (only `>`-whitespace-`<` runs collapse).
    public static func normalizeCotXml(_ xml: String) -> String {
        var result = replaceAll(xmlDecl, in: xml, with: "")
        result = replaceAll(interTagWs, in: result, with: "><")
        return result.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    // MARK: - Helpers

    private static func nsRange(of string: String) -> NSRange {
        NSRange(string.startIndex..., in: string)
    }

    /// Replace every match of `regex` in `string` with the literal `replacement`.
    /// `NSRegularExpression.escapedTemplate` keeps the replacement literal so `$`
    /// / `\` in a replacement are not treated as capture-group references —
    /// matching Kotlin's `Regex.replace(input, literal)` semantics.
    private static func replaceAll(
        _ regex: NSRegularExpression,
        in string: String,
        with replacement: String
    ) -> String {
        regex.stringByReplacingMatches(
            in: string,
            range: nsRange(of: string),
            withTemplate: NSRegularExpression.escapedTemplate(for: replacement)
        )
    }
}
