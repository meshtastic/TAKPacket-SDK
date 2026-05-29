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
    # Directed-routing recipients (v0.3.2). Empty list = broadcast (default
    # for PLI / situational-awareness); populated for TAKTALK m-t-t and
    # directed b-t-f DMs. TAKTALK gates voice TTS on this list matching
    # the receiver's callsign so a regression here silently breaks voice
    # messaging end-to-end.
    assert list(packet.marti.dest_callsign) == list(decompressed.marti.dest_callsign), (
        f"marti mismatch in {fixture}"
    )

    # Payload-specific field assertions
    which_orig = packet.WhichOneof("payload_variant")
    which_dec = decompressed.WhichOneof("payload_variant")
    assert which_orig == which_dec, f"payload type mismatch in {fixture}: {which_orig} vs {which_dec}"
    if which_orig == "chat":
        assert packet.chat.message == decompressed.chat.message, f"chat.message mismatch in {fixture}"
        assert packet.chat.to == decompressed.chat.to, f"chat.to mismatch in {fixture}"
        # TAKTALK sidecars must survive on chats that carry them
        assert packet.chat.lang == decompressed.chat.lang, f"chat.lang mismatch in {fixture}"
        assert packet.chat.room_id == decompressed.chat.room_id, f"chat.room_id mismatch in {fixture}"
        assert packet.chat.HasField("voice_profile_id") == decompressed.chat.HasField("voice_profile_id"), (
            f"chat.voice_profile_id presence mismatch in {fixture}"
        )
        if packet.chat.HasField("voice_profile_id"):
            assert packet.chat.voice_profile_id == decompressed.chat.voice_profile_id, (
                f"chat.voice_profile_id value mismatch in {fixture}"
            )
    elif which_orig == "aircraft":
        assert packet.aircraft.icao == decompressed.aircraft.icao, f"aircraft.icao mismatch in {fixture}"
        assert packet.aircraft.registration == decompressed.aircraft.registration, f"aircraft.registration mismatch in {fixture}"
        assert packet.aircraft.flight == decompressed.aircraft.flight, f"aircraft.flight mismatch in {fixture}"
        assert packet.aircraft.squawk == decompressed.aircraft.squawk, f"aircraft.squawk mismatch in {fixture}"
    elif which_orig == "taktalk":
        assert packet.taktalk.text == decompressed.taktalk.text, f"taktalk.text mismatch in {fixture}"
        assert packet.taktalk.chatroom_id == decompressed.taktalk.chatroom_id, (
            f"taktalk.chatroom_id mismatch in {fixture}"
        )
        assert packet.taktalk.lang == decompressed.taktalk.lang, f"taktalk.lang mismatch in {fixture}"
        assert packet.taktalk.from_voice == decompressed.taktalk.from_voice, (
            f"taktalk.from_voice mismatch in {fixture}"
        )
    elif which_orig == "taktalk_room":
        # sender_callsign deprecated in v0.3.2 — always equals envelope
        # packet.callsign; envelope-level assertion above covers it. Skip
        # the redundant payload-level comparison.
        assert packet.taktalk_room.room_id == decompressed.taktalk_room.room_id, (
            f"taktalk_room.room_id mismatch in {fixture}"
        )
        assert packet.taktalk_room.room_name == decompressed.taktalk_room.room_name, (
            f"taktalk_room.room_name mismatch in {fixture}"
        )
        assert list(packet.taktalk_room.participants) == list(decompressed.taktalk_room.participants), (
            f"taktalk_room.participants mismatch in {fixture}"
        )

    rebuilt_xml = builder.build(decompressed)
    assert "<event" in rebuilt_xml, f"Rebuilt XML missing <event> for {fixture}"


def test_pli_basic_parses_correctly():
    xml = load_fixture_xml("pli_basic")
    pkt = parser.parse(xml)
    assert pkt.uid == "testnode"
    assert pkt.cot_type_id == COTTYPE_A_F_G_U_C
    assert pkt.how == COTHOW_M_G
    assert pkt.callsign == "testnode"
    assert pkt.latitude_i == round(33.1284 * 1e7)
    assert pkt.longitude_i == round(-107.2528 * 1e7)


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


def test_pli_stationary_clamps_negative_speed_and_course():
    """Regression for an iOS crash where ATAK's <track speed="-1.0"
    course="-1.0"/> sentinel for stationary / unknown targets tripped a
    Double -> UInt32 conversion trap in the Swift parser. The proto
    field is uint32 on all platforms, so the fix is to clamp negatives
    to 0 rather than wrap them into huge unsigned values.
    """
    xml = load_fixture_xml("pli_stationary")
    pkt = parser.parse(xml)
    assert pkt.speed == 0, "Negative speed must clamp to 0"
    assert pkt.course == 0, "Negative course must clamp to 0"
    assert pkt.callsign == "iPadTAKAware"
    # PLI is the implicit payload since v0.4.0 — no payload_variant is set.
    assert pkt.WhichOneof("payload_variant") is None


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
    # PLI is the implicit payload (no payload_variant set) since v0.4.0.

    proto_bytes = pkt.SerializeToString()
    wire = bytes([0xFF]) + proto_bytes

    decompressed = compressor.decompress(wire)
    assert decompressed.cot_type_id == pkt.cot_type_id
    assert decompressed.callsign == "TEST"
    assert decompressed.latitude_i == 340522000
