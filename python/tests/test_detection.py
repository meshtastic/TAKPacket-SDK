"""The passive-sensor detection annotation (TAKPacketV2 tag 47) as ``<__detection>``
and on the wire, mirroring the Kotlin binding's ``DetectionXmlTest``.
"""

from __future__ import annotations

import os

import pytest

from meshtastic_tak import CotXmlBuilder, CotXmlParser, TakCompressor, atak_pb2
from meshtastic_tak.dictionary_provider import DICT_ID_AIRCRAFT, DICT_ID_NON_AIRCRAFT, DictionaryProvider
from conftest import PROTOBUF_DIR, load_fixture_xml, load_golden

parser = CotXmlParser()
builder = CotXmlBuilder()
compressor = TakCompressor()

D = atak_pb2.TAKDetection

_UID = "2f6d3b8e-5a41-4c1f-9e07-6b2d8c4a1f35"

# A synthetic CTA-2063-A serial number: the placeholder manufacturer code ZZZZ,
# length code 8, then the eight-character serial.
UAS_ID = "ZZZZ8TEST0001"

FULL_ELEMENT = (
    '<__detection kind="uas" sources="remote_id rf" confidence="87" state="active"'
    ' trend="closing" count="2" sensorAnchored="true" cep="12" rssi="-71"'
    f' uasId="{UAS_ID}" uasIdType="serial_number" uasStatus="airborne"/>'
)


def _event(detail: str, cot_type: str = "a-u-A-M-H-Q", ce: str = "9999999") -> str:
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<event version="2.0" uid="{_UID}" type="{cot_type}" how="m-r" '
        'time="2026-11-20T15:00:00Z" start="2026-11-20T15:00:00Z" stale="2026-11-20T15:01:00Z">\n'
        f'  <point lat="33.1286" lon="-107.2470" hae="1452.0" ce="{ce}" le="9999999"/>\n'
        f'  <detail><contact callsign="UAS-1"/>{detail}</detail>\n'
        '</event>'
    )


def _detection(element: str, ce: str = "9999999") -> atak_pb2.TAKDetection | None:
    pkt = parser.parse(_event(element, ce=ce))
    return pkt.detection if pkt.HasField("detection") else None


def _packet(detection: atak_pb2.TAKDetection) -> atak_pb2.TAKPacketV2:
    pkt = atak_pb2.TAKPacketV2(cot_type_str="a-u-A-M-H-Q", uid=_UID, callsign="UAS-1")
    pkt.detection.CopyFrom(detection)
    return pkt


def _through_the_wire(pkt: atak_pb2.TAKPacketV2) -> atak_pb2.TAKPacketV2:
    return compressor.decompress(compressor.compress(pkt))


# ---------------------------------------------------------------------- tokens

# Spelled out rather than taken from the binding, so a changed token fails here.
KINDS = {
    D.Kind_Unspecified: "unspecified",
    D.Kind_Uas: "uas",
    D.Kind_UasOperator: "uas_operator",
    D.Kind_UasHome: "uas_home",
    D.Kind_Person: "person",
    D.Kind_Vehicle: "vehicle",
    D.Kind_Other: "other",
}
STATES = {
    D.State_Unspecified: "unspecified",
    D.State_New: "new",
    D.State_Active: "active",
    D.State_Coasting: "coasting",
    D.State_Lost: "lost",
}
TRENDS = {
    D.Trend_Unspecified: "unspecified",
    D.Trend_Closing: "closing",
    D.Trend_Steady: "steady",
    D.Trend_Opening: "opening",
}
UAS_ID_TYPES = {
    D.UasIdType_Unspecified: "unspecified",
    D.UasIdType_SerialNumber: "serial_number",
    D.UasIdType_CaaRegistration: "caa_registration",
    D.UasIdType_UtmAssigned: "utm_assigned",
    D.UasIdType_SpecificSession: "specific_session",
}
UAS_STATUSES = {
    D.UasStatus_Unspecified: "unspecified",
    D.UasStatus_Ground: "ground",
    D.UasStatus_Airborne: "airborne",
    D.UasStatus_Emergency: "emergency",
    D.UasStatus_SystemFailure: "system_failure",
}
SOURCES = {
    D.Source_Unspecified: "unspecified",
    D.Source_RemoteId: "remote_id",
    D.Source_Rf: "rf",
    D.Source_Acoustic: "acoustic",
    D.Source_Radar: "radar",
    D.Source_Optical: "optical",
    D.Source_Thermal: "thermal",
    D.Source_Seismic: "seismic",
    D.Source_Magnetic: "magnetic",
}

# (attribute, field, enum, table)
TABLES = [
    ("kind", "kind", D.Kind, KINDS),
    ("state", "state", D.State, STATES),
    ("trend", "trend", D.Trend, TRENDS),
    ("uasIdType", "uas_id_type", D.UasIdType, UAS_ID_TYPES),
    ("uasStatus", "uas_status", D.UasStatus, UAS_STATUSES),
    ("sources", "sources", D.Source, SOURCES),
]
ROWS = [
    (attr, field, value, token)
    for attr, field, _, table in TABLES
    for value, token in table.items()
]


@pytest.mark.parametrize(("attr", "field", "enum", "table"), TABLES, ids=[t[0] for t in TABLES])
def test_every_enum_value_has_a_token(attr, field, enum, table):
    # A value added to the enum later without a token fails here.
    assert set(table) == set(enum.values())


@pytest.mark.parametrize(
    ("attr", "field", "value", "token"), ROWS, ids=[f"{r[0]}={r[3]}" for r in ROWS]
)
def test_every_token_reads_back_and_builds(attr, field, value, token):
    assert getattr(_detection(f'<__detection {attr}="{token}"/>'), field) == value
    xml = builder.build(_packet(D(**{field: value})))
    if value == 0:  # Unspecified sets nothing, so nothing is written.
        assert "    <__detection/>\n" in xml, xml
    else:
        assert f'<__detection {attr}="{token}"/>' in xml, xml


def test_sources_are_written_in_bit_order_and_read_as_a_bitmask():
    every = 0
    for bit in SOURCES:
        every |= bit
    xml = builder.build(_packet(D(sources=every)))
    assert 'sources="remote_id rf acoustic radar optical thermal seismic magnetic"' in xml, xml
    assert parser.parse(xml).detection.sources == every


# --------------------------------------------------------------------- element

def test_the_full_element_survives_the_mesh():
    expected = D(
        kind=D.Kind_Uas,
        sources=D.Source_RemoteId | D.Source_Rf,
        confidence=87,
        state=D.State_Active,
        trend=D.Trend_Closing,
        count=2,
        sensor_anchored=True,
        cep_m=12,
        rssi_dbm=-71,
        uas_id=UAS_ID.encode(),
        uas_id_type=D.UasIdType_SerialNumber,
        uas_status=D.UasStatus_Airborne,
    )
    parsed = parser.parse(_event(FULL_ELEMENT))
    assert parsed.detection == expected

    received = _through_the_wire(parsed)
    assert received == parsed

    rebuilt = builder.build(received)
    assert f"    {FULL_ELEMENT}\n" in rebuilt, rebuilt
    assert 'ce="12" le="9999999"' in rebuilt, rebuilt
    assert parser.parse(rebuilt).detection == expected


def test_a_bare_element_is_present_with_every_default():
    assert _detection("<__detection/>") == D()
    xml = builder.build(_packet(D()))
    assert "    <__detection/>\n" in xml, xml
    assert 'ce="9999999"' in xml, xml


def test_an_event_without_the_element_has_no_detection_and_keeps_the_unknown_ce():
    parsed = parser.parse(_event("", ce="12.0"))
    assert not parsed.HasField("detection")
    assert not _through_the_wire(parsed).HasField("detection")
    xml = builder.build(parsed)
    assert "__detection" not in xml, xml
    assert 'ce="9999999"' in xml, xml


def test_unknown_tokens_read_as_unspecified_and_case_is_ignored():
    d = _detection(
        '<__detection kind="balloon" sources=" RF bogus  ACOUSTIC " state="LOST"'
        ' trend="sideways" uasIdType="mystery" uasStatus="Ground" sensorAnchored="TRUE"/>'
    )
    assert d.kind == D.Kind_Unspecified
    assert d.sources == D.Source_Rf | D.Source_Acoustic
    assert d.state == D.State_Lost
    assert d.trend == D.Trend_Unspecified
    assert d.uas_id_type == D.UasIdType_Unspecified
    assert d.uas_status == D.UasStatus_Ground
    assert d.sensor_anchored is True
    assert _detection('<__detection sensorAnchored="yes"/>').sensor_anchored is False


def test_whitespace_is_trimmed_and_split_as_kotlin_does():
    # Kotlin's trim() drops spaces such as U+00A0 and U+3000 but keeps U+0085.
    assert _detection('<__detection confidence="&#xA0;87&#x3000;"/>').confidence == 87
    assert _detection('<__detection confidence="&#x85;87"/>').confidence == 0
    # sources splits on ASCII whitespace only, so U+00A0 leaves one unknown token.
    assert _detection('<__detection sources="rf&#9;acoustic"/>').sources == (
        D.Source_Rf | D.Source_Acoustic
    )
    assert _detection('<__detection sources="rf&#xA0;acoustic"/>').sources == 0


@pytest.mark.parametrize(
    ("element", "field", "expected"),
    [
        ('<__detection confidence="250"/>', "confidence", 100),
        ('<__detection confidence="-5"/>', "confidence", 0),
        ('<__detection confidence="87.5"/>', "confidence", 88),
        ('<__detection count="-3"/>', "count", 0),
        ('<__detection cep="-1"/>', "cep_m", 0),
        ('<__detection cep=" 12 "/>', "cep_m", 12),
        ('<__detection rssi="-70.5"/>', "rssi_dbm", -70),
        ('<__detection rssi="-70.6"/>', "rssi_dbm", -71),
        ('<__detection count="1.5e3"/>', "count", 1500),
        ('<__detection count="2f"/>', "count", 0),
    ],
)
def test_numbers_are_rounded_half_up_and_clamped(element, field, expected):
    assert getattr(_detection(element), field) == expected


def test_numbers_that_are_not_plain_decimals_are_absent():
    # Not plain decimals, or too large to be finite: read as absent.
    assert _detection(
        '<__detection confidence="NaN" count="Infinity" cep="0x10" rssi="1e400"/>'
    ) == D()


def test_the_builder_clamps_a_value_that_arrived_out_of_range():
    # count -2 and cep -4 as int32: the bits a negative Kotlin Int puts in a uint32.
    xml = builder.build(_packet(D(confidence=150, count=2**32 - 2, cep_m=2**32 - 4)))
    assert '<__detection confidence="100"/>' in xml, xml
    assert 'ce="9999999"' in xml, xml


def test_the_uas_id_is_cut_to_20_bytes_and_kept_only_for_a_uas():
    long_id = "ZZZZFTEST000000000001EXTRA"  # 26 characters
    assert _detection(f'<__detection kind="uas" uasId="{long_id}"/>').uas_id == long_id[:20].encode()
    # Text beyond ASCII counts in UTF-8 bytes.
    accented = "A" * 19 + "é"
    assert _detection(f'<__detection kind="uas" uasId="{accented}"/>').uas_id == accented.encode()[:20]

    for kind in ("uas_operator", "uas_home", "person", "vehicle", "other", "unspecified"):
        assert _detection(f'<__detection kind="{kind}" uasId="{UAS_ID}"/>').uas_id == b"", kind
    assert _detection(f'<__detection uasId="{UAS_ID}"/>').uas_id == b""

    # The builder holds a packet built in code to the same rule.
    xml = builder.build(_packet(D(kind=D.Kind_Person, uas_id=UAS_ID.encode())))
    assert UAS_ID not in xml, xml
    cut = builder.build(_packet(D(kind=D.Kind_Uas, uas_id=long_id.encode())))
    assert f'uasId="{long_id[:20]}"' in cut, cut


def test_a_binary_uas_id_travels_as_hex():
    # A UTM-assigned UUID is 16 raw bytes, most of them outside the text set.
    uuid = bytes.fromhex("123e4567e89b42d3a456426614174000")
    d = D(kind=D.Kind_Uas, uas_id=uuid, uas_id_type=D.UasIdType_UtmAssigned)
    xml = builder.build(_packet(d))
    assert 'uasId="hex:123e4567e89b42d3a456426614174000"' in xml, xml
    assert parser.parse(xml).detection == d
    assert _through_the_wire(_packet(d)).detection == d

    # Hex reads in any case; a value that is not whole hex digits is dropped.
    upper = '<__detection kind="uas" uasId="HEX:123E4567E89B42D3A456426614174000"/>'
    assert _detection(upper).uas_id == uuid
    for bad in ("hex:123", "hex:12zz", "hex:+1", "hex:"):
        assert _detection(f'<__detection kind="uas" uasId="{bad}"/>').uas_id == b"", bad
    # Hex longer than 20 bytes is cut like text.
    assert _detection(f'<__detection kind="uas" uasId="hex:{"11" * 25}"/>').uas_id == b"\x11" * 20

    # One byte outside letters, digits, '-', '.' and '_' sends the whole ID as hex.
    spaced = builder.build(_packet(D(kind=D.Kind_Uas, uas_id=b"ZZZZ8 TEST")))
    assert 'uasId="hex:5a5a5a5a382054455354"' in spaced, spaced
    quoted = builder.build(_packet(D(kind=D.Kind_Uas, uas_id=b'ZZZZ8"&<')))
    assert 'uasId="hex:5a5a5a5a3822263c"' in quoted, quoted
    assert parser.parse(quoted).detection.uas_id == b'ZZZZ8"&<'


@pytest.mark.parametrize(
    ("element", "ce", "expected"),
    [
        ("<__detection/>", "15.0", 15),
        ('<__detection cep="12"/>', "15.0", 12),
        ('<__detection cep="unknown"/>', "15.0", 15),
        ("<__detection/>", "9999999", 0),
        ("<__detection/>", "9999999.0", 0),
        ("<__detection/>", "-3", 0),
    ],
)
def test_cep_falls_back_to_the_point_ce(element, ce, expected):
    assert _detection(element, ce=ce).cep_m == expected


def test_the_annotation_leaves_the_payload_variant_alone():
    parent_link = (
        '<link uid="ANDROID-0000000000000012" type="a-f-G-E-S"'
        ' parent_callsign="SENSOR-1" relation="p-p"/>'
    )
    annotated = parser.parse(_event(parent_link + FULL_ELEMENT))
    assert annotated.WhichOneof("payload_variant") == "marker"
    assert annotated.marker.parent_callsign == "SENSOR-1"
    assert parser.parse(_event(parent_link)).marker == annotated.marker
    # A position report stays one: no payload variant.
    pli = parser.parse(_event(FULL_ELEMENT, cot_type="a-f-G-U-C"))
    assert pli.WhichOneof("payload_variant") is None


def test_source_bits_without_a_token_survive_the_wire():
    unnamed = 1 << 9
    d = D(kind=D.Kind_Uas, sources=D.Source_Radar | unnamed)
    assert _through_the_wire(_packet(d)).detection == d
    # CoT XML can only name the bits it knows.
    xml = builder.build(_packet(d))
    assert '<__detection kind="uas" sources="radar"/>' in xml, xml


# -------------------------------------------------------------------- fixtures

FIXTURES = {
    "detection_uas_remoteid": D(
        kind=D.Kind_Uas,
        sources=3,
        confidence=87,
        state=D.State_Active,
        trend=D.Trend_Closing,
        cep_m=12,
        rssi_dbm=-71,
        uas_id=UAS_ID.encode(),
        uas_id_type=D.UasIdType_SerialNumber,
        uas_status=D.UasStatus_Airborne,
    ),
    "detection_uas_sensor_anchored": D(
        kind=D.Kind_Uas,
        sources=2,
        confidence=60,
        state=D.State_New,
        sensor_anchored=True,
        rssi_dbm=-84,
    ),
    # cep comes from <point ce="15.0">.
    "detection_uas_operator": D(
        kind=D.Kind_UasOperator,
        sources=1,
        confidence=87,
        state=D.State_Active,
        cep_m=15,
    ),
    "detection_ground_vehicle": D(
        kind=D.Kind_Vehicle,
        sources=68,
        confidence=72,
        state=D.State_Active,
        trend=D.Trend_Opening,
        count=2,
        cep_m=25,
    ),
}


@pytest.mark.parametrize("name", sorted(FIXTURES))
def test_fixture_parses_to_the_kotlin_goldens(name):
    pkt = parser.parse(load_fixture_xml(name))
    assert pkt.detection == FIXTURES[name]

    # Byte for byte: Kotlin writes tag 47 itself, after every other field, which
    # is where this runtime's field-number order puts it.
    with open(os.path.join(PROTOBUF_DIR, f"{name}.pb"), "rb") as f:
        assert pkt.SerializeToString().hex() == f.read().hex()

    assert compressor.decompress(load_golden(name)).detection == pkt.detection


def test_detection_uas_remoteid_keeps_its_marker_payload():
    pkt = parser.parse(load_fixture_xml("detection_uas_remoteid"))
    assert pkt.WhichOneof("payload_variant") == "marker"
    assert pkt.marker.parent_uid == "ANDROID-0000000000000012"


def test_detection_uas_sensor_anchored_keeps_its_sensor():
    s = parser.parse(load_fixture_xml("detection_uas_sensor_anchored")).sensor_fov
    assert s.azimuth_deg == 0
    assert s.fov_horizontal_deg == 360
    assert s.HasField("range_m")
    assert s.range_m == 2000
    assert s.type == atak_pb2.SensorFov.SensorType_Rf


# --- Dictionary -----------------------------------------------------------------
# select_dict_id picks by CoT type, which would send an a-u-A-... UAS marker with
# the 4 KB aircraft dictionary. The compressor sends a detection that is not an
# aircraft track with the non-aircraft dictionary instead.


def _wire_dict_id(pkt: atak_pb2.TAKPacketV2) -> int:
    return compressor.compress(pkt)[0]


def test_a_detection_marker_uses_the_non_aircraft_dictionary_whatever_its_type():
    uas = parser.parse(load_fixture_xml("detection_uas_remoteid"))
    assert DictionaryProvider.select_dict_id(uas.cot_type_id, uas.cot_type_str or None) == DICT_ID_AIRCRAFT
    assert _wire_dict_id(uas) == DICT_ID_NON_AIRCRAFT


def test_the_same_marker_without_its_detection_keeps_the_aircraft_dictionary():
    uas = parser.parse(load_fixture_xml("detection_uas_remoteid"))
    uas.ClearField("detection")
    assert _wire_dict_id(uas) == DICT_ID_AIRCRAFT


def test_an_aircraft_track_keeps_the_aircraft_dictionary_with_a_detection():
    detection = parser.parse(load_fixture_xml("detection_uas_remoteid")).detection
    track = parser.parse(load_fixture_xml("aircraft_adsb"))
    assert track.WhichOneof("payload_variant") == "aircraft"
    track.detection.CopyFrom(detection)
    assert _wire_dict_id(track) == DICT_ID_AIRCRAFT


def test_the_wire_keeps_a_uas_id_only_for_a_uas_and_at_most_20_bytes():
    vehicle = _packet(D(kind=D.Kind_Vehicle, uas_id=UAS_ID.encode()))
    assert _through_the_wire(vehicle).detection.uas_id == b""
    # The caller's packet keeps what it had.
    assert vehicle.detection.uas_id == UAS_ID.encode()

    long_id = b"A" * 25
    assert _through_the_wire(_packet(D(kind=D.Kind_Uas, uas_id=long_id))).detection.uas_id == long_id[:20]

    # A peer's packet is read the same way; a 0xFF frame carries its protobuf as is.
    raw = bytes([0xFF]) + vehicle.SerializeToString()
    assert compressor.decompress(raw).detection.uas_id == b""


def test_a_uas_id_is_read_trimmed():
    assert _detection(f'<__detection kind="uas" uasId="  {UAS_ID} "/>').uas_id == UAS_ID.encode()
    assert _detection('<__detection kind="uas" uasId=" hex:5a5a "/>').uas_id == b"ZZ"
