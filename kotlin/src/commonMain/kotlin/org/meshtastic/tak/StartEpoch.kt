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
     * This guards CLOCK SKEW and nothing else. Transit only ever makes a report
     * older, so a start ahead of the receiver means the two clocks disagree, and a
     * sender whose clock runs consistently fast still orders its own reports
     * correctly because the offset cancels. Rejecting such a start loses that
     * ordering outright, and the fallback to decode time is silent, so this is
     * deliberately generous rather than tight.
     *
     * It does NOT guard a wrong-window reconstruction. Nearest-candidate selection is
     * exact at a window boundary, and an out-of-window value lands a full [MODULUS]
     * away, so any bound far below that catches it. (An earlier revision of this file
     * claimed otherwise; that was left over from a bit-splice implementation whose
     * error really did land just ahead of the clock.)
     */
    const val FUTURE_TOLERANCE_SECONDS: Long = 60L

    /**
     * How far behind the receiver's clock a reconstructed start may be.
     *
     * Loose, because a sender whose clock runs slow still orders its own
     * reports correctly and a tight bound here would discard all of its
     * traffic. Kept well inside [HALF] so it cannot be confused with a
     * wrong-window reconstruction.
     */
    const val PAST_LIMIT_SECONDS: Long = 3600L

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
