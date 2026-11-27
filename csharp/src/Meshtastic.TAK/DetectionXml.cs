using System.Globalization;
using System.Text;
using System.Xml.Linq;
using Google.Protobuf;
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
/// <c>confidence</c> is clamped to 0-100, and <c>count</c> and <c>cep</c> to zero or more.</para>
/// <para><c>uasId</c> holds up to 20 bytes and is kept only when the kind is <c>Uas</c>. An ID made
/// only of ASCII letters, digits, <c>-</c>, <c>.</c> and <c>_</c>, as serial numbers and CAA
/// registrations are, is written as that text; any other is written as <c>hex:</c> and its bytes
/// in lowercase hex, so neither form needs escaping. Reading takes a value that starts with
/// <c>hex:</c> (in any case) as hex, and drops it unless the rest is whole hex digits; any other
/// value is the ID's UTF-8 text. Either way the ID is cut to 20 bytes.</para>
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

    private const int UasIdMaxBytes = 20;
    private const int ConfidenceMax = 100;

    // A uasId of only these characters is written as text; any other is written as hex.
    private const string UasIdTextChars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._";
    private const string UasIdHexPrefix = "hex:";

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
            UasId = kind == TAKDetection.Types.Kind.Uas ? ParseUasId(Attr("uasId")) : ByteString.Empty,
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
    /// Appends the element as a <c>&lt;detail&gt;</c> child line. No value it writes needs
    /// escaping. Unsigned fields are read as their Int32 value, which is how the Kotlin model
    /// holds them, so a value out of range on the wire is clamped or left out.
    /// </summary>
    public static void Append(StringBuilder sb, TAKDetection detection)
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
        if (detection.Kind == TAKDetection.Types.Kind.Uas && !detection.UasId.IsEmpty)
            Attr(sb, "uasId", UasIdAttribute(detection.UasId.Span[..Math.Min(detection.UasId.Length, UasIdMaxBytes)]));
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

    // A uasId attribute's bytes: hex after "hex:" (in any case), else the text's UTF-8, cut to
    // 20. A hex value whose rest is not an even number of hex digits gives no ID.
    private static ByteString ParseUasId(string? raw)
    {
        if (raw == null) return ByteString.Empty;
        byte[] bytes;
        if (raw.StartsWith(UasIdHexPrefix, StringComparison.OrdinalIgnoreCase))
        {
            var digits = raw.Substring(UasIdHexPrefix.Length);
            if (digits.Length % 2 != 0 || !digits.All(Uri.IsHexDigit)) return ByteString.Empty;
            bytes = Convert.FromHexString(digits);
        }
        else
        {
            bytes = Encoding.UTF8.GetBytes(raw);
        }
        return ByteString.CopyFrom(bytes, 0, Math.Min(bytes.Length, UasIdMaxBytes));
    }

    // The uasId attribute: the ID's text when every byte is in UasIdTextChars, else "hex:" and
    // its bytes in lowercase hex.
    private static string UasIdAttribute(ReadOnlySpan<byte> id)
    {
        foreach (var b in id)
        {
            if (UasIdTextChars.IndexOf((char)b) < 0) return UasIdHexPrefix + Convert.ToHexString(id).ToLowerInvariant();
        }
        return Encoding.ASCII.GetString(id);
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
