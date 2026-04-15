import os
import pytest
from datetime import date
from meshtastic_tak import CotXmlParser, TakCompressor
from conftest import FIXTURE_NAMES, TESTDATA_DIR, load_fixture_xml

LORA_MTU = 237

parser = CotXmlParser()
compressor = TakCompressor()


@pytest.mark.parametrize("fixture", FIXTURE_NAMES)
def test_fits_under_lora_mtu(fixture):
    xml = load_fixture_xml(fixture)
    pkt = parser.parse(xml)
    result = compressor.compress_with_stats(pkt)
    assert result.compressed_size <= LORA_MTU, (
        f"{fixture}: {result.compressed_size}B exceeds LoRa MTU {LORA_MTU}B"
    )


def test_meaningful_compression_ratio():
    total_xml = 0
    total_compressed = 0
    for fixture in FIXTURE_NAMES:
        xml = load_fixture_xml(fixture)
        pkt = parser.parse(xml)
        result = compressor.compress_with_stats(pkt)
        total_xml += len(xml)
        total_compressed += result.compressed_size
    ratio = total_xml / total_compressed
    assert ratio >= 3.0, f"Average compression ratio {ratio:.1f}x below 3x minimum"


def test_generate_compression_report():
    """Generate the compression-report.md living document."""
    rows = []
    for fixture in FIXTURE_NAMES:
        xml = load_fixture_xml(fixture)
        pkt = parser.parse(xml)
        result = compressor.compress_with_stats(pkt)
        from meshtastic_tak.cot_type_mapper import CotTypeMapper
        cot_type = CotTypeMapper.type_to_string(pkt.cot_type_id) or pkt.cot_type_str or ""
        rows.append({
            "fixture": fixture,
            "cot_type": cot_type,
            "xml_size": len(xml),
            "proto_size": result.protobuf_size,
            "compressed_size": result.compressed_size,
            "ratio": len(xml) / result.compressed_size,
            "dict_name": result.dict_name,
        })

    all_under_mtu = all(r["compressed_size"] <= LORA_MTU for r in rows)
    compressed_sizes = sorted(r["compressed_size"] for r in rows)
    median_compressed = compressed_sizes[len(compressed_sizes) // 2]
    ratios = sorted(r["ratio"] for r in rows)
    median_ratio = ratios[len(ratios) // 2]
    worst = max(rows, key=lambda r: r["compressed_size"])

    lines = [
        "# TAKPacket-SDK Compression Report",
        f"Generated: {date.today()} | Dictionary: v1 (non-aircraft 8KB + aircraft 4KB)",
        "",
        "## Summary",
        "| Metric | Value |",
        "|--------|-------|",
        f"| Total test messages | {len(rows)} |",
        f"| 100% under {LORA_MTU}B | {'YES' if all_under_mtu else 'NO'} |",
        f"| Median compressed size | {median_compressed}B |",
        f"| Median compression ratio | {median_ratio:.1f}x |",
        f'| Worst case | {worst["compressed_size"]}B ({worst["compressed_size"] * 100 // LORA_MTU}% of LoRa MTU) |',
        "",
        "## Per-Message Results",
        "| Fixture | CoT Type | XML Size | Proto Size | Compressed | Ratio | Dict |",
        "|---------|----------|----------|------------|------------|-------|------|",
    ]
    for r in rows:
        lines.append(
            f'| {r["fixture"]} | {r["cot_type"]} | {r["xml_size"]}B | {r["proto_size"]}B '
            f'| {r["compressed_size"]}B | {r["ratio"]:.1f}x | {r["dict_name"]} |'
        )

    lines.append("")
    lines.append("## Size Distribution")
    lines.append("```")
    for r in sorted(rows, key=lambda x: x["compressed_size"]):
        bar = "#" * max(1, int(r["compressed_size"] / LORA_MTU * 50))
        lines.append(f'{r["fixture"]:<20} {r["compressed_size"]:>4}B |{bar}')
    lines.append(f'{"LoRa MTU":<20} {LORA_MTU:>4}B |{"#" * 50}')
    lines.append("```")

    report_path = os.path.join(TESTDATA_DIR, "compression-report.md")
    with open(report_path, "w") as f:
        f.write("\n".join(lines) + "\n")

    assert os.path.exists(report_path)
    assert all_under_mtu
