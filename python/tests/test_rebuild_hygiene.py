"""Rebuild hygiene: the builder always emits exactly ONE well-formed event.

Two invariants are locked here:

1. The raw-detail fallback stores the inner content of a SINGLE ``<detail>``
   element. Well-formed CoT escapes a literal ``<`` as ``&lt;``, so that
   content can never legitimately carry an ``<event>``/``<detail>`` tag token.
   A fragment that does is malformed and is dropped — re-emitting it verbatim
   would unbalance the wrapper the builder is writing into and leave the
   receiver unable to parse the result as one event.
2. A contact endpoint never travels over the mesh: the parser stores none, and
   the builder always emits the "reply via this server" constant even when a
   packet from an older sender still carries a concrete host.
"""
from meshtastic_tak import CotXmlParser, CotXmlBuilder, atak_pb2
from meshtastic_tak.cot_type_mapper import *

parser = CotXmlParser()
builder = CotXmlBuilder()

CONTACT_WITH_HOST_XML = """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="6B0F1D2E-5A44-4C31-9E77-2B7C4D9E1F3A" type="a-f-G-U-C" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-15T14:27:10Z" how="m-g">
  <point lat="33.1296" lon="-107.2540" hae="109.2" ce="9999999.0" le="9999999.0"/>
  <detail>
    <contact endpoint="192.0.2.1:4242:tcp" callsign="ALPHA-1"/>
    <__group role="Team Member" name="Cyan"/>
  </detail>
</event>
"""


def _pli_packet():
    """A minimal PLI packet — the common envelope the builder tests extend."""
    pkt = atak_pb2.TAKPacketV2()
    pkt.cot_type_id = COTTYPE_A_F_G_U_C
    pkt.how = COTHOW_M_G
    pkt.uid = "6B0F1D2E-5A44-4C31-9E77-2B7C4D9E1F3A"
    pkt.callsign = "ALPHA-1"
    pkt.latitude_i = 331296000
    pkt.longitude_i = -1072540000
    pkt.altitude = 109
    return pkt


def test_raw_detail_fragment_closing_the_wrapper_is_dropped():
    pkt = _pli_packet()
    pkt.raw_detail = (
        b'<remarks>ALPHA-1 checking in</remarks>'
        b'</detail></event><event version="2.0" uid="SECOND">'
    )

    xml = builder.build(pkt)

    assert "</detail></event><event" not in xml, f"malformed fragment re-emitted:\n{xml}"
    assert xml.count("<event") == 1, f"expected exactly one <event token:\n{xml}"
    assert xml.count("</event>") == 1, f"expected exactly one </event> token:\n{xml}"
    # Dropping the fragment must not abort the rest of the build.
    assert 'callsign="ALPHA-1"' in xml, f"contact missing from rebuilt event:\n{xml}"


def test_raw_detail_fragment_closing_the_wrapper_in_mixed_case_is_dropped():
    """The tag-token check is case-insensitive.

    XML element names are case-sensitive, so ``</DETAIL></Event>`` never closes
    the wrapper the builder is writing into — but it is not the content of a
    single ``<detail>`` either, and re-emitting it produces a document with two
    ``event`` elements in it. Case is therefore irrelevant to the decision: any
    spelling of the tag tokens marks the fragment malformed and drops it, the
    same as the all-lowercase spelling above.
    """
    pkt = _pli_packet()
    pkt.raw_detail = (
        b'<remarks>ALPHA-1 checking in</remarks>'
        b'</DETAIL></Event><Event version="2.0" uid="SECOND">'
    )

    xml = builder.build(pkt)

    assert "</DETAIL></Event><Event" not in xml, f"malformed fragment re-emitted:\n{xml}"
    assert 'uid="SECOND"' not in xml, f"second event header re-emitted:\n{xml}"
    # Count case-insensitively: the fragment's tokens are not lowercase, so the
    # plain xml.count() used by the sibling tests would not see them at all.
    lowered = xml.lower()
    assert lowered.count("<event") == 1, f"expected exactly one event element:\n{xml}"
    assert lowered.count("</event>") == 1, f"expected exactly one event close:\n{xml}"
    # Dropping the fragment must not abort the rest of the build.
    assert 'callsign="ALPHA-1"' in xml, f"contact missing from rebuilt event:\n{xml}"


def test_benign_raw_detail_fragment_round_trips_verbatim():
    pkt = _pli_packet()
    pkt.raw_detail = b'<foo bar="1"/>'

    xml = builder.build(pkt)

    assert '<foo bar="1"/>' in xml, f"benign fragment lost in rebuild:\n{xml}"
    assert xml.count("<event") == 1, f"expected exactly one <event token:\n{xml}"
    assert xml.count("</event>") == 1, f"expected exactly one </event> token:\n{xml}"


def test_builder_emits_the_server_stream_endpoint_for_a_concrete_host():
    """A packet from an older sender may still carry a concrete endpoint; over
    the mesh that host is unreachable, so the builder ignores it."""
    pkt = _pli_packet()
    pkt.endpoint = "192.0.2.1:4242:tcp"

    xml = builder.build(pkt)

    assert 'endpoint="*:-1:stcp"' in xml, f"default endpoint not emitted:\n{xml}"
    assert "192.0.2.1" not in xml, f"concrete endpoint leaked into rebuild:\n{xml}"


def test_parser_never_stores_a_contact_endpoint():
    pkt = parser.parse(CONTACT_WITH_HOST_XML)

    assert pkt.callsign == "ALPHA-1"
    assert pkt.endpoint == "", "contact endpoint must never reach the wire"
