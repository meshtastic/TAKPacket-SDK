using System.Globalization;
using System.Text;
using System.Xml.Linq;
using Meshtastic.Protobufs;

namespace Meshtastic.TAK;

/// <summary>
/// The <c>&lt;__detection&gt;</c> CoT detail element that carries <c>TAKPacketV2.detection</c>,
/// shared by <see cref="CotXmlParser"/> and <see cref="CotXmlBuilder"/>. Matches the Kotlin
/// binding's <c>DetectionXml</c>.
/// </summary>
/// <remarks>
/// <para>One self-closing element, a direct child of <c>&lt;detail&gt;</c>, with each attribute
/// left out at its default:</para>
/// <code>
/// &lt;__detection kind="uas" sources="remote_id rf" confidence="87" state="active" trend="closing"
///     count="2" sensorAnchored="true" cep="12" rssi="-71" uasId="..." uasIdType="serial_number"
///     uasStatus="airborne"/&gt;
/// </code>
/// <para>An enum attribute carries the value's name in lowercase snake_case (<c>UasOperator</c>
/// is <c>uas_operator</c>), and <c>sources</c> is a space-separated list of them. Reading ignores
/// case, takes an unknown token as unspecified and skips one in <c>sources</c>.
/// <c>confidence</c> is clamped to 0-100, <c>count</c> and <c>cep</c> to zero or more, and
/// <c>uasId</c> is cut to 20 code points and kept only when the kind is <c>Uas</c>.</para>
/// <para><c>cep</c> is also the event's <c>&lt;point ce&gt;</c>: the builder writes it there,
/// and the parser reads <c>&lt;point ce&gt;</c> when the element has no <c>cep</c>. An event
/// without the element keeps <c>ce="9999999"</c>.</para>
/// <para>None of these names is one the parser classifies an event by or
/// <see cref="CotMeshSanitizer"/> strips, so adding the annotation never changes an event's
/// payload variant. Keep it that way.</para>
/// </remarks>
internal static class DetectionXml
{
    /// <summary>The element's name.</summary>
    public const string Element = "__detection";

    /// <summary>CoT's "unknown" circular error, written to <c>&lt;point ce&gt;</c> when there is no cep to carry.</summary>
    public const int UnknownCe = 9_999_999;

    private const int UasIdMaxCodePoints = 20;
    private const int ConfidenceMax = 100;

    // What Kotlin splits sources on (Java's \s): ASCII whitespace only.
    private static readonly char[] SourceSeparators = { ' ', '\t', '\n', '\v', '\f', '\r' };

    // Spelled out rather than derived from the generated names, so a renamed enum value can't
    // quietly change the XML.
    private static readonly TokenTable<TAKDetection.Types.Kind> Kinds = new(
        (TAKDetection.Types.Kind.Unspecified, "unspecified"),
        (TAKDetection.Types.Kind.Uas, "uas"),
        (TAKDetection.Types.Kind.UasOperator, "uas_operator"),
        (TAKDetection.Types.Kind.UasHome, "uas_home"),
        (TAKDetection.Types.Kind.Person, "person"),
        (TAKDetection.Types.Kind.Vehicle, "vehicle"),
        (TAKDetection.Types.Kind.Other, "other"));

    private static readonly TokenTable<TAKDetection.Types.Source> Sources = new(
        (TAKDetection.Types.Source.Unspecified, "unspecified"),
        (TAKDetection.Types.Source.RemoteId, "remote_id"),
        (TAKDetection.Types.Source.Rf, "rf"),
        (TAKDetection.Types.Source.Acoustic, "acoustic"),
        (TAKDetection.Types.Source.Radar, "radar"),
        (TAKDetection.Types.Source.Optical, "optical"),
        (TAKDetection.Types.Source.Thermal, "thermal"),
        (TAKDetection.Types.Source.Seismic, "seismic"),
        (TAKDetection.Types.Source.Magnetic, "magnetic"));

    private static readonly TokenTable<TAKDetection.Types.State> States = new(
        (TAKDetection.Types.State.Unspecified, "unspecified"),
        (TAKDetection.Types.State.New, "new"),
        (TAKDetection.Types.State.Active, "active"),
        (TAKDetection.Types.State.Coasting, "coasting"),
        (TAKDetection.Types.State.Lost, "lost"));

    private static readonly TokenTable<TAKDetection.Types.Trend> Trends = new(
        (TAKDetection.Types.Trend.Unspecified, "unspecified"),
        (TAKDetection.Types.Trend.Closing, "closing"),
        (TAKDetection.Types.Trend.Steady, "steady"),
        (TAKDetection.Types.Trend.Opening, "opening"));

    private static readonly TokenTable<TAKDetection.Types.UasIdType> UasIdTypes = new(
        (TAKDetection.Types.UasIdType.Unspecified, "unspecified"),
        (TAKDetection.Types.UasIdType.SerialNumber, "serial_number"),
        (TAKDetection.Types.UasIdType.CaaRegistration, "caa_registration"),
        (TAKDetection.Types.UasIdType.UtmAssigned, "utm_assigned"),
        (TAKDetection.Types.UasIdType.SpecificSession, "specific_session"));

    private static readonly TokenTable<TAKDetection.Types.UasStatus> UasStatuses = new(
        (TAKDetection.Types.UasStatus.Unspecified, "unspecified"),
        (TAKDetection.Types.UasStatus.Ground, "ground"),
        (TAKDetection.Types.UasStatus.Airborne, "airborne"),
        (TAKDetection.Types.UasStatus.Emergency, "emergency"),
        (TAKDetection.Types.UasStatus.SystemFailure, "system_failure"));

    /// <summary>
    /// The detection one element carries: present even with no attributes.
    /// <paramref name="pointCe"/> is the event's <c>&lt;point ce&gt;</c>, used when the element
    /// has no <c>cep</c>.
    /// </summary>
    public static TAKDetection Parse(XElement el, string? pointCe)
    {
        string? Attr(string name) => el.Attribute(name)?.Value;

        var kind = Kinds.Read(Attr("kind"));
        // cep falls back to the event's <point ce>, unless that is CoT's "unknown".
        var cep = Number(Attr("cep"));
        if (cep == null && Number(pointCe) is { } ce && ce < UnknownCe) cep = ce;
        return new TAKDetection
        {
            Kind = kind,
            Sources = SourceBits(Attr("sources")),
            Confidence = (uint)Math.Clamp(Number(Attr("confidence")) ?? 0, 0, ConfidenceMax),
            State = States.Read(Attr("state")),
            Trend = Trends.Read(Attr("trend")),
            Count = (uint)Math.Max(Number(Attr("count")) ?? 0, 0),
            SensorAnchored = string.Equals(Attr("sensorAnchored")?.Trim(), "true", StringComparison.OrdinalIgnoreCase),
            CepM = (uint)Math.Max(cep ?? 0, 0),
            RssiDbm = Number(Attr("rssi")) ?? 0,
            UasId = kind == TAKDetection.Types.Kind.Uas ? TakeCodePoints(Attr("uasId") ?? "", UasIdMaxCodePoints) : "",
            UasIdType = UasIdTypes.Read(Attr("uasIdType")),
            UasStatus = UasStatuses.Read(Attr("uasStatus")),
        };
    }

    /// <summary>The <c>ce</c> for the event's <c>&lt;point&gt;</c>: the detection's cep when it has one, else unknown.</summary>
    public static int PointCe(TAKDetection? detection)
    {
        var cep = detection == null ? 0 : unchecked((int)detection.CepM);
        return cep > 0 ? cep : UnknownCe;
    }

    /// <summary>
    /// Appends the element as a <c>&lt;detail&gt;</c> child line, escaping <c>uasId</c> with
    /// <paramref name="esc"/>. Unsigned fields are read as their Int32 value, which is how the
    /// Kotlin model holds them, so a value out of range on the wire is clamped or left out.
    /// </summary>
    public static void Append(StringBuilder sb, TAKDetection detection, Func<string, string> esc)
    {
        sb.Append("    <").Append(Element);
        Attr(sb, "kind", Kinds.Write(detection.Kind));
        // Known bits only, in ascending order. An unknown bit still travels on the wire.
        var sources = new List<string>();
        for (var bit = 1u; bit != 0; bit <<= 1)
        {
            if ((detection.Sources & bit) != 0 && Sources.Write(unchecked((TAKDetection.Types.Source)bit)) is { } token)
                sources.Add(token);
        }
        if (sources.Count > 0) Attr(sb, "sources", string.Join(" ", sources));
        var confidence = Math.Clamp(unchecked((int)detection.Confidence), 0, ConfidenceMax);
        if (confidence > 0) Attr(sb, "confidence", confidence.ToString(CultureInfo.InvariantCulture));
        Attr(sb, "state", States.Write(detection.State));
        Attr(sb, "trend", Trends.Write(detection.Trend));
        var count = unchecked((int)detection.Count);
        if (count > 0) Attr(sb, "count", count.ToString(CultureInfo.InvariantCulture));
        if (detection.SensorAnchored) Attr(sb, "sensorAnchored", "true");
        var cep = unchecked((int)detection.CepM);
        if (cep > 0) Attr(sb, "cep", cep.ToString(CultureInfo.InvariantCulture));
        if (detection.RssiDbm != 0) Attr(sb, "rssi", detection.RssiDbm.ToString(CultureInfo.InvariantCulture));
        var uasId = detection.Kind == TAKDetection.Types.Kind.Uas ? TakeCodePoints(detection.UasId, UasIdMaxCodePoints) : "";
        if (uasId.Length > 0) Attr(sb, "uasId", esc(uasId));
        Attr(sb, "uasIdType", UasIdTypes.Write(detection.UasIdType));
        Attr(sb, "uasStatus", UasStatuses.Write(detection.UasStatus));
        sb.AppendLine("/>");
    }

    // Appends name="value", or nothing for a null value.
    private static void Attr(StringBuilder sb, string name, string? value)
    {
        if (value != null) sb.Append(' ').Append(name).Append("=\"").Append(value).Append('"');
    }

    private static uint SourceBits(string? raw)
    {
        if (raw == null) return 0;
        uint bits = 0;
        foreach (var token in raw.Trim().Split(SourceSeparators, StringSplitOptions.RemoveEmptyEntries))
            bits |= (uint)Sources.Read(token);
        return bits;
    }

    // A plain decimal rounded the way the parser rounds (half up), or null. Trimmed first, so
    // the parser's pattern sees no surrounding whitespace: NaN, Infinity, hex, a type suffix
    // and an overflow such as 1e400 all read as absent.
    private static int? Number(string? raw) =>
        raw != null && CotXmlParser.ToDoubleOrNull(raw.Trim()) is { } d ? CotXmlParser.RoundToInt(d) : null;

    /// <summary>The first <paramref name="n"/> Unicode code points, never splitting a surrogate pair.</summary>
    private static string TakeCodePoints(string s, int n)
    {
        var end = 0;
        for (var taken = 0; taken < n && end < s.Length; taken++)
            end += char.IsHighSurrogate(s[end]) && end + 1 < s.Length && char.IsLowSurrogate(s[end + 1]) ? 2 : 1;
        return s.Substring(0, end);
    }

    /// <summary>One enum's tokens, both ways.</summary>
    private sealed class TokenTable<T> where T : struct, Enum
    {
        private readonly Dictionary<T, string> _tokens = new();
        private readonly Dictionary<string, T> _values = new(StringComparer.Ordinal);

        public TokenTable(params (T Value, string Token)[] rows)
        {
            foreach (var (value, token) in rows)
            {
                _tokens.Add(value, token);
                _values.Add(token, value);
            }
        }

        /// <summary>The value a raw attribute names, trimmed and lowercased; Unspecified when absent or unknown.</summary>
        public T Read(string? raw) =>
            raw != null && _values.TryGetValue(raw.Trim().ToLowerInvariant(), out var value) ? value : default;

        /// <summary>The token for a value, or null for Unspecified and for a value the table lacks.</summary>
        public string? Write(T value) =>
            EqualityComparer<T>.Default.Equals(value, default) ? null : _tokens.GetValueOrDefault(value);
    }
}
