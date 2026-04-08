"""Compresses TAKPacketV2 protobuf bytes using zstd with pre-trained dictionaries."""

from dataclasses import dataclass
import zstandard
from . import atak_pb2
from .dictionary_provider import DictionaryProvider, DICT_ID_NON_AIRCRAFT, DICT_ID_AIRCRAFT, DICT_ID_UNCOMPRESSED


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
            protobuf_bytes = dctx.decompress(compressed_bytes)

        pkt = atak_pb2.TAKPacketV2()
        pkt.ParseFromString(protobuf_bytes)
        return pkt

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
