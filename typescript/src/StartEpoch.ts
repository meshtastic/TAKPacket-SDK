/**
 * The 14-bit `start` timestamp carried in `TAKPacketV2.start_epoch_lo14`.
 *
 * A CoT event's `start` is an absolute instant, but the wire carries only its low
 * 14 bits as epoch seconds, because a value below 16384 fits a two-byte varint
 * where a full epoch-seconds value needs five. The 16384 second (4.55 hour)
 * window that implies is far wider than any plausible mesh transit, so a receiver
 * rebuilds the full value from its own clock.
 *
 * ## Every instant here is UTC
 *
 * Stated explicitly because the field carries bare seconds, so a timestamp built
 * in the wrong zone arrives as a well-formed value that is simply wrong by whole
 * hours, and no bound below can tell that from clock skew. ATAK formats CoT
 * instants against a UTC calendar fixed at class initialisation and appends a
 * literal `Z`, and its parser reads fixed character positions as GMT, so UTC is
 * the ecosystem invariant rather than this library's choice.
 *
 * ## Reconstruction picks the NEAREST candidate
 *
 * Splicing the low bits onto the receiver's current window, as
 * `(now & ~0x3fff) | lo14`, is wrong at a window boundary in *both* directions.
 * For a boundary B, a sender `start` of B read by a receiver whose clock sits at
 * B - 1 yields B - 16384: a freshly sent event that appears 4.55 hours old, which
 * no future-side sanity check would catch. Choosing the candidate nearest the
 * receiver's clock is exact in both directions whenever the true start is within
 * 8192 s of it, which subsumes any realistic transit plus clock skew.
 *
 * ## Out of range cannot be detected, so the sender must not send it
 *
 * A `start` further from the receiver's clock than half the window reconstructs
 * silently as a plausible recent time in the wrong window. There is no way to
 * notice after the fact, which is why {@link truncate} is only meaningful for a
 * `start` close to transmission, and why the bounds reject rather than clamp.
 */

/** 2^14. The field is defined as `start` mod this. */
export const MODULUS = 16384;

const HALF = MODULUS / 2;

/**
 * How far ahead of the receiver's clock a reconstructed start may be.
 *
 * Guards CLOCK SKEW. Transit only ever makes a report older, so a start ahead of
 * the receiver means the clocks disagree, and the budget is spent by BOTH of
 * them: a receiver 20 min slow reading a sender 20 min fast sees a 40 min
 * apparent offset while neither is far off alone. So an hour is about half an
 * hour of drift per device. A sender whose clock runs consistently fast still
 * orders its own reports correctly, because a constant offset cancels.
 */
export const FUTURE_TOLERANCE_SECONDS = 3600;

/**
 * How far behind the receiver's clock a reconstructed start may be.
 *
 * Loose for the same reason: a slow sender still orders its own reports
 * correctly, and a tight bound here would discard all of its traffic.
 */
export const PAST_LIMIT_SECONDS = 3600;

/*
 * What the bounds actually protect, and why widening them costs double.
 *
 * Reconstruction is exact while the true age is under HALF. Past that it lands a
 * full MODULUS out, which does NOT simply get rejected: a true age `d` in
 * [HALF, MODULUS) reconstructs as `d - MODULUS`, i.e. in the future, so the future
 * bound catches it only until `d` reaches `MODULUS - FUTURE_TOLERANCE_SECONDS`.
 * Measured, at an hour each way:
 *
 *     true age        0 ..  3600 s   exact
 *     true age     3601 .. 12783 s   rejected, caller falls back to decode time
 *     true age    12784 .. 19984 s   WRONGLY ACCEPTED, off by a full MODULUS
 *
 * That band is an ACCEPTED RISK rather than a hole the gates close, and it is
 * reached by sender-to-receiver clock DISAGREEMENT, not by transit. The sender
 * gate in the parser will not encode a `start` older than PAST_LIMIT_SECONDS, but
 * that bounds the start against the sender's OWN clock and says nothing about the
 * two clocks disagreeing; transit contributes seconds. So the gate supplies at
 * most an hour of the 12784 s gap: it buys margin, not closure.
 *
 * It is acceptable because drift cannot get there: breaking reconstruction takes
 * 2.28 h of disagreement and being wrongly accepted takes 3.55 h, while a device
 * reporting a live GNSS position has GNSS time by construction. What DOES get
 * there is a whole-hour offset from stamping local time as UTC, so a producer MUST
 * stamp `start` in UTC.
 */

/** The low 14 bits of `epochSeconds`, which is what goes on the wire. */
export function truncate(epochSeconds: number): number {
  // Non-negative remainder: `%` in JS keeps the sign of the dividend.
  return ((epochSeconds % MODULUS) + MODULUS) % MODULUS;
}

/**
 * Rebuild a full epoch-seconds `start` from `lo14`.
 *
 * @param lo14 the carried value, which must be in `[0, MODULUS)`
 * @param nowSeconds the receiver's reference instant, in epoch seconds
 * @returns the reconstructed `start`, or `undefined` when the result is not
 *          plausible and the caller should fall back to its decode time
 */
export function reconstruct(lo14: number, nowSeconds: number): number | undefined {
  if (!Number.isInteger(lo14) || lo14 < 0 || lo14 >= MODULUS) return undefined;
  // Fold the upper half into negatives so the nearest candidate is chosen rather
  // than the one in `now`'s window.
  let delta = (((lo14 - nowSeconds) % MODULUS) + MODULUS) % MODULUS;
  if (delta >= HALF) delta -= MODULUS;
  const start = nowSeconds + delta;
  const age = nowSeconds - start;
  if (age < -FUTURE_TOLERANCE_SECONDS || age > PAST_LIMIT_SECONDS) return undefined;
  return start;
}
