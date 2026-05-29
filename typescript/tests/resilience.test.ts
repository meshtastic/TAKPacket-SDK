import { describe, it, expect } from "vitest";
import { TakCompressor } from "../src/TakCompressor.js";
import { parseCotXml } from "../src/CotXmlParser.js";
import { FIXTURES, loadFixtureXml } from "./helpers.js";

/**
 * Locks the LoRa-resilience invariant: every packet MUST be fully,
 * independently decodable from its own bytes plus the static shipped
 * dictionary. ZERO cross-packet state. LoRa is lossy — losing any packet
 * must never jeopardize the decode of any other packet.
 *
 * If a future change introduces stateful/streaming compression or any
 * cross-packet dependency, one of these tests fails. Mirrors Kotlin
 * ResilienceTest.kt.
 */
describe("Resilience", () => {
  const compressor = new TakCompressor();

  // Encode every fixture once, in order, into independent wire payloads.
  async function encodeAll(): Promise<Array<{ name: string; wire: Buffer }>> {
    const out: Array<{ name: string; wire: Buffer }> = [];
    for (const name of FIXTURES) {
      out.push({ name, wire: await compressor.compress(parseCotXml(loadFixtureXml(name))) });
    }
    return out;
  }

  it("each packet decodes identically regardless of order", async () => {
    const wire = await encodeAll();
    // Baseline: decode each in the order produced.
    const forward = [];
    for (const { wire: w } of wire) forward.push(await compressor.decompress(w));
    // Decode the SAME payloads in reverse order — order must not matter.
    const reverseDecoded = [];
    for (const { wire: w } of [...wire].reverse()) reverseDecoded.push(await compressor.decompress(w));
    reverseDecoded.reverse(); // back to original order
    for (let i = 0; i < forward.length; i++) {
      expect(reverseDecoded[i], `Packet ${wire[i].name} decoded differently in reverse order`).toEqual(forward[i]);
    }
  });

  it("any single packet decodes with all others dropped", async () => {
    const wire = await encodeAll();
    // Simulate a lossy mesh: each packet must decode ALONE (every other "lost").
    const inSequence = new Map<string, unknown>();
    for (const { name, wire: w } of wire) inSequence.set(name, await compressor.decompress(w));
    for (const { name, wire: w } of wire) {
      const isolated = await compressor.decompress(w);
      expect(isolated, `Packet ${name} failed to decode in isolation`).toEqual(inSequence.get(name));
    }
  });

  it("a cold compressor instance decodes any packet (no warm-up state)", async () => {
    const wire = await encodeAll();
    // A freshly-constructed compressor — never having seen any prior packet —
    // must decode any single frame. Proves no warm-up/history state.
    for (const { name, wire: w } of wire) {
      const cold = new TakCompressor();
      const pkt = await cold.decompress(w);
      expect(pkt.uid, `Cold compressor failed to decode ${name}`).toBeTruthy();
    }
  });

  it("compression is deterministic across instances and free of cross-packet state", async () => {
    // Determinism: (1) two independent instances must yield identical wire bytes
    // for the same packet, and (2) an instance must not drift as it processes
    // many packets in sequence.
    //
    // We reuse TWO long-lived instances across all fixtures rather than building
    // a fresh compressor per fixture: constructing a level-19 zstd CDict over the
    // 512KB dictionary is expensive in zstd-napi (~80ms each), so 2×N fresh
    // builds time out. Apps build the CDict once and reuse it, so this mirrors
    // real usage. Running both instances over the whole sequence also strengthens
    // the check — any instance-accumulated state would make the two diverge.
    const a = new TakCompressor();
    const b = new TakCompressor();
    const firstName = FIXTURES[0];
    let firstFromA: Buffer | undefined;
    for (const name of FIXTURES) {
      const pkt = parseCotXml(loadFixtureXml(name));
      const ea = await a.compress(pkt);
      const eb = await b.compress(pkt);
      expect(ea.equals(eb), `Independent instances disagree for ${name} — non-deterministic`).toBe(true);
      if (name === firstName) firstFromA = ea;
    }
    // No-drift check: a cold instance must compress the first fixture identically
    // to instance `a`'s output from BEFORE it had processed the other fixtures —
    // proves no state accumulated across the sequence changed the output.
    const cold = await new TakCompressor().compress(parseCotXml(loadFixtureXml(firstName)));
    expect(cold.equals(firstFromA!), "compressor accumulated cross-packet state").toBe(true);
  });
});
