"""Compresses TAKPacketV2 protobuf bytes using zstd with pre-trained dictionaries."""

from dataclasses import dataclass
import zstandard
from . import atak_pb2
from .dictionary_provider import DictionaryProvider, DICT_ID_NON_AIRCRAFT, DICT_ID_AIRCRAFT, DICT_ID_UNCOMPRESSED

# Maximum allowed decompressed payload size (bytes). Prevents decompression bombs.
MAX_DECOMPRESSED_SIZE = 4096


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
        return "unknown"


class TakCompressor:
    def __init__(self, compression_level: int = 19):
        self._level = compression_level
        self._compressors: dict[int, zstandard.ZstdCompressionDict] = {}
        self._dict_data: dict[int, zstandard.ZstdCompressionDict] = {}

        for dict_id in (DICT_ID_NON_AIRCRAFT, DICT_ID_AIRCRAFT):
            raw = DictionaryProvider.get_dictionary(dict_id)
            if raw:
                self._dict_data[dict_id] = zstandard.ZstdCompressionDict(raw)

    def compress(self, packet: atak_pb2.TAKPacketV2) -> bytes:
        """Compress a TAKPacketV2 into wire payload: [flags byte][zstd compressed protobuf]."""
        protobuf_bytes = packet.SerializeToString()
        dict_id = DictionaryProvider.select_dict_id(packet.cot_type_id, packet.cot_type_str or None)

        zdict = self._dict_data.get(dict_id)
        if zdict is None:
            raise ValueError(f"No dictionary for ID {dict_id}")

        cctx = zstandard.ZstdCompressor(level=self._level, dict_data=zdict)
        compressed = cctx.compress(protobuf_bytes)

        flags_byte = bytes([dict_id & 0x3F])
        return flags_byte + compressed

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
            zdict = self._dict_data.get(dict_id)
            if zdict is None:
                raise ValueError(f"Unknown dictionary ID: {dict_id}")

            dctx = zstandard.ZstdDecompressor(dict_data=zdict)
            try:
                protobuf_bytes = dctx.decompress(compressed_bytes, max_output_size=MAX_DECOMPRESSED_SIZE)
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

    def compress_best_of(
        self,
        packet: atak_pb2.TAKPacketV2,
        raw_detail_bytes: bytes,
    ) -> bytes:
        """Compress with whichever format yields the smaller wire payload.

        Tries both the fully-typed :class:`TAKPacketV2` (the default
        :meth:`compress` path) and a ``raw_detail`` fallback carrying the
        original ``<detail>`` bytes, then returns the smaller of the two.

        On every bundled fixture the typed path wins — delta-encoded
        geometry and palette-enum colors compress much tighter than raw
        XML tag names, even with a 16KB zstd dictionary.  This method is a
        safety net for CoT types the structured parser can't decompose or
        for shapes with geometry beyond ``MAX_VERTICES`` that would
        otherwise be silently truncated.

        The fallback path strips detail-derived top-level fields
        (callsign, takVersion, …) from the alternate packet so the
        ``<detail>`` content isn't duplicated on the wire; the receiver
        re-parses those fields out of the raw bytes if needed.

        :param packet:           Typed-variant packet from
                                 :meth:`CotXmlParser.parse`.
        :param raw_detail_bytes: Raw ``<detail>`` inner bytes from
                                 :meth:`CotXmlParser.extract_raw_detail_bytes`.
        :returns: Whichever wire payload is smaller.  Ties go to the typed
                  packet since it preserves strong typing on the receiver.
        """
        typed_wire = self.compress(packet)
        if not raw_detail_bytes:
            return typed_wire

        # Clear every detail-derived top-level field in the fallback packet.
        # These come from <contact>, <__group>, <status>, <track>, <takv>,
        # <precisionlocation>, and <uid Droid="…"/> — all of which live
        # inside <detail>, so they'd be duplicated if we shipped them both
        # as proto fields and inside raw_detail.  The envelope (uid,
        # cot_type_id, how, stale, lat/lon/alt) stays intact.
        raw_packet = atak_pb2.TAKPacketV2()
        raw_packet.CopyFrom(packet)
        raw_packet.callsign = ""
        raw_packet.team = 0
        raw_packet.role = 0
        raw_packet.battery = 0
        raw_packet.speed = 0
        raw_packet.course = 0
        raw_packet.device_callsign = ""
        raw_packet.tak_version = ""
        raw_packet.tak_device = ""
        raw_packet.tak_platform = ""
        raw_packet.tak_os = ""
        raw_packet.endpoint = ""
        raw_packet.phone = ""
        raw_packet.geo_src = 0
        raw_packet.alt_src = 0
        raw_packet.raw_detail = raw_detail_bytes  # sets the oneof

        raw_wire = self.compress(raw_packet)
        return raw_wire if len(raw_wire) < len(typed_wire) else typed_wire

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

        :param packet:         The packet with remarks populated.
        :param max_wire_bytes: Maximum allowed wire payload size (e.g. 225).
        :returns: The wire payload, or ``None`` if the packet is too large
                  even without remarks.
        """
        full = self.compress(packet)
        if len(full) <= max_wire_bytes:
            return full

        # Strip remarks and retry
        if not packet.remarks:
            return None
        stripped = atak_pb2.TAKPacketV2()
        stripped.CopyFrom(packet)
        stripped.remarks = ""
        stripped_wire = self.compress(stripped)
        return stripped_wire if len(stripped_wire) <= max_wire_bytes else None

    def compress_with_stats(self, packet: atak_pb2.TAKPacketV2) -> CompressionResult:
        """Compress and return stats for reporting."""
        protobuf_bytes = packet.SerializeToString()
        wire_payload = self.compress(packet)
        dict_id = DictionaryProvider.select_dict_id(packet.cot_type_id, packet.cot_type_str or None)

        return CompressionResult(
            protobuf_size=len(protobuf_bytes),
            compressed_size=len(wire_payload),
            dict_id=dict_id,
            wire_payload=wire_payload,
        )
