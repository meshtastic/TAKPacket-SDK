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
    """Stats and bytes from :meth:`TakCompressor.compress_with_stats`.

    :ivar protobuf_size: Size in bytes of the serialized ``TAKPacketV2``
        protobuf before compression.
    :ivar compressed_size: Size in bytes of the full wire payload (the flags
        byte plus the body).
    :ivar dict_id: The dictionary ID actually emitted in the flags byte —
        0/1 for a dictionary-compressed payload, or
        :data:`DICT_ID_UNCOMPRESSED` (``0xFF``) when the skip-compress path
        emitted raw protobuf.
    :ivar wire_payload: The full ``[flags][body]`` wire payload bytes.
    """

    protobuf_size: int
    compressed_size: int
    dict_id: int
    wire_payload: bytes

    @property
    def dict_name(self) -> str:
        """Human-readable name of the emitted mode for reporting.

        Returns:
            ``"non-aircraft"``, ``"aircraft"``, ``"uncompressed"``, or
            ``"unknown"`` for an unrecognized :attr:`dict_id`.
        """
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
    """Compresses ``TAKPacketV2`` to/from the LoRa wire payload.

    Produces the ``[1 byte flags][zstd body]`` wire payload and reverses it.
    Bits 0-5 of the flags byte carry the dictionary ID; ``0xFF`` marks an
    uncompressed raw-protobuf payload. The 4-byte zstd frame magic is stripped
    on compress and re-prepended on decompress (see :data:`_ZSTD_MAGIC`), so
    the on-wire body carries no magic number.

    Resilience invariant: each packet is one independent zstd frame using the
    static shipped dictionary, produced/consumed only via the one-shot
    compress/decompress API — never the streaming API. There is no cross-packet
    state, so any packet decodes from its own bytes alone. The per-dictionary
    compressor/decompressor objects are reused only as a performance
    optimization; reuse carries no state across frames.

    An instance loads the dictionaries eagerly in :meth:`__init__`; construct
    one and reuse it for many packets.
    """

    def __init__(self, compression_level: int = 19):
        """Build a compressor and load the shipped dictionaries.

        Eagerly loads the non-aircraft and aircraft dictionaries and builds a
        reusable one-shot compressor and decompressor for each. The compressors
        write frames with the dictionary ID, content size, and checksum fields
        all OFF — the dictionary ID lives in the SDK's own flags byte and the
        other two are dead weight on tiny dict-compressed payloads.

        Args:
            compression_level: zstd compression level; defaults to 19 (the
                maximum, used for the shipped goldens).
        """
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
        """Compress a ``TAKPacketV2`` into a wire payload.

        Serializes the packet, picks the dictionary from its CoT type, and
        emits ``[1 byte flags][zstd body]`` with the 4-byte frame magic
        stripped. If compression does not pay (the raw protobuf is no larger
        than the zstd body), emits the skip-compress form
        ``[0xFF][raw protobuf]`` instead, so tiny/incompressible packets never
        expand. Each call produces one independent frame (one-shot, never
        streaming).

        Args:
            packet: The packet to compress.

        Returns:
            The wire payload bytes (flags byte plus body).

        Raises:
            ValueError: If no dictionary is configured for the selected ID, or
                if the produced zstd frame does not start with the expected
                magic (which would break the strip/prepend contract).
        """
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
        """Decompress a wire payload back to a ``TAKPacketV2``.

        Reads the dictionary ID from bits 0-5 of the flags byte (or detects the
        ``0xFF`` uncompressed form), re-attaches the stripped 4-byte frame magic
        for the dictionary-compressed case, decompresses with the matching
        dictionary, and parses the protobuf. Output is capped at
        :data:`MAX_DECOMPRESSED_SIZE` bytes as a decompression-bomb guard.

        Args:
            wire_payload: The ``[flags][body]`` wire payload bytes.

        Returns:
            The decoded ``TAKPacketV2`` message.

        Raises:
            ValueError: If the payload is shorter than 2 bytes, the dictionary
                ID is unknown, decompression fails, the decompressed size
                exceeds :data:`MAX_DECOMPRESSED_SIZE`, or protobuf parsing
                fails.
        """
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
        """Compress a packet and return sizing stats alongside the payload.

        Reports the mode actually emitted from the flags byte, so the
        skip-compress path is visible as ``DICT_ID_UNCOMPRESSED`` rather than
        the dictionary that was tried. Used to build the compression report.

        Args:
            packet: The packet to compress.

        Returns:
            A :class:`CompressionResult` with the protobuf size, wire size,
            emitted dictionary ID, and the wire payload.
        """
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
