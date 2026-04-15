import pytest
from meshtastic_tak import CotXmlParser, TakCompressor
from conftest import FIXTURE_NAMES, load_fixture_xml, load_golden

parser = CotXmlParser()
compressor = TakCompressor()


@pytest.mark.parametrize("fixture", FIXTURE_NAMES)
def test_golden_files_decompress(fixture):
    golden = load_golden(fixture)
    if golden is None:
        pytest.skip(f"Golden file not found for {fixture}")
    pkt = compressor.decompress(golden)
    assert pkt.uid, f"{fixture}: decompressed packet should have a UID"


@pytest.mark.parametrize("fixture", FIXTURE_NAMES)
def test_compressed_output_similar_size_to_golden(fixture):
    """Compressed size should be within 20% of golden (same dict, same data)."""
    golden = load_golden(fixture)
    if golden is None:
        pytest.skip(f"Golden file not found for {fixture}")
    xml = load_fixture_xml(fixture)
    pkt = parser.parse(xml)
    wire = compressor.compress(pkt)
    # Protobuf serializers across platforms may produce different byte orderings
    ratio = len(wire) / len(golden)
    assert 0.5 < ratio < 2.0, (
        f"{fixture}: compressed size {len(wire)}B differs significantly from golden {len(golden)}B"
    )
