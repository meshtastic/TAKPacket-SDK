using Meshtastic.TAK;
using Meshtastic.Protobufs;

namespace Meshtastic.TAK.Tests;

/// <summary>
/// The passive-sensor detection annotation (TAKPacketV2 tag 47) as <c>&lt;__detection&gt;</c>
/// and on the wire, mirroring the Kotlin binding's DetectionXmlTest.
/// </summary>
public class DetectionTests
{
    private static readonly string TestDataDir = Path.GetFullPath(
        Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "..", "..", "..", "..", "..", "testdata"));

    private static readonly string NL = Environment.NewLine;

    private readonly CotXmlParser _parser = new();
    private readonly CotXmlBuilder _builder = new();
    private readonly TakCompressor _compressor = new();

    // A synthetic CTA-2063-A serial number: the placeholder manufacturer code ZZZZ,
    // length code 8, then the eight-character serial.
    private const string UasId = "ZZZZ8TEST0001";

    private const string EventUid = "2f6d3b8e-5a41-4c1f-9e07-6b2d8c4a1f35";

    private const string FullElement =
        "<__detection kind=\"uas\" sources=\"remote_id rf\" confidence=\"87\" state=\"active\" trend=\"closing\" count=\"2\" " +
        "sensorAnchored=\"true\" cep=\"12\" rssi=\"-71\" uasId=\"" + UasId + "\" uasIdType=\"serial_number\" uasStatus=\"airborne\"/>";

    private static string Event(string detail, string type = "a-u-A-M-H-Q", string ce = "9999999") =>
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
        $"<event version=\"2.0\" uid=\"{EventUid}\" type=\"{type}\" how=\"m-r\" " +
        "time=\"2026-11-20T15:00:00Z\" start=\"2026-11-20T15:00:00Z\" stale=\"2026-11-20T15:01:00Z\">\n" +
        $"  <point lat=\"33.1286\" lon=\"-107.2470\" hae=\"1452.0\" ce=\"{ce}\" le=\"9999999\"/>\n" +
        $"  <detail><contact callsign=\"UAS-1\"/>{detail}</detail>\n" +
        "</event>";

    private TAKDetection? DetectionOf(string element, string ce = "9999999") =>
        _parser.Parse(Event(element, ce: ce)).Detection;

    private static TAKPacketV2 PacketWith(TAKDetection detection) => new()
    {
        CotTypeStr = "a-u-A-M-H-Q",
        How = CotHow.MR,
        Uid = EventUid,
        Callsign = "UAS-1",
        Detection = detection,
    };

    private TAKPacketV2 ThroughTheWire(TAKPacketV2 pkt) => _compressor.Decompress(_compressor.Compress(pkt));

    private static string LoadXml(string name) =>
        File.ReadAllText(Path.Combine(TestDataDir, "cot_xml", $"{name}.xml"));

    private static byte[] LoadProtobuf(string name) =>
        File.ReadAllBytes(Path.Combine(TestDataDir, "protobuf", $"{name}.pb"));

    private static byte[] LoadGolden(string name) =>
        File.ReadAllBytes(Path.Combine(TestDataDir, "golden", $"{name}.bin"));

    // === Tokens ===

    // Spelled out rather than derived, so a renamed enum value can't quietly change the XML.
    private static readonly Dictionary<TAKDetection.Types.Kind, string> Kinds = new()
    {
        [TAKDetection.Types.Kind.Unspecified] = "unspecified",
        [TAKDetection.Types.Kind.Uas] = "uas",
        [TAKDetection.Types.Kind.UasOperator] = "uas_operator",
        [TAKDetection.Types.Kind.UasHome] = "uas_home",
        [TAKDetection.Types.Kind.Person] = "person",
        [TAKDetection.Types.Kind.Vehicle] = "vehicle",
        [TAKDetection.Types.Kind.Other] = "other",
    };

    private static readonly Dictionary<TAKDetection.Types.Source, string> Sources = new()
    {
        [TAKDetection.Types.Source.Unspecified] = "unspecified",
        [TAKDetection.Types.Source.RemoteId] = "remote_id",
        [TAKDetection.Types.Source.Rf] = "rf",
        [TAKDetection.Types.Source.Acoustic] = "acoustic",
        [TAKDetection.Types.Source.Radar] = "radar",
        [TAKDetection.Types.Source.Optical] = "optical",
        [TAKDetection.Types.Source.Thermal] = "thermal",
        [TAKDetection.Types.Source.Seismic] = "seismic",
        [TAKDetection.Types.Source.Magnetic] = "magnetic",
    };

    private static readonly Dictionary<TAKDetection.Types.State, string> States = new()
    {
        [TAKDetection.Types.State.Unspecified] = "unspecified",
        [TAKDetection.Types.State.New] = "new",
        [TAKDetection.Types.State.Active] = "active",
        [TAKDetection.Types.State.Coasting] = "coasting",
        [TAKDetection.Types.State.Lost] = "lost",
    };

    private static readonly Dictionary<TAKDetection.Types.Trend, string> Trends = new()
    {
        [TAKDetection.Types.Trend.Unspecified] = "unspecified",
        [TAKDetection.Types.Trend.Closing] = "closing",
        [TAKDetection.Types.Trend.Steady] = "steady",
        [TAKDetection.Types.Trend.Opening] = "opening",
    };

    private static readonly Dictionary<TAKDetection.Types.UasIdType, string> UasIdTypes = new()
    {
        [TAKDetection.Types.UasIdType.Unspecified] = "unspecified",
        [TAKDetection.Types.UasIdType.SerialNumber] = "serial_number",
        [TAKDetection.Types.UasIdType.CaaRegistration] = "caa_registration",
        [TAKDetection.Types.UasIdType.UtmAssigned] = "utm_assigned",
        [TAKDetection.Types.UasIdType.SpecificSession] = "specific_session",
    };

    private static readonly Dictionary<TAKDetection.Types.UasStatus, string> UasStatuses = new()
    {
        [TAKDetection.Types.UasStatus.Unspecified] = "unspecified",
        [TAKDetection.Types.UasStatus.Ground] = "ground",
        [TAKDetection.Types.UasStatus.Airborne] = "airborne",
        [TAKDetection.Types.UasStatus.Emergency] = "emergency",
        [TAKDetection.Types.UasStatus.SystemFailure] = "system_failure",
    };

    private static void AssertCoversEveryValue<T>(Dictionary<T, string> table) where T : struct, Enum =>
        Assert.Equal(Enum.GetValues<T>().OrderBy(v => v), table.Keys.OrderBy(v => v));

    [Fact]
    public void EveryEnumValueHasAToken()
    {
        // Every value is in its table, so a value added later without a token fails here.
        AssertCoversEveryValue(Kinds);
        AssertCoversEveryValue(Sources);
        AssertCoversEveryValue(States);
        AssertCoversEveryValue(Trends);
        AssertCoversEveryValue(UasIdTypes);
        AssertCoversEveryValue(UasStatuses);
    }

    public static TheoryData<string, string, int> TokenRows()
    {
        var rows = new TheoryData<string, string, int>();
        foreach (var (value, token) in Kinds) rows.Add("kind", token, (int)value);
        foreach (var (value, token) in Sources) rows.Add("sources", token, (int)value);
        foreach (var (value, token) in States) rows.Add("state", token, (int)value);
        foreach (var (value, token) in Trends) rows.Add("trend", token, (int)value);
        foreach (var (value, token) in UasIdTypes) rows.Add("uasIdType", token, (int)value);
        foreach (var (value, token) in UasStatuses) rows.Add("uasStatus", token, (int)value);
        return rows;
    }

    // A detection with only the named attribute's field set.
    private static TAKDetection WithOnly(string attribute, int value) => attribute switch
    {
        "kind" => new TAKDetection { Kind = (TAKDetection.Types.Kind)value },
        "sources" => new TAKDetection { Sources = (uint)value },
        "state" => new TAKDetection { State = (TAKDetection.Types.State)value },
        "trend" => new TAKDetection { Trend = (TAKDetection.Types.Trend)value },
        "uasIdType" => new TAKDetection { UasIdType = (TAKDetection.Types.UasIdType)value },
        "uasStatus" => new TAKDetection { UasStatus = (TAKDetection.Types.UasStatus)value },
        _ => throw new ArgumentOutOfRangeException(nameof(attribute), attribute, null),
    };

    [Theory]
    [MemberData(nameof(TokenRows))]
    public void EveryTokenReadsAndBuildsBack(string attribute, string token, int value)
    {
        var expected = WithOnly(attribute, value);
        Assert.Equal(expected, DetectionOf($"<__detection {attribute}=\"{token}\"/>"));
        var xml = _builder.Build(PacketWith(expected));
        Assert.Contains(value == 0 ? "    <__detection/>" + NL : $"    <__detection {attribute}=\"{token}\"/>" + NL, xml);
    }

    [Fact]
    public void SourcesAreWrittenInBitOrderAndReadAsABitmask()
    {
        var all = Sources.Keys.Aggregate(0u, (bits, s) => bits | (uint)s);
        var xml = _builder.Build(PacketWith(new TAKDetection { Sources = all }));
        Assert.Contains("sources=\"remote_id rf acoustic radar optical thermal seismic magnetic\"", xml);
        Assert.Equal(all, _parser.Parse(xml).Detection?.Sources);
    }

    // === The element ===

    [Fact]
    public void TheFullElementSurvivesTheMesh()
    {
        var expected = new TAKDetection
        {
            Kind = TAKDetection.Types.Kind.Uas,
            Sources = (uint)(TAKDetection.Types.Source.RemoteId | TAKDetection.Types.Source.Rf),
            Confidence = 87,
            State = TAKDetection.Types.State.Active,
            Trend = TAKDetection.Types.Trend.Closing,
            Count = 2,
            SensorAnchored = true,
            CepM = 12,
            RssiDbm = -71,
            UasId = UasId,
            UasIdType = TAKDetection.Types.UasIdType.SerialNumber,
            UasStatus = TAKDetection.Types.UasStatus.Airborne,
        };
        var parsed = _parser.Parse(Event(FullElement));
        Assert.Equal(expected, parsed.Detection);

        var received = ThroughTheWire(parsed);
        Assert.Equal(parsed, received);

        var rebuilt = _builder.Build(received);
        Assert.Contains("    " + FullElement + NL, rebuilt);
        Assert.Contains("ce=\"12\" le=\"9999999\"", rebuilt);
        Assert.Equal(expected, _parser.Parse(rebuilt).Detection);
    }

    [Fact]
    public void ABareElementIsPresentWithEveryDefault()
    {
        Assert.Equal(new TAKDetection(), DetectionOf("<__detection/>"));
        var xml = _builder.Build(PacketWith(new TAKDetection()));
        Assert.Contains("    <__detection/>" + NL, xml);
        Assert.Contains("ce=\"9999999\"", xml);
    }

    [Fact]
    public void AnEventWithoutTheElementHasNoDetectionAndKeepsTheUnknownCe()
    {
        var parsed = _parser.Parse(Event("", ce: "12.0"));
        Assert.Null(parsed.Detection);
        Assert.Null(ThroughTheWire(parsed).Detection);
        var xml = _builder.Build(parsed);
        Assert.DoesNotContain("__detection", xml);
        Assert.Contains("ce=\"9999999\"", xml);
    }

    [Fact]
    public void UnknownTokensReadAsUnspecifiedAndCaseIsIgnored()
    {
        var d = DetectionOf(
            "<__detection kind=\"balloon\" sources=\" RF bogus  ACOUSTIC \" state=\"LOST\" trend=\"sideways\" " +
            "uasIdType=\"mystery\" uasStatus=\"Ground\" sensorAnchored=\"TRUE\"/>");
        Assert.NotNull(d);
        Assert.Equal(TAKDetection.Types.Kind.Unspecified, d.Kind);
        Assert.Equal((uint)(TAKDetection.Types.Source.Rf | TAKDetection.Types.Source.Acoustic), d.Sources);
        Assert.Equal(TAKDetection.Types.State.Lost, d.State);
        Assert.Equal(TAKDetection.Types.Trend.Unspecified, d.Trend);
        Assert.Equal(TAKDetection.Types.UasIdType.Unspecified, d.UasIdType);
        Assert.Equal(TAKDetection.Types.UasStatus.Ground, d.UasStatus);
        Assert.True(d.SensorAnchored);
        Assert.False(DetectionOf("<__detection sensorAnchored=\"yes\"/>")?.SensorAnchored);
    }

    [Fact]
    public void NumbersAreRoundedHalfUpAndClamped()
    {
        Assert.Equal(100u, DetectionOf("<__detection confidence=\"250\"/>")?.Confidence);
        Assert.Equal(0u, DetectionOf("<__detection confidence=\"-5\"/>")?.Confidence);
        Assert.Equal(88u, DetectionOf("<__detection confidence=\"87.5\"/>")?.Confidence);
        Assert.Equal(0u, DetectionOf("<__detection count=\"-3\"/>")?.Count);
        Assert.Equal(0u, DetectionOf("<__detection cep=\"-1\"/>")?.CepM);
        Assert.Equal(12u, DetectionOf("<__detection cep=\" 12 \"/>")?.CepM);
        Assert.Equal(-70, DetectionOf("<__detection rssi=\"-70.5\"/>")?.RssiDbm);
        Assert.Equal(-71, DetectionOf("<__detection rssi=\"-70.6\"/>")?.RssiDbm);
        Assert.Equal(1500u, DetectionOf("<__detection count=\"1.5e3\"/>")?.Count);
        // Not plain decimals, or too large to be finite: read as absent.
        Assert.Equal(
            new TAKDetection(),
            DetectionOf("<__detection confidence=\"NaN\" count=\"Infinity\" cep=\"0x10\" rssi=\"1e400\"/>"));
        Assert.Equal(0u, DetectionOf("<__detection count=\"2f\"/>")?.Count);
        // The builder clamps a value that arrived out of range on the wire.
        var xml = _builder.Build(PacketWith(new TAKDetection
        {
            Confidence = 150,
            Count = unchecked((uint)-2),
            CepM = unchecked((uint)-4),
        }));
        Assert.Contains("<__detection confidence=\"100\"/>", xml);
        Assert.Contains("ce=\"9999999\"", xml);
    }

    [Fact]
    public void TheUasIdIsCutTo20CodePointsAndKeptOnlyForAUas()
    {
        const string longId = "ZZZZFTEST000000000001EXTRA"; // 26 characters
        Assert.Equal(longId[..20], DetectionOf($"<__detection kind=\"uas\" uasId=\"{longId}\"/>")?.UasId);
        // A character outside the BMP counts once and is never split.
        var astral = char.ConvertFromUtf32(0x1D400);
        Assert.Equal(
            new string('A', 19) + astral,
            DetectionOf($"<__detection kind=\"uas\" uasId=\"{new string('A', 19) + astral + "B"}\"/>")?.UasId);

        Assert.All(
            new[] { "uas_operator", "uas_home", "person", "vehicle", "other", "unspecified" },
            kind => Assert.Equal("", DetectionOf($"<__detection kind=\"{kind}\" uasId=\"{UasId}\"/>")?.UasId));
        Assert.Equal("", DetectionOf($"<__detection uasId=\"{UasId}\"/>")?.UasId);

        // The builder holds a packet built in code to the same rule.
        var xml = _builder.Build(PacketWith(new TAKDetection { Kind = TAKDetection.Types.Kind.Person, UasId = UasId }));
        Assert.DoesNotContain(UasId, xml);
        var cut = _builder.Build(PacketWith(new TAKDetection { Kind = TAKDetection.Types.Kind.Uas, UasId = longId }));
        Assert.Contains($"uasId=\"{longId[..20]}\"", cut);
    }

    [Fact]
    public void TheUasIdIsEscapedInItsAttribute()
    {
        const string awkward = "ZZZZ8\"TEST<&'0001";
        var xml = _builder.Build(PacketWith(new TAKDetection { Kind = TAKDetection.Types.Kind.Uas, UasId = awkward }));
        Assert.Contains("uasId=\"ZZZZ8&quot;TEST&lt;&amp;&apos;0001\"", xml);
        Assert.Equal(awkward, _parser.Parse(xml).Detection?.UasId);
    }

    [Fact]
    public void CepFallsBackToThePointCe()
    {
        Assert.Equal(15u, DetectionOf("<__detection/>", ce: "15.0")?.CepM);
        Assert.Equal(12u, DetectionOf("<__detection cep=\"12\"/>", ce: "15.0")?.CepM);
        Assert.Equal(15u, DetectionOf("<__detection cep=\"unknown\"/>", ce: "15.0")?.CepM);
        Assert.Equal(0u, DetectionOf("<__detection/>", ce: "9999999")?.CepM);
        Assert.Equal(0u, DetectionOf("<__detection/>", ce: "9999999.0")?.CepM);
        Assert.Equal(0u, DetectionOf("<__detection/>", ce: "-3")?.CepM);
    }

    [Fact]
    public void TheAnnotationLeavesThePayloadVariantAlone()
    {
        const string parentLink =
            "<link uid=\"ANDROID-0000000000000012\" type=\"a-f-G-E-S\" parent_callsign=\"SENSOR-1\" relation=\"p-p\"/>";
        var annotated = _parser.Parse(Event(parentLink + FullElement));
        Assert.Equal(TAKPacketV2.PayloadVariantOneofCase.Marker, annotated.PayloadVariantCase);
        Assert.Equal("SENSOR-1", annotated.Marker.ParentCallsign);
        Assert.Equal(_parser.Parse(Event(parentLink)).Marker, annotated.Marker);
        Assert.Equal(
            TAKPacketV2.PayloadVariantOneofCase.None,
            _parser.Parse(Event(FullElement, type: "a-f-G-U-C")).PayloadVariantCase);
    }

    [Fact]
    public void SourceBitsTheTableDoesNotNameSurviveTheWire()
    {
        const uint unnamed = 1u << 9;
        var d = new TAKDetection
        {
            Kind = TAKDetection.Types.Kind.Uas,
            Sources = (uint)TAKDetection.Types.Source.Radar | unnamed,
        };
        Assert.Equal(d, ThroughTheWire(PacketWith(d)).Detection);
        // CoT XML can only name the bits it knows.
        Assert.Contains("<__detection kind=\"uas\" sources=\"radar\"/>", _builder.Build(PacketWith(d)));
    }

    // === Shared fixtures ===

    // Parses a fixture and checks it against Kotlin's goldens. Message equality, not bytes:
    // Wire writes fields in .proto declaration order, where detection (47) sits ahead of marti
    // and the payload oneof, while Google.Protobuf writes them in field-number order, so tag 47
    // lands in a different place. The fixtures' start is months old, so neither side encodes
    // start_epoch_lo14 and the comparison doesn't depend on the clock.
    private TAKPacketV2 ParseFixtureAgainstKotlin(string name)
    {
        var parsed = _parser.Parse(LoadXml(name));
        Assert.Equal(TAKPacketV2.Parser.ParseFrom(LoadProtobuf(name)), parsed);
        Assert.Equal(parsed.Detection, _compressor.Decompress(LoadGolden(name)).Detection);
        return parsed;
    }

    [Fact]
    public void Parse_DetectionUasRemoteId()
    {
        var pkt = ParseFixtureAgainstKotlin("detection_uas_remoteid");
        Assert.Equal(new TAKDetection
        {
            Kind = TAKDetection.Types.Kind.Uas,
            Sources = 3,
            Confidence = 87,
            State = TAKDetection.Types.State.Active,
            Trend = TAKDetection.Types.Trend.Closing,
            CepM = 12,
            RssiDbm = -71,
            UasId = UasId,
            UasIdType = TAKDetection.Types.UasIdType.SerialNumber,
            UasStatus = TAKDetection.Types.UasStatus.Airborne,
        }, pkt.Detection);
        Assert.Equal(TAKPacketV2.PayloadVariantOneofCase.Marker, pkt.PayloadVariantCase);
        Assert.Equal("ANDROID-0000000000000012", pkt.Marker.ParentUid);
    }

    [Fact]
    public void Parse_DetectionUasSensorAnchored()
    {
        var pkt = ParseFixtureAgainstKotlin("detection_uas_sensor_anchored");
        Assert.Equal(new TAKDetection
        {
            Kind = TAKDetection.Types.Kind.Uas,
            Sources = 2,
            Confidence = 60,
            State = TAKDetection.Types.State.New,
            SensorAnchored = true,
            RssiDbm = -84,
        }, pkt.Detection);
        var s = pkt.SensorFov;
        Assert.NotNull(s);
        Assert.Equal(0u, s.AzimuthDeg);
        Assert.Equal(360u, s.FovHorizontalDeg);
        Assert.True(s.HasRangeM);
        Assert.Equal(2000u, s.RangeM);
        Assert.Equal(SensorFov.Types.SensorType.Rf, s.Type);
    }

    [Fact]
    public void Parse_DetectionUasOperator()
    {
        var pkt = ParseFixtureAgainstKotlin("detection_uas_operator");
        // cep comes from <point ce="15.0">, and a uasId is kept only for kind uas.
        Assert.Equal(new TAKDetection
        {
            Kind = TAKDetection.Types.Kind.UasOperator,
            Sources = 1,
            Confidence = 87,
            State = TAKDetection.Types.State.Active,
            CepM = 15,
        }, pkt.Detection);
    }

    [Fact]
    public void Parse_DetectionGroundVehicle()
    {
        var pkt = ParseFixtureAgainstKotlin("detection_ground_vehicle");
        Assert.Equal(new TAKDetection
        {
            Kind = TAKDetection.Types.Kind.Vehicle,
            Sources = 68,
            Confidence = 72,
            State = TAKDetection.Types.State.Active,
            Trend = TAKDetection.Types.Trend.Opening,
            Count = 2,
            CepM = 25,
        }, pkt.Detection);
    }
}
