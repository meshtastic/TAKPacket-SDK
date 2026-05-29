using System.Text.RegularExpressions;

namespace Meshtastic.TAK;

/// <summary>
/// Stateless CoT-XML hygiene for LoRa-mesh transport.
/// </summary>
/// <remarks>
/// <para>Centralized here so every consumer (Meshtastic-Android <c>takserver</c>,
/// Meshtastic-Apple <c>AccessoryManager</c>, …) shares ONE golden-tested
/// implementation instead of each maintaining its own regex list. Those lists
/// had drifted and silently broken features — most recently TAK-Talk <c>&lt;voice&gt;</c>
/// and <c>&lt;marti&gt;</c> were re-added to one side's strip set, so directed/voice
/// TAK-Talk stopped surfacing end-to-end.</para>
///
/// <para>Pure string transforms — no platform, protobuf, or compression dependencies.</para>
///
/// <para>Regexes use <c>[\s\S]</c> rather than the Singleline/DOTALL flag so behaviour is
/// identical across all five language bindings. The cross-binding fixtures under
/// <c>testdata/sanitizer/</c> lock byte-for-byte parity.</para>
/// </remarks>
public static class CotMeshSanitizer
{
    // Display-only / receiver-rederivable elements that add ~100–200 wire bytes.
    //
    // DELIBERATELY ABSENT: <voice> and <marti>. They are TAK-Talk essentials —
    // <voice/> marks a push-to-talk (voice) message and <marti><dest
    // callsign="…"/></marti> carries the directed-routing recipients. Stripping
    // either breaks TAK-Talk: the receiving ATAK plugin can neither play nor
    // route the m-t-t. The SDK carries both compactly (voice→bool,
    // marti→repeated string) and re-emits them on rebuild, omitting an empty
    // marti — so there is nothing to gain by stripping and a feature to lose.
    private static readonly Regex[] StripElements =
    {
        new("<takv[^>]*/>"),
        new("<takv[^>]*>[\\s\\S]*?</takv>"),
        new("<__geofence[^>]*/>"),
        new("<__geofence[^>]*>[\\s\\S]*?</__geofence>"),
        new("<tog[^>]*/>"),
        new("<archive[^>]*/>"),
        new("<__shapeExtras[^>]*/>"),
        new("<__shapeExtras[^>]*>[\\s\\S]*?</__shapeExtras>"),
        new("<creator[^>]*/>"),
        new("<creator[^>]*>[\\s\\S]*?</creator>"),
        new("<remarks[^>]*/>"),
        new("<remarks[^>]*></remarks>"),
        new("<strokeStyle[^>]*/>"),
        new("<precisionlocation[^>]*/>"),
        new("<precisionlocation[^>]*>[\\s\\S]*?</precisionlocation>"),
        new("<precisionLocation[^>]*/>"),
        new("<precisionLocation[^>]*>[\\s\\S]*?</precisionLocation>"),
    };

    // Strip any attribute whose value is the literal placeholder "???".
    private static readonly Regex UnknownAttr = new("\\s+\\w+\\s*=\\s*\"\\?{3}\"");

    // Display-only attributes the SDK doesn't carry. Empty callsign/phone only
    // (a populated callsign — e.g. <contact>, <dest> — is preserved).
    private static readonly Regex[] StripAttrs =
    {
        new("\\s+routetype\\s*=\\s*\"[^\"]*\""),
        new("\\s+order\\s*=\\s*\"[^\"]*\""),
        new("\\s+color\\s*=\\s*\"[^\"]*\""),
        new("\\s+access\\s*=\\s*\"[^\"]*\""),
        new("\\s+callsign\\s*=\\s*\"\""),
        new("\\s+phone\\s*=\\s*\"\""),
    };

    // Route-waypoint / shape-vertex <link> elements carry full 36-char UUIDs
    // (~40 wire bytes each) the receiver re-derives. Strip uid ONLY from <link>
    // elements that have a point= attribute, never from other elements.
    private static readonly Regex RouteLink = new("<link\\s[^>]*\\bpoint=\"[^\"]*\"[^>]*/>");
    private static readonly Regex LinkUid = new("\\s+uid=\"[^\"]*\"");

    private static readonly Regex XmlDecl = new("<\\?xml[^>]*\\?>");
    private static readonly Regex InterTagWs = new(">\\s+<");

    /// <summary>
    /// Strip display-only CoT <c>&lt;detail&gt;</c> content to fit the LoRa MTU, preserving
    /// everything the receiver needs to render/route — including TAK-Talk
    /// <c>&lt;voice&gt;</c> and <c>&lt;marti&gt;</c>. Safe to run on any CoT XML; a no-op when there is
    /// nothing to strip.
    /// </summary>
    public static string StripNonEssentialForMesh(string xml)
    {
        var result = xml;
        foreach (var re in StripElements) result = re.Replace(result, "");
        result = UnknownAttr.Replace(result, "");
        foreach (var re in StripAttrs) result = re.Replace(result, "");
        result = RouteLink.Replace(result, m => LinkUid.Replace(m.Value, ""));
        return result;
    }

    /// <summary>
    /// Normalize CoT XML for the TAK TCP stream: drop the <c>&lt;?xml …?&gt;</c> declaration
    /// and collapse inter-tag whitespace (<c>&gt;   &lt;</c> → <c>&gt;&lt;</c>). TAK clients read a
    /// continuous stream of single-line events and choke on a pretty-printed,
    /// multi-line document with a prologue. Whitespace inside text nodes is left
    /// intact (only <c>&gt;</c>-whitespace-<c>&lt;</c> runs collapse).
    /// </summary>
    public static string NormalizeCotXml(string xml)
    {
        var result = XmlDecl.Replace(xml, "");
        result = InterTagWs.Replace(result, "><");
        return result.Trim();
    }
}
