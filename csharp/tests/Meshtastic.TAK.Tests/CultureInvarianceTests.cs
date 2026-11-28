using System.Globalization;
using System.Text.RegularExpressions;
using System.Xml.Linq;
using Meshtastic.Protobufs;

namespace Meshtastic.TAK.Tests;

/// <summary>
/// Locks the rebuilt CoT XML to the invariant culture. String interpolation and a
/// parameterless <c>ToString</c> format with the current culture, so a builder that
/// leans on them writes <c>hae="−22"</c> under sv-SE (a U+2212 minus that no binding's
/// parser reads back), a time of <c>06.28.05</c> under fi-FI, and a Buddhist-era year
/// under th-TH. Each test builds under such a culture and checks the result against
/// what the invariant culture gives.
/// </summary>
public class CultureInvarianceTests
{
    private static readonly string TestDataDir = Path.GetFullPath(
        Path.Combine(AppDomain.CurrentDomain.BaseDirectory, "..", "..", "..", "..", "..", "..", "testdata"));

    private const string CotTimeFormat = "yyyy-MM-ddTHH:mm:ss.fffZ";

    // A time, start or stale attribute anywhere in the document: GeoChat's <remarks>
    // carries a time too.
    private static readonly Regex TimeAttr = new("\\b(time|start|stale)=\"([^\"]*)\"");

    // Washington Monument, the repo's public-landmark placeholder position.
    private const int MonumentLatI = 388895000;
    private const int MonumentLonI = -770353000;

    private readonly CotXmlParser _parser = new();
    private readonly CotXmlBuilder _builder = new();

    /// <summary>
    /// Sets the current culture and UI culture for one test and restores both on
    /// dispose. Both belong to the current thread, so tests running in parallel on
    /// other threads keep their own.
    /// </summary>
    private sealed class CultureScope : IDisposable
    {
        private readonly CultureInfo _culture = CultureInfo.CurrentCulture;
        private readonly CultureInfo _uiCulture = CultureInfo.CurrentUICulture;

        public CultureScope(CultureInfo culture)
        {
            CultureInfo.CurrentCulture = culture;
            CultureInfo.CurrentUICulture = culture;
        }

        public void Dispose()
        {
            CultureInfo.CurrentCulture = _culture;
            CultureInfo.CurrentUICulture = _uiCulture;
        }
    }

    // The calendar each calendar culture is here for. A culture's default calendar comes
    // from the platform's CLDR data, and that moves: CLDR 46 made Gregorian the default in
    // Saudi Arabia, so on an ICU built from it ar-SA formats a CoT time just as the
    // invariant culture does.
    private static readonly Dictionary<string, Type> Calendars = new()
    {
        ["th-TH"] = typeof(ThaiBuddhistCalendar),
        ["ar-SA"] = typeof(UmAlQuraCalendar),
        ["fa-IR"] = typeof(PersianCalendar),
    };

    /// <summary>
    /// A new <paramref name="name"/> culture, set to the calendar <see cref="Calendars"/>
    /// gives it rather than to its default.
    /// </summary>
    private static CultureInfo Culture(string name)
    {
        var culture = new CultureInfo(name);
        if (Calendars.TryGetValue(name, out var type))
        {
            culture.DateTimeFormat.Calendar = culture.OptionalCalendars.FirstOrDefault(c => c.GetType() == type)
                ?? throw new InvalidOperationException($"{name} offers no {type.Name} here");
        }
        return culture;
    }

    private static TAKPacketV2 Envelope(CotType type, string uid) => new()
    {
        CotTypeId = type,
        How = CotHow.HE,
        Uid = uid,
        Callsign = "ALPHA-1",
        LatitudeI = MonumentLatI,
        LongitudeI = MonumentLonI,
        Altitude = -22,
        StaleSeconds = 600,
    };

    // Each color has its alpha high bit set, so it prints as a negative Int32.
    private static TAKPacketV2 MarkerPacket()
    {
        var pkt = Envelope(CotType.BMPSM, "ALPHA-1-SPOT");
        pkt.Marker = new Marker { ColorArgb = 0xFFFF0000 };
        return pkt;
    }

    private static TAKPacketV2 CirclePacket()
    {
        var pkt = Envelope(CotType.UDCC, "ALPHA-1-CIRCLE");
        pkt.Shape = new DrawnShape
        {
            Kind = DrawnShape.Types.Kind.Circle,
            Style = DrawnShape.Types.StyleMode.StrokeAndFill,
            MajorCm = 22698,
            MinorCm = 22698,
            AngleDeg = 360,
            StrokeArgb = 0xFF123456,
            FillArgb = 0x80ABCDEF,
            StrokeWeightX10 = 40,
        };
        return pkt;
    }

    private static TAKPacketV2 RangeAndBearingPacket()
    {
        var pkt = Envelope(CotType.URbA, "ALPHA-1-RB");
        pkt.Rab = new RangeAndBearing { RangeCm = 125050, BearingCdeg = 13500, StrokeArgb = 0xFFFF0000 };
        return pkt;
    }

    private static DateTimeOffset ParseCotTime(string s) =>
        DateTimeOffset.ParseExact(s, CotTimeFormat, CultureInfo.InvariantCulture, DateTimeStyles.AssumeUniversal);

    private static IEnumerable<string> NonAscii(string xml) =>
        xml.Where(c => !char.IsAscii(c)).Distinct().Select(c => "U+" + ((int)c).ToString("X4", CultureInfo.InvariantCulture));

    [Fact]
    public void Build_UnderSwedishCulture_WritesAsciiMinusSignsThatReparse()
    {
        using var scope = new CultureScope(new CultureInfo("sv-SE"));
        // The premise: under sv-SE an interpolated negative number really is "−22".
        Assert.Equal("\u2212", CultureInfo.CurrentCulture.NumberFormat.NegativeSign);

        var marker = _builder.Build(MarkerPacket());
        var circle = _builder.Build(CirclePacket());
        var rab = _builder.Build(RangeAndBearingPacket());

        foreach (var xml in new[] { marker, circle, rab })
        {
            Assert.Contains("hae=\"-22\"", xml);
            Assert.Empty(NonAscii(xml));
        }
        Assert.Contains("<color argb=\"-65536\"/>", marker);
        Assert.Contains("<strokeColor value=\"-15584170\"/>", circle);
        Assert.Contains("<fillColor value=\"-2136224273\"/>", circle);
        Assert.Contains("<strokeColor value=\"-65536\"/>", rab);

        // Still under sv-SE, the parser reads every value back.
        var m = _parser.Parse(marker);
        Assert.Equal(-22, m.Altitude);
        Assert.Equal(0xFFFF0000u, AtakPalette.ResolveColor(m.Marker.Color, m.Marker.ColorArgb));
        var c = _parser.Parse(circle);
        Assert.Equal(-22, c.Altitude);
        Assert.Equal(0xFF123456u, AtakPalette.ResolveColor(c.Shape.StrokeColor, c.Shape.StrokeArgb));
        Assert.Equal(0x80ABCDEFu, AtakPalette.ResolveColor(c.Shape.FillColor, c.Shape.FillArgb));
        var r = _parser.Parse(rab);
        Assert.Equal(-22, r.Altitude);
        Assert.Equal(0xFFFF0000u, AtakPalette.ResolveColor(r.Rab.StrokeColor, r.Rab.StrokeArgb));
    }

    [Theory]
    [InlineData("fi-FI")] // '.' time separator
    [InlineData("da-DK")] // '.' time separator
    [InlineData("th-TH")] // Thai Buddhist calendar, 543 years ahead
    [InlineData("ar-SA")] // Um al-Qura (Hijri) calendar
    [InlineData("fa-IR")] // Persian calendar
    public void Build_UnderCultureWithItsOwnClockOrCalendar_WritesGregorianIsoTimes(string cultureName)
    {
        var culture = Culture(cultureName);
        // The premise: this culture really does format a CoT time differently.
        var probe = new DateTimeOffset(2026, 11, 27, 6, 28, 5, 123, TimeSpan.Zero);
        Assert.NotEqual(
            probe.ToString(CotTimeFormat, CultureInfo.InvariantCulture),
            probe.ToString(CotTimeFormat, culture));

        using var scope = new CultureScope(culture);
        var before = DateTimeOffset.UtcNow;
        var xml = _builder.Build(MarkerPacket());
        var after = DateTimeOffset.UtcNow;

        var evt = XDocument.Parse(xml).Root!;
        var time = ParseCotTime(evt.Attribute("time")!.Value);
        // The format keeps milliseconds and drops the rest, so allow that one millisecond.
        Assert.InRange(time, before.AddMilliseconds(-1), after);
        Assert.Equal(time, ParseCotTime(evt.Attribute("start")!.Value));
        Assert.Equal(TimeSpan.FromSeconds(600), ParseCotTime(evt.Attribute("stale")!.Value) - time);
        Assert.Equal(600u, _parser.Parse(xml).StaleSeconds);
    }

    public static TheoryData<string> HostileCultures => new()
    {
        "sv-SE", // U+2212 minus sign
        "fi-FI", // U+2212 minus sign and '.' time separator
        "ar-SA", // Arabic letter mark before the minus, Um al-Qura calendar
        "fa-IR", // left-to-right mark and U+2212, Persian calendar
        "he-IL", // left-to-right mark before the minus
        "th-TH", // Thai Buddhist calendar
        "de-DE", // comma decimal separator
    };

    /// <summary>
    /// Every fixture parses to the same packet and rebuilds to the same XML under
    /// <paramref name="cultureName"/> as under the invariant culture. The time, start
    /// and stale values come from the clock, so they are compared as instants (within
    /// a few seconds of each other) rather than as text.
    /// </summary>
    [Theory]
    [MemberData(nameof(HostileCultures))]
    public void EveryFixture_ParsesAndBuildsTheSameUnderAnyCulture(string cultureName)
    {
        var culture = Culture(cultureName);
        var failures = new List<string>();
        foreach (var path in Directory.EnumerateFiles(Path.Combine(TestDataDir, "cot_xml"), "*.xml")
                     .Order(StringComparer.Ordinal))
        {
            var name = Path.GetFileNameWithoutExtension(path);
            var source = File.ReadAllText(path);
            TAKPacketV2 pkt, pktUnderCulture;
            string expected, actual;
            using (new CultureScope(CultureInfo.InvariantCulture))
            {
                pkt = _parser.Parse(source);
                expected = _builder.Build(pkt);
            }
            using (new CultureScope(culture))
            {
                pktUnderCulture = _parser.Parse(source);
                actual = _builder.Build(pkt);
            }

            if (!pkt.Equals(pktUnderCulture))
                failures.Add($"{name}: parses to a different packet");

            var expectedMasked = TimeAttr.Replace(expected, "$1=\"(time)\"");
            var actualMasked = TimeAttr.Replace(actual, "$1=\"(time)\"");
            if (expectedMasked != actualMasked)
            {
                failures.Add($"{name}: {FirstDifference(expectedMasked, actualMasked)}");
                continue;
            }

            var expectedTimes = TimeAttr.Matches(expected);
            var actualTimes = TimeAttr.Matches(actual);
            for (var i = 0; i < expectedTimes.Count; i++)
            {
                var text = actualTimes[i].Groups[2].Value;
                if (!DateTimeOffset.TryParseExact(text, CotTimeFormat, CultureInfo.InvariantCulture,
                        DateTimeStyles.AssumeUniversal, out var t)
                    || (t - ParseCotTime(expectedTimes[i].Groups[2].Value)).Duration() > TimeSpan.FromSeconds(5))
                {
                    failures.Add($"{name}: {actualTimes[i].Groups[1].Value}=\"{text}\"");
                }
            }
        }
        Assert.Empty(failures);
    }

    private static string FirstDifference(string expected, string actual)
    {
        var i = 0;
        while (i < expected.Length && i < actual.Length && expected[i] == actual[i]) i++;
        var from = Math.Max(0, i - 20);
        string Excerpt(string s) => s.Substring(from, Math.Min(s.Length - from, 50));
        return $"expected …{Excerpt(expected)}… but got …{Excerpt(actual)}…";
    }
}
