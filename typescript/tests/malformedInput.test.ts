import { describe, it, expect } from "vitest";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { TakCompressor } from "../src/TakCompressor.js";
import { parseCotXml } from "../src/CotXmlParser.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const MALFORMED_DIR = path.resolve(__dirname, "../../testdata/malformed");

const compressor = new TakCompressor();

function load(name: string): Buffer {
  return fs.readFileSync(path.join(MALFORMED_DIR, name));
}

describe("Malformed Input", () => {
  it("rejects empty payload", async () => {
    await expect(compressor.decompress(Buffer.alloc(0))).rejects.toThrow();
  });

  it("rejects single byte", async () => {
    await expect(compressor.decompress(Buffer.from([0x00]))).rejects.toThrow();
  });

  it("rejects invalid dictionary ID", async () => {
    await expect(compressor.decompress(load("invalid_dict_id.bin"))).rejects.toThrow();
  });

  it("rejects truncated zstd frame", async () => {
    await expect(compressor.decompress(load("truncated_zstd.bin"))).rejects.toThrow();
  });

  it("rejects corrupted zstd", async () => {
    await expect(compressor.decompress(load("corrupted_zstd.bin"))).rejects.toThrow();
  });

  it("handles invalid protobuf without crash", async () => {
    // 0xFF + garbage bytes — protobuf parser may be lenient or may throw
    try {
      await compressor.decompress(load("invalid_protobuf.bin"));
    } catch {
      // Expected — either outcome is acceptable, just no crash
    }
  });

  it("ignores reserved bits in flags byte", async () => {
    // 0xC0 has reserved bits set but dict ID = 0 (0xC0 & 0x3F = 0)
    const pkt = await compressor.decompress(load("reserved_bits_set.bin"));
    expect(pkt.uid).toBeTruthy();
  });

  // Security attack tests

  it("rejects XML with DOCTYPE declaration", () => {
    const xml = load("xml_doctype.xml").toString("utf-8");
    expect(() => parseCotXml(xml)).toThrow();
  });

  it("rejects XML with entity expansion", () => {
    const xml = load("xml_entity_expansion.xml").toString("utf-8");
    expect(() => parseCotXml(xml)).toThrow();
  });

  it("rejects oversized protobuf fields", async () => {
    await expect(compressor.decompress(load("oversized_callsign.bin"))).rejects.toThrow();
  });

  it("rejects decompression bomb", async () => {
    await expect(compressor.decompress(load("decompression_bomb.bin"))).rejects.toThrow();
  });
});
