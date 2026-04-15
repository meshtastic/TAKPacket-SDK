import { describe, it, expect } from "vitest";
import { TakCompressor } from "../src/TakCompressor.js";
import { parseCotXml } from "../src/CotXmlParser.js";
import { FIXTURES, loadFixtureXml } from "./helpers.js";

const LORA_MTU = 237;
const compressor = new TakCompressor();

// Minimal synthetic-packet seed for the fallback tests. Using a real fixture
// (`pli_basic.xml`) avoids reconstructing every optional field by hand; the
// tests then mutate `remarks` directly to control wire size.
const SYNTHETIC_XML = `<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="testnode" type="a-f-G-U-C" how="m-g"
       time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z"
       stale="2026-03-15T14:22:55Z">
  <point lat="37.7749" lon="-122.4194" hae="100" ce="4.9" le="9999999"/>
  <detail>
    <contact callsign="TESTER"/>
    <uid Droid="testnode"/>
  </detail>
</event>`;

function makePacketWithRemarks(remarks: string): Record<string, unknown> {
  const pkt = parseCotXml(SYNTHETIC_XML) as Record<string, unknown>;
  pkt.remarks = remarks;
  return pkt;
}

describe("Compression", () => {
  it.each(FIXTURES)("fits under LoRa MTU: %s", async (fixture) => {
    const xml = loadFixtureXml(fixture);
    const pkt = parseCotXml(xml);
    const result = await compressor.compressWithStats(pkt);
    expect(result.compressedSize).toBeLessThanOrEqual(LORA_MTU);
  });

  it("achieves meaningful compression ratio", async () => {
    let totalXml = 0;
    let totalCompressed = 0;
    for (const fixture of FIXTURES) {
      const xml = loadFixtureXml(fixture);
      const pkt = parseCotXml(xml);
      const result = await compressor.compressWithStats(pkt);
      totalXml += xml.length;
      totalCompressed += result.compressedSize;
    }
    const ratio = totalXml / totalCompressed;
    expect(ratio).toBeGreaterThanOrEqual(3.0);
  });
});

describe("compressWithRemarksFallback", () => {
  // These tests pin down the four outcomes of compressWithRemarksFallbackDetailed
  // (fit-as-is / fit-after-strip / dropped-with-remarks / dropped-no-remarks)
  // and the <=-inclusive MTU boundary. They use a synthetic packet built from
  // a tiny seed XML rather than real fixtures, so the thresholds are
  // deterministic regardless of future fixture or dictionary changes.

  it("returns full payload when under limit with no strip", async () => {
    const pkt = makePacketWithRemarks("");
    const result = await compressor.compressWithRemarksFallbackDetailed(pkt, 500);
    expect(result.wirePayload).not.toBeNull();
    expect(result.remarksStripped).toBe(false);
  });

  it("strips remarks when oversized with remarks but fits without", async () => {
    const pkt = makePacketWithRemarks("x".repeat(500));
    const full = await compressor.compress(pkt);
    const stripped = await compressor.compress({ ...pkt, remarks: "" });
    expect(full.length).toBeGreaterThan(stripped.length);

    // Limit sits between the two sizes — stripping must save the packet.
    const limit = stripped.length + Math.floor((full.length - stripped.length) / 2);
    const result = await compressor.compressWithRemarksFallbackDetailed(pkt, limit);
    expect(result.wirePayload).not.toBeNull();
    expect(result.remarksStripped).toBe(true);
    expect(result.wirePayload!.length).toBeLessThanOrEqual(limit);

    // Backward-compat wrapper must agree with the detailed variant.
    const legacy = await compressor.compressWithRemarksFallback(pkt, limit);
    expect(legacy).not.toBeNull();
    expect(legacy!.equals(result.wirePayload!)).toBe(true);
  });

  it("drops when even stripped payload exceeds limit", async () => {
    const pkt = makePacketWithRemarks("x".repeat(500));
    const stripped = await compressor.compress({ ...pkt, remarks: "" });

    // Limit is below even the stripped size — nothing can save this packet.
    const result = await compressor.compressWithRemarksFallbackDetailed(pkt, stripped.length - 1);
    expect(result.wirePayload).toBeNull();
    expect(result.remarksStripped).toBe(true);

    const legacy = await compressor.compressWithRemarksFallback(pkt, stripped.length - 1);
    expect(legacy).toBeNull();
  });

  it("drops when packet has no remarks to strip", async () => {
    const pkt = makePacketWithRemarks("");
    const full = await compressor.compress(pkt);

    const result = await compressor.compressWithRemarksFallbackDetailed(pkt, full.length - 1);
    expect(result.wirePayload).toBeNull();
    expect(result.remarksStripped).toBe(false);
  });

  it("maxWireBytes boundary is inclusive", async () => {
    // Pins the `<=` check. The boundary case (limit == actual size) MUST
    // succeed; limit-1 MUST drop (no remarks to strip).
    const pkt = makePacketWithRemarks("");
    const natural = await compressor.compress(pkt);

    const atLimit = await compressor.compressWithRemarksFallbackDetailed(pkt, natural.length);
    expect(atLimit.wirePayload).not.toBeNull();
    expect(atLimit.wirePayload!.length).toBe(natural.length);
    expect(atLimit.remarksStripped).toBe(false);

    const belowLimit = await compressor.compressWithRemarksFallbackDetailed(pkt, natural.length - 1);
    expect(belowLimit.wirePayload).toBeNull();
  });

  it("accepts every fixture at the real LoRa MTU", async () => {
    // Sanity check: every real fixture survives the full fallback path at
    // the canonical 237B MTU. Worst-case is drawing_telestration at 212B,
    // so all of them should come back with remarksStripped=false.
    for (const fixture of FIXTURES) {
      const xml = loadFixtureXml(fixture);
      const pkt = parseCotXml(xml);
      const result = await compressor.compressWithRemarksFallbackDetailed(pkt, LORA_MTU);
      expect(result.wirePayload, `${fixture}: should fit under LoRa MTU`).not.toBeNull();
      expect(result.wirePayload!.length).toBeLessThanOrEqual(LORA_MTU);
    }
  });
});
