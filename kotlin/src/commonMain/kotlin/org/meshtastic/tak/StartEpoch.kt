package org.meshtastic.tak

/**
 * The 14-bit `start` timestamp carried in `TAKPacketV2.start_epoch_lo14`.
 *
 * A CoT event's `start` is an absolute instant, but the wire carries only its
 * low 14 bits as epoch seconds, because a value below 16384 fits a two-byte
 * varint where a full epoch-seconds value needs five. The 16384 second
 * (4.55 hour) window that implies is far wider than any plausible mesh
 * transit, so a receiver rebuilds the full value from its own clock.
 *
 * ## Reconstruction picks the NEAREST candidate
 *
 * Splicing the low bits onto the receiver's current window, as
 * `(now and 0x3FFF.inv()) or lo14`, is wrong at a window boundary in *both*
 * directions. For a boundary B, a sender `start` of B read by a receiver whose
 * clock sits at B - 1 yields B - 16384: a freshly sent event that appears
 * 4.55 hours old, which no future-side sanity check would catch. Choosing the
 * candidate nearest the receiver's clock is exact in both directions whenever
 * the true start is within 8192 s of it, which subsumes any realistic transit
 * plus clock skew.
 *
 * ## Out of range cannot be detected, so the sender must not send it
 *
 * A `start` further from the receiver's clock than half the window
 * reconstructs silently as a plausible recent time in the wrong window. There
 * is no way to notice this after the fact, which is why [truncate] is only
 * meaningful for a `start` close to transmission, and why the bounds below
 * reject rather than clamp.
 */
internal object StartEpoch {
    /** 2^14. The field is defined as `start` mod this. */
    const val MODULUS: Long = 16384L

    private const val HALF: Long = MODULUS / 2

    /**
     * How far ahead of the receiver's clock a reconstructed start may be.
     *
     * Guards CLOCK SKEW. Transit only ever makes a report older, so a start ahead of
     * the receiver means the clocks disagree, and the budget is spent by BOTH of them:
     * a receiver 20 min slow reading a sender 20 min fast sees a 40 min apparent
     * offset while neither is far off alone. So an hour is about half an hour of drift
     * per device, which is the figure to reason about. A sender whose clock runs
     * consistently fast still orders its own reports correctly, because a constant
     * offset cancels, and rejecting those starts loses that ordering for nothing.
     *
     * This value also bounds how far a recovered `start` can pull CoT `time`, which
     * [CotXmlBuilder] writes from it. That is the same exposure a TAK Server peer
     * already has, since clients stamp their own `time`; here it is capped at an hour.
     */
    const val FUTURE_TOLERANCE_SECONDS: Long = 3600L

    /**
     * How far behind the receiver's clock a reconstructed start may be.
     *
     * Loose for the same reason: a slow sender still orders its own reports correctly,
     * and a tight bound here would discard all of its traffic.
     */
    const val PAST_LIMIT_SECONDS: Long = 3600L

    /*
     * What the bounds actually protect, and why widening them costs double.
     *
     * Reconstruction is exact while the true age is under [HALF]. Past that it lands a
     * full [MODULUS] out, which does NOT simply get rejected: a true age `d` in
     * [HALF, MODULUS) reconstructs as `d - MODULUS`, i.e. in the future, so the future
     * bound catches it only until `d` reaches `MODULUS - FUTURE_TOLERANCE_SECONDS`.
     * Measured, at an hour each way:
     *
     *     true age        0 ..  3600 s   exact
     *     true age     3601 .. 12783 s   rejected, caller falls back to decode time
     *     true age    12784 .. 19984 s   WRONGLY ACCEPTED, off by a full MODULUS
     *
     * so the band is [MODULUS - FUTURE_TOLERANCE_SECONDS, MODULUS + PAST_LIMIT_SECONDS]
     * and it cannot be closed from the receiver alone.
     *
     * What keeps it unreachable is the SENDER gate: [CotXmlParser] will not encode a
     * `start` older than [PAST_LIMIT_SECONDS], so arriving in that band takes transit
     * (net of skew) of `MODULUS - 2 * bound`. At an hour that is 9184 s, 2.55 h, which
     * no mesh delivers. Note both gates use the same constant, so widening costs margin
     * twice over: at two hours each way the required transit falls to 1984 s, 33 min,
     * which is no longer implausible. Check a wider bound against that figure, not
     * against [HALF].
     */

    /** The low 14 bits of [epochSeconds], which is what goes on the wire. */
    fun truncate(epochSeconds: Long): Int = epochSeconds.mod(MODULUS).toInt()

    /**
     * Rebuild a full epoch-seconds `start` from [lo14], or null when the result
     * is not plausible and the caller should fall back to its decode time.
     */
    fun reconstruct(
        lo14: Int,
        nowSeconds: Long,
    ): Long? {
        if (lo14 < 0 || lo14 >= MODULUS) return null
        // Non-negative remainder, then fold the upper half into negatives so the
        // nearest candidate is chosen rather than the one in `now`'s window.
        var delta = (lo14.toLong() - nowSeconds).mod(MODULUS)
        if (delta >= HALF) delta -= MODULUS
        val start = nowSeconds + delta
        val age = nowSeconds - start
        return when {
            age < -FUTURE_TOLERANCE_SECONDS -> null
            age > PAST_LIMIT_SECONDS -> null
            else -> start
        }
    }
}
