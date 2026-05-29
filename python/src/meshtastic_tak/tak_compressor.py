"""Compresses TAKPacketV2 protobuf bytes using zstd with pre-trained dictionaries."""

from __future__ import annotations

from dataclasses import dataclass
import zstandard
from . import atak_pb2
from .dictionary_provider import DictionaryProvider, DICT_ID_NON_AIRCRAFT, DICT_ID_AIRCRAFT, DICT_ID_UNCOMPRESSED

# Maximum allowed decompressed payload size (bytes). Prevents decompression bombs.
MAX_DECOMPRESSED_SIZE = 4096

# The 4-byte zstd frame magic (little-endian 0xFD2FB528). Stripped on compress
# and prepended on decompress: the SDK is both ends and identifies the frame
# from its own 1-byte flags prefix, so the magic is pure overhead. Done in app
# code (not the binding's native magicless format) so the wire bytes stay
# byte-identical across all 5 language bindings — TypeScript's zstd-napi cannot
# set the experimental magicless parameter. The magic is a fixed constant, so
# this stays fully stateless: every frame is independently reconstructable.
_ZSTD_MAGIC = b"\x28\xb5\x2f\xfd"


@dataclass
class CompressionResult:
    protobuf_size: int
    compressed_size: int
    dict_id: int
    wire_payload: bytes

    @property
    def dict_name(self) -> str:
        if self.dict_id == DICT_ID_NON_AIRCRAFT:
            return "non-aircraft"
        elif self.dict_id == DICT_ID_AIRCRAFT:
            return "aircraft"
        elif self.dict_id == DICT_ID_UNCOMPRESSED:
            return "uncompressed"
        return "unknown"


@dataclass
class RemarksFallbackResult:
    """Result of :meth:`TakCompressor.compress_with_remarks_fallback_detailed`.

    +--------------+--------------------+---------------------------------------+
    | wire_payload | remarks_stripped   | Meaning                               |
    +==============+====================+=======================================+
    | bytes        | False              | Fit as-is, no stripping needed        |
    +--------------+--------------------+---------------------------------------+
    | bytes        | True               | Stripped remarks to make it fit       |
    +--------------+--------------------+---------------------------------------+
    | None         | False              | Too big, had no remarks to strip      |
    +--------------+--------------------+---------------------------------------+
    | None         | True               | Stripped remarks, still too big       |
    +--------------+--------------------+---------------------------------------+

    :ivar wire_payload: The compressed wire bytes, or ``None`` if the caller
        should drop the packet.
    :ivar remarks_stripped: ``True`` if this call stripped the remarks field
        before compressing — either successfully (``wire_payload`` is non-None)
        or unsuccessfully (``wire_payload`` is None because even stripped it
        was too big).
    """

    wire_payload: bytes | None
    remarks_stripped: bool

    @property
    def fits(self) -> bool:
        """Did this call produce a sendable wire payload?"""
        return self.wire_payload is not None


class TakCompressor:
    def __init__(self, compression_level: int = 19):
        self._level = compression_level
        self._dict_data: dict[int, zstandard.ZstdCompressionDict] = {}
        # Reusable one-shot compressors/decompressors per dict. python-zstandard's
        # ``.compress()`` / ``.decompress()`` each produce/consume one independent
        # frame (NOT the streaming API), so reuse carries no cross-packet state —
        # the LoRa-resilience invariant holds.
        self._compressors: dict[int, zstandard.ZstdCompressor] = {}
        self._decompressors: dict[int, zstandard.ZstdDecompressor] = {}

        # Strip redundant frame fields: dict ID lives in our flags byte;
        # content-size / checksum are dead weight on tiny dict-compressed payloads.
        params = zstandard.ZstdCompressionParameters(
            compression_level=compression_level,
            write_dict_id=False,
            write_content_size=False,
            write_checksum=False,
        )
        for dict_id in (DICT_ID_NON_AIRCRAFT, DICT_ID_AIRCRAFT):
            raw = DictionaryProvider.get_dictionary(dict_id)
            if raw:
                zdict = zstandard.ZstdCompressionDict(raw)
                self._dict_data[dict_id] = zdict
                self._compressors[dict_id] = zstandard.ZstdCompressor(
                    dict_data=zdict, compression_params=params
                )
                self._decompressors[dict_id] = zstandard.ZstdDecompressor(dict_data=zdict)

    def compress(self, packet: atak_pb2.TAKPacketV2) -> bytes:
        """Compress a TAKPacketV2 into wire payload: [flags byte][zstd compressed protobuf]."""
        protobuf_bytes = packet.SerializeToString()
        dict_id = DictionaryProvider.select_dict_id(packet.cot_type_id, packet.cot_type_str or None)

        cctx = self._compressors.get(dict_id)
        if cctx is None:
            raise ValueError(f"No dictionary for ID {dict_id}")

        # One independent frame per packet (one-shot, never streaming).
        framed = cctx.compress(protobuf_bytes)
        # Strip the 4-byte magic (see _ZSTD_MAGIC). Defensive: the frame must
        # start with the known magic or our strip/prepend contract is broken.
        if framed[:4] != _ZSTD_MAGIC:
            raise ValueError("Unexpected zstd frame header (magic mismatch)")
        body = framed[4:]

        # Skip compression when it doesn't pay (tiny payloads where frame +
        # dict-reference overhead exceeds entropy saved). 0xFF uncompressed path
        # is already understood by every decoder. Ties -> raw (cheaper decode).
        if len(protobuf_bytes) <= len(body):
            return bytes([DICT_ID_UNCOMPRESSED]) + protobuf_bytes
        return bytes([dict_id & 0x3F]) + body

    def decompress(self, wire_payload: bytes) -> atak_pb2.TAKPacketV2:
        """Decompress wire payload back to TAKPacketV2."""
        if len(wire_payload) < 2:
            raise ValueError(f"Wire payload too short: {len(wire_payload)} bytes")

        flags_byte = wire_payload[0]
        compressed_bytes = wire_payload[1:]

        if flags_byte == DICT_ID_UNCOMPRESSED:
            protobuf_bytes = compressed_bytes
        else:
            dict_id = flags_byte & 0x3F
            dctx = self._decompressors.get(dict_id)
            if dctx is None:
                raise ValueError(f"Unknown dictionary ID: {dict_id}")

            # Re-attach the 4-byte magic stripped on compress (see _ZSTD_MAGIC),
            # yielding a standard frame the stock decoder accepts. The supplied
            # dict (not a frame-embedded dict ID) selects the dictionary.
            restored = _ZSTD_MAGIC + compressed_bytes
            try:
                protobuf_bytes = dctx.decompress(restored, max_output_size=MAX_DECOMPRESSED_SIZE)
            except Exception as e:
                raise ValueError(f"Zstd decompression failed: {e}") from e

        if len(protobuf_bytes) > MAX_DECOMPRESSED_SIZE:
            raise ValueError(f"Payload size {len(protobuf_bytes)} exceeds limit {MAX_DECOMPRESSED_SIZE}")

        try:
            pkt = atak_pb2.TAKPacketV2()
            pkt.ParseFromString(protobuf_bytes)
        except Exception as e:
            raise ValueError(f"Protobuf parsing failed: {e}") from e
        return pkt

    def compress_with_remarks_fallback(
        self,
        packet: atak_pb2.TAKPacketV2,
        max_wire_bytes: int,
    ) -> bytes | None:
        """Compress a packet, stripping remarks if the result exceeds *max_wire_bytes*.

        First attempts compression with remarks intact.  If the wire payload
        fits within *max_wire_bytes*, returns it as-is.  Otherwise, clears the
        remarks field and re-compresses.  Returns ``None`` if even the stripped
        packet exceeds the limit (caller should drop the packet).

        This is a thin wrapper over :meth:`compress_with_remarks_fallback_detailed`
        that discards the ``remarks_stripped`` flag.  Use the ``_detailed``
        variant if you need to tell "fit as-is", "fit after strip", and
        "dropped" apart — e.g. for observability or metrics.

        :param packet:         The packet with remarks populated.
        :param max_wire_bytes: Maximum allowed wire payload size (e.g. 225).
        :returns: The wire payload, or ``None`` if the packet is too large
                  even without remarks.
        """
        return self.compress_with_remarks_fallback_detailed(packet, max_wire_bytes).wire_payload

    def compress_with_remarks_fallback_detailed(
        self,
        packet: atak_pb2.TAKPacketV2,
        max_wire_bytes: int,
    ) -> RemarksFallbackResult:
        """Compress a packet, stripping remarks if needed, and return a detailed
        result that distinguishes the four possible outcomes.  See
        :class:`RemarksFallbackResult` for the outcome table.

        Callers that want to log/meter "how often does remarks-stripping save
        a packet" should use this variant;
        :meth:`compress_with_remarks_fallback` loses the distinction.
        """
        full = self.compress(packet)
        if len(full) <= max_wire_bytes:
            return RemarksFallbackResult(wire_payload=full, remarks_stripped=False)

        if not packet.remarks:
            return RemarksFallbackResult(wire_payload=None, remarks_stripped=False)

        stripped = atak_pb2.TAKPacketV2()
        stripped.CopyFrom(packet)
        stripped.remarks = ""
        stripped_wire = self.compress(stripped)
        if len(stripped_wire) <= max_wire_bytes:
            return RemarksFallbackResult(wire_payload=stripped_wire, remarks_stripped=True)
        return RemarksFallbackResult(wire_payload=None, remarks_stripped=True)

    def compress_with_stats(self, packet: atak_pb2.TAKPacketV2) -> CompressionResult:
        """Compress and return stats for reporting."""
        protobuf_bytes = packet.SerializeToString()
        wire_payload = self.compress(packet)
        # Report the ACTUAL emitted mode from the flags byte — skip-compress may
        # have emitted 0xFF (uncompressed) when compression didn't pay.
        flags = wire_payload[0]
        dict_id = flags if flags == DICT_ID_UNCOMPRESSED else (flags & 0x3F)

        return CompressionResult(
            protobuf_size=len(protobuf_bytes),
            compressed_size=len(wire_payload),
            dict_id=dict_id,
            wire_payload=wire_payload,
        )
