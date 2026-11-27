using Google.Protobuf;
using Meshtastic.TAK;
using Meshtastic.Protobufs;

namespace Meshtastic.TAK.Tests;

public class TakTests
{
    private static readonly string TestDataDir = Path.GetFullPath(
        Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "..", "..", "..", "..", "..", "testdata"));

    // Dynamically enumerate all XML fixtures in the shared testdata directory
    // so new fixtures can be added without editing this list. Kotlin's
    // CompressionTest is the canonical generator for the corresponding .pb
    // and .bin files — run it first when adding new fixtures, then the C#
    // suite picks them up on the next `dotnet test` run. Sorted for stable
    // test ordering. Lazy evaluation so the test class can still load even
    // if the testdata directory is absent at type-load time.
    private static readonly Lazy<string[]> FixturesLazy = new(() =>
        Directory.EnumerateFiles(Path.Combine(TestDataDir, "cot_xml"), "*.xml")
            .Select(Path.GetFileNameWithoutExtension)
            .OrderBy(n => n, StringComparer.Ordinal)
            .ToArray()!);

    private static string[] Fixtures => FixturesLazy.Value;

    private readonly CotXmlParser _parser = new();
    private readonly TakCompressor _compressor = new();

    private string LoadXml(string name) =>
        File.ReadAllText(Path.Combine(TestDataDir, "cot_xml", $"{name}.xml"));

    private byte[]? LoadGolden(string name)
    {
        var path = Path.Combine(TestDataDir, "golden", $"{name}.bin");
        return File.Exists(path) ? File.ReadAllBytes(path) : null;
    }

    // Kotlin's intermediate protobuf bytes for a fixture (testdata/protobuf/<name>.pb).
    private byte[] LoadProtobuf(string name) =>
        File.ReadAllBytes(Path.Combine(TestDataDir, "protobuf", $"{name}.pb"));

    // === CotTypeMapper ===

    [Fact]
    public void TypeMapper_KnownTypes()
    {
        Assert.Equal(1, CotTypeMapper.TypeToEnum("a-f-G-U-C"));
        Assert.Equal(3, CotTypeMapper.TypeToEnum("a-n-A-C-F"));
        Assert.Equal(14, CotTypeMapper.TypeToEnum("t-x-d-d"));
        Assert.Equal(25, CotTypeMapper.TypeToEnum("b-t-f"));
        Assert.Equal(28, CotTypeMapper.TypeToEnum("b-a-o-opn"));
        Assert.Equal(40, CotTypeMapper.TypeToEnum("u-d-f"));
        Assert.Equal(0, CotTypeMapper.TypeToEnum("unknown"));
    }

    [Fact]
    public void TypeMapper_OtherReturnsNull()
    {
        Assert.Null(CotTypeMapper.TypeToString(0));
    }

    [Fact]
    public void TypeMapper_RoundTrip()
    {
        for (int i = 1; i <= 75; i++)
        {
            var s = CotTypeMapper.TypeToString(i);
            if (s != null) Assert.Equal(i, CotTypeMapper.TypeToEnum(s));
        }
    }

    [Fact]
    public void TypeMapper_Aircraft()
    {
        Assert.True(CotTypeMapper.IsAircraft(3));  // a-n-A-C-F
        Assert.True(CotTypeMapper.IsAircraft(6));  // a-f-A-M-H
        Assert.True(CotTypeMapper.IsAircraft(44)); // a-h-A
        Assert.True(CotTypeMapper.IsAircraft(45)); // a-u-A
        Assert.False(CotTypeMapper.IsAircraft(1)); // a-f-G-U-C
        Assert.False(CotTypeMapper.IsAircraft(14));// t-x-d-d
        Assert.False(CotTypeMapper.IsAircraft(25));// b-t-f
        Assert.False(CotTypeMapper.IsAircraft(17));// a-f-S
    }

    [Fact]
    public void TypeMapper_IsAircraftString()
    {
        Assert.True(CotTypeMapper.IsAircraftString("a-f-A-M-H"));
        Assert.True(CotTypeMapper.IsAircraftString("a-n-A-C-F"));
        Assert.False(CotTypeMapper.IsAircraftString("a-f-G-U-C"));
        Assert.False(CotTypeMapper.IsAircraftString("b-t-f"));
        Assert.False(CotTypeMapper.IsAircraftString("a-f-S"));
    }

    [Fact]
    public void HowMapper()
    {
        Assert.Equal(1, CotTypeMapper.HowToEnum("h-e"));
        Assert.Equal(2, CotTypeMapper.HowToEnum("m-g"));
        Assert.Equal(3, CotTypeMapper.HowToEnum("h-g-i-g-o"));
        Assert.Equal(4, CotTypeMapper.HowToEnum("m-r"));
        Assert.Equal(0, CotTypeMapper.HowToEnum("unknown"));
    }

    [Fact]
    public void HowMapper_RoundTrip()
    {
        for (int i = 1; i <= 7; i++)
        {
            var s = CotTypeMapper.HowToString(i);
            if (s != null) Assert.Equal(i, CotTypeMapper.HowToEnum(s));
        }
    }

    // === Round-trip ===

    [Theory]
    [MemberData(nameof(FixtureData))]
    public void RoundTrip_PreservesFields(string fixture)
    {
        var xml = LoadXml(fixture);
        var pkt = _parser.Parse(xml);
        Assert.NotEmpty(pkt.Uid);

        var wire = _compressor.Compress(pkt);
        var dec = _compressor.Decompress(wire);

        Assert.Equal(pkt.CotTypeId, dec.CotTypeId);
        Assert.Equal(pkt.How, dec.How);
        Assert.Equal(pkt.Callsign, dec.Callsign);
        Assert.Equal(pkt.Team, dec.Team);
        Assert.Equal(pkt.LatitudeI, dec.LatitudeI);
        Assert.Equal(pkt.LongitudeI, dec.LongitudeI);
        Assert.Equal(pkt.Altitude, dec.Altitude);
        Assert.Equal(pkt.Battery, dec.Battery);
        Assert.Equal(pkt.Uid, dec.Uid);
        Assert.Equal(pkt.Speed, dec.Speed);
        Assert.Equal(pkt.Course, dec.Course);
        Assert.Equal(pkt.Role, dec.Role);
        Assert.Equal(pkt.DeviceCallsign, dec.DeviceCallsign);
        Assert.Equal(pkt.TakVersion, dec.TakVersion);
        Assert.Equal(pkt.TakPlatform, dec.TakPlatform);
        Assert.Equal(pkt.Endpoint, dec.Endpoint);
        // v0.3.2 directed-routing recipients. Empty list = broadcast
        // (default for PLI / situational-awareness); populated for TAKTALK
        // m-t-t and directed b-t-f DMs. TAKTALK gates voice TTS on this
        // list matching the receiver's callsign so a regression here
        // silently breaks voice messaging end-to-end.
        Assert.Equal(
            pkt.Marti?.DestCallsign?.ToArray() ?? Array.Empty<string>(),
            dec.Marti?.DestCallsign?.ToArray() ?? Array.Empty<string>());
        // Passive-sensor detection annotation; null on both sides when absent.
        Assert.Equal(pkt.Detection, dec.Detection);

        // Payload-specific field assertions
        Assert.Equal(pkt.PayloadVariantCase, dec.PayloadVariantCase);
        if (pkt.PayloadVariantCase == Meshtastic.Protobufs.TAKPacketV2.PayloadVariantOneofCase.Chat)
        {
            Assert.Equal(pkt.Chat.Message, dec.Chat.Message);
            Assert.Equal(pkt.Chat.To, dec.Chat.To);
        }
        else if (pkt.PayloadVariantCase == Meshtastic.Protobufs.TAKPacketV2.PayloadVariantOneofCase.Aircraft)
        {
            Assert.Equal(pkt.Aircraft.Icao, dec.Aircraft.Icao);
            Assert.Equal(pkt.Aircraft.Registration, dec.Aircraft.Registration);
            Assert.Equal(pkt.Aircraft.Flight, dec.Aircraft.Flight);
            Assert.Equal(pkt.Aircraft.Squawk, dec.Aircraft.Squawk);
        }
    }

    [Fact]
    public void Parse_PliBasic()
    {
        var pkt = _parser.Parse(LoadXml("pli_basic"));
        Assert.Equal("testnode", pkt.Uid);
        Assert.Equal(Meshtastic.Protobufs.CotType.AFGUC, pkt.CotTypeId);
        Assert.Equal(Meshtastic.Protobufs.CotHow.MG, pkt.How);
        Assert.Equal("testnode", pkt.Callsign);
        Assert.Equal((int)(33.1284 * 1e7), pkt.LatitudeI);
    }

    [Fact]
    public void Parse_AircraftAdsb()
    {
        var pkt = _parser.Parse(LoadXml("aircraft_adsb"));
        Assert.Equal(Meshtastic.Protobufs.CotType.ANACF, pkt.CotTypeId);
        Assert.NotEmpty(pkt.Aircraft.Icao);
    }

    [Fact]
    public void Parse_GeoChat()
    {
        var pkt = _parser.Parse(LoadXml("geochat_simple"));
        Assert.Equal(Meshtastic.Protobufs.CotType.BTF, pkt.CotTypeId);
        Assert.NotEmpty(pkt.Chat.Message);
    }

    [Fact]
    public void Parse_DeleteEvent()
    {
        var pkt = _parser.Parse(LoadXml("delete_event"));
        Assert.Equal(Meshtastic.Protobufs.CotType.TXDD, pkt.CotTypeId);
        Assert.Equal(Meshtastic.Protobufs.CotHow.HGIGO, pkt.How);
    }

    [Fact]
    public void Parse_Casevac()
    {
        var pkt = _parser.Parse(LoadXml("casevac"));
        Assert.Equal(Meshtastic.Protobufs.CotType.BRFHC, pkt.CotTypeId);
        Assert.Equal("CASEVAC-1", pkt.Callsign);
    }

    [Fact]
    public void Parse_CasevacMedlineFullFields()
    {
        var pkt = _parser.Parse(LoadXml("casevac_medline"));
        Assert.NotNull(pkt.Casevac);
        var c = pkt.Casevac;
        Assert.Equal(CasevacReport.Types.Precedence.Urgent, c.Precedence);
        Assert.True(c.LitterPatients > 0);
        Assert.True((c.EquipmentFlags & 0x02) == 0x02, "hoist flag must round-trip");
        Assert.NotEqual(CasevacReport.Types.HlzMarking.Unspecified, c.HlzMarking);
        Assert.Equal("38.90", c.Frequency);
    }

    [Fact]
    public void Parse_Emergency911()
    {
        var pkt = _parser.Parse(LoadXml("emergency_911"));
        Assert.NotNull(pkt.Emergency);
        Assert.Equal(EmergencyAlert.Types.Type.Alert911, pkt.Emergency.Type);
        Assert.NotEmpty(pkt.Emergency.AuthoringUid);
    }

    [Fact]
    public void Parse_EmergencyCancel()
    {
        var pkt = _parser.Parse(LoadXml("emergency_cancel"));
        Assert.NotNull(pkt.Emergency);
        Assert.Equal(EmergencyAlert.Types.Type.Cancel, pkt.Emergency.Type);
    }

    [Fact]
    public void Parse_TaskEngage()
    {
        var pkt = _parser.Parse(LoadXml("task_engage"));
        Assert.NotNull(pkt.Task);
        var t = pkt.Task;
        Assert.Equal("engage", t.TaskType);
        Assert.Equal("target-01", t.TargetUid);
        Assert.Equal("ANDROID-0000000000000005", t.AssigneeUid);
        Assert.Equal(TaskRequest.Types.Priority.High, t.Priority);
        Assert.Equal(TaskRequest.Types.Status.Pending, t.Status);
        Assert.Equal("cover by fire", t.Note);
    }

    [Fact]
    public void Parse_ChatReceiptDelivered()
    {
        var pkt = _parser.Parse(LoadXml("chat_receipt_delivered"));
        Assert.NotNull(pkt.Chat);
        Assert.Equal(GeoChat.Types.ReceiptType.Delivered, pkt.Chat.ReceiptType);
        Assert.NotEmpty(pkt.Chat.ReceiptForUid);
    }

    [Fact]
    public void Parse_ChatReceiptRead()
    {
        var pkt = _parser.Parse(LoadXml("chat_receipt_read"));
        Assert.NotNull(pkt.Chat);
        Assert.Equal(GeoChat.Types.ReceiptType.Read, pkt.Chat.ReceiptType);
        Assert.NotEmpty(pkt.Chat.ReceiptForUid);
    }

    [Fact]
    public void Parse_DrawingEllipse()
    {
        var pkt = _parser.Parse(LoadXml("drawing_ellipse"));
        Assert.NotNull(pkt.Shape);
        Assert.Equal(DrawnShape.Types.Kind.Ellipse, pkt.Shape.Kind);
        Assert.True(pkt.Shape.MajorCm > 0);
        Assert.True(pkt.Shape.MinorCm > 0);
    }

    [Fact]
    public void Parse_MarkerGoto()
    {
        var pkt = _parser.Parse(LoadXml("marker_goto"));
        Assert.NotNull(pkt.Marker);
        Assert.Equal(Marker.Types.Kind.GoToPoint, pkt.Marker.Kind);
    }

    [Fact]
    public void Parse_MarkerTank_UsesNumericCotTypeId()
    {
        var pkt = _parser.Parse(LoadXml("marker_tank"));
        // a-h-G-E-V-A-T → one of the new 2525 tank enum entries (82+)
        Assert.True((int)pkt.CotTypeId >= 82);
        Assert.Empty(pkt.CotTypeStr);
    }

    [Fact]
    public void Parse_AlertTic()
    {
        var pkt = _parser.Parse(LoadXml("alert_tic"));
        Assert.Equal(Meshtastic.Protobufs.CotType.BAOOpn, pkt.CotTypeId);
        Assert.Equal("ALPHA-6", pkt.Callsign);
    }

    [Fact]
    public void Parse_PliFullAllFields()
    {
        var pkt = _parser.Parse(LoadXml("pli_full"));
        Assert.Equal(Meshtastic.Protobufs.CotType.AFGUC, pkt.CotTypeId);
        Assert.NotEmpty(pkt.Callsign);
        Assert.NotEmpty(pkt.TakVersion);
        Assert.NotEmpty(pkt.TakPlatform);
        Assert.True(pkt.Battery > 0, "Battery should be > 0");
    }

    [Fact]
    public void Parse_PliStationary_ClampsNegativeSpeedAndCourse()
    {
        // Regression for an iOS crash where ATAK's <track speed="-1.0"
        // course="-1.0"/> sentinel for stationary / unknown targets tripped a
        // Double -> UInt32 conversion trap in the Swift parser. The proto
        // field is uint32 on all platforms; the C# cast `(uint)negativeDouble`
        // is undefined behavior and typically wraps to a huge value, so the
        // parser must clamp before the cast.
        var pkt = _parser.Parse(LoadXml("pli_stationary"));
        Assert.Equal(0u, pkt.Speed);
        Assert.Equal(0u, pkt.Course);
        Assert.Equal("iPadTAKAware", pkt.Callsign);
        // PLI is the implicit payload since v0.4.0 — no payload_variant is set.
        Assert.Equal(TAKPacketV2.PayloadVariantOneofCase.None, pkt.PayloadVariantCase);
    }

    // === Rebuilt XML ===

    [Theory]
    [MemberData(nameof(FixtureData))]
    public void RoundTrip_RebuildsXml(string fixture)
    {
        var pkt = _parser.Parse(LoadXml(fixture));
        var wire = _compressor.Compress(pkt);
        var dec = _compressor.Decompress(wire);
        var builder = new CotXmlBuilder();
        var xml = builder.Build(dec);
        Assert.Contains("<event", xml);
    }

    // === Compression ===

    [Theory]
    [MemberData(nameof(FixtureData))]
    public void Compression_UnderLoraMtu(string fixture)
    {
        var pkt = _parser.Parse(LoadXml(fixture));
        var result = _compressor.CompressWithStats(pkt);
        Assert.True(result.CompressedSize <= 225, $"{fixture}: {result.CompressedSize}B exceeds 225B MTU");
    }

    [Fact]
    public void Compression_MeaningfulRatio()
    {
        int totalXml = 0, totalCompressed = 0;
        foreach (var f in Fixtures)
        {
            var xml = LoadXml(f);
            var result = _compressor.CompressWithStats(_parser.Parse(xml));
            totalXml += xml.Length;
            totalCompressed += result.CompressedSize;
        }
        Assert.True((double)totalXml / totalCompressed >= 3.0);
    }

    // === Compatibility ===

    [Theory]
    [MemberData(nameof(FixtureData))]
    public void Golden_Decompresses(string fixture)
    {
        var golden = LoadGolden(fixture);
        if (golden == null) return;
        var pkt = _compressor.Decompress(golden);
        Assert.NotEmpty(pkt.Uid);
    }

    [Theory]
    [MemberData(nameof(FixtureData))]
    public void Golden_SimilarSize(string fixture)
    {
        var golden = LoadGolden(fixture);
        if (golden == null) return;
        var wire = _compressor.Compress(_parser.Parse(LoadXml(fixture)));
        var ratio = (double)wire.Length / golden.Length;
        Assert.InRange(ratio, 0.5, 2.0);
    }

    [Fact]
    public void Uncompressed_0xFF_RoundTrip()
    {
        var pkt = new Meshtastic.Protobufs.TAKPacketV2
        {
            CotTypeId = Meshtastic.Protobufs.CotType.AFGUC,
            How = Meshtastic.Protobufs.CotHow.MG,
            Callsign = "TEST",
            LatitudeI = 340522000,
            LongitudeI = -1182437000,
            Altitude = 100,
            // PLI is the implicit payload since v0.4.0 — no payload_variant set.
        };
        var proto = pkt.ToByteArray();
        var wire = new byte[1 + proto.Length];
        wire[0] = 0xFF;
        proto.CopyTo(wire, 1);

        var dec = _compressor.Decompress(wire);
        Assert.Equal(Meshtastic.Protobufs.CotType.AFGUC, dec.CotTypeId);
        Assert.Equal("TEST", dec.Callsign);
        Assert.Equal(340522000, dec.LatitudeI);
    }

    // === Environment / SensorFov annotations ===
    //
    // <environment> and <sensor> attach to any event. The expectations are Kotlin's
    // behavior after a wire round trip, which is what a mesh receiver sees. Decimals
    // print through the builder's F(), so 12.0 is written "12" where Kotlin writes "12.0".

    private static string CotWithDetail(string detail) =>
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<event version=\"2.0\" uid=\"ALPHA-1-UID\" type=\"a-f-G-U-C\" how=\"m-g\" " +
        "time=\"2026-03-15T16:12:00Z\" start=\"2026-03-15T16:12:00Z\" stale=\"2026-03-15T16:12:45Z\">" +
        "<point lat=\"38.8895\" lon=\"-77.0353\" hae=\"0.0\" ce=\"9999999.0\" le=\"9999999.0\"/>" +
        $"<detail><contact callsign=\"ALPHA-1\"/>{detail}</detail></event>";

    private TAKPacketV2 ParseDetail(string detail) => _parser.Parse(CotWithDetail(detail));

    private string RebuildOverWire(TAKPacketV2 pkt) =>
        new CotXmlBuilder().Build(_compressor.Decompress(_compressor.Compress(pkt)));

    private static void AssertPliWithSensorAnnotations(TAKPacketV2 pkt)
    {
        var env = pkt.Environment;
        Assert.NotNull(env);
        Assert.Equal(225, env.TemperatureCX10);
        Assert.Equal(270u, env.WindDirectionDeg);
        Assert.Equal(1200u, env.WindSpeedCmS);

        var s = pkt.SensorFov;
        Assert.NotNull(s);
        Assert.Equal(SensorFov.Types.SensorType.Thermal, s.Type);
        Assert.Equal(45u, s.AzimuthDeg);
        Assert.True(s.HasRangeM, "range was set in the fixture");
        Assert.Equal(2500u, s.RangeM);
        Assert.Equal(45u, s.FovHorizontalDeg);
        Assert.Equal(30u, s.FovVerticalDeg);
        Assert.Equal(-5, s.ElevationDeg);
        Assert.Equal(0, s.RollDeg);
        Assert.Equal("FLIR-Boson-640", s.Model);
    }

    [Fact]
    public void PliWithSensor_AnnotationsSurviveTheWireAndTheRebuild()
    {
        var pkt = _parser.Parse(LoadXml("pli_with_sensor"));
        // The annotations don't perturb payload dispatch: still an implicit PLI.
        Assert.Equal(TAKPacketV2.PayloadVariantOneofCase.None, pkt.PayloadVariantCase);
        AssertPliWithSensorAnnotations(pkt);

        var dec = _compressor.Decompress(_compressor.Compress(pkt));
        AssertPliWithSensorAnnotations(dec);

        // Both elements sit right after <uid Droid>, environment first, one per line.
        var xml = new CotXmlBuilder().Build(dec);
        var lines = xml.Split('\n').Select(l => l.Trim()).ToList();
        var uidLine = lines.IndexOf("<uid Droid=\"OBSERVER-07\"/>");
        Assert.True(uidLine >= 0, "rebuilt XML lost <uid Droid>");
        Assert.Equal("<environment temperature=\"22.5\" windDirection=\"270\" windSpeed=\"12\"/>", lines[uidLine + 1]);
        var sensorLine = lines[uidLine + 2];
        Assert.Equal(
            "<sensor azimuth=\"45\" range=\"2500\" fov=\"45\" vfov=\"30\" elevation=\"-5\" model=\"FLIR-Boson-640\"/>",
            sensorLine);
        Assert.DoesNotContain(" type=", sensorLine);

        AssertPliWithSensorAnnotations(_parser.Parse(xml));
    }

    [Fact]
    public void PliWithSensor_ProtobufMatchesTheKotlinGolden()
    {
        // Protobuf serialization is deterministic, so the parsed packet must
        // serialize to exactly the bytes Kotlin wrote for this fixture. The
        // fixture's start is months old, so neither side encodes start_epoch_lo14
        // and the comparison doesn't depend on the clock.
        var pkt = _parser.Parse(LoadXml("pli_with_sensor"));
        Assert.Equal(LoadProtobuf("pli_with_sensor"), pkt.ToByteArray());
    }

    [Fact]
    public void Sensor_WithNoAttributes_TakesTheKotlinDefaults()
    {
        var pkt = ParseDetail("<sensor/>");
        var s = pkt.SensorFov;
        Assert.NotNull(s);
        Assert.Equal(270u, s.AzimuthDeg);
        Assert.Equal(45u, s.FovHorizontalDeg);
        Assert.False(s.HasRangeM, "an absent range must stay absent");
        Assert.Equal(0u, s.FovVerticalDeg);
        Assert.Equal(0, s.ElevationDeg);
        Assert.Equal(0, s.RollDeg);
        Assert.Equal("", s.Model);
        Assert.Equal(SensorFov.Types.SensorType.Unspecified, s.Type);
        Assert.Contains("<sensor azimuth=\"270\" fov=\"45\" elevation=\"0\"/>", RebuildOverWire(pkt));
    }

    [Fact]
    public void Environment_TemperatureOnly_LeavesTheWindAtZero()
    {
        var pkt = ParseDetail("<environment temperature=\"-3\"/>");
        Assert.NotNull(pkt.Environment);
        Assert.Equal(-30, pkt.Environment.TemperatureCX10);
        Assert.Equal(0u, pkt.Environment.WindDirectionDeg);
        Assert.Equal(0u, pkt.Environment.WindSpeedCmS);
        Assert.Contains("<environment temperature=\"-3\"/>", RebuildOverWire(pkt));
    }

    [Theory]
    [InlineData("<environment windDirection=\"270.5\"/>")] // windDirection must be a plain Int
    [InlineData("<environment windDirection=\" 270\"/>")]  // with no whitespace
    [InlineData("<environment/>")]
    [InlineData("<environment temperature=\"abc\"/>")]
    [InlineData("<environment temperature=\"NaN\"/>")]     // NaN and Infinity read as absent
    [InlineData("<environment temperature=\"1e400\"/>")]
    public void Environment_WithNothingParseable_IsAbsent(string element)
    {
        var pkt = ParseDetail(element);
        Assert.Null(pkt.Environment);
        Assert.DoesNotContain("<environment", RebuildOverWire(pkt));
    }

    [Fact]
    public void Annotations_RoundHalvesUpLikeKotlinRoundToInt()
    {
        var s = ParseDetail("<sensor azimuth=\"44.5\" elevation=\"-2.5\"/>").SensorFov;
        Assert.Equal(45u, s.AzimuthDeg);
        Assert.Equal(-2, s.ElevationDeg); // toward +infinity, not away from zero
        // Java's Math.round gives 0 here; floor(d + 0.5) would give 1.
        Assert.Equal(0, ParseDetail("<sensor roll=\"0.49999999999999994\"/>").SensorFov.RollDeg);
        // 22.45 * 10 is exactly 224.5 as a double, so this is 225 (banker's rounding gives 224).
        Assert.Equal(225, ParseDetail("<environment temperature=\"22.45\"/>").Environment.TemperatureCX10);
        // 1.005 * 100 is 100.49999999999999 as a double: the double product is rounded, not the decimal.
        Assert.Equal(100u, ParseDetail("<environment windSpeed=\"1.005\"/>").Environment.WindSpeedCmS);
    }

    [Theory]
    [InlineData("45", 45u)]
    [InlineData(" 45 ", 45u)]          // surrounding whitespace is allowed for a double
    [InlineData("+45", 45u)]
    [InlineData("2500.0", 2500u)]
    [InlineData(".5", 1u)]
    [InlineData("5.", 5u)]
    [InlineData("1e3", 1000u)]
    [InlineData("-5", 4294967291u)]    // uint32 takes the Int32's two's-complement bits
    [InlineData("", 270u)]             // anything unparseable falls back to the default
    [InlineData("abc", 270u)]
    [InlineData("4,5", 270u)]
    [InlineData("0x10", 270u)]
    [InlineData("NaN", 270u)]          // NaN and Infinity read as absent, where Kotlin
    [InlineData("Infinity", 270u)]     // throws or clamps: the deliberate deviation
    [InlineData("1e400", 270u)]        // overflows to Infinity
    public void Sensor_AzimuthParsesAsAnOrdinaryDecimal(string raw, uint expected)
    {
        Assert.Equal(expected, ParseDetail($"<sensor azimuth=\"{raw}\"/>").SensorFov.AzimuthDeg);
    }

    [Fact]
    public void Annotations_UnsignedFieldsRebuildAsTheirInt32Reading()
    {
        var pkt = ParseDetail("<environment temperature=\"0\" windDirection=\"-90\"/><sensor azimuth=\"-10\"/>");
        Assert.Equal(unchecked((uint)-90), pkt.Environment.WindDirectionDeg);
        Assert.Equal(unchecked((uint)-10), pkt.SensorFov.AzimuthDeg);
        var xml = RebuildOverWire(pkt);
        Assert.Contains("<environment temperature=\"0\" windDirection=\"-90\"/>", xml);
        Assert.Contains("<sensor azimuth=\"-10\" fov=\"45\" elevation=\"0\"/>", xml);
    }

    [Theory]
    [InlineData(null, SensorFov.Types.SensorType.Unspecified)]
    [InlineData("", SensorFov.Types.SensorType.Unspecified)]
    [InlineData("FLIR-Boson-640", SensorFov.Types.SensorType.Thermal)]
    [InlineData("thermal cam", SensorFov.Types.SensorType.Thermal)]
    [InlineData("Boson", SensorFov.Types.SensorType.Thermal)]
    [InlineData("LRF-3000", SensorFov.Types.SensorType.Laser)]
    [InlineData("laser", SensorFov.Types.SensorType.Laser)]
    [InlineData("NVG-21", SensorFov.Types.SensorType.Nvg)]
    [InlineData("night vision", SensorFov.Types.SensorType.Nvg)]
    [InlineData("radar", SensorFov.Types.SensorType.Rf)]
    [InlineData("rf-detector", SensorFov.Types.SensorType.Rf)]
    [InlineData("RF-Scanner", SensorFov.Types.SensorType.Rf)]
    [InlineData("rfscanner", SensorFov.Types.SensorType.Other)]
    [InlineData("rf", SensorFov.Types.SensorType.Other)]
    [InlineData("EO-IR", SensorFov.Types.SensorType.Camera)]
    [InlineData("camera", SensorFov.Types.SensorType.Camera)]
    [InlineData("optic", SensorFov.Types.SensorType.Camera)]
    [InlineData("widget", SensorFov.Types.SensorType.Other)]
    [InlineData("FLIR radar", SensorFov.Types.SensorType.Thermal)] // order matters
    public void Sensor_TypeIsInferredFromModel(string? model, SensorFov.Types.SensorType expected)
    {
        var element = model == null ? "<sensor/>" : $"<sensor model=\"{model}\"/>";
        Assert.Equal(expected, ParseDetail(element).SensorFov.Type);
    }

    [Fact]
    public void Sensor_RangeZero_StaysPresentOverTheWire()
    {
        var pkt = ParseDetail("<sensor range=\"0\"/>");
        Assert.True(pkt.SensorFov.HasRangeM, "range=\"0\" is present, not absent");
        Assert.Equal(0u, pkt.SensorFov.RangeM);
        Assert.Contains(" range=\"0\"", RebuildOverWire(pkt));
    }

    [Fact]
    public void Annotations_LaterElementsReplaceEarlierOnes()
    {
        // An <environment> with nothing parseable keeps the earlier reading...
        var kept = ParseDetail("<environment temperature=\"10\"/><environment windSpeed=\"abc\"/>").Environment;
        Assert.Equal(100, kept.TemperatureCX10);
        // ...and one that parses replaces it outright rather than merging.
        var replaced = ParseDetail("<environment temperature=\"10\"/><environment windSpeed=\"2\"/>").Environment;
        Assert.Equal(0, replaced.TemperatureCX10);
        Assert.Equal(200u, replaced.WindSpeedCmS);
        // The last <sensor> wins, and its type attribute is never read.
        var s = ParseDetail("<sensor azimuth=\"10\" model=\"FLIR\"/><sensor type=\"Thermal\" model=\"camera\"/>").SensorFov;
        Assert.Equal(270u, s.AzimuthDeg);
        Assert.Equal(SensorFov.Types.SensorType.Camera, s.Type);
    }

    public static TheoryData<string> FixtureData()
    {
        var data = new TheoryData<string>();
        foreach (var f in Fixtures) data.Add(f);
        return data;
    }
}
