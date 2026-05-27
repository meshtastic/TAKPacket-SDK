import { describe, it, expect } from "vitest";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { TakCompressor } from "../src/TakCompressor.js";
import { parseCotXml } from "../src/CotXmlParser.js";
import { COTTYPE_M_T_T, COTTYPE_Y_DASH } from "../src/CotTypeMapper.js";

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

  // -- TAKTALK edge-case tests ---------------------------------------------
  //
  // These confirm that anomalous-but-legal TAKTALK shapes — empty bodies,
  // missing children, surprise sibling elements — parse without crashing
  // and don't corrupt the surrounding envelope.

  it("m-t-t with empty text parses without crash and round-trips", async () => {
    const xml = `<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="TAKTALK-MESSAGE-empty" type="m-t-t" how="null"
    time="2026-05-27T01:36:25.023Z" start="2026-05-27T01:36:25.023Z"
    stale="2026-05-27T01:42:26.251Z">
  <point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
  <detail>
    <callsign>ASPEN</callsign>
    <lang>English</lang>
    <text></text>
    <chatroom-id>1</chatroom-id>
  </detail>
</event>`;
    const pkt = parseCotXml(xml);
    expect(pkt.cotTypeId).toBe(COTTYPE_M_T_T);
    expect(pkt.taktalk).toBeDefined();
    expect(pkt.taktalk?.text ?? "").toBe("");
    expect(pkt.taktalk?.chatroomId).toBe("1");
    expect(pkt.taktalk?.lang).toBe("English");
    expect(pkt.taktalk?.fromVoice ?? false).toBe(false);
    // Full wire round-trip — compress + decompress shouldn't throw
    const wire = await compressor.compress(pkt);
    const decompressed = await compressor.decompress(wire);
    expect(decompressed.cotTypeId).toBe(COTTYPE_M_T_T);
  });

  it("y- with no participants parses with empty participants list", async () => {
    const xml = `<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="ROOM-DATA-no-roster" type="y-" how="null"
    time="2026-05-27T02:09:08.426Z" start="2026-05-27T02:09:08.426Z"
    stale="2026-05-27T02:15:09.699Z">
  <point lat="0.0" lon="0.0" hae="9999999.0" ce="9999999.0" le="9999999.0"/>
  <detail>
    <sender-callsign>ASPEN</sender-callsign>
    <chatroom-id>30b2755c-c547-44ef-a0cc-cdbd8a15616f</chatroom-id>
    <chatroom-name>test-empty-room</chatroom-name>
  </detail>
</event>`;
    const pkt = parseCotXml(xml);
    expect(pkt.cotTypeId).toBe(COTTYPE_Y_DASH);
    expect(pkt.taktalkRoom).toBeDefined();
    expect(pkt.taktalkRoom?.senderCallsign).toBe("ASPEN");
    expect(pkt.taktalkRoom?.roomId).toBe("30b2755c-c547-44ef-a0cc-cdbd8a15616f");
    expect(pkt.taktalkRoom?.roomName).toBe("test-empty-room");
    expect(pkt.taktalkRoom?.participants ?? []).toEqual([]);
    // Round-trip wire to confirm no crash on serialize/deserialize
    const wire = await compressor.compress(pkt);
    const decompressed = await compressor.decompress(wire);
    expect(decompressed.cotTypeId).toBe(COTTYPE_Y_DASH);
    expect(decompressed.taktalkRoom?.participants ?? []).toEqual([]);
  });

  it("b-t-f with empty Ea element does not corrupt chat parsing", async () => {
    // <Ea></Ea> with no body — chat message in <remarks> must NOT be
    // clobbered by the empty Ea text.
    const xml = `<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="GeoChat.ANDROID-aaa.ANDROID-bbb.malformed-ea" type="b-t-f" how="h-g-i-g-o"
    time="2026-05-27T02:09:20.718Z" start="2026-05-27T02:09:20.718Z"
    stale="2026-05-28T02:09:20.718Z">
  <point lat="38.8895" lon="-77.0353" hae="73.863" ce="6.9" le="9999999.0"/>
  <detail>
    <__chat parent="RootContactGroup" messageId="malformed-ea" chatroom="ETHEL"
        id="ANDROID-bbb" senderCallsign="ASPEN">
      <chatgrp uid0="ANDROID-aaa" uid1="ANDROID-bbb" id="ANDROID-bbb"/>
    </__chat>
    <link uid="ANDROID-aaa" type="a-f-G-U-C" relation="p-p"/>
    <remarks source="BAO.F.ATAK.ANDROID-aaa" to="ANDROID-bbb"
        time="2026-05-27T02:09:20.718Z">Real message body</remarks>
    <Ea></Ea>
    <roomId>30b2755c-c547-44ef-a0cc-cdbd8a15616f</roomId>
  </detail>
</event>`;
    const pkt = parseCotXml(xml);
    expect(pkt.chat).toBeDefined();
    // The critical assertion — chat message survives intact despite the
    // empty <Ea/> sibling sitting next to <remarks>.
    expect(pkt.chat?.message).toBe("Real message body");
    expect(pkt.chat?.lang ?? "").toBe("");
    expect(pkt.chat?.roomId).toBe("30b2755c-c547-44ef-a0cc-cdbd8a15616f");
    // Round-trip should preserve message body
    const wire = await compressor.compress(pkt);
    const decompressed = await compressor.decompress(wire);
    expect(decompressed.chat?.message).toBe("Real message body");
  });
});
