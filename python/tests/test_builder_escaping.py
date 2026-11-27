"""Builder escaping: a value carrying XML metacharacters never breaks the event.

Every attribute the builder writes is double-quoted, so a ``"`` inside a value
has to leave as ``&quot;``. Left bare, it ends the attribute early and the
receiver is handed XML it cannot parse. The builder escapes ``& < > " '`` as
``&amp; &lt; &gt; &quot; &apos;``, the five entities Kotlin's
``CotXmlBuilder.esc`` writes, in attribute values and element text alike.
"""
import xml.etree.ElementTree as ET

import pytest

from meshtastic_tak import CotXmlParser, CotXmlBuilder, atak_pb2
from meshtastic_tak.cot_type_mapper import *

parser = CotXmlParser()
builder = CotXmlBuilder()

# All five XML metacharacters, in a value an operator could plausibly type.
TRICKY = 'ALPHA "1" & O\'Brien <HQ>'
# TRICKY as Kotlin's CotXmlBuilder.esc writes it.
TRICKY_ESCAPED = "ALPHA &quot;1&quot; &amp; O&apos;Brien &lt;HQ&gt;"

_ENTITIES = ("&amp;", "&lt;", "&gt;", "&quot;", "&apos;")


def _pli_packet():
    pkt = atak_pb2.TAKPacketV2()
    pkt.cot_type_id = COTTYPE_A_F_G_U_C
    pkt.how = COTHOW_M_G
    pkt.uid = "6B0F1D2E-5A44-4C31-9E77-2B7C4D9E1F3A"
    pkt.callsign = "ALPHA-1"
    pkt.latitude_i = 331296000
    pkt.longitude_i = -1072540000
    pkt.altitude = 109
    return pkt


def _build_and_parse(pkt):
    """Build ``pkt`` and return the XML with its ElementTree root."""
    xml = builder.build(pkt)
    try:
        root = ET.fromstring(xml)
    except ET.ParseError as e:
        pytest.fail(f"builder emitted malformed XML ({e}):\n{xml}")
    return xml, root


def test_callsign_with_metacharacters_round_trips():
    pkt = _pli_packet()
    pkt.callsign = TRICKY

    xml, root = _build_and_parse(pkt)

    assert f'callsign="{TRICKY_ESCAPED}"' in xml, xml
    assert root.find("detail/contact").get("callsign") == TRICKY
    assert parser.parse(xml).callsign == TRICKY


def test_uid_with_metacharacters_round_trips():
    pkt = _pli_packet()
    pkt.uid = TRICKY

    xml, root = _build_and_parse(pkt)

    assert root.get("uid") == TRICKY
    assert parser.parse(xml).uid == TRICKY


def test_marti_dest_callsign_with_metacharacters_round_trips():
    pkt = _pli_packet()
    pkt.marti.dest_callsign.append(TRICKY)

    xml, root = _build_and_parse(pkt)

    assert root.find("detail/marti/dest").get("callsign") == TRICKY
    assert list(parser.parse(xml).marti.dest_callsign) == [TRICKY]


def test_remarks_body_is_escaped_like_kotlin_and_round_trips():
    """Quotes are legal in element text, so this body would parse either way.
    It goes through the same helper as the attributes, so the text the builder
    writes matches Kotlin's."""
    pkt = _pli_packet()
    pkt.remarks = TRICKY

    xml, root = _build_and_parse(pkt)

    assert f"<remarks>{TRICKY_ESCAPED}</remarks>" in xml, xml
    assert root.find("detail/remarks").text == TRICKY
    assert parser.parse(xml).remarks == TRICKY


def test_geochat_attributes_with_metacharacters_round_trip():
    """The sender UID and chatroom from ``GeoChat.{sender}.{room}.{id}`` land in
    the ``__chat``, ``chatgrp``, ``link``, ``__serverdestination`` and
    ``remarks`` attributes."""
    pkt = _pli_packet()
    pkt.cot_type_id = COTTYPE_B_T_F
    pkt.uid = f"GeoChat.{TRICKY}.{TRICKY}.msg-1"
    pkt.chat.message = TRICKY

    xml, root = _build_and_parse(pkt)

    remarks = root.find("detail/remarks")
    assert remarks.get("source") == f"BAO.F.ATAK.{TRICKY}"
    assert remarks.get("to") == TRICKY
    assert remarks.text == TRICKY
    assert root.find("detail/__chat").get("chatroom") == TRICKY
    back = parser.parse(xml)
    assert back.uid == pkt.uid
    assert back.chat.message == TRICKY


def _is_repeated(field):
    # protobuf 7 dropped FieldDescriptor.label for is_repeated, which older
    # releases inside this package's floor (5.29) may not have.
    if hasattr(field, "is_repeated"):
        return field.is_repeated
    return field.label == field.LABEL_REPEATED


def _fill_strings(msg):
    """Set every string field of ``msg``, recursing into sub-messages, to TRICKY.

    The payload arms are skipped; the caller picks the one arm to fill.
    """
    for field in msg.DESCRIPTOR.fields:
        oneof = field.containing_oneof
        if oneof is not None and oneof.name == "payload_variant":
            continue
        if field.type == field.TYPE_STRING:
            if _is_repeated(field):
                getattr(msg, field.name).append(TRICKY)
            else:
                setattr(msg, field.name, TRICKY)
        elif field.type == field.TYPE_MESSAGE:
            if _is_repeated(field):
                _fill_strings(getattr(msg, field.name).add())
            else:
                _fill_strings(getattr(msg, field.name))


# raw_detail is excluded: its bytes are emitted verbatim by design.
_PAYLOAD_ARMS = [
    f.name
    for f in atak_pb2.TAKPacketV2.DESCRIPTOR.oneofs_by_name["payload_variant"].fields
    if f.name != "raw_detail"
]


@pytest.mark.parametrize("arm", _PAYLOAD_ARMS)
def test_every_string_field_survives_metacharacters(arm):
    """With every string field of the envelope and of one payload arm set to
    TRICKY, the event still parses and each value comes back escaped once."""
    pkt = _pli_packet()
    pkt.cot_type_id = COTTYPE_OTHER  # so `type` is written from cot_type_str
    _fill_strings(pkt)
    getattr(pkt, arm).SetInParent()
    _fill_strings(getattr(pkt, arm))
    if arm == "shape":
        # A bullseye with a radius writes the KML style link and bullseyeUID.
        pkt.shape.kind = 7
        pkt.shape.major_cm = 10000

    xml, root = _build_and_parse(pkt)

    values = [v for el in root.iter() for v in (*el.attrib.values(), el.text or "")]
    assert TRICKY in values, xml
    leftover = [v for v in values if any(e in v for e in _ENTITIES)]
    assert not leftover, f"double-escaped values {leftover} in:\n{xml}"
