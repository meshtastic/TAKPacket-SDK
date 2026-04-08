#!/usr/bin/env python3
"""Generate malformed test fixtures for negative/error testing."""

import os
import random

SCRIPT_DIR = os.path.dirname(os.path.abspath(__file__))
GOLDEN_DIR = os.path.join(SCRIPT_DIR, "golden")
MALFORMED_DIR = os.path.join(SCRIPT_DIR, "malformed")

os.makedirs(MALFORMED_DIR, exist_ok=True)

# Use a fixed seed for reproducible fixtures
random.seed(42)


def write(name: str, data: bytes):
    path = os.path.join(MALFORMED_DIR, name)
    with open(path, "wb") as f:
        f.write(data)
    print(f"  {name}: {len(data)} bytes")


def load_golden(name: str) -> bytes:
    with open(os.path.join(GOLDEN_DIR, name), "rb") as f:
        return f.read()


print("Generating malformed test fixtures...")

# 1. Empty payload (0 bytes)
write("empty.bin", b"")

# 2. Single byte (just flags, no compressed data)
write("single_byte.bin", bytes([0x00]))

# 3. Invalid dictionary ID (0x05, which is reserved)
golden = load_golden("pli_basic.bin")
write("invalid_dict_id.bin", bytes([0x05]) + golden[1:])

# 4. Truncated zstd frame (flags + first 10 bytes of compressed data)
write("truncated_zstd.bin", golden[:11])

# 5. Corrupted zstd (flags byte + random garbage)
write("corrupted_zstd.bin", bytes([0x00]) + bytes(random.getrandbits(8) for _ in range(50)))

# 6. Invalid protobuf (0xFF uncompressed flag + random bytes, not valid protobuf)
write("invalid_protobuf.bin", bytes([0xFF]) + bytes(random.getrandbits(8) for _ in range(30)))

# 7. Reserved bits set (flags = 0xC0 | 0x00 = dict ID 0 with reserved bits set)
# Should still decompress successfully (receiver ignores reserved bits)
write("reserved_bits_set.bin", bytes([0xC0]) + golden[1:])

# === Security attack fixtures ===

# 8. XML with DOCTYPE declaration (XXE attempt)
write("xml_doctype.xml",
      b'<?xml version="1.0"?><!DOCTYPE foo [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>'
      b'<event version="2.0" uid="evil" type="a-f-G-U-C" how="m-g" '
      b'time="2026-01-01T00:00:00Z" start="2026-01-01T00:00:00Z" stale="2026-01-01T00:01:00Z">'
      b'<point lat="0" lon="0" hae="0" ce="0" le="0"/>'
      b'<detail><contact callsign="&xxe;"/></detail></event>')

# 9. XML entity expansion bomb (billion laughs)
write("xml_entity_expansion.xml",
      b'<?xml version="1.0"?><!DOCTYPE lolz ['
      b'<!ENTITY lol "lol"><!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">'
      b'<!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">'
      b']><event version="2.0" uid="bomb" type="a-f-G-U-C" how="m-g" '
      b'time="2026-01-01T00:00:00Z" start="2026-01-01T00:00:00Z" stale="2026-01-01T00:01:00Z">'
      b'<point lat="0" lon="0" hae="0" ce="0" le="0"/>'
      b'<detail><contact callsign="&lol3;"/></detail></event>')

# 10. Oversized callsign field (0xFF + protobuf with 10KB callsign)
# Build a minimal TAKPacketV2 protobuf with an enormous callsign
# Field 3 (callsign) = tag 0x1a, length-delimited
big_callsign = b"A" * 10000
# Protobuf: field 1 (cot_type_id) varint = 0x08 0x01, field 3 (callsign) = 0x1a + varint_len + data
import struct
def encode_varint(value):
    parts = []
    while value > 127:
        parts.append((value & 0x7F) | 0x80)
        value >>= 7
    parts.append(value)
    return bytes(parts)

oversized_proto = b"\x08\x01" + b"\x1a" + encode_varint(len(big_callsign)) + big_callsign
write("oversized_callsign.bin", bytes([0xFF]) + oversized_proto)

# 11. Decompression bomb — create a zstd frame that decompresses to much more than 4KB
# Compress 64KB of zeros with dict 0 (non-aircraft)
import zstandard
dict_path = os.path.join(SCRIPT_DIR, "..", "dictionaries", "dict_non_aircraft.zstd")
with open(dict_path, "rb") as f:
    dict_data = zstandard.ZstdCompressionDict(f.read())
cctx = zstandard.ZstdCompressor(level=1, dict_data=dict_data)
bomb_data = cctx.compress(b"\x00" * 65536)  # 64KB of zeros -> small compressed
write("decompression_bomb.bin", bytes([0x00]) + bomb_data)

print(f"\nGenerated {len(os.listdir(MALFORMED_DIR))} fixtures in {MALFORMED_DIR}")
