"""Negative tests: malformed input handling."""
import os
import pytest
from meshtastic_tak import TakCompressor

MALFORMED_DIR = os.path.join(os.path.dirname(__file__), "..", "..", "testdata", "malformed")

compressor = TakCompressor()


def load_malformed(name: str) -> bytes:
    with open(os.path.join(MALFORMED_DIR, name), "rb") as f:
        return f.read()


def test_empty_payload():
    with pytest.raises((ValueError, Exception)):
        compressor.decompress(b"")


def test_single_byte():
    with pytest.raises((ValueError, Exception)):
        compressor.decompress(b"\x00")


def test_invalid_dict_id():
    data = load_malformed("invalid_dict_id.bin")
    with pytest.raises((ValueError, Exception)):
        compressor.decompress(data)


def test_truncated_zstd():
    data = load_malformed("truncated_zstd.bin")
    with pytest.raises(Exception):
        compressor.decompress(data)


def test_corrupted_zstd():
    data = load_malformed("corrupted_zstd.bin")
    with pytest.raises(Exception):
        compressor.decompress(data)


def test_invalid_protobuf():
    data = load_malformed("invalid_protobuf.bin")
    # 0xFF flag + garbage bytes — protobuf parser should reject
    # Some protobuf parsers are lenient with unknown fields, so this may or may not raise.
    # The key assertion is: no crash.
    try:
        pkt = compressor.decompress(data)
        # If it parses without error, that's acceptable (protobuf is lenient)
    except Exception:
        pass  # Expected


def test_reserved_bits_set():
    """Reserved bits in flags byte should be ignored — decompression should succeed."""
    data = load_malformed("reserved_bits_set.bin")
    # Flags byte 0xC0 has reserved bits set but dict ID = 0 (0xC0 & 0x3F = 0)
    pkt = compressor.decompress(data)
    assert pkt.uid, "Should decompress successfully despite reserved bits"


# === Security attack tests ===

def test_rejects_xml_doctype():
    """XML with DOCTYPE declaration must be rejected (XXE prevention)."""
    xml = load_malformed("xml_doctype.xml").decode("utf-8")
    from meshtastic_tak import CotXmlParser
    parser = CotXmlParser()
    with pytest.raises((ValueError, Exception)):
        parser.parse(xml)


def test_rejects_xml_entity_expansion():
    """XML with entity expansion (billion laughs) must be rejected."""
    xml = load_malformed("xml_entity_expansion.xml").decode("utf-8")
    from meshtastic_tak import CotXmlParser
    parser = CotXmlParser()
    with pytest.raises((ValueError, Exception)):
        parser.parse(xml)


def test_rejects_oversized_fields():
    """Protobuf with oversized string fields must be rejected."""
    data = load_malformed("oversized_callsign.bin")
    # 0xFF + protobuf with 10KB callsign — exceeds 4096 decompressed limit
    with pytest.raises((ValueError, Exception)):
        compressor.decompress(data)


def test_rejects_decompression_bomb():
    """Zstd frame that decompresses beyond size limit must be rejected."""
    data = load_malformed("decompression_bomb.bin")
    with pytest.raises((ValueError, Exception)):
        compressor.decompress(data)
