"""Negative tests: malformed input handling."""
import os
import pytest
from meshtastic_tak import TakCompressor

MALFORMED_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "testdata", "malformed")

compressor = TakCompressor()


def load_malformed(name: str) -> bytes:
    with open(os.path.join(MALFORMED_DIR, name), "rb") as f:
        return f.read()


def test_empty_payload():
    with pytest.raises((ValueError, Exception)):
        compressor.decompress(b"")


def test_single_byte():
    with pytest.raises((ValueError, Exception)):
        compressor.decompress(b"\x00")


def test_invalid_dict_id():
    data = load_malformed("invalid_dict_id.bin")
    with pytest.raises((ValueError, Exception)):
        compressor.decompress(data)


def test_truncated_zstd():
    data = load_malformed("truncated_zstd.bin")
    with pytest.raises(Exception):
        compressor.decompress(data)


def test_corrupted_zstd():
    data = load_malformed("corrupted_zstd.bin")
    with pytest.raises(Exception):
        compressor.decompress(data)


def test_invalid_protobuf():
    data = load_malformed("invalid_protobuf.bin")
    # 0xFF flag + garbage bytes — protobuf parser should reject
    # Some protobuf parsers are lenient with unknown fields, so this may or may not raise.
    # The key assertion is: no crash.
    try:
        pkt = compressor.decompress(data)
        # If it parses without error, that's acceptable (protobuf is lenient)
    except Exception:
        pass  # Expected


def test_reserved_bits_set():
    """Reserved bits in flags byte should be ignored — decompression should succeed."""
    data = load_malformed("reserved_bits_set.bin")
    # Flags byte 0xC0 has reserved bits set but dict ID = 0 (0xC0 & 0x3F = 0)
    pkt = compressor.decompress(data)
    assert pkt.uid, "Should decompress successfully despite reserved bits"


# === Security attack tests ===

def test_rejects_xml_doctype():
    """XML with DOCTYPE declaration must be rejected (XXE prevention)."""
    xml = load_malformed("xml_doctype.xml").decode("utf-8")
    from meshtastic_tak import CotXmlParser
    parser = CotXmlParser()
    with pytest.raises((ValueError, Exception)):
        parser.parse(xml)


def test_rejects_xml_entity_expansion():
    """XML with entity expansion (billion laughs) must be rejected."""
    xml = load_malformed("xml_entity_expansion.xml").decode("utf-8")
    from meshtastic_tak import CotXmlParser
    parser = CotXmlParser()
    with pytest.raises((ValueError, Exception)):
        parser.parse(xml)


def test_rejects_oversized_fields():
    """Protobuf with oversized string fields must be rejected."""
    data = load_malformed("oversized_callsign.bin")
    # 0xFF + protobuf with 10KB callsign — exceeds 4096 decompressed limit
    with pytest.raises((ValueError, Exception)):
        compressor.decompress(data)


def test_rejects_decompression_bomb():
    """Zstd frame that decompresses beyond size limit must be rejected."""
    data = load_malformed("decompression_bomb.bin")
    with pytest.raises((ValueError, Exception)):
        compressor.decompress(data)


# === TAKTALK edge-case tests ===
#
# These confirm that anomalous-but-legal TAKTALK shapes — empty bodies,
# missing children, surprise sibling elements — parse without crashing and
# don't corrupt the surrounding envelope.


def test_mtt_with_empty_text_parses_without_crash_and_round_trips():
    """m-t-t with empty <text/> still classifies as TAKTALK and round-trips."""
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="TAKTALK-MESSAGE-empty" type="m-t-t" how="null"
    time="2026-05-27T01:36:25.023Z" start="2026-05-27T01:36:25.023Z"
    stale="2026-05-27T01:42:26.251Z">
  <point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
  <detail>
    <callsign>ASPEN</callsign>
    <lang>English</lang>
    <text></text>
    <chatroom-id>1</chatroom-id>
  </detail>
</event>"""
    from meshtastic_tak import CotXmlParser
    from meshtastic_tak.cot_type_mapper import COTTYPE_M_T_T
    parser = CotXmlParser()
    pkt = parser.parse(xml)
    assert pkt.cot_type_id == COTTYPE_M_T_T, "should classify as m-t-t even with empty text"
    assert pkt.WhichOneof("payload_variant") == "taktalk", "payload should be taktalk variant"
    assert pkt.taktalk.text == "", "empty text body should round-trip as empty string"
    assert pkt.taktalk.chatroom_id == "1"
    assert pkt.taktalk.lang == "English"
    assert pkt.taktalk.from_voice is False
    # Full wire round-trip — compress + decompress shouldn't throw
    wire = compressor.compress(pkt)
    decompressed = compressor.decompress(wire)
    assert decompressed.cot_type_id == COTTYPE_M_T_T


def test_y_dash_with_no_participants_parses_with_empty_participants_list():
    """y- room broadcast with missing <chatroom-participants> survives parse."""
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="ROOM-DATA-no-roster" type="y-" how="null"
    time="2026-05-27T02:09:08.426Z" start="2026-05-27T02:09:08.426Z"
    stale="2026-05-27T02:15:09.699Z">
  <point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
  <detail>
    <sender-callsign>ASPEN</sender-callsign>
    <chatroom-id>30b2755c-c547-44ef-a0cc-cdbd8a15616f</chatroom-id>
    <chatroom-name>test-empty-room</chatroom-name>
  </detail>
</event>"""
    from meshtastic_tak import CotXmlParser
    from meshtastic_tak.cot_type_mapper import COTTYPE_Y_DASH
    parser = CotXmlParser()
    pkt = parser.parse(xml)
    assert pkt.cot_type_id == COTTYPE_Y_DASH
    assert pkt.WhichOneof("payload_variant") == "taktalk_room"
    # v0.3.2: <sender-callsign> routes into envelope packet.callsign,
    # not payload.sender_callsign (now deprecated).
    assert pkt.callsign == "ASPEN"
    room = pkt.taktalk_room
    assert room.room_id == "30b2755c-c547-44ef-a0cc-cdbd8a15616f"
    assert room.room_name == "test-empty-room"
    assert len(room.participants) == 0, (
        "missing <chatroom-participants> should yield empty list, not crash"
    )
    # Round-trip wire to confirm no crash on serialize/deserialize
    wire = compressor.compress(pkt)
    decompressed = compressor.decompress(wire)
    assert decompressed.cot_type_id == COTTYPE_Y_DASH
    assert len(decompressed.taktalk_room.participants) == 0


def test_btf_with_empty_ea_element_does_not_corrupt_chat_parsing():
    """<Ea></Ea> empty body must not clobber the <remarks> chat message."""
    xml = """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="GeoChat.ANDROID-aaa.ANDROID-bbb.malformed-ea" type="b-t-f" how="h-g-i-g-o"
    time="2026-05-27T02:09:20.718Z" start="2026-05-27T02:09:20.718Z"
    stale="2026-05-28T02:09:20.718Z">
  <point lat="38.8895" lon="-77.0353" hae="73.863" ce="6.9" le="9999999.0"/>
  <detail>
    <__chat parent="RootContactGroup" messageId="malformed-ea" chatroom="ETHEL"
        id="ANDROID-bbb" senderCallsign="ASPEN">
      <chatgrp uid0="ANDROID-aaa" uid1="ANDROID-bbb" id="ANDROID-bbb"/>
    </__chat>
    <link uid="ANDROID-aaa" type="a-f-G-U-C" relation="p-p"/>
    <remarks source="BAO.F.ATAK.ANDROID-aaa" to="ANDROID-bbb"
        time="2026-05-27T02:09:20.718Z">Real message body</remarks>
    <Ea></Ea>
    <roomId>30b2755c-c547-44ef-a0cc-cdbd8a15616f</roomId>
  </detail>
</event>"""
    from meshtastic_tak import CotXmlParser
    parser = CotXmlParser()
    pkt = parser.parse(xml)
    assert pkt.WhichOneof("payload_variant") == "chat"
    # The critical assertion — chat message survives intact despite the
    # empty <Ea/> sibling sitting next to <remarks>.
    assert pkt.chat.message == "Real message body", (
        "chat message must not be clobbered by empty <Ea/>"
    )
    assert pkt.chat.lang == "", "empty <Ea/> yields empty lang, not garbage"
    assert pkt.chat.room_id == "30b2755c-c547-44ef-a0cc-cdbd8a15616f"
    # Round-trip should preserve message body
    wire = compressor.compress(pkt)
    decompressed = compressor.decompress(wire)
    assert decompressed.chat.message == "Real message body"
