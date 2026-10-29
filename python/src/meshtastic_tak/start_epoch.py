"""The 14-bit ``start`` timestamp carried in ``TAKPacketV2.start_epoch_lo14``.

A CoT event's ``start`` is an absolute instant, but the wire carries only its low
14 bits as epoch seconds, because a value below 16384 fits a two-byte varint
where a full epoch-seconds value needs five. The 16384 second (4.55 hour) window
that implies is far wider than any plausible mesh transit, so a receiver rebuilds
the full value from its own clock.

Every instant here is UTC. Stated explicitly because the field carries bare
seconds, so a timestamp built in the wrong zone arrives as a well-formed value
that is simply wrong by whole hours, and no bound below can tell that from clock
skew. ATAK formats CoT instants with a literal ``Z`` against a UTC calendar fixed
at class initialisation, and its parser reads fixed character positions as GMT, so
UTC is the ecosystem invariant rather than this library's choice.

Reconstruction picks the NEAREST candidate
------------------------------------------
Splicing the low bits onto the receiver's current window, as
``(now & ~0x3FFF) | lo14``, is wrong at a window boundary in *both* directions.
For a boundary B, a sender ``start`` of B read by a receiver whose clock sits at
B - 1 yields B - 16384: a freshly sent event that appears 4.55 hours old, which no
future-side sanity check would catch. Choosing the candidate nearest the
receiver's clock is exact in both directions whenever the true start is within
8192 s of it, which subsumes any realistic transit plus clock skew.

Out of range cannot be detected, so the sender must not send it
--------------------------------------------------------------
A ``start`` further from the receiver's clock than half the window reconstructs
silently as a plausible recent time in the wrong window. There is no way to
notice this after the fact, which is why :func:`truncate` is only meaningful for
a ``start`` close to transmission, and why the bounds below reject rather than
clamp.
"""

from __future__ import annotations

#: 2^14. The field is defined as ``start`` mod this.
MODULUS = 16384

_HALF = MODULUS // 2

#: How far ahead of the receiver's clock a reconstructed start may be.
#:
#: Guards CLOCK SKEW. Transit only ever makes a report older, so a start ahead of
#: the receiver means the clocks disagree, and the budget is spent by BOTH of
#: them: a receiver 20 min slow reading a sender 20 min fast sees a 40 min
#: apparent offset while neither is far off alone. So an hour is about half an
#: hour of drift per device, which is the figure to reason about. A sender whose
#: clock runs consistently fast still orders its own reports correctly, because a
#: constant offset cancels, and rejecting those starts loses that ordering for
#: nothing.
FUTURE_TOLERANCE_SECONDS = 3600

#: How far behind the receiver's clock a reconstructed start may be.
#:
#: Loose for the same reason: a slow sender still orders its own reports
#: correctly, and a tight bound here would discard all of its traffic.
PAST_LIMIT_SECONDS = 3600

# What the bounds actually protect, and why widening them costs double.
#
# Reconstruction is exact while the true age is under _HALF. Past that it lands a
# full MODULUS out, which does NOT simply get rejected: a true age `d` in
# [_HALF, MODULUS) reconstructs as `d - MODULUS`, i.e. in the future, so the
# future bound catches it only until `d` reaches
# `MODULUS - FUTURE_TOLERANCE_SECONDS`. Measured, at an hour each way:
#
#     true age        0 ..  3600 s   exact
#     true age     3601 .. 12783 s   rejected, caller falls back to decode time
#     true age    12784 .. 19984 s   WRONGLY ACCEPTED, off by a full MODULUS
#
# so the band is [MODULUS - FUTURE_TOLERANCE_SECONDS, MODULUS + PAST_LIMIT_SECONDS].
#
# That band is an ACCEPTED RISK, not a hole the gates close, and it is reached by
# sender-to-receiver clock DISAGREEMENT rather than by transit. The sender gate in
# the parser will not encode a `start` older than PAST_LIMIT_SECONDS, but that
# bounds the start against the sender's OWN clock and says nothing about the two
# clocks disagreeing. Transit contributes seconds. So the gate supplies at most an
# hour of the 12784 s gap: it buys margin, not closure.
#
# It is acceptable because drift cannot get there. Breaking reconstruction at all
# takes 2.28 h of disagreement, being wrongly accepted takes 3.55 h, and a device
# reporting a live GNSS position has GNSS time by construction. What DOES get
# there is a whole-hour offset from stamping local time as if it were UTC: for a
# fresh `start`, 4 h and 5 h land inside the band while 3 h and 6 h are merely
# rejected. So a producer MUST stamp `start` in UTC.


def truncate(epoch_seconds: int) -> int:
    """Return the low 14 bits of ``epoch_seconds``, which is what goes on the wire."""
    return epoch_seconds % MODULUS


def reconstruct(lo14: int, now_seconds: int) -> int | None:
    """Rebuild a full epoch-seconds ``start`` from ``lo14``.

    Args:
        lo14: The carried value, which must be in ``[0, MODULUS)``.
        now_seconds: The receiver's reference instant, in epoch seconds.

    Returns:
        The reconstructed ``start`` in epoch seconds, or ``None`` when the result
        is not plausible and the caller should fall back to its decode time.
    """
    if lo14 < 0 or lo14 >= MODULUS:
        return None
    # Non-negative remainder, then fold the upper half into negatives so the
    # nearest candidate is chosen rather than the one in `now`'s window.
    delta = (lo14 - now_seconds) % MODULUS
    if delta >= _HALF:
        delta -= MODULUS
    start = now_seconds + delta
    age = now_seconds - start
    if age < -FUTURE_TOLERANCE_SECONDS or age > PAST_LIMIT_SECONDS:
        return None
    return start
