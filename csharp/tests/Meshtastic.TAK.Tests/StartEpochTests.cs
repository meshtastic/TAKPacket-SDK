using System.Text.RegularExpressions;
using Google.Protobuf;
using Meshtastic.TAK;
using Meshtastic.Protobufs;

namespace Meshtastic.TAK.Tests;

/// <summary>
/// Coverage for the <c>start</c> timestamp, mirroring the Kotlin, Python and
/// TypeScript bindings.
/// </summary>
/// <remarks>
/// The shared fixtures cannot provide this: every one is stamped months in the past,
/// so the parser correctly refuses to encode its <c>start</c> and the builder falls
/// back to the decode instant, which makes the whole corpus pass whether this works
/// or not. So the timestamps here are minted at run time rather than committed: a
/// fixture written today would age out of the bound within the hour and start passing
/// vacuously.
/// </remarks>
public class StartEpochTests
{
    private const long Boundary = StartEpoch.Modulus * 109_000L;

    private static string Iso(long epochSeconds) =>
        DateTimeOffset.FromUnixTimeSeconds(epochSeconds).ToString("yyyy-MM-ddTHH:mm:ss.fffZ");

    private static string Cot(string start, string stale) =>
        "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
        "<event version=\"2.0\" uid=\"ATOM-START-RT\" type=\"a-f-G-U-C\" how=\"m-g\" " +
        $"time=\"{start}\" start=\"{start}\" stale=\"{stale}\">" +
        "<point lat=\"38.8895\" lon=\"-77.0353\" hae=\"0.0\" ce=\"9999999.0\" le=\"9999999.0\"/>" +
        "<detail><contact callsign=\"ALPHA-1\"/></detail></event>";

    private static string? Attr(string xml, string name) =>
        Regex.Match(xml, $"{name}=\"([^\"]+)\"") is { Success: true } m ? m.Groups[1].Value : null;

    private static long NowSec() => DateTimeOffset.UtcNow.ToUnixTimeSeconds();

    // ------------------------------------------------------------ pure arithmetic

    [Fact]
    public void RoundTripsAStartEqualToNow()
    {
        var now = Boundary + 1234;
        Assert.Equal(now, StartEpoch.Reconstruct(StartEpoch.Truncate(now), now));
    }

    [Fact]
    public void RoundTripsAStartAFewSecondsOld()
    {
        var now = Boundary + 1234;
        var start = now - 40;
        Assert.Equal(start, StartEpoch.Reconstruct(StartEpoch.Truncate(start), now));
    }

    [Fact]
    public void IsExactAtABoundaryWithTheSenderAheadOfTheReceiver()
    {
        // start = B, receiver still at B - 1. The naive splice yields B - 16384.
        Assert.Equal(Boundary, StartEpoch.Reconstruct(StartEpoch.Truncate(Boundary), Boundary - 1));
    }

    [Fact]
    public void IsExactAtABoundaryWithThePacketCrossingInFlight()
    {
        // start = B - 1, receiver already at B. The naive splice yields B + 16383.
        Assert.Equal(Boundary - 1, StartEpoch.Reconstruct(StartEpoch.Truncate(Boundary - 1), Boundary));
    }

    [Fact]
    public void RejectsAStartImplausiblyFarInTheFuture()
    {
        var now = Boundary + 1234;
        var future = now + StartEpoch.FutureToleranceSeconds + 10;
        Assert.Null(StartEpoch.Reconstruct(StartEpoch.Truncate(future), now));
    }

    [Fact]
    public void RejectsAStartBeyondThePastLimit()
    {
        var now = Boundary + 8000;
        var tooOld = now - StartEpoch.PastLimitSeconds - 10;
        Assert.Null(StartEpoch.Reconstruct(StartEpoch.Truncate(tooOld), now));
    }

    [Fact]
    public void AcceptsAStartJustInsideThePastLimit()
    {
        var now = Boundary + 8000;
        var old = now - StartEpoch.PastLimitSeconds;
        Assert.Equal(old, StartEpoch.Reconstruct(StartEpoch.Truncate(old), now));
    }

    [Fact]
    public void RejectsAValueOutsideTheFieldRange()
    {
        Assert.Null(StartEpoch.Reconstruct(-1, Boundary));
        Assert.Null(StartEpoch.Reconstruct(StartEpoch.Modulus, Boundary));
    }

    [Fact]
    public void TruncatesToTheLow14Bits()
    {
        Assert.Equal(0u, StartEpoch.Truncate(Boundary));
        Assert.Equal(1u, StartEpoch.Truncate(Boundary + 1));
        Assert.Equal((uint)(StartEpoch.Modulus - 1), StartEpoch.Truncate(Boundary - 1));
    }

    [Fact]
    public void DocumentsTheBandWhereReconstructionIsWronglyAccepted()
    {
        // Not a bug report: the band cannot be closed from the receiver alone, and the
        // sender gate is what keeps it out of reach. Pinned so a bound change has to
        // confront it rather than silently widen it.
        var now = Boundary + 8000;
        var low = StartEpoch.Modulus - StartEpoch.FutureToleranceSeconds;
        var high = StartEpoch.Modulus + StartEpoch.PastLimitSeconds;
        Assert.Equal(12784L, low);
        Assert.Equal(19984L, high);
        foreach (var age in new[] { low, (low + high) / 2, high })
        {
            var trueStart = now - age;
            var got = StartEpoch.Reconstruct(StartEpoch.Truncate(trueStart), now);
            Assert.NotNull(got);
            Assert.Equal(trueStart + StartEpoch.Modulus, got);
        }
        // Just outside the band is rejected rather than wrong, on BOTH sides. The far
        // edge is the one a widened bound would walk into, so pinning only the near edge
        // leaves the comment above unenforced.
        Assert.Null(StartEpoch.Reconstruct(StartEpoch.Truncate(now - (low - 1)), now));
        Assert.Null(StartEpoch.Reconstruct(StartEpoch.Truncate(now - (high + 1)), now));
    }

    // -------------------------------------------------------------- round trip

    [Fact]
    public void ARecentStartSurvivesTheRoundTrip()
    {
        // 90 s old: inside the bound, and far enough from now that a fallback to the
        // decode instant is unambiguous rather than a rounding tie.
        var start = NowSec() - 90;
        var pkt = new CotXmlParser().Parse(Cot(Iso(start), Iso(start + 300)));
        Assert.True(pkt.HasStartEpochLo14, "parser did not capture start");
        Assert.Equal(StartEpoch.Truncate(start), pkt.StartEpochLo14);

        // The rebuilt event must carry the ORIGINAL instant, not the decode instant.
        var rebuilt = new CotXmlBuilder().Build(pkt);
        Assert.Equal(Iso(start), Attr(rebuilt, "start"));
        Assert.Equal(Iso(start), Attr(rebuilt, "time"));
    }

    [Fact]
    public void ALo14OfZeroStaysPresentRatherThanAbsent()
    {
        // `start` mod 16384 is 0 for one second in every 16384, i.e. once every 4.55 h,
        // and that second is the only one where a genuine value collides with proto3's
        // default. Explicit presence is what separates them. Lose it and a real start
        // reads as absent once every 4.55 hours, silently, and no fixture can catch it.
        var zero = new TAKPacketV2 { Uid = "ATOM-LO14-ZERO", CotTypeStr = "a-f-G-U-C", StartEpochLo14 = 0 };
        Assert.True(zero.HasStartEpochLo14);
        var rt = TAKPacketV2.Parser.ParseFrom(zero.ToByteArray());
        Assert.True(rt.HasStartEpochLo14, "a lo14 of 0 came back absent: presence was lost");
        Assert.Equal(0u, rt.StartEpochLo14);

        // The other direction, so this pins presence rather than just non-zeroness.
        var absent = new TAKPacketV2 { Uid = "ATOM-LO14-ABSENT", CotTypeStr = "a-f-G-U-C" };
        var rtAbsent = TAKPacketV2.Parser.ParseFrom(absent.ToByteArray());
        Assert.False(rtAbsent.HasStartEpochLo14, "an absent start came back as 0");
    }

    [Fact]
    public void AnAbsentStartFallsBackToTheDecodeInstant()
    {
        var pkt = new TAKPacketV2 { Uid = "ATOM-NO-START", CotTypeStr = "a-f-G-U-C" };
        Assert.False(pkt.HasStartEpochLo14);
        var before = NowSec();
        var emitted = Attr(new CotXmlBuilder().Build(pkt), "start");
        Assert.NotNull(emitted);
        var delta = DateTimeOffset.Parse(emitted!).ToUnixTimeSeconds() - before;
        Assert.InRange(delta, 0, 10);
    }

    [Fact]
    public void AStartPastTheSenderGateIsNotEncoded()
    {
        // Well inside the 8192 s half-window, so this exercises the sender gate and not
        // the wrap. That gate is the only thing keeping a receiver out of the band above.
        var tooOld = NowSec() - StartEpoch.PastLimitSeconds - 60;
        var pkt = new CotXmlParser().Parse(Cot(Iso(tooOld), Iso(tooOld + 300)));
        Assert.False(pkt.HasStartEpochLo14);
    }

    [Fact]
    public void APresentButOutOfRangeStartFallsBackRatherThanBeingUsed()
    {
        // The receiver-side counterpart to the sender gate: present, refused, and
        // travelling through the builder. 7200 s is inside the half-window, so this is
        // the bound rather than the wrap. Not dropped: uid, type and position are fine.
        var nowSec = NowSec();
        var pkt = new TAKPacketV2
        {
            Uid = "ATOM-OUT-OF-RANGE",
            CotTypeStr = "a-f-G-U-C",
            StartEpochLo14 = StartEpoch.Truncate(nowSec - 7200),
        };
        var emitted = Attr(new CotXmlBuilder().Build(pkt), "start");
        Assert.NotNull(emitted);
        var delta = DateTimeOffset.Parse(emitted!).ToUnixTimeSeconds() - nowSec;
        Assert.InRange(delta, 0, 10);
    }

    [Fact]
    public void TheRebuiltTimestampsAreUtc()
    {
        var start = NowSec() - 90;
        var rebuilt = new CotXmlBuilder().Build(
            new CotXmlParser().Parse(Cot(Iso(start), Iso(start + 300))));
        foreach (var name in new[] { "time", "start", "stale" })
        {
            Assert.EndsWith("Z", Attr(rebuilt, name));
        }
    }

    [Fact]
    public void AnOffsetBearingStartIsConvertedRatherThanReadPositionally()
    {
        // Same instant written with a +04:00 offset. Both must encode the same bits,
        // which holds because we resolve the instant instead of reading fixed character
        // positions the way ATAK's fast path does.
        var start = NowSec() - 90;
        var stale = Iso(start + 300);
        var asOffset = DateTimeOffset.FromUnixTimeSeconds(start)
            .ToOffset(TimeSpan.FromHours(4))
            .ToString("yyyy-MM-ddTHH:mm:ss.fffzzz");

        var fromZ = new CotXmlParser().Parse(Cot(Iso(start), stale));
        var fromOffset = new CotXmlParser().Parse(Cot(asOffset, stale));
        Assert.True(fromOffset.HasStartEpochLo14, "offset form was not encoded");
        Assert.Equal(fromZ.StartEpochLo14, fromOffset.StartEpochLo14);
    }

    [Theory]
    [InlineData("yyyy-MM-ddTHH:mm:ss.fff")]
    [InlineData("yyyy-MM-ddTHH:mm:ss")]
    // Space rather than 'T'. Regression: the designator check used to be a text search
    // for 'Z'/'+'/a trailing '-', and on this shape IndexOf('T') is -1, so the final
    // comparison was false, the guard never fired, and TryParse had already applied the
    // host zone. Under TZ=America/New_York that encoded a value 14400 s off, silently.
    // Detection now reads DateTime.Kind, which does not depend on the separator.
    [InlineData("yyyy-MM-dd HH:mm:ss")]
    [InlineData("yyyy-MM-dd HH:mm:ss.fff")]
    public void AStartWithNoZoneDesignatorIsNotGuessedAt(string format)
    {
        // TryParse would apply the HOST's local zone, so this would be wrong by the
        // runner's offset, silently and differently per machine. ATAK would read it as
        // GMT. We refuse either way.
        var start = NowSec() - 90;
        var noZone = DateTimeOffset.FromUnixTimeSeconds(start).ToString(format);
        var pkt = new CotXmlParser().Parse(Cot(noZone, Iso(start + 300)));
        Assert.False(pkt.HasStartEpochLo14, $"'{noZone}' ({format}) was encoded anyway");
    }

    [Fact]
    public void ALowercaseZIsAValidDesignatorAndIsAccepted()
    {
        // The other half of the same regression: the old text search looked for an
        // uppercase 'Z' only, so a lowercase one fell through to the trailing-'-' test
        // and a perfectly good UTC stamp was refused. Reading Kind accepts it, and it
        // must agree with the uppercase form rather than merely be non-null.
        var start = NowSec() - 90;
        var lower = DateTimeOffset.FromUnixTimeSeconds(start)
            .ToString("yyyy-MM-ddTHH:mm:ss.fff") + "z";
        var fromLower = new CotXmlParser().Parse(Cot(lower, Iso(start + 300)));
        Assert.True(fromLower.HasStartEpochLo14, $"'{lower}' was refused");
        var fromUpper = new CotXmlParser().Parse(Cot(Iso(start), Iso(start + 300)));
        Assert.Equal(fromUpper.StartEpochLo14, fromLower.StartEpochLo14);
    }

    [Fact]
    public void StaleIsMeasuredFromArrivalRatherThanTheRecoveredStart()
    {
        // An honest `start` must not shorten an event's life. Ten minutes in transit
        // against a 60 s window would otherwise arrive already expired.
        var start = NowSec() - 600;
        var rebuilt = new CotXmlBuilder().Build(
            new CotXmlParser().Parse(Cot(Iso(start), Iso(start + 60))));
        var stale = DateTimeOffset.Parse(Attr(rebuilt, "stale")!);
        Assert.True(stale > DateTimeOffset.UtcNow, $"stale is already in the past: {stale}");
    }
}
