// R3 SPIKE — js compress byte-compat against the SDK wire format.
//
// For each fixture: compress its golden .pb with @bokuweb/zstd-wasm + the
// non-aircraft dict at level 19, inspect the produced frame header, strip the
// 4-byte zstd magic to wire form, and compare the resulting wire size to the
// committed golden .bin. Also re-decompress (round-trip self-check) and dump
// the frame header bytes so we can see whether dictID/contentSize/checksum are
// embedded (which would break cross-binding byte/size parity).

import * as zstd from "@bokuweb/zstd-wasm";
import fs from "node:fs";
import path from "node:path";

const REPO = process.argv[2];
if (!REPO) { console.error("usage: node spike.mjs <repo-root>"); process.exit(2); }

const dict = new Uint8Array(fs.readFileSync(path.join(REPO, "kotlin/src/jvmMain/resources/dict_non_aircraft.zstd")));
const ZSTD_MAGIC = [0x28, 0xb5, 0x2f, 0xfd];

const goldenDir = path.join(REPO, "testdata/golden");
const pbDir = path.join(REPO, "testdata/protobuf");

await zstd.init();

// Pick a spread of non-aircraft, dict-0 fixtures of varying sizes.
const fixtures = fs.readdirSync(pbDir).filter((f) => f.endsWith(".pb")).map((f) => f.replace(/\.pb$/, "")).sort();

let attempted = 0, sizePass = 0, roundTripPass = 0;
const rows = [];
const outFrames = {}; // name -> wire bytes (for the JVM cross-decode step)

for (const name of fixtures) {
  const goldenPath = path.join(goldenDir, name + ".bin");
  if (!fs.existsSync(goldenPath)) continue;
  const golden = fs.readFileSync(goldenPath);
  const flags = golden[0];
  // Only dict-0 (non-aircraft) compressed goldens — skip aircraft(1) + 0xFF.
  if (flags !== 0x00) continue;

  const pb = new Uint8Array(fs.readFileSync(path.join(pbDir, name + ".pb")));
  attempted++;

  const cctx = zstd.createCCtx();
  let frame;
  try {
    frame = zstd.compressUsingDict(cctx, pb, dict, 19);
  } finally {
    zstd.freeCCtx(cctx);
  }

  // Inspect/strip magic.
  const magicOk = frame[0] === ZSTD_MAGIC[0] && frame[1] === ZSTD_MAGIC[1] &&
    frame[2] === ZSTD_MAGIC[2] && frame[3] === ZSTD_MAGIC[3];
  const fhd = frame[4]; // frame header descriptor byte (after magic)
  const dictIdFlag = fhd & 0x3;
  const checksumFlag = (fhd >> 2) & 0x1;
  const fcsFlag = (fhd >> 6) & 0x3;
  const body = frame.subarray(4); // stripped wire body

  // SDK wire payload = [flags byte][body]
  const wireLen = 1 + body.length;
  const ratio = wireLen / golden.length;
  const sizeOk = ratio >= 0.5 && ratio <= 2.0;
  if (sizeOk) sizePass++;

  // Round-trip self-check via the same lib.
  const dctx = zstd.createDCtx();
  let rt;
  try {
    rt = zstd.decompressUsingDict(dctx, frame, dict);
  } finally {
    zstd.freeDCtx(dctx);
  }
  const rtOk = rt.length === pb.length && rt.every((b, i) => b === pb[i]);
  if (rtOk) roundTripPass++;

  // Build the SDK wire form: [flags=0x00][body], for the JVM cross-decode step.
  const wire = Buffer.alloc(wireLen);
  wire[0] = 0x00;
  Buffer.from(body).copy(wire, 1);
  outFrames[name] = wire.toString("base64");

  rows.push({ name, pb: pb.length, golden: golden.length, wire: wireLen, ratio: ratio.toFixed(3),
    magicOk, dictIdFlag, checksumFlag, fcsFlag, rtOk });
}

console.log("=== R3 SPIKE: @bokuweb/zstd-wasm compress (dict-0, level 19) ===");
console.log("library version:", JSON.parse(fs.readFileSync("./node_modules/@bokuweb/zstd-wasm/package.json")).version);
console.table(rows.map((r) => ({ name: r.name, pb: r.pb, golden: r.golden, wire: r.wire, ratio: r.ratio,
  dictIdFlag: r.dictIdFlag, checksum: r.checksumFlag, fcsFlag: r.fcsFlag, magicOk: r.magicOk, rt: r.rtOk })));
console.log(`attempted=${attempted} sizeWithinTolerance=${sizePass}/${attempted} roundTrip=${roundTripPass}/${attempted}`);

// Emit the wire frames for the JVM cross-decode step.
fs.writeFileSync("/tmp/bokuweb-probe/spike_frames.json", JSON.stringify(outFrames));
console.log("wrote /tmp/bokuweb-probe/spike_frames.json with", Object.keys(outFrames).length, "frames");
