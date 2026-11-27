"""The ``<__detection>`` CoT detail element that carries ``TAKPacketV2.detection``.

The annotation describes an object a passive sensor reported. It is one
self-closing element, a direct child of ``<detail>``, with each attribute left
out at its default::

    <__detection kind="uas" sources="remote_id rf" confidence="87" state="active"
        trend="closing" count="2" sensorAnchored="true" cep="12" rssi="-71"
        uasId="..." uasIdType="serial_number" uasStatus="airborne"/>

An enum attribute carries the value's name in lowercase snake_case
(``Kind_UasOperator`` is ``uas_operator``), and ``sources`` is a space-separated
list of them. ``confidence`` is clamped to 0-100, and ``count`` and ``cep`` to
zero or more. ``cep`` is also the event's ``<point ce>``: the builder writes it
there, and the parser reads ``<point ce>`` when the element has no ``cep``.

``uasId`` holds up to 20 bytes and is kept only for kind ``uas``. An ID made
only of ASCII letters, digits, ``-``, ``.`` and ``_``, as serial numbers and CAA
registrations are, is written as that text; any other is written as ``hex:``
and its bytes in lowercase hex, so neither form needs escaping. Reading takes a
value that starts with ``hex:`` (in any case) as hex, and drops it unless the
rest is whole hex digits; any other value is the ID's UTF-8 text. Either way
the ID is cut to 20 bytes.

:class:`~meshtastic_tak.cot_xml_parser.CotXmlParser` and
:class:`~meshtastic_tak.cot_xml_builder.CotXmlBuilder` share the tokens and
limits defined here, which match the Kotlin binding's ``DetectionXml``.
"""

from __future__ import annotations

from . import atak_pb2

_D = atak_pb2.TAKDetection

#: The element name.
ELEMENT = "__detection"

#: CoT's "unknown" circular error, written to ``<point ce>`` when there is no cep to carry.
UNKNOWN_CE = 9_999_999

#: ``uasId`` keeps at most this many bytes.
UAS_ID_MAX_BYTES = 20

#: A ``uasId`` of only these bytes is written as text; any other is written as hex.
UAS_ID_TEXT_BYTES = frozenset(b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-._")

#: The prefix of a ``uasId`` written as hex.
UAS_ID_HEX_PREFIX = "hex:"

_HEX_DIGITS = frozenset("0123456789abcdefABCDEF")


def parse_uas_id(raw: str | None) -> bytes:
    """A ``uasId`` attribute's bytes: hex after ``hex:``, else the text's UTF-8.

    The prefix matches in any case. A hex value whose rest is not an even
    number of hex digits gives no ID. Either form is cut to 20 bytes.
    """
    if raw is None:
        return b""
    prefix = len(UAS_ID_HEX_PREFIX)
    if raw[:prefix].lower() == UAS_ID_HEX_PREFIX:
        digits = raw[prefix:]
        if len(digits) % 2 or not all(c in _HEX_DIGITS for c in digits):
            return b""
        return bytes.fromhex(digits)[:UAS_ID_MAX_BYTES]
    return raw.encode("utf-8")[:UAS_ID_MAX_BYTES]


def uas_id_attribute(uas_id: bytes) -> str:
    """The ``uasId`` attribute for ``uas_id``: its text when every byte is a
    plain ID character, else ``hex:`` and its bytes in lowercase hex."""
    if all(b in UAS_ID_TEXT_BYTES for b in uas_id):
        return uas_id.decode("ascii")
    return UAS_ID_HEX_PREFIX + uas_id.hex()

#: ``confidence`` is a percentage.
CONFIDENCE_MAX = 100

# Each table maps a wire value to its token. They are spelled out, so the XML
# does not follow a renamed enum value.

KIND_TOKENS = {
    _D.Kind_Unspecified: "unspecified",
    _D.Kind_Uas: "uas",
    _D.Kind_UasOperator: "uas_operator",
    _D.Kind_UasHome: "uas_home",
    _D.Kind_Person: "person",
    _D.Kind_Vehicle: "vehicle",
    _D.Kind_Other: "other",
}

#: Keyed by the bit each source sets in ``sources``, in ascending bit order.
SOURCE_TOKENS = {
    _D.Source_Unspecified: "unspecified",
    _D.Source_RemoteId: "remote_id",
    _D.Source_Rf: "rf",
    _D.Source_Acoustic: "acoustic",
    _D.Source_Radar: "radar",
    _D.Source_Optical: "optical",
    _D.Source_Thermal: "thermal",
    _D.Source_Seismic: "seismic",
    _D.Source_Magnetic: "magnetic",
}

STATE_TOKENS = {
    _D.State_Unspecified: "unspecified",
    _D.State_New: "new",
    _D.State_Active: "active",
    _D.State_Coasting: "coasting",
    _D.State_Lost: "lost",
}

TREND_TOKENS = {
    _D.Trend_Unspecified: "unspecified",
    _D.Trend_Closing: "closing",
    _D.Trend_Steady: "steady",
    _D.Trend_Opening: "opening",
}

#: The ASTM F3411 UAS ID types.
UAS_ID_TYPE_TOKENS = {
    _D.UasIdType_Unspecified: "unspecified",
    _D.UasIdType_SerialNumber: "serial_number",
    _D.UasIdType_CaaRegistration: "caa_registration",
    _D.UasIdType_UtmAssigned: "utm_assigned",
    _D.UasIdType_SpecificSession: "specific_session",
}

#: The ASTM F3411 operational status.
UAS_STATUS_TOKENS = {
    _D.UasStatus_Unspecified: "unspecified",
    _D.UasStatus_Ground: "ground",
    _D.UasStatus_Airborne: "airborne",
    _D.UasStatus_Emergency: "emergency",
    _D.UasStatus_SystemFailure: "system_failure",
}
