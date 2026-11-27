"""The parser rounds every scaled value as Kotlin's ``roundToInt()`` does (Java's
``Math.round``: a half goes toward positive infinity), so each field gets the
same integer in both bindings. Every expected value below is what the Kotlin
parser produces for the same XML. Most inputs land exactly on a half once
scaled (0.025 m/s is 2.5 cm/s), where ``round()`` picks the even neighbor and
``int()`` truncates; the rest are ordinary fractions, which ``int()`` gets
wrong too.
"""

import math
import os

import pytest

from meshtastic_tak import CotXmlParser, atak_pb2
from conftest import FIXTURE_NAMES, PROTOBUF_DIR, load_fixture_xml

parser = CotXmlParser()

# Washington Monument, the repo's public-landmark placeholder position.
MONUMENT_POINT = 'lat="38.8895" lon="-77.0353" hae="0"'

INT32_MAX = 2**31 - 1
INT32_MIN = -(2**31)


def _event(cot_type: str, point: str, detail: str) -> str:
    return (
        f'<event version="2.0" uid="ROUNDING-1" type="{cot_type}" how="m-g" '
        'time="2026-01-01T00:00:00Z" start="2026-01-01T00:00:00Z" stale="2026-01-01T00:10:00Z">'
        f'<point {point} ce="9999999" le="9999999"/><detail>{detail}</detail></event>'
    )


@pytest.mark.parametrize(
    "speed,course,expected_speed,expected_course",
    [
        ("0.025", "0.125", 3, 13),  # 2.5 and 12.5: int() gives 2 and 12
        ("0.005", "0.375", 1, 38),  # 0.5 and 37.5: int() gives 0 and 37
        ("-0.025", "-0.125", 0, 0),  # -2.5 and -12.5 round to -2 and -12, then clamp to 0
    ],
)
def test_track_speed_and_course_round_half_up_like_kotlin(
    speed, course, expected_speed, expected_course
):
    pkt = parser.parse(
        _event("a-f-G-U-C", MONUMENT_POINT, f'<track speed="{speed}" course="{course}"/>')
    )
    assert (pkt.speed, pkt.course) == (expected_speed, expected_course)


@pytest.mark.parametrize(
    "lat,lon,hae,expected_lat_i,expected_lon_i,expected_altitude",
    [
        # 388895000.5 and -770353001.5 after scaling: round() gives ...000 and ...002.
        ("38.88950005", "-77.03530015", "2.5", 388895001, -770353001, 3),
        # 388895002.5 and -770353003.5: round() gives ...002 and ...004.
        ("38.88950025", "-77.03530035", "-3.5", 388895003, -770353003, -3),
        ("38.8895", "-77.0353", "0.5", 388895000, -770353000, 1),
        ("38.8895", "-77.0353", "-0.5", 388895000, -770353000, 0),
        ("38.8895", "-77.0353", "-2.5", 388895000, -770353000, -2),
        # Not ties: int() gives -29 and 73.
        ("38.8895", "-77.0353", "-29.667", 388895000, -770353000, -30),
        ("38.8895", "-77.0353", "73.963", 388895000, -770353000, 74),
    ],
)
def test_point_lat_lon_and_hae_round_half_up_like_kotlin(
    lat, lon, hae, expected_lat_i, expected_lon_i, expected_altitude
):
    pkt = parser.parse(_event("a-f-G-U-C", f'lat="{lat}" lon="{lon}" hae="{hae}"', ""))
    assert (pkt.latitude_i, pkt.longitude_i, pkt.altitude) == (
        expected_lat_i,
        expected_lon_i,
        expected_altitude,
    )


def test_point_largest_double_below_half_rounds_down_like_kotlin():
    # math.floor(d + 0.5) gives 1 here, because the sum itself rounds up to 1.0.
    just_below_half = repr(math.nextafter(0.5, 0.0))
    pkt = parser.parse(
        _event("a-f-G-U-C", f'lat="38.8895" lon="-77.0353" hae="{just_below_half}"', "")
    )
    assert pkt.altitude == 0


@pytest.mark.parametrize(
    "hae,expected_altitude",
    [
        ("1e10", INT32_MAX),  # Kotlin clamps to the Int32 range; int() overflows sint32
        ("-1e10", INT32_MIN),
        ("Infinity", INT32_MAX),  # int() raises OverflowError
        ("NaN", 0),  # int() raises ValueError; Java's Math.round gives 0
    ],
)
def test_point_hae_out_of_range_clamps_instead_of_raising(hae, expected_altitude):
    pkt = parser.parse(_event("a-f-G-U-C", f'lat="38.8895" lon="-77.0353" hae="{hae}"', ""))
    assert pkt.altitude == expected_altitude


def test_track_out_of_range_speed_and_course_clamp_like_kotlin():
    # 1e12 cm/s overflows the uint32 field; Kotlin clamps to Int32 first.
    pkt = parser.parse(_event("a-f-G-U-C", MONUMENT_POINT, '<track speed="1e10" course="1e10"/>'))
    assert (pkt.speed, pkt.course) == (INT32_MAX, INT32_MAX)


@pytest.mark.parametrize(
    "rssi,expected_rssi_x10",
    [
        ("0.25", 3),  # 2.5: round() and int() both give 2
        ("-0.25", -2),  # -2.5 goes toward positive infinity
        ("-19.35", -193),  # -193.5: round() gives -194
        ("-19.47", -195),  # -194.7: int() gives -194
    ],
)
def test_aircraft_rssi_rounds_like_kotlin(rssi, expected_rssi_x10):
    pkt = parser.parse(
        _event(
            "a-n-A-C-F",
            'lat="38.8895" lon="-77.0353" hae="3048"',
            f'<_aircot_ icao="000001"/><_radio rssi="{rssi}" gps="true"/>',
        )
    )
    assert pkt.WhichOneof("payload_variant") == "aircraft"
    assert pkt.aircraft.rssi_x10 == expected_rssi_x10


@pytest.mark.parametrize(
    "major,minor,stroke_weight,expected_major_cm,expected_minor_cm,expected_stroke_weight_x10",
    [
        # 12.5 cm, 0.29 m (just short of 29 cm as a double) and 2.5: int() gives 12, 28 and 2.
        ("0.125", "0.29", "0.25", 13, 29, 3),
        ("0.375", "0.125", "0.35", 38, 13, 4),
    ],
)
def test_shape_radii_and_stroke_weight_round_like_kotlin(
    major, minor, stroke_weight, expected_major_cm, expected_minor_cm, expected_stroke_weight_x10
):
    pkt = parser.parse(
        _event(
            "u-d-c-c",
            MONUMENT_POINT,
            f'<shape><ellipse major="{major}" minor="{minor}" angle="360"/></shape>'
            f'<strokeColor value="-1"/><strokeWeight value="{stroke_weight}"/>'
            '<labels_on value="false"/>',
        )
    )
    assert pkt.WhichOneof("payload_variant") == "shape"
    assert (pkt.shape.major_cm, pkt.shape.minor_cm, pkt.shape.stroke_weight_x10) == (
        expected_major_cm,
        expected_minor_cm,
        expected_stroke_weight_x10,
    )


@pytest.mark.parametrize(
    "distance,expected_distance_dm",
    [
        ("0.25", 3),  # 2.5 dm: int() gives 2
        ("0.35", 4),  # 3.5 dm: int() gives 3
    ],
)
def test_bullseye_distance_rounds_like_kotlin(distance, expected_distance_dm):
    pkt = parser.parse(
        _event(
            "u-r-b-bullseye",
            MONUMENT_POINT,
            '<shape><ellipse major="500.0" minor="500.0" angle="360"/></shape>'
            f'<bullseye distance="{distance}" bearingRef="M"/><strokeColor value="-65536"/>',
        )
    )
    assert pkt.WhichOneof("payload_variant") == "shape"
    assert pkt.shape.bullseye_distance_dm == expected_distance_dm


@pytest.mark.parametrize(
    "range_m,bearing,expected_range_cm,expected_bearing_cdeg",
    [
        ("0.29", "0.125", 29, 13),  # int() gives 28 and 12
        ("0.125", "0.375", 13, 38),  # int() gives 12 and 37
    ],
)
def test_range_and_bearing_round_like_kotlin(
    range_m, bearing, expected_range_cm, expected_bearing_cdeg
):
    pkt = parser.parse(
        _event(
            "u-rb-a",
            MONUMENT_POINT,
            '<link uid="anchor-1" relation="p-p" type="b-m-p-w" point="38.8895,-77.0353"/>'
            f'<range value="{range_m}"/><bearing value="{bearing}"/>',
        )
    )
    assert pkt.WhichOneof("payload_variant") == "rab"
    assert (pkt.rab.range_cm, pkt.rab.bearing_cdeg) == (expected_range_cm, expected_bearing_cdeg)


def _rounded_values(p: atak_pb2.TAKPacketV2) -> dict:
    """Every field the parser fills by rounding a scaled double."""
    v = {
        "latitude_i": p.latitude_i,
        "longitude_i": p.longitude_i,
        "altitude": p.altitude,
        "speed": p.speed,
        "course": p.course,
    }
    which = p.WhichOneof("payload_variant")
    if which == "shape":
        s = p.shape
        v["shape.major_cm"] = s.major_cm
        v["shape.minor_cm"] = s.minor_cm
        v["shape.stroke_weight_x10"] = s.stroke_weight_x10
        v["shape.bullseye_distance_dm"] = s.bullseye_distance_dm
        v["shape.vertex_lat_deltas"] = list(s.vertex_lat_deltas)
        v["shape.vertex_lon_deltas"] = list(s.vertex_lon_deltas)
    elif which == "rab":
        r = p.rab
        v["rab.range_cm"] = r.range_cm
        v["rab.bearing_cdeg"] = r.bearing_cdeg
        v["rab.stroke_weight_x10"] = r.stroke_weight_x10
        v["rab.anchor"] = (r.anchor.lat_delta_i, r.anchor.lon_delta_i)
    elif which == "aircraft":
        v["aircraft.rssi_x10"] = p.aircraft.rssi_x10
    elif which == "route":
        v["route.points"] = [(l.point.lat_delta_i, l.point.lon_delta_i) for l in p.route.links]
    return v


@pytest.mark.parametrize("fixture", FIXTURE_NAMES)
def test_fixture_rounded_values_match_kotlin_golden(fixture):
    """Kotlin wrote ``testdata/protobuf/*.pb`` from the same fixtures, so every
    value this parser rounds must equal the golden's. Whole packets are not
    compared: a few fixtures differ for reasons that have nothing to do with
    rounding.
    """
    with open(os.path.join(PROTOBUF_DIR, f"{fixture}.pb"), "rb") as f:
        kotlin = atak_pb2.TAKPacketV2.FromString(f.read())
    python = parser.parse(load_fixture_xml(fixture))
    assert _rounded_values(python) == _rounded_values(kotlin)
