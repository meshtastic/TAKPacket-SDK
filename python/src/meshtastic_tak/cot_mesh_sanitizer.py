"""Stateless CoT-XML hygiene for LoRa-mesh transport.

Centralized here so every consumer (Meshtastic-Android ``takserver``,
Meshtastic-Apple ``AccessoryManager``, ...) shares ONE golden-tested
implementation instead of each maintaining its own regex list. Those lists
had drifted and silently broken features -- most recently TAK-Talk ``<voice>``
and ``<marti>`` were re-added to one side's strip set, so directed/voice
TAK-Talk stopped surfacing end-to-end.

Pure string transforms -- no platform, protobuf, or compression dependencies.

Regexes use ``[\\s\\S]`` rather than the DOTALL flag so behaviour is identical
across all five language bindings (and Kotlin/Native, which doesn't expose
``RegexOption.DOT_MATCHES_ALL``). The cross-binding fixtures under
``testdata/sanitizer/`` lock byte-for-byte parity.
"""

from __future__ import annotations

import re

# Display-only / receiver-rederivable elements that add ~100-200 wire bytes.
#
# DELIBERATELY ABSENT: <voice> and <marti>. They are TAK-Talk essentials --
# <voice/> marks a push-to-talk (voice) message and <marti><dest
# callsign="..."/></marti> carries the directed-routing recipients. Stripping
# either breaks TAK-Talk: the receiving ATAK plugin can neither play nor
# route the m-t-t. The SDK carries both compactly (voice->bool,
# marti->repeated string) and re-emits them on rebuild, omitting an empty
# marti -- so there is nothing to gain by stripping and a feature to lose.
_STRIP_ELEMENTS = [
    re.compile(p)
    for p in (
        r"<takv[^>]*/>",
        r"<takv[^>]*>[\s\S]*?</takv>",
        r"<__geofence[^>]*/>",
        r"<__geofence[^>]*>[\s\S]*?</__geofence>",
        r"<tog[^>]*/>",
        r"<archive[^>]*/>",
        r"<__shapeExtras[^>]*/>",
        r"<__shapeExtras[^>]*>[\s\S]*?</__shapeExtras>",
        r"<creator[^>]*/>",
        r"<creator[^>]*>[\s\S]*?</creator>",
        r"<remarks[^>]*/>",
        r"<remarks[^>]*></remarks>",
        r"<strokeStyle[^>]*/>",
        r"<precisionlocation[^>]*/>",
        r"<precisionlocation[^>]*>[\s\S]*?</precisionlocation>",
        r"<precisionLocation[^>]*/>",
        r"<precisionLocation[^>]*>[\s\S]*?</precisionLocation>",
    )
]

# Strip any attribute whose value is the literal placeholder "???".
_UNKNOWN_ATTR = re.compile(r'\s+\w+\s*=\s*"\?{3}"')

# Display-only attributes the SDK doesn't carry. Empty callsign/phone only
# (a populated callsign -- e.g. <contact>, <dest> -- is preserved).
_STRIP_ATTRS = [
    re.compile(p)
    for p in (
        r'\s+routetype\s*=\s*"[^"]*"',
        r'\s+order\s*=\s*"[^"]*"',
        r'\s+color\s*=\s*"[^"]*"',
        r'\s+access\s*=\s*"[^"]*"',
        r'\s+callsign\s*=\s*""',
        r'\s+phone\s*=\s*""',
    )
]

# Route-waypoint / shape-vertex <link> elements carry full 36-char UUIDs
# (~40 wire bytes each) the receiver re-derives. Strip uid ONLY from <link>
# elements that have a point= attribute, never from other elements.
_ROUTE_LINK = re.compile(r'<link\s[^>]*\bpoint="[^"]*"[^>]*/>')
_LINK_UID = re.compile(r'\s+uid="[^"]*"')

_XML_DECL = re.compile(r"<\?xml[^>]*\?>")
_INTER_TAG_WS = re.compile(r">\s+<")


def strip_non_essential_for_mesh(xml: str) -> str:
    """Strip display-only CoT ``<detail>`` content to fit the LoRa MTU.

    Preserves everything the receiver needs to render/route -- including
    TAK-Talk ``<voice>`` and ``<marti>``. Safe to run on any CoT XML; a no-op
    when there is nothing to strip.
    """
    result = xml
    for regex in _STRIP_ELEMENTS:
        result = regex.sub("", result)
    result = _UNKNOWN_ATTR.sub("", result)
    for regex in _STRIP_ATTRS:
        result = regex.sub("", result)
    result = _ROUTE_LINK.sub(lambda m: _LINK_UID.sub("", m.group(0)), result)
    return result


def normalize_cot_xml(xml: str) -> str:
    """Normalize CoT XML for the TAK TCP stream.

    Drop the ``<?xml ...?>`` declaration and collapse inter-tag whitespace
    (``>   <`` -> ``><``). TAK clients read a continuous stream of single-line
    events and choke on a pretty-printed, multi-line document with a prologue.
    Whitespace inside text nodes is left intact (only ``>``-whitespace-``<``
    runs collapse).
    """
    result = _XML_DECL.sub("", xml)
    result = _INTER_TAG_WS.sub("><", result)
    return result.strip()


class CotMeshSanitizer:
    """Object-style facade mirroring the Kotlin ``CotMeshSanitizer`` object.

    Both the module-level functions and these static methods are public API;
    the methods delegate to the functions so behaviour is identical.
    """

    strip_non_essential_for_mesh = staticmethod(strip_non_essential_for_mesh)
    normalize_cot_xml = staticmethod(normalize_cot_xml)
