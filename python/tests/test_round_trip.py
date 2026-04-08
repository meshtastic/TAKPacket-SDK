import pytest
from meshtastic_tak import CotXmlParser, CotXmlBuilder, TakCompressor
from meshtastic_tak.cot_type_mapper import *
from conftest import FIXTURE_NAMES, load_fixture_xml

parser = CotXmlParser()
builder = CotXmlBuilder()
compressor = TakCompressor()


@pytest.mark.parametrize("fixture", FIXTURE_NAMES)
def test_full_round_trip_preserves_fields(fixture):
    xml = load_fixture_xml(fixture)
    packet = parser.parse(xml)
    assert packet.uid, f"UID empty for {fixture}"

    wire = compressor.compress(packet)
    decompressed = compressor.decompress(wire)

    assert packet.cot_type_id == decompressed.cot_type_id, f"cotTypeId mismatch in {fixture}"
    assert packet.how == decompressed.how, f"how mismatch in {fixture}"
    assert packet.callsign == decompressed.callsign, f"callsign mismatch in {fixture}"
    assert packet.team == decompressed.team, f"team mismatch in {fixture}"
    assert packet.latitude_i == decompressed.latitude_i, f"lat mismatch in {fixture}"
    assert packet.longitude_i == decompressed.longitude_i, f"lon mismatch in {fixture}"
    assert packet.altitude == decompressed.altitude, f"alt mismatch in {fixture}"
    assert packet.battery == decompressed.battery, f"battery mismatch in {fixture}"
    assert packet.uid == decompressed.uid, f"uid mismatch in {fixture}"
    assert packet.speed == decompressed.speed, f"speed mismatch in {fixture}"
    assert packet.course == decompressed.course, f"course mismatch in {fixture}"
    assert packet.role == decompressed.role, f"role mismatch in {fixture}"
    assert packet.device_callsign == decompressed.device_callsign, f"deviceCallsign mismatch in {fixture}"
    assert packet.tak_version == decompressed.tak_version, f"takVersion mismatch in {fixture}"
    assert packet.tak_platform == decompressed.tak_platform, f"takPlatform mismatch in {fixture}"
    assert packet.endpoint == decompressed.endpoint, f"endpoint mismatch in {fixture}"

    # Payload-specific field assertions
    which_orig = packet.WhichOneof("payload_variant")
    which_dec = decompressed.WhichOneof("payload_variant")
    assert which_orig == which_dec, f"payload type mismatch in {fixture}: {which_orig} vs {which_dec}"
    if which_orig == "chat":
        assert packet.chat.message == decompressed.chat.message, f"chat.message mismatch in {fixture}"
        assert packet.chat.to == decompressed.chat.to, f"chat.to mismatch in {fixture}"
    elif which_orig == "aircraft":
        assert packet.aircraft.icao == decompressed.aircraft.icao, f"aircraft.icao mismatch in {fixture}"
        assert packet.aircraft.registration == decompressed.aircraft.registration, f"aircraft.registration mismatch in {fixture}"
        assert packet.aircraft.flight == decompressed.aircraft.flight, f"aircraft.flight mismatch in {fixture}"
        assert packet.aircraft.squawk == decompressed.aircraft.squawk, f"aircraft.squawk mismatch in {fixture}"

    rebuilt_xml = builder.build(decompressed)
    assert "<event" in rebuilt_xml, f"Rebuilt XML missing <event> for {fixture}"


def test_pli_basic_parses_correctly():
    xml = load_fixture_xml("pli_basic")
    pkt = parser.parse(xml)
    assert pkt.uid == "testnode"
    assert pkt.cot_type_id == COTTYPE_A_F_G_U_C
    assert pkt.how == COTHOW_M_G
    assert pkt.callsign == "testnode"
    assert pkt.latitude_i == int(37.7749 * 1e7)
    assert pkt.longitude_i == int(-122.4194 * 1e7)


def test_aircraft_adsb_parses_icao():
    xml = load_fixture_xml("aircraft_adsb")
    pkt = parser.parse(xml)
    assert pkt.cot_type_id == COTTYPE_A_N_A_C_F
    assert pkt.WhichOneof("payload_variant") == "aircraft"
    assert pkt.aircraft.icao, "ICAO should not be empty"


def test_geochat_parses_message():
    xml = load_fixture_xml("geochat_simple")
    pkt = parser.parse(xml)
    assert pkt.cot_type_id == COTTYPE_B_T_F
    assert pkt.WhichOneof("payload_variant") == "chat"
    assert pkt.chat.message, "Chat message should not be empty"


def test_delete_event():
    xml = load_fixture_xml("delete_event")
    pkt = parser.parse(xml)
    assert pkt.cot_type_id == COTTYPE_T_X_D_D
    assert pkt.how == COTHOW_H_G_I_G_O


def test_casevac():
    xml = load_fixture_xml("casevac")
    pkt = parser.parse(xml)
    assert pkt.cot_type_id == COTTYPE_B_R_F_H_C
    assert pkt.callsign == "CASEVAC-1"


def test_alert_tic():
    xml = load_fixture_xml("alert_tic")
    pkt = parser.parse(xml)
    assert pkt.cot_type_id == COTTYPE_B_A_O_OPN
    assert pkt.callsign == "ALPHA-6"


def test_pli_full_all_fields():
    xml = load_fixture_xml("pli_full")
    pkt = parser.parse(xml)
    assert pkt.cot_type_id == COTTYPE_A_F_G_U_C
    assert pkt.callsign
    assert pkt.tak_version
    assert pkt.tak_platform
    assert pkt.battery > 0


def test_uncompressed_payload_round_trips():
    """Simulate firmware TAK_TRACKER: flags=0xFF + raw protobuf."""
    from meshtastic_tak import atak_pb2

    pkt = atak_pb2.TAKPacketV2()
    pkt.cot_type_id = COTTYPE_A_F_G_U_C
    pkt.how = COTHOW_M_G
    pkt.callsign = "TEST"
    pkt.latitude_i = 340522000
    pkt.longitude_i = -1182437000
    pkt.altitude = 100
    pkt.pli = True

    proto_bytes = pkt.SerializeToString()
    wire = bytes([0xFF]) + proto_bytes

    decompressed = compressor.decompress(wire)
    assert decompressed.cot_type_id == pkt.cot_type_id
    assert decompressed.callsign == "TEST"
    assert decompressed.latitude_i == 340522000
