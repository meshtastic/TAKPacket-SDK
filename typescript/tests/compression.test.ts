import { describe, it, expect } from "vitest";
import { TakCompressor } from "../src/TakCompressor.js";
import { parseCotXml } from "../src/CotXmlParser.js";
import { FIXTURES, loadFixtureXml } from "./helpers.js";

const LORA_MTU = 237;
const compressor = new TakCompressor();

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
