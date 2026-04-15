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

  // -- Decompression size-cap boundary tests (audit item #19) ---------------
  //
  // The existing decompression_bomb.bin fixture proves "> 4096 rejects" for
  // a dict-compressed payload via the zstd library's max_output_size guard.
  // These tests pin the boundary on the 0xFF uncompressed path — the only
  // branch where TakCompressor enforces the cap itself — with synthetic
  // wire payloads of exactly 4096 and 4097 bytes.

  const MAX_DECOMPRESSED_SIZE = 4096;

  it("rejects uncompressed payload over MAX_DECOMPRESSED_SIZE", async () => {
    // [0xFF] + 4097 bytes of anything -> size check MUST fire before
    // the bytes are handed to the protobuf parser.
    const wire = Buffer.alloc(1 + MAX_DECOMPRESSED_SIZE + 1);
    wire[0] = 0xff;
    await expect(compressor.decompress(wire)).rejects.toThrow(/exceeds limit/);
  });

  it("accepts uncompressed payload at MAX_DECOMPRESSED_SIZE (size guard inclusive)", async () => {
    // [0xFF] + exactly 4096 bytes. The size check is `> MAX_DECOMPRESSED_SIZE`
    // so 4096 bytes is within the limit. 4096 zero bytes is NOT valid
    // protobuf (field tag 0 is reserved), so the call will still throw —
    // but the failure must come from the downstream protobuf parse step,
    // NOT from the size guard.
    const wire = Buffer.alloc(1 + MAX_DECOMPRESSED_SIZE);
    wire[0] = 0xff;
    try {
      await compressor.decompress(wire);
      // If it somehow parses successfully, that's fine — no size error.
    } catch (e: unknown) {
      const msg = e instanceof Error ? e.message : String(e);
      expect(msg, `size check fired at the exact boundary: ${msg}`).not.toMatch(/exceeds limit/);
    }
  });
});
