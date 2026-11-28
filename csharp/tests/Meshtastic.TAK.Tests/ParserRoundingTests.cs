using System.Globalization;
using Meshtastic.Protobufs;

namespace Meshtastic.TAK.Tests;

/// <summary>
/// The parser rounds every scaled value as Kotlin's <c>roundToInt()</c> does (Java's
/// <c>Math.round</c>: a half goes toward positive infinity), so each field gets the
/// same integer in both bindings. Every expected value below is what the Kotlin
/// parser produces for the same XML. Most inputs land exactly on a half once scaled
/// (0.025 m/s is 2.5 cm/s), where banker's rounding picks the even neighbor instead;
/// the rest are ordinary fractions, which a truncating cast gets wrong.
/// </summary>
public class ParserRoundingTests
{
    private static readonly string TestDataDir = Path.GetFullPath(
        Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "..", "..", "..", "..", "..", "testdata"));

    // Washington Monument, the repo's public-landmark placeholder position.
    private const string MonumentPoint = "lat=\"38.8895\" lon=\"-77.0353\" hae=\"0\"";

    private readonly CotXmlParser _parser = new();

    private static string Event(string type, string point, string detail) =>
        $"<event version=\"2.0\" uid=\"ROUNDING-1\" type=\"{type}\" how=\"m-g\" " +
        "time=\"2026-01-01T00:00:00Z\" start=\"2026-01-01T00:00:00Z\" stale=\"2026-01-01T00:10:00Z\">" +
        $"<point {point} ce=\"9999999\" le=\"9999999\"/><detail>{detail}</detail></event>";

    [Theory]
    [InlineData("0.025", "0.125", 3u, 13u)]  // 2.5 and 12.5: banker's gives 2 and 12
    [InlineData("0.005", "0.375", 1u, 38u)]  // 0.5 and 37.5: banker's gives 0 for speed
    [InlineData("-0.025", "-0.125", 0u, 0u)] // -2.5 and -12.5 round to -2 and -12, then clamp to 0
    public void Track_SpeedAndCourse_RoundHalfUpLikeKotlin(
        string speed, string course, uint expectedSpeed, uint expectedCourse)
    {
        var pkt = _parser.Parse(Event("a-f-G-U-C", MonumentPoint, $"<track speed=\"{speed}\" course=\"{course}\"/>"));
        Assert.Equal(expectedSpeed, pkt.Speed);
        Assert.Equal(expectedCourse, pkt.Course);
    }

    [Theory]
    // 388895000.5 and -770353001.5 after scaling: banker's gives ...000 and ...002.
    [InlineData("38.88950005", "-77.03530015", "2.5", 388895001, -770353001, 3)]
    // 388895002.5 and -770353003.5: banker's gives ...002 and ...004.
    [InlineData("38.88950025", "-77.03530035", "-3.5", 388895003, -770353003, -3)]
    [InlineData("38.8895", "-77.0353", "0.5", 388895000, -770353000, 1)]
    [InlineData("38.8895", "-77.0353", "-0.5", 388895000, -770353000, 0)]
    [InlineData("38.8895", "-77.0353", "-2.5", 388895000, -770353000, -2)]
    // Not ties: a truncating cast gives -29 and 73.
    [InlineData("38.8895", "-77.0353", "-29.667", 388895000, -770353000, -30)]
    [InlineData("38.8895", "-77.0353", "73.963", 388895000, -770353000, 74)]
    public void Point_LatLonAndHae_RoundHalfUpLikeKotlin(
        string lat, string lon, string hae, int expectedLatI, int expectedLonI, int expectedAltitude)
    {
        var pkt = _parser.Parse(Event("a-f-G-U-C", $"lat=\"{lat}\" lon=\"{lon}\" hae=\"{hae}\"", ""));
        Assert.Equal(expectedLatI, pkt.LatitudeI);
        Assert.Equal(expectedLonI, pkt.LongitudeI);
        Assert.Equal(expectedAltitude, pkt.Altitude);
    }

    [Fact]
    public void Point_LargestDoubleBelowHalf_RoundsDownLikeKotlin()
    {
        // floor(d + 0.5) gives 1 here, because the sum itself rounds up to 1.0.
        var justBelowHalf = Math.BitDecrement(0.5).ToString("R", CultureInfo.InvariantCulture);
        var pkt = _parser.Parse(Event("a-f-G-U-C", $"lat=\"38.8895\" lon=\"-77.0353\" hae=\"{justBelowHalf}\"", ""));
        Assert.Equal(0, pkt.Altitude);
    }

    [Theory]
    [InlineData("0.25", 3)]      // 2.5: banker's and a truncating cast both give 2
    [InlineData("-0.25", -2)]    // -2.5 goes toward positive infinity
    [InlineData("-19.35", -193)] // -193.5: banker's gives -194
    [InlineData("-19.47", -195)] // -194.7: a truncating cast gives -194
    public void Aircraft_Rssi_RoundsLikeKotlin(string rssi, int expectedRssiX10)
    {
        var pkt = _parser.Parse(Event(
            "a-n-A-C-F", "lat=\"38.8895\" lon=\"-77.0353\" hae=\"3048\"",
            $"<_aircot_ icao=\"000001\"/><_radio rssi=\"{rssi}\" gps=\"true\"/>"));
        Assert.Equal(TAKPacketV2.PayloadVariantOneofCase.Aircraft, pkt.PayloadVariantCase);
        Assert.Equal(expectedRssiX10, pkt.Aircraft.RssiX10);
    }

    [Theory]
    // 12.5 cm, 0.29 m (just short of 29 cm as a double) and 2.5: a truncating cast gives 12, 28 and 2.
    [InlineData("0.125", "0.29", "0.25", 13u, 29u, 3u)]
    [InlineData("0.375", "0.125", "0.35", 38u, 13u, 4u)]
    public void Shape_RadiiAndStrokeWeight_RoundLikeKotlin(
        string major, string minor, string strokeWeight,
        uint expectedMajorCm, uint expectedMinorCm, uint expectedStrokeWeightX10)
    {
        var pkt = _parser.Parse(Event(
            "u-d-c-c", MonumentPoint,
            $"<shape><ellipse major=\"{major}\" minor=\"{minor}\" angle=\"360\"/></shape>" +
            $"<strokeColor value=\"-1\"/><strokeWeight value=\"{strokeWeight}\"/><labels_on value=\"false\"/>"));
        Assert.Equal(TAKPacketV2.PayloadVariantOneofCase.Shape, pkt.PayloadVariantCase);
        Assert.Equal(expectedMajorCm, pkt.Shape.MajorCm);
        Assert.Equal(expectedMinorCm, pkt.Shape.MinorCm);
        Assert.Equal(expectedStrokeWeightX10, pkt.Shape.StrokeWeightX10);
    }

    [Theory]
    [InlineData("0.25", 3u)] // 2.5 dm: a truncating cast gives 2
    [InlineData("0.35", 4u)] // 3.5 dm: a truncating cast gives 3
    public void Bullseye_Distance_RoundsLikeKotlin(string distance, uint expectedDistanceDm)
    {
        var pkt = _parser.Parse(Event(
            "u-r-b-bullseye", MonumentPoint,
            "<shape><ellipse major=\"500.0\" minor=\"500.0\" angle=\"360\"/></shape>" +
            $"<bullseye distance=\"{distance}\" bearingRef=\"M\"/><strokeColor value=\"-65536\"/>"));
        Assert.Equal(TAKPacketV2.PayloadVariantOneofCase.Shape, pkt.PayloadVariantCase);
        Assert.Equal(expectedDistanceDm, pkt.Shape.BullseyeDistanceDm);
    }

    [Theory]
    [InlineData("0.29", "0.125", 29u, 13u)] // a truncating cast gives 28 and 12
    [InlineData("0.125", "0.375", 13u, 38u)] // a truncating cast gives 12 and 37
    public void RangeAndBearing_RoundLikeKotlin(
        string range, string bearing, uint expectedRangeCm, uint expectedBearingCdeg)
    {
        var pkt = _parser.Parse(Event(
            "u-rb-a", MonumentPoint,
            "<link uid=\"anchor-1\" relation=\"p-p\" type=\"b-m-p-w\" point=\"38.8895,-77.0353\"/>" +
            $"<range value=\"{range}\"/><bearing value=\"{bearing}\"/>"));
        Assert.Equal(TAKPacketV2.PayloadVariantOneofCase.Rab, pkt.PayloadVariantCase);
        Assert.Equal(expectedRangeCm, pkt.Rab.RangeCm);
        Assert.Equal(expectedBearingCdeg, pkt.Rab.BearingCdeg);
    }

    /// <summary>
    /// Kotlin wrote <c>testdata/protobuf/*.pb</c> from the same fixtures, so every value
    /// this parser rounds must equal the golden's. Whole packets are not compared: a few
    /// fixtures differ for reasons that have nothing to do with rounding.
    /// </summary>
    [Fact]
    public void EveryFixture_RoundedValuesMatchKotlinGoldens()
    {
        var mismatches = new List<string>();
        foreach (var xmlPath in Directory.EnumerateFiles(Path.Combine(TestDataDir, "cot_xml"), "*.xml")
                     .Order(StringComparer.Ordinal))
        {
            var name = Path.GetFileNameWithoutExtension(xmlPath);
            var kotlin = TAKPacketV2.Parser.ParseFrom(
                File.ReadAllBytes(Path.Combine(TestDataDir, "protobuf", $"{name}.pb")));
            var csharp = _parser.Parse(File.ReadAllText(xmlPath));
            var expected = RoundedValues(kotlin);
            var actual = RoundedValues(csharp);
            foreach (var field in expected.Keys.Union(actual.Keys))
            {
                var k = expected.GetValueOrDefault(field, "(absent)");
                var c = actual.GetValueOrDefault(field, "(absent)");
                if (k != c) mismatches.Add($"{name} {field}: Kotlin {k}, C# {c}");
            }
        }
        Assert.Empty(mismatches);
    }

    /// <summary>Every field the parser fills by rounding a scaled double.</summary>
    private static Dictionary<string, string> RoundedValues(TAKPacketV2 p)
    {
        static string N(long n) => n.ToString(CultureInfo.InvariantCulture);
        static string Deltas(IEnumerable<int> deltas) => string.Join(",", deltas.Select(d => N(d)));
        static string Point(CotGeoPoint? g) => $"{N(g?.LatDeltaI ?? 0)}/{N(g?.LonDeltaI ?? 0)}";

        var v = new Dictionary<string, string>
        {
            ["latitude_i"] = N(p.LatitudeI),
            ["longitude_i"] = N(p.LongitudeI),
            ["altitude"] = N(p.Altitude),
            ["speed"] = N(p.Speed),
            ["course"] = N(p.Course),
        };
        if (p.Shape is { } s)
        {
            v["shape.major_cm"] = N(s.MajorCm);
            v["shape.minor_cm"] = N(s.MinorCm);
            v["shape.stroke_weight_x10"] = N(s.StrokeWeightX10);
            v["shape.bullseye_distance_dm"] = N(s.BullseyeDistanceDm);
            v["shape.vertex_lat_deltas"] = Deltas(s.VertexLatDeltas);
            v["shape.vertex_lon_deltas"] = Deltas(s.VertexLonDeltas);
        }
        if (p.Rab is { } r)
        {
            v["rab.range_cm"] = N(r.RangeCm);
            v["rab.bearing_cdeg"] = N(r.BearingCdeg);
            v["rab.stroke_weight_x10"] = N(r.StrokeWeightX10);
            v["rab.anchor"] = Point(r.Anchor);
        }
        if (p.Aircraft is { } a)
            v["aircraft.rssi_x10"] = N(a.RssiX10);
        if (p.Route is { } route)
            v["route.points"] = string.Join(",", route.Links.Select(l => Point(l.Point)));
        return v;
    }
}
