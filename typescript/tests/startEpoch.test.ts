import { describe, it, expect } from "vitest";
import { TakCompressor } from "../src/TakCompressor.js";
import { parseCotXml } from "../src/CotXmlParser.js";
import { buildCotXml } from "../src/CotXmlBuilder.js";
import * as StartEpoch from "../src/StartEpoch.js";
import type { TAKPacketV2 } from "../src/types.js";

/**
 * Coverage for the `start` timestamp, mirroring the Kotlin and Python bindings.
 *
 * The shared fixtures cannot provide this: every one is stamped months in the past,
 * so the parser correctly refuses to encode its `start` and the builder falls back
 * to the decode instant, which makes the whole corpus pass whether this works or
 * not. So the timestamps here are minted at run time rather than committed.
 */

const BOUNDARY = StartEpoch.MODULUS * 109_000;

const iso = (sec: number) => new Date(sec * 1000).toISOString();

const cot = (start: string, stale: string) =>
  `<?xml version="1.0" encoding="UTF-8"?>` +
  `<event version="2.0" uid="ATOM-START-RT" type="a-f-G-U-C" how="m-g" ` +
  `time="${start}" start="${start}" stale="${stale}">` +
  `<point lat="38.8895" lon="-77.0353" hae="0.0" ce="9999999.0" le="9999999.0"/>` +
  `<detail><contact callsign="ALPHA-1"/></detail></event>`;

const attr = (xml: string, name: string) =>
  xml.match(new RegExp(`${name}="([^"]+)"`))?.[1];

describe("StartEpoch arithmetic", () => {
  it("round-trips a start equal to now", () => {
    const now = BOUNDARY + 1234;
    expect(StartEpoch.reconstruct(StartEpoch.truncate(now), now)).toBe(now);
  });

  it("round-trips a start a few seconds old", () => {
    const now = BOUNDARY + 1234;
    const start = now - 40;
    expect(StartEpoch.reconstruct(StartEpoch.truncate(start), now)).toBe(start);
  });

  it("is exact at a window boundary with the sender ahead of the receiver", () => {
    // start = B, receiver still at B - 1. The naive splice yields B - 16384.
    expect(StartEpoch.reconstruct(StartEpoch.truncate(BOUNDARY), BOUNDARY - 1)).toBe(BOUNDARY);
  });

  it("is exact at a window boundary with the packet crossing in flight", () => {
    // start = B - 1, receiver already at B. The naive splice yields B + 16383.
    expect(StartEpoch.reconstruct(StartEpoch.truncate(BOUNDARY - 1), BOUNDARY)).toBe(BOUNDARY - 1);
  });

  it("rejects a start implausibly far in the future", () => {
    const now = BOUNDARY + 1234;
    const future = now + StartEpoch.FUTURE_TOLERANCE_SECONDS + 10;
    expect(StartEpoch.reconstruct(StartEpoch.truncate(future), now)).toBeUndefined();
  });

  it("rejects a start beyond the past limit", () => {
    const now = BOUNDARY + 8000;
    const tooOld = now - StartEpoch.PAST_LIMIT_SECONDS - 10;
    expect(StartEpoch.reconstruct(StartEpoch.truncate(tooOld), now)).toBeUndefined();
  });

  it("accepts a start just inside the past limit", () => {
    const now = BOUNDARY + 8000;
    const old = now - StartEpoch.PAST_LIMIT_SECONDS;
    expect(StartEpoch.reconstruct(StartEpoch.truncate(old), now)).toBe(old);
  });

  it("rejects a value outside the field range", () => {
    expect(StartEpoch.reconstruct(-1, BOUNDARY)).toBeUndefined();
    expect(StartEpoch.reconstruct(StartEpoch.MODULUS, BOUNDARY)).toBeUndefined();
  });

  it("truncates to the low 14 bits", () => {
    expect(StartEpoch.truncate(BOUNDARY)).toBe(0);
    expect(StartEpoch.truncate(BOUNDARY + 1)).toBe(1);
    expect(StartEpoch.truncate(BOUNDARY - 1)).toBe(StartEpoch.MODULUS - 1);
  });

  it("documents the band where reconstruction is wrongly accepted", () => {
    // Not a bug report: the band cannot be closed from the receiver alone, and the
    // sender gate is what keeps it out of reach. Pinned so a bound change has to
    // confront it rather than silently widen it.
    const now = BOUNDARY + 8000;
    const low = StartEpoch.MODULUS - StartEpoch.FUTURE_TOLERANCE_SECONDS;
    const high = StartEpoch.MODULUS + StartEpoch.PAST_LIMIT_SECONDS;
    expect([low, high]).toEqual([12784, 19984]);
    for (const age of [low, Math.floor((low + high) / 2), high]) {
      const trueStart = now - age;
      const got = StartEpoch.reconstruct(StartEpoch.truncate(trueStart), now);
      expect(got, `age ${age} should be (wrongly) accepted`).toBeDefined();
      expect(got).toBe(trueStart + StartEpoch.MODULUS);
    }
    // Just outside the band is rejected rather than wrong.
    expect(StartEpoch.reconstruct(StartEpoch.truncate(now - (low - 1)), now)).toBeUndefined();
  });
});

describe("StartEpoch round trip", () => {
  const compressor = new TakCompressor();

  it("survives the full wire round trip", async () => {
    // 90 s old: inside the bound, and far enough from now that a fallback to the
    // decode instant is unambiguous rather than a rounding tie.
    const start = Math.floor(Date.now() / 1000) - 90;
    const pkt = parseCotXml(cot(iso(start), iso(start + 300)));
    expect(pkt.startEpochLo14, "parser did not capture start").toBe(StartEpoch.truncate(start));

    const rt = await compressor.decompress(await compressor.compress(pkt));
    expect(rt.startEpochLo14, "start did not survive the wire").toBe(pkt.startEpochLo14);

    // The rebuilt event must carry the ORIGINAL instant, not the decode instant.
    const rebuilt = buildCotXml(rt);
    expect(attr(rebuilt, "start")).toBe(iso(start));
    expect(attr(rebuilt, "time")).toBe(iso(start));
  });

  it("keeps a lo14 of zero present rather than absent", async () => {
    // `start` mod 16384 is 0 for one second in every 16384, i.e. once every 4.55 h,
    // and that second is the only one where a genuine value collides with proto3's
    // default. Explicit presence is what separates them. Lose it and a real start
    // reads as absent once every 4.55 hours, silently, and no fixture can catch it.
    const zero: TAKPacketV2 = { uid: "ATOM-LO14-ZERO", cotTypeStr: "a-f-G-U-C", startEpochLo14: 0 };
    const rt = await compressor.decompress(await compressor.compress(zero));
    expect(rt.startEpochLo14, "a lo14 of 0 came back absent: presence was lost").toBe(0);

    // The other direction, so this pins presence rather than just non-zeroness.
    const absent: TAKPacketV2 = { uid: "ATOM-LO14-ABSENT", cotTypeStr: "a-f-G-U-C" };
    const rtAbsent = await compressor.decompress(await compressor.compress(absent));
    expect(rtAbsent.startEpochLo14 ?? undefined, "an absent start came back as 0").toBeUndefined();
  });

  it("falls back to the decode instant when the sender supplied nothing", () => {
    const before = Math.floor(Date.now() / 1000);
    const rebuilt = buildCotXml({ uid: "ATOM-NO-START", cotTypeStr: "a-f-G-U-C" });
    const emitted = attr(rebuilt, "start");
    expect(emitted).toBeDefined();
    const delta = Math.floor(new Date(emitted!).getTime() / 1000) - before;
    expect(delta).toBeGreaterThanOrEqual(0);
    expect(delta).toBeLessThanOrEqual(10);
  });

  it("does not encode a start past the sender gate", () => {
    // Well inside the 8192 s half-window, so this exercises the sender gate and not
    // the wrap. That gate is the only thing keeping a receiver out of the band above.
    const tooOld = Math.floor(Date.now() / 1000) - StartEpoch.PAST_LIMIT_SECONDS - 60;
    const pkt = parseCotXml(cot(iso(tooOld), iso(tooOld + 300)));
    expect(pkt.startEpochLo14 ?? undefined).toBeUndefined();
  });

  it("falls back rather than using a present but out-of-range start", () => {
    // The receiver-side counterpart to the sender gate: present, refused, and
    // travelling through the builder. 7200 s is inside the half-window, so this is
    // the bound rather than the wrap. Not dropped: uid, type and position are fine.
    const nowSec = Math.floor(Date.now() / 1000);
    const rebuilt = buildCotXml({
      uid: "ATOM-OUT-OF-RANGE",
      cotTypeStr: "a-f-G-U-C",
      startEpochLo14: StartEpoch.truncate(nowSec - 7200),
    });
    const emitted = attr(rebuilt, "start");
    expect(emitted, "the event must still be built").toBeDefined();
    const delta = Math.floor(new Date(emitted!).getTime() / 1000) - nowSec;
    expect(delta, `out-of-range start was used: ${emitted}`).toBeLessThanOrEqual(10);
    expect(delta).toBeGreaterThanOrEqual(0);
  });

  it("emits time, start and stale in UTC", () => {
    const start = Math.floor(Date.now() / 1000) - 90;
    const rebuilt = buildCotXml(parseCotXml(cot(iso(start), iso(start + 300))));
    for (const name of ["time", "start", "stale"]) {
      expect(attr(rebuilt, name), `${name} is not UTC`).toMatch(/Z$/);
    }
  });

  it("converts an offset-bearing start rather than reading it positionally", () => {
    // Same instant written with a +04:00 offset. Both must encode the same bits,
    // which holds because we resolve the instant instead of reading fixed character
    // positions the way ATAK's fast path does.
    const start = Math.floor(Date.now() / 1000) - 90;
    const asOffset = new Date((start + 4 * 3600) * 1000).toISOString().replace("Z", "+04:00");
    const fromZ = parseCotXml(cot(iso(start), iso(start + 300)));
    const fromOffset = parseCotXml(cot(asOffset, iso(start + 300)));
    expect(fromOffset.startEpochLo14, "offset form was not encoded").toBeDefined();
    expect(fromOffset.startEpochLo14).toBe(fromZ.startEpochLo14);
  });

  it("does not guess at a start with no zone designator", () => {
    // `new Date("2026-10-29T12:00:00")` reads as LOCAL time, so this would be wrong
    // by the runner's offset. ATAK would read it as GMT. We refuse either way.
    const start = Math.floor(Date.now() / 1000) - 90;
    const noZone = iso(start).replace("Z", "");
    const pkt = parseCotXml(cot(noZone, iso(start + 300)));
    expect(pkt.startEpochLo14 ?? undefined).toBeUndefined();
  });

  it("measures stale from arrival rather than the recovered start", () => {
    // An honest `start` must not shorten an event's life. Ten minutes in transit
    // against a 60 s window would otherwise arrive already expired.
    const start = Math.floor(Date.now() / 1000) - 600;
    const rebuilt = buildCotXml(parseCotXml(cot(iso(start), iso(start + 60))));
    expect(new Date(attr(rebuilt, "stale")!).getTime()).toBeGreaterThan(Date.now());
  });
});
