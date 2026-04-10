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
        Assert.Equal((int)(37.7749 * 1e7), pkt.LatitudeI);
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
        Assert.True(result.CompressedSize <= 237, $"{fixture}: {result.CompressedSize}B exceeds 237B MTU");
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
            Pli = true,
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

    public static TheoryData<string> FixtureData()
    {
        var data = new TheoryData<string>();
        foreach (var f in Fixtures) data.Add(f);
        return data;
    }
}
