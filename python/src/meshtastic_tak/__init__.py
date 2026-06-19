"""TAKPacket-SDK — ATAK CoT XML to Meshtastic ``TAKPacketV2`` for LoRa mesh.

This package converts ATAK Cursor-on-Target (CoT) XML into Meshtastic's
``TAKPacketV2`` protobuf and compresses it with zstd dictionary compression for
transport over a LoRa mesh (237-byte MTU, port 78, ``ATAK_PLUGIN_V2``). It is
the Python binding of TAKPacket-SDK; five parallel language bindings (Kotlin,
Swift, Python, TypeScript, C#) produce cross-decodable wire payloads.

Pipeline
--------
Encode: CoT XML -> :class:`CotXmlParser` -> ``TAKPacketV2`` protobuf ->
:class:`TakCompressor` -> ``[1-byte flags][zstd body]`` wire payload.

Decode: wire payload -> :class:`TakCompressor` -> ``TAKPacketV2`` ->
:class:`CotXmlBuilder` -> CoT XML.

Before encoding, callers may run :func:`strip_non_essential_for_mesh` and
:func:`normalize_cot_xml` (or the :class:`CotMeshSanitizer` facade) to drop
display-only detail and fit the MTU without losing routable content.

Wire format
-----------
- MTU 237 bytes; LoRa port 78.
- Wire payload is ``[1 byte flags][N bytes zstd body]``.
- Flags byte: bits 0-5 = dictionary ID, bits 6-7 reserved (zeroed on send,
  ignored on receive). Dict ID 0 = non-aircraft dictionary, 1 = aircraft,
  ``0xFF`` = uncompressed raw protobuf (skip-compress path for tiny payloads).
- The 4-byte zstd frame magic is stripped on encode and re-prepended on decode
  to save 4 bytes; the body on the wire carries no magic number.
- Maximum decompressed size is guarded at 4096 bytes.

Resilience invariant
---------------------
Every packet is fully, independently decodable from its own bytes plus the
static shipped dictionary — there is ZERO cross-packet state. Only the one-shot
zstd compress/decompress API is used (never the streaming API), and the
dictionary is static, never adapted from runtime traffic. LoRa is lossy; losing
one packet never affects any other.

PLI note
--------
A Position Location Information (PLI) report is implicit: a packet with no
payload variant set and an ``a-f-*`` CoT type IS a PLI. There is no ``pli``
boolean on the wire.

Public API
----------
- :class:`CotXmlParser` — CoT XML string to ``TAKPacketV2`` protobuf.
- :class:`CotXmlBuilder` — ``TAKPacketV2`` protobuf to CoT XML string.
- :class:`TakCompressor` — ``TAKPacketV2`` to/from the compressed wire payload.
- :class:`CotTypeMapper` — bidirectional CoT type string <-> enum, plus
  aircraft classification.
- :class:`DictionaryProvider` — loads the shipped zstd dictionaries and selects
  the dictionary ID for a packet.
- :class:`CotMeshSanitizer` — object-style facade over the sanitizer functions.
- :func:`strip_non_essential_for_mesh` — drop display-only CoT detail.
- :func:`normalize_cot_xml` — drop the XML declaration and collapse inter-tag
  whitespace for the TAK TCP stream.
"""

from __future__ import annotations

from .cot_type_mapper import CotTypeMapper
from .cot_xml_parser import CotXmlParser
from .cot_xml_builder import CotXmlBuilder
from .tak_compressor import TakCompressor
from .dictionary_provider import DictionaryProvider
from .cot_mesh_sanitizer import (
    CotMeshSanitizer,
    normalize_cot_xml,
    strip_non_essential_for_mesh,
)

__all__ = [
    "CotTypeMapper",
    "CotXmlParser",
    "CotXmlBuilder",
    "TakCompressor",
    "DictionaryProvider",
    "CotMeshSanitizer",
    "normalize_cot_xml",
    "strip_non_essential_for_mesh",
]
