"""Coverage for the ``start`` timestamp, mirroring the Kotlin binding's tests.

The 47 shared fixtures cannot provide this. Every one is stamped months in the
past, so the parser correctly refuses to encode its ``start`` (outside the bound)
and the builder falls back to the decode instant. That makes the whole fixture
corpus pass whether this feature works or not. So the timestamps here are minted
at run time rather than committed: a fixture written today would age out of the
bound within the hour and start passing vacuously.
"""

from __future__ import annotations

import re
from datetime import datetime, timedelta, timezone

from meshtastic_tak import atak_pb2, start_epoch
from meshtastic_tak.cot_xml_builder import CotXmlBuilder
from meshtastic_tak.cot_xml_parser import CotXmlParser

_BOUNDARY = start_epoch.MODULUS * 109_000


def _cot(start: str, stale: str) -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<event version="2.0" uid="ATOM-START-RT" type="a-f-G-U-C" how="m-g" '
        f'time="{start}" start="{start}" stale="{stale}">'
        '<point lat="38.8895" lon="-77.0353" hae="0.0" ce="9999999.0" le="9999999.0"/>'
        '<detail><contact callsign="ALPHA-1"/></detail></event>'
    )


def _iso(dt: datetime) -> str:
    return dt.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"


def _attr(xml: str, name: str) -> str | None:
    m = re.search(rf'{name}="([^"]+)"', xml)
    return m.group(1) if m else None


# --------------------------------------------------------------- pure arithmetic

def test_round_trips_a_start_equal_to_now():
    now = _BOUNDARY + 1234
    assert start_epoch.reconstruct(start_epoch.truncate(now), now) == now


def test_round_trips_a_start_a_few_seconds_old():
    now = _BOUNDARY + 1234
    start = now - 40
    assert start_epoch.reconstruct(start_epoch.truncate(start), now) == start


def test_at_a_window_boundary_with_the_sender_ahead_of_the_receiver():
    # start = B, receiver still at B - 1. The naive splice yields B - 16384.
    assert start_epoch.reconstruct(start_epoch.truncate(_BOUNDARY), _BOUNDARY - 1) == _BOUNDARY


def test_at_a_window_boundary_with_the_packet_crossing_in_flight():
    # start = B - 1, receiver already at B. The naive splice yields B + 16383.
    assert start_epoch.reconstruct(start_epoch.truncate(_BOUNDARY - 1), _BOUNDARY) == _BOUNDARY - 1


def test_rejects_a_start_implausibly_far_in_the_future():
    now = _BOUNDARY + 1234
    future = now + start_epoch.FUTURE_TOLERANCE_SECONDS + 10
    assert start_epoch.reconstruct(start_epoch.truncate(future), now) is None


def test_rejects_a_start_beyond_the_past_limit():
    now = _BOUNDARY + 8000
    too_old = now - start_epoch.PAST_LIMIT_SECONDS - 10
    assert start_epoch.reconstruct(start_epoch.truncate(too_old), now) is None


def test_accepts_a_start_just_inside_the_past_limit():
    now = _BOUNDARY + 8000
    old = now - start_epoch.PAST_LIMIT_SECONDS
    assert start_epoch.reconstruct(start_epoch.truncate(old), now) == old


def test_rejects_a_value_outside_the_field_range():
    assert start_epoch.reconstruct(-1, _BOUNDARY) is None
    assert start_epoch.reconstruct(start_epoch.MODULUS, _BOUNDARY) is None


def test_truncate_is_the_low_14_bits():
    assert start_epoch.truncate(_BOUNDARY) == 0
    assert start_epoch.truncate(_BOUNDARY + 1) == 1
    assert start_epoch.truncate(_BOUNDARY - 1) == start_epoch.MODULUS - 1


def test_documents_the_band_where_reconstruction_is_wrongly_accepted():
    # Not a bug report: the band cannot be closed from the receiver alone, and the
    # sender gate is what keeps it out of reach. Pinned so a bound change has to
    # confront it rather than silently widen it.
    now = _BOUNDARY + 8000
    low = start_epoch.MODULUS - start_epoch.FUTURE_TOLERANCE_SECONDS
    high = start_epoch.MODULUS + start_epoch.PAST_LIMIT_SECONDS
    assert (low, high) == (12784, 19984)
    for age in (low, (low + high) // 2, high):
        true_start = now - age
        got = start_epoch.reconstruct(start_epoch.truncate(true_start), now)
        assert got is not None, f"age {age} should be (wrongly) accepted"
        assert got == true_start + start_epoch.MODULUS, "off by exactly one modulus"
    # Just outside the band is rejected rather than wrong, on BOTH sides. The far edge
    # is the one a widened bound would walk into, so pinning only the near edge leaves
    # the comment above unenforced.
    assert start_epoch.reconstruct(start_epoch.truncate(now - (low - 1)), now) is None
    assert start_epoch.reconstruct(start_epoch.truncate(now - (high + 1)), now) is None


# ----------------------------------------------------------------- round trip

def test_a_recent_start_survives_the_round_trip():
    # 90 s old: comfortably inside the bound, and far enough from now that a
    # fallback to the decode instant is unambiguous rather than a rounding tie.
    start = datetime.now(timezone.utc) - timedelta(seconds=90)
    start = start.replace(microsecond=0)
    pkt = CotXmlParser().parse(_cot(_iso(start), _iso(start + timedelta(seconds=300))))
    assert pkt.HasField("start_epoch_lo14"), "parser did not capture start"
    assert pkt.start_epoch_lo14 == start_epoch.truncate(int(start.timestamp()))

    rebuilt = CotXmlBuilder().build(pkt)
    # The rebuilt event must carry the ORIGINAL instant, not the decode instant.
    assert _attr(rebuilt, "start") == _iso(start), rebuilt
    assert _attr(rebuilt, "time") == _iso(start), rebuilt


def test_an_absent_start_falls_back_to_the_decode_instant():
    pkt = atak_pb2.TAKPacketV2(uid="ATOM-NO-START", cot_type_str="a-f-G-U-C")
    assert not pkt.HasField("start_epoch_lo14")
    before = int(datetime.now(timezone.utc).timestamp())
    emitted = _attr(CotXmlBuilder().build(pkt), "start")
    assert emitted is not None
    delta = int(datetime.fromisoformat(emitted.replace("Z", "+00:00")).timestamp()) - before
    assert 0 <= delta <= 10, emitted


def test_a_lo14_of_zero_survives_as_present_not_absent():
    # `start` mod 16384 is 0 for one second in every 16384, i.e. once every 4.55 h,
    # and that second is the only one where a genuine value collides with proto3's
    # default. Explicit presence is what separates them, which is why the field is
    # `optional`. Drop that and a real start reads as absent once every 4.55 hours,
    # silently, and the fixture corpus cannot catch it.
    pkt = atak_pb2.TAKPacketV2(uid="ATOM-LO14-ZERO", cot_type_str="a-f-G-U-C")
    pkt.start_epoch_lo14 = 0
    assert pkt.HasField("start_epoch_lo14")
    rt = atak_pb2.TAKPacketV2(); rt.ParseFromString(pkt.SerializeToString())
    assert rt.HasField("start_epoch_lo14"), "a lo14 of 0 came back absent: presence lost"
    assert rt.start_epoch_lo14 == 0

    # The other direction, so this pins presence rather than just non-zeroness.
    absent = atak_pb2.TAKPacketV2(uid="ATOM-LO14-ABSENT", cot_type_str="a-f-G-U-C")
    rt2 = atak_pb2.TAKPacketV2(); rt2.ParseFromString(absent.SerializeToString())
    assert not rt2.HasField("start_epoch_lo14"), "an absent start came back as 0"


def test_a_start_past_the_sender_gate_is_not_encoded():
    # 3660 s is well INSIDE the 8192 s half-window, so this exercises the sender
    # gate and not the window. Worth pinning in its own right: that gate is the only
    # thing keeping a receiver out of the wrongly-accepted band.
    too_old = datetime.now(timezone.utc) - timedelta(
        seconds=start_epoch.PAST_LIMIT_SECONDS + 60
    )
    pkt = CotXmlParser().parse(_cot(_iso(too_old), _iso(too_old + timedelta(seconds=300))))
    assert not pkt.HasField("start_epoch_lo14")


def test_a_present_but_out_of_range_start_falls_back_rather_than_being_used():
    # The receiver-side counterpart to the sender gate: a field that IS present and
    # that the receiver refuses, travelling through the builder. 7200 s out is inside
    # the 8192 s half-window, so this is the bound rather than the wrap.
    now_s = int(datetime.now(timezone.utc).timestamp())
    pkt = atak_pb2.TAKPacketV2(uid="ATOM-OUT-OF-RANGE", cot_type_str="a-f-G-U-C")
    pkt.start_epoch_lo14 = start_epoch.truncate(now_s - 7200)
    emitted = _attr(CotXmlBuilder().build(pkt), "start")
    assert emitted is not None, "the event must still be built"
    delta = int(datetime.fromisoformat(emitted.replace("Z", "+00:00")).timestamp()) - now_s
    assert 0 <= delta <= 10, f"out-of-range start was used, not the decode instant: {emitted}"


def test_the_rebuilt_timestamps_are_utc():
    start = (datetime.now(timezone.utc) - timedelta(seconds=90)).replace(microsecond=0)
    pkt = CotXmlParser().parse(_cot(_iso(start), _iso(start + timedelta(seconds=300))))
    rebuilt = CotXmlBuilder().build(pkt)
    for name in ("time", "start", "stale"):
        value = _attr(rebuilt, name)
        assert value is not None and value.endswith("Z"), f"{name} is not UTC: {value}"


def test_an_offset_bearing_start_is_converted_rather_than_read_positionally():
    # Same instant, written with a +04:00 offset instead of Z. We must encode the
    # same 14 bits for both, which holds because the parser resolves the instant
    # instead of reading fixed character positions the way ATAK's fast path does.
    start = (datetime.now(timezone.utc) - timedelta(seconds=90)).replace(microsecond=0)
    stale = _iso(start + timedelta(seconds=300))
    offset = timezone(timedelta(hours=4))
    as_offset = start.astimezone(offset).strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "+04:00"

    from_z = CotXmlParser().parse(_cot(_iso(start), stale))
    from_offset = CotXmlParser().parse(_cot(as_offset, stale))
    assert from_offset.HasField("start_epoch_lo14"), "offset form was not encoded"
    assert from_z.start_epoch_lo14 == from_offset.start_epoch_lo14


def test_a_start_with_no_zone_designator_is_not_guessed_at():
    # ATAK's fixed-position parser would read this as GMT. We refuse instead, because
    # a producer that omits the designator has told us nothing trustworthy, and
    # inventing a zone is how a local-time stamp becomes a silent multi-hour error.
    start = (datetime.now(timezone.utc) - timedelta(seconds=90)).replace(microsecond=0)
    no_zone = start.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3]
    pkt = CotXmlParser().parse(_cot(no_zone, _iso(start + timedelta(seconds=300))))
    assert not pkt.HasField("start_epoch_lo14")


def test_stale_is_measured_from_arrival_rather_than_the_recovered_start():
    # An honest `start` must not shorten the event's life. This one spent ten minutes
    # in transit against a 60 s window, so deriving `stale` from `start` would make it
    # arrive already expired.
    start = (datetime.now(timezone.utc) - timedelta(seconds=600)).replace(microsecond=0)
    pkt = CotXmlParser().parse(_cot(_iso(start), _iso(start + timedelta(seconds=60))))
    rebuilt = CotXmlBuilder().build(pkt)
    stale = datetime.fromisoformat(_attr(rebuilt, "stale").replace("Z", "+00:00"))
    assert stale > datetime.now(timezone.utc), f"stale is already in the past: {stale}"
