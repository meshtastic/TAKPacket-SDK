package org.meshtastic.tak

/**
 * Stateless CoT-XML hygiene for LoRa-mesh transport.
 *
 * Centralized here so every consumer (Meshtastic-Android `takserver`,
 * Meshtastic-Apple `AccessoryManager`, …) shares ONE golden-tested
 * implementation instead of each maintaining its own regex list. Those lists
 * had drifted and silently broken features — most recently TAK-Talk `<voice>`
 * and `<marti>` were re-added to one side's strip set, so directed/voice
 * TAK-Talk stopped surfacing end-to-end.
 *
 * Pure string transforms — no platform, protobuf, or compression dependencies —
 * so this lives in `commonMain` and is available on every KMP target (JVM,
 * Android, iOS), which is what lets a multiplatform consumer call it from its
 * own `commonMain`.
 *
 * Regexes use `[\s\S]` rather than the DOTALL flag so behaviour is identical
 * across all five language bindings (and Kotlin/Native, which doesn't expose
 * `RegexOption.DOT_MATCHES_ALL`). The cross-binding fixtures under
 * `testdata/sanitizer/` lock byte-for-byte parity.
 */
public object CotMeshSanitizer {
    // Display-only / receiver-rederivable elements that add ~100–200 wire bytes.
    //
    // DELIBERATELY ABSENT: <voice> and <marti>. They are TAK-Talk essentials —
    // <voice/> marks a push-to-talk (voice) message and <marti><dest
    // callsign="…"/></marti> carries the directed-routing recipients. Stripping
    // either breaks TAK-Talk: the receiving ATAK plugin can neither play nor
    // route the m-t-t. The SDK carries both compactly (voice→bool,
    // marti→repeated string) and re-emits them on rebuild, omitting an empty
    // marti — so there is nothing to gain by stripping and a feature to lose.
    private val STRIP_ELEMENTS =
        listOf(
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
        ).map { Regex(it) }

    // Strip any attribute whose value is the literal placeholder "???".
    private val UNKNOWN_ATTR = Regex("\\s+\\w+\\s*=\\s*\"\\?{3}\"")

    // Display-only attributes the SDK doesn't carry. Empty callsign/phone only
    // (a populated callsign — e.g. <contact>, <dest> — is preserved).
    private val STRIP_ATTRS =
        listOf(
            "\\s+routetype\\s*=\\s*\"[^\"]*\"",
            "\\s+order\\s*=\\s*\"[^\"]*\"",
            "\\s+color\\s*=\\s*\"[^\"]*\"",
            "\\s+access\\s*=\\s*\"[^\"]*\"",
            "\\s+callsign\\s*=\\s*\"\"",
            "\\s+phone\\s*=\\s*\"\"",
        ).map { Regex(it) }

    // Route-waypoint / shape-vertex <link> elements carry full 36-char UUIDs
    // (~40 wire bytes each) the receiver re-derives. Strip uid ONLY from <link>
    // elements that have a point= attribute, never from other elements.
    private val ROUTE_LINK = Regex("<link\\s[^>]*\\bpoint=\"[^\"]*\"[^>]*/>")
    private val LINK_UID = Regex("\\s+uid=\"[^\"]*\"")

    private val XML_DECL = Regex("<\\?xml[^>]*\\?>")
    private val INTER_TAG_WS = Regex(">\\s+<")

    /**
     * Strip display-only CoT `<detail>` content to fit the LoRa MTU, preserving
     * everything the receiver needs to render/route — including TAK-Talk
     * `<voice>` and `<marti>`. Safe to run on any CoT XML; a no-op when there is
     * nothing to strip.
     */
    public fun stripNonEssentialForMesh(xml: String): String {
        var result = xml
        for (re in STRIP_ELEMENTS) result = re.replace(result, "")
        result = UNKNOWN_ATTR.replace(result, "")
        for (re in STRIP_ATTRS) result = re.replace(result, "")
        result = ROUTE_LINK.replace(result) { LINK_UID.replace(it.value, "") }
        return result
    }

    /**
     * Normalize CoT XML for the TAK TCP stream: drop the `<?xml …?>` declaration
     * and collapse inter-tag whitespace (`>   <` → `><`). TAK clients read a
     * continuous stream of single-line events and choke on a pretty-printed,
     * multi-line document with a prologue. Whitespace inside text nodes is left
     * intact (only `>`-whitespace-`<` runs collapse).
     */
    public fun normalizeCotXml(xml: String): String {
        var result = XML_DECL.replace(xml, "")
        result = INTER_TAG_WS.replace(result, "><")
        return result.trim()
    }
}
