"""``<environment>`` and ``<sensor>`` round trip, mirroring the Kotlin binding.

Both are optional annotations that attach to any event. Kotlin's model holds
natural units and converts when it serializes; this binding's model is the proto
message, so the parser and builder do that conversion. The expected values are
therefore what Kotlin yields after a wire round trip, which is what a mesh
receiver sees.
"""

from __future__ import annotations

import os
import re

import pytest

from meshtastic_tak import CotXmlBuilder, CotXmlParser, TakCompressor, atak_pb2
from conftest import PROTOBUF_DIR, load_fixture_xml

parser = CotXmlParser()
builder = CotXmlBuilder()
compressor = TakCompressor()

SensorFov = atak_pb2.SensorFov


def _cot(detail: str) -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>'
        '<event version="2.0" uid="ALPHA-1-SENSOR" type="a-f-G-U-C" how="m-g" '
        'time="2026-03-15T16:12:00Z" start="2026-03-15T16:12:00Z" stale="2026-03-15T16:12:45Z">'
        '<point lat="38.8895" lon="-77.0353" hae="0.0" ce="9999999.0" le="9999999.0"/>'
        f'<detail><contact callsign="ALPHA-1"/>{detail}</detail></event>'
    )


def _element(xml: str, tag: str) -> str | None:
    """The first self-closing ``<tag .../>`` in ``xml``, or ``None``."""
    m = re.search(rf"<{tag}\b[^>]*/>", xml)
    return m.group(0) if m else None


def _round_trip(pkt: atak_pb2.TAKPacketV2) -> atak_pb2.TAKPacketV2:
    return compressor.decompress(compressor.compress(pkt))


# ------------------------------------------------------------ shared fixture

def _assert_fixture_annotations(pkt: atak_pb2.TAKPacketV2) -> None:
    # The annotations do not perturb payload dispatch: this is still a PLI.
    assert pkt.WhichOneof("payload_variant") is None
    assert pkt.HasField("environment")
    assert pkt.environment.temperature_c_x10 == 225
    assert pkt.environment.wind_direction_deg == 270
    assert pkt.environment.wind_speed_cm_s == 1200
    assert pkt.HasField("sensor_fov")
    s = pkt.sensor_fov
    assert s.type == SensorFov.SensorType_Thermal
    assert s.azimuth_deg == 45
    assert s.HasField("range_m")
    assert s.range_m == 2500
    assert s.fov_horizontal_deg == 45
    assert s.fov_vertical_deg == 30
    assert s.elevation_deg == -5
    assert s.roll_deg == 0
    assert s.model == "FLIR-Boson-640"


def test_pli_with_sensor_round_trips_both_annotations():
    pkt = parser.parse(load_fixture_xml("pli_with_sensor"))
    _assert_fixture_annotations(pkt)

    decompressed = _round_trip(pkt)
    _assert_fixture_annotations(decompressed)

    xml = builder.build(decompressed)
    assert '<environment temperature="22.5" windDirection="270" windSpeed="12.0"/>' in xml, xml
    assert (
        '<sensor azimuth="45" range="2500" fov="45" vfov="30" elevation="-5"'
        ' model="FLIR-Boson-640"/>'
    ) in xml, xml
    assert " type=" not in _element(xml, "sensor"), xml

    _assert_fixture_annotations(parser.parse(xml))


def test_pli_with_sensor_serializes_to_the_kotlin_protobuf_golden():
    with open(os.path.join(PROTOBUF_DIR, "pli_with_sensor.pb"), "rb") as f:
        golden = f.read()
    pkt = parser.parse(load_fixture_xml("pli_with_sensor"))
    assert pkt.SerializeToString() == golden


# ------------------------------------------------------------------ <sensor>

def test_a_bare_sensor_is_present_with_the_defaults():
    pkt = parser.parse(_cot("<sensor/>"))
    assert pkt.HasField("sensor_fov")
    s = pkt.sensor_fov
    assert s.azimuth_deg == 270
    assert s.fov_horizontal_deg == 45
    assert not s.HasField("range_m")
    assert s.fov_vertical_deg == 0
    assert s.elevation_deg == 0
    assert s.roll_deg == 0
    assert s.model == ""
    assert s.type == SensorFov.SensorType_Unspecified

    assert _element(builder.build(pkt), "sensor") == '<sensor azimuth="270" fov="45" elevation="0"/>'


def test_a_zero_range_is_present_and_rebuilt():
    pkt = parser.parse(_cot('<sensor range="0"/>'))
    assert pkt.sensor_fov.HasField("range_m")
    assert pkt.sensor_fov.range_m == 0

    rt = _round_trip(pkt)
    assert rt.sensor_fov.HasField("range_m"), "a range of 0 came back absent"
    assert ' range="0"' in _element(builder.build(rt), "sensor")


@pytest.mark.parametrize(
    ("model", "expected"),
    [
        (None, SensorFov.SensorType_Unspecified),
        ("", SensorFov.SensorType_Unspecified),
        ("FLIR-Boson-640", SensorFov.SensorType_Thermal),
        ("thermal cam", SensorFov.SensorType_Thermal),
        ("Boson", SensorFov.SensorType_Thermal),
        ("LRF-3000", SensorFov.SensorType_Laser),
        ("laser", SensorFov.SensorType_Laser),
        ("NVG-21", SensorFov.SensorType_Nvg),
        ("night vision", SensorFov.SensorType_Nvg),
        ("radar", SensorFov.SensorType_Rf),
        ("rf-detector", SensorFov.SensorType_Rf),
        ("RF-Scanner", SensorFov.SensorType_Rf),
        ("rfscanner", SensorFov.SensorType_Other),
        ("rf", SensorFov.SensorType_Other),
        ("EO-IR", SensorFov.SensorType_Camera),
        ("camera", SensorFov.SensorType_Camera),
        ("optic", SensorFov.SensorType_Camera),
        ("widget", SensorFov.SensorType_Other),
        # The checks run in order, so thermal wins over radar.
        ("FLIR radar", SensorFov.SensorType_Thermal),
    ],
)
def test_sensor_type_is_inferred_from_model(model, expected):
    # None is an absent model attribute.
    element = "<sensor/>" if model is None else f'<sensor model="{model}"/>'
    assert parser.parse(_cot(element)).sensor_fov.type == expected


# ------------------------------------------------------------- <environment>

def test_environment_with_only_a_temperature():
    pkt = parser.parse(_cot('<environment temperature="-3"/>'))
    assert pkt.HasField("environment")
    assert pkt.environment.temperature_c_x10 == -30
    assert pkt.environment.wind_direction_deg == 0
    assert pkt.environment.wind_speed_cm_s == 0

    assert _element(builder.build(pkt), "environment") == '<environment temperature="-3.0"/>'


@pytest.mark.parametrize(
    "element",
    [
        # windDirection parses as an int, so a fraction is unparseable.
        '<environment windDirection="270.5"/>',
        "<environment/>",
        '<environment temperature="abc"/>',
    ],
)
def test_environment_with_nothing_parseable_is_absent(element):
    pkt = parser.parse(_cot(element))
    assert not pkt.HasField("environment")
    assert "<environment" not in builder.build(pkt)


def test_later_elements_replace_earlier_ones():
    pkt = parser.parse(_cot(
        '<environment temperature="10" windDirection="90"/>'
        # Replaces the first outright, so its temperature and direction go.
        '<environment windSpeed="2"/>'
        # Nothing parseable, so the previous one stays.
        '<environment temperature="abc"/>'
        '<sensor model="FLIR" range="100"/>'
        '<sensor azimuth="10"/>'
    ))
    assert pkt.environment == atak_pb2.TAKEnvironment(wind_speed_cm_s=200)
    assert pkt.sensor_fov == SensorFov(azimuth_deg=10, fov_horizontal_deg=45)


# ------------------------------------------------------------ number handling

def test_rounding_is_half_up_on_the_double_product():
    pkt = parser.parse(_cot(
        '<sensor azimuth="44.5" elevation="-2.5" roll="0.49999999999999994"/>'
        '<environment temperature="22.45" windSpeed="1.005"/>'
    ))
    assert pkt.sensor_fov.azimuth_deg == 45
    # Half up, not away from zero.
    assert pkt.sensor_fov.elevation_deg == -2
    # Java's Math.round gives 0 here; a float floor(d + 0.5) would give 1.
    assert pkt.sensor_fov.roll_deg == 0
    # 22.45 * 10 is exactly 224.5 as a double, so it rounds up, as on the JVM.
    assert pkt.environment.temperature_c_x10 == 225
    # 1.005 * 100 is 100.49999999999999 as a double, so it rounds down.
    assert pkt.environment.wind_speed_cm_s == 100


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("45", 450),
        ("-5", -50),
        ("2500.0", 25000),
        (".5", 5),
        ("5.", 50),
        ("1e3", 10000),
        (" 22.5 ", 225),
        # Kotlin throws on NaN and clamps Infinity; this port treats both as absent.
        ("NaN", None),
        ("Infinity", None),
        ("1e999", None),
        ("", None),
        ("0x10", None),
        ("1_0", None),
    ],
)
def test_temperature_parses_as_a_kotlin_double(raw, expected):
    pkt = parser.parse(_cot(f'<environment temperature="{raw}"/>'))
    if expected is None:
        assert not pkt.HasField("environment")
    else:
        assert pkt.environment.temperature_c_x10 == expected


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("270", 270),
        ("+270", 270),
        ("0270", 270),
        (" 270", None),
        ("270 ", None),
        ("2147483648", None),
        ("", None),
    ],
)
def test_wind_direction_parses_as_a_kotlin_int(raw, expected):
    pkt = parser.parse(_cot(f'<environment windDirection="{raw}"/>'))
    if expected is None:
        assert not pkt.HasField("environment")
    else:
        assert pkt.environment.wind_direction_deg == expected


def test_negative_unsigned_values_keep_kotlins_bits_and_print_signed():
    # Kotlin's model is Int, and Wire writes a negative Int into a uint32 as its
    # two's-complement bits, so the wire bytes match Kotlin's for these too.
    pkt = parser.parse(_cot(
        '<environment windDirection="-90" windSpeed="-1.5"/>'
        '<sensor azimuth="-10" range="-1" fov="-45" vfov="-30"/>'
    ))
    assert pkt.environment.wind_direction_deg == 2**32 - 90
    assert pkt.environment.wind_speed_cm_s == 2**32 - 150
    assert pkt.sensor_fov.azimuth_deg == 2**32 - 10
    assert pkt.sensor_fov.range_m == 2**32 - 1
    assert pkt.sensor_fov.fov_horizontal_deg == 2**32 - 45
    assert pkt.sensor_fov.fov_vertical_deg == 2**32 - 30

    xml = builder.build(_round_trip(pkt))
    assert _element(xml, "environment") == (
        '<environment temperature="0.0" windDirection="-90" windSpeed="-1.5"/>'
    )
    assert _element(xml, "sensor") == (
        '<sensor azimuth="-10" range="-1" fov="-45" vfov="-30" elevation="0"/>'
    )
