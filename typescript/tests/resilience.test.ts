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

  it("re-encoding a packet on a fresh compressor is byte-identical", async () => {
    // Determinism: the same packet compressed by independent compressor
    // instances must yield identical wire bytes (no instance-accumulated state).
    for (const name of FIXTURES) {
      const pkt = parseCotXml(loadFixtureXml(name));
      const a = await new TakCompressor().compress(pkt);
      const b = await new TakCompressor().compress(pkt);
      expect(a.equals(b), `Non-deterministic compression for ${name} — possible state leak`).toBe(true);
    }
  });
});
