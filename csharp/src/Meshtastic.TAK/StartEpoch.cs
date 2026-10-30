namespace Meshtastic.TAK;

/// <summary>
/// The 14-bit <c>start</c> timestamp carried in <c>TAKPacketV2.start_epoch_lo14</c>.
/// </summary>
/// <remarks>
/// <para>A CoT event's <c>start</c> is an absolute instant, but the wire carries only its
/// low 14 bits as epoch seconds, because a value below 16384 fits a two-byte varint where
/// a full epoch-seconds value needs five. The 16384 second (4.55 hour) window that implies
/// is far wider than any plausible mesh transit, so a receiver rebuilds the full value
/// from its own clock.</para>
///
/// <para><b>Every instant here is UTC.</b> Stated explicitly because the field carries bare
/// seconds, so a timestamp built in the wrong zone arrives as a well-formed value that is
/// simply wrong by whole hours, and no bound below can tell that from clock skew. ATAK
/// formats CoT instants against a UTC calendar fixed at class initialisation and appends a
/// literal <c>Z</c>, and its parser reads fixed character positions as GMT, so UTC is the
/// ecosystem invariant rather than this library's choice.</para>
///
/// <para><b>Reconstruction picks the NEAREST candidate.</b> Splicing the low bits onto the
/// receiver's current window, as <c>(now &amp; ~0x3FFF) | lo14</c>, is wrong at a window
/// boundary in <i>both</i> directions. For a boundary B, a sender <c>start</c> of B read by
/// a receiver whose clock sits at B - 1 yields B - 16384: a freshly sent event that appears
/// 4.55 hours old, which no future-side sanity check would catch. Choosing the candidate
/// nearest the receiver's clock is exact in both directions whenever the true start is
/// within 8192 s of it, which subsumes any realistic transit plus clock skew.</para>
///
/// <para><b>Out of range cannot be detected, so the sender must not send it.</b> A
/// <c>start</c> further from the receiver's clock than half the window reconstructs
/// silently as a plausible recent time in the wrong window. There is no way to notice
/// after the fact, which is why <see cref="Truncate"/> is only meaningful for a
/// <c>start</c> close to transmission, and why the bounds reject rather than clamp.</para>
/// </remarks>
public static class StartEpoch
{
    /// <summary>2^14. The field is defined as <c>start</c> mod this.</summary>
    public const long Modulus = 16384L;

    private const long Half = Modulus / 2;

    /// <summary>
    /// How far ahead of the receiver's clock a reconstructed start may be.
    /// </summary>
    /// <remarks>
    /// Guards CLOCK SKEW. Transit only ever makes a report older, so a start ahead of the
    /// receiver means the clocks disagree, and the budget is spent by BOTH of them: a
    /// receiver 20 min slow reading a sender 20 min fast sees a 40 min apparent offset
    /// while neither is far off alone. So an hour is about half an hour of drift per
    /// device. A sender whose clock runs consistently fast still orders its own reports
    /// correctly, because a constant offset cancels.
    /// </remarks>
    public const long FutureToleranceSeconds = 3600L;

    /// <summary>
    /// How far behind the receiver's clock a reconstructed start may be.
    /// </summary>
    /// <remarks>
    /// Loose for the same reason: a slow sender still orders its own reports correctly,
    /// and a tight bound here would discard all of its traffic.
    /// </remarks>
    public const long PastLimitSeconds = 3600L;

    /*
     * What the bounds actually protect, and why widening them costs double.
     *
     * Reconstruction is exact while the true age is under Half. Past that it lands a full
     * Modulus out, which does NOT simply get rejected: a true age `d` in [Half, Modulus)
     * reconstructs as `d - Modulus`, i.e. in the future, so the future bound catches it
     * only until `d` reaches `Modulus - FutureToleranceSeconds`. Measured, at an hour each
     * way:
     *
     *     true age        0 ..  3600 s   exact
     *     true age     3601 .. 12783 s   rejected, caller falls back to decode time
     *     true age    12784 .. 19984 s   WRONGLY ACCEPTED, off by a full Modulus
     *
     * That band is an ACCEPTED RISK rather than a hole the gates close, and it is reached
     * by sender-to-receiver clock DISAGREEMENT, not by transit. The sender gate in the
     * parser will not encode a `start` older than PastLimitSeconds, but that bounds the
     * start against the sender's OWN clock and says nothing about the two clocks
     * disagreeing; transit contributes seconds. So the gate supplies at most an hour of the
     * 12784 s gap: it buys margin, not closure.
     *
     * It is acceptable because drift cannot get there: breaking reconstruction takes 2.28 h
     * of disagreement and being wrongly accepted takes 3.55 h, while a device reporting a
     * live GNSS position has GNSS time by construction. What DOES get there is a whole-hour
     * offset from stamping local time as UTC, so a producer MUST stamp `start` in UTC.
     */

    /// <summary>The low 14 bits of <paramref name="epochSeconds"/>, which is what goes on the wire.</summary>
    public static uint Truncate(long epochSeconds)
    {
        // Non-negative remainder: C# `%` keeps the sign of the dividend.
        var m = epochSeconds % Modulus;
        if (m < 0) m += Modulus;
        return (uint)m;
    }

    /// <summary>
    /// Rebuild a full epoch-seconds <c>start</c> from <paramref name="lo14"/>.
    /// </summary>
    /// <param name="lo14">The carried value, which must be in <c>[0, Modulus)</c>.</param>
    /// <param name="nowSeconds">The receiver's reference instant, in epoch seconds.</param>
    /// <returns>
    /// The reconstructed <c>start</c> in epoch seconds, or <c>null</c> when the result is
    /// not plausible and the caller should fall back to its decode time.
    /// </returns>
    public static long? Reconstruct(long lo14, long nowSeconds)
    {
        if (lo14 < 0 || lo14 >= Modulus) return null;
        // Fold the upper half into negatives so the nearest candidate is chosen rather
        // than the one in `now`'s window.
        var delta = (lo14 - nowSeconds) % Modulus;
        if (delta < 0) delta += Modulus;
        if (delta >= Half) delta -= Modulus;
        var start = nowSeconds + delta;
        var age = nowSeconds - start;
        if (age < -FutureToleranceSeconds || age > PastLimitSeconds) return null;
        return start;
    }
}
