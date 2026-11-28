namespace Meshtastic.TAK.Tests;

/// <summary>
/// <c>stale_seconds</c> is computed as Kotlin's parser computes it,
/// <c>stale.epochSeconds - time.epochSeconds</c>: each timestamp is rounded down to
/// whole epoch seconds before subtracting. Every expected value below is what the
/// Kotlin parser produces for the same XML, as Kotlin's own StaleSecondsTest checks.
/// Truncating or rounding the difference instead disagrees with Kotlin whenever the
/// two timestamps carry different fractions of a second.
/// </summary>
public class StaleSecondsTests
{
    private readonly CotXmlParser _parser = new();

    private static string Event(string time, string stale) =>
        $"<event version=\"2.0\" uid=\"STALE-1\" type=\"a-f-G-U-C\" how=\"m-g\" " +
        $"time=\"{time}\" start=\"{time}\" stale=\"{stale}\">" +
        "<point lat=\"38.8895\" lon=\"-77.0353\" hae=\"0\" ce=\"9999999\" le=\"9999999\"/>" +
        "<detail></detail></event>";

    [Theory]
    // 1.2 s and 0.5 s apart but 2 and 1 whole seconds: truncating the difference gives 1 and 0.
    [InlineData("2026-01-01T00:00:00.900Z", "2026-01-01T00:00:02.100Z", 2u)]
    [InlineData("2026-01-01T00:00:00.500Z", "2026-01-01T00:00:01.000Z", 1u)]
    // 1.8 s and 0.999 s apart: rounding the difference gives 2 and 1.
    [InlineData("2026-01-01T00:00:00.100Z", "2026-01-01T00:00:01.900Z", 1u)]
    [InlineData("2026-01-01T00:00:00.000Z", "2026-01-01T00:00:00.999Z", 0u)]
    [InlineData("2026-01-01T00:00:00Z", "2026-01-01T00:10:00Z", 600u)]
    [InlineData("2026-01-01T00:00:02.100Z", "2026-01-01T00:00:00.900Z", 0u)] // stale before time
    [InlineData("2026-01-01T00:00:00.900Z", "2026-01-01T00:00:00.900Z", 0u)]
    // Rounded down, not toward zero: -2 and -1 whole seconds.
    [InlineData("1969-12-31T23:59:58.500Z", "1969-12-31T23:59:59.400Z", 1u)]
    [InlineData("2026-01-01T01:00:00.900+01:00", "2026-01-01T00:00:02.100Z", 2u)]
    // Past 68 years every binding caps at int.MaxValue, because the Kotlin binding reads a
    // larger value back as a negative Int.
    [InlineData("2026-01-01T00:00:00Z", "2100-01-01T00:00:00Z", 2147483647u)]
    [InlineData("2026-01-01T00:00:00Z", "2200-01-01T00:00:00Z", 2147483647u)]
    public void StaleSeconds_SubtractsWholeEpochSecondsLikeKotlin(string time, string stale, uint expected)
    {
        Assert.Equal(expected, _parser.Parse(Event(time, stale)).StaleSeconds);
    }
}
