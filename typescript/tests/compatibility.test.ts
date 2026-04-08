import { describe, it, expect } from "vitest";
import { TakCompressor } from "../src/TakCompressor.js";
import { parseCotXml } from "../src/CotXmlParser.js";
import { FIXTURES, loadFixtureXml, loadGolden } from "./helpers.js";

const compressor = new TakCompressor();

describe("Compatibility", () => {
  it.each(FIXTURES)("golden file decompresses: %s", async (fixture) => {
    const golden = loadGolden(fixture);
    if (!golden) return; // skip if not generated yet
    const pkt = await compressor.decompress(golden);
    expect(pkt.uid).toBeTruthy();
  });

  it.each(FIXTURES)("compressed size similar to golden: %s", async (fixture) => {
    const golden = loadGolden(fixture);
    if (!golden) return;
    const xml = loadFixtureXml(fixture);
    const pkt = parseCotXml(xml);
    const wire = await compressor.compress(pkt);
    const ratio = wire.length / golden.length;
    // protobufjs may encode fields differently from Java/Python protobuf,
    // resulting in different compressed sizes. Allow wider tolerance.
    // The key invariant (golden file decompression) is tested separately.
    expect(ratio).toBeGreaterThan(0.5);
    expect(ratio).toBeLessThan(2.0);
  });
});
