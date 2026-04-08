import { describe, it, expect } from "vitest";
import { TakCompressor } from "../src/TakCompressor.js";
import { parseCotXml } from "../src/CotXmlParser.js";
import { buildCotXml } from "../src/CotXmlBuilder.js";
import { FIXTURES, loadFixtureXml } from "./helpers.js";

const compressor = new TakCompressor();

describe("RoundTrip", () => {
  it.each(FIXTURES)("full round-trip preserves fields: %s", async (fixture) => {
    const xml = loadFixtureXml(fixture);
    const packet = parseCotXml(xml);
    expect(packet.uid).toBeTruthy();

    const wire = await compressor.compress(packet);
    const decompressed = await compressor.decompress(wire);

    expect(decompressed.cotTypeId).toBe(packet.cotTypeId);
    expect(decompressed.how).toBe(packet.how);
    expect(decompressed.callsign).toBe(packet.callsign);
    expect(decompressed.team).toBe(packet.team);
    expect(decompressed.latitudeI).toBe(packet.latitudeI);
    expect(decompressed.longitudeI).toBe(packet.longitudeI);
    expect(decompressed.altitude).toBe(packet.altitude);
    expect(decompressed.battery).toBe(packet.battery);
    expect(decompressed.uid).toBe(packet.uid);
    expect(decompressed.speed).toBe(packet.speed);
    expect(decompressed.course).toBe(packet.course);
    expect(decompressed.role).toBe(packet.role);
    expect(decompressed.deviceCallsign).toBe(packet.deviceCallsign);
    expect(decompressed.takVersion).toBe(packet.takVersion);
    expect(decompressed.takPlatform).toBe(packet.takPlatform);
    expect(decompressed.endpoint).toBe(packet.endpoint);

    // Payload-specific field assertions
    if (packet.chat) {
      const origChat = packet.chat as Record<string, unknown>;
      const decChat = decompressed.chat as Record<string, unknown>;
      expect(decChat).toBeTruthy();
      expect(decChat.message).toBe(origChat.message);
    }
    if (packet.aircraft) {
      const origAc = packet.aircraft as Record<string, unknown>;
      const decAc = decompressed.aircraft as Record<string, unknown>;
      expect(decAc).toBeTruthy();
      expect(decAc.icao).toBe(origAc.icao);
      expect(decAc.registration).toBe(origAc.registration);
      expect(decAc.flight).toBe(origAc.flight);
      expect(decAc.squawk).toBe(origAc.squawk);
    }

    const rebuiltXml = buildCotXml(decompressed as Record<string, unknown>);
    expect(rebuiltXml).toContain("<event");
  });

  it("parses PLI basic correctly", () => {
    const xml = loadFixtureXml("pli_basic");
    const pkt = parseCotXml(xml);
    expect(pkt.uid).toBe("testnode");
    expect(pkt.cotTypeId).toBe(1); // a-f-G-U-C
    expect(pkt.how).toBe(2); // m-g
    expect(pkt.callsign).toBe("testnode");
    expect(pkt.latitudeI).toBe(Math.round(37.7749 * 1e7));
    expect(pkt.longitudeI).toBe(Math.round(-122.4194 * 1e7));
  });

  it("parses ADS-B aircraft ICAO", () => {
    const xml = loadFixtureXml("aircraft_adsb");
    const pkt = parseCotXml(xml);
    expect(pkt.cotTypeId).toBe(3); // a-n-A-C-F
    expect(pkt.aircraft).toBeTruthy();
    const ac = pkt.aircraft as Record<string, unknown>;
    expect(ac.icao).toBeTruthy();
  });

  it("parses GeoChat message", () => {
    const xml = loadFixtureXml("geochat_simple");
    const pkt = parseCotXml(xml);
    expect(pkt.cotTypeId).toBe(25); // b-t-f
    expect(pkt.chat).toBeTruthy();
    const chat = pkt.chat as Record<string, unknown>;
    expect(chat.message).toBeTruthy();
  });

  it("parses delete event", () => {
    const pkt = parseCotXml(loadFixtureXml("delete_event"));
    expect(pkt.cotTypeId).toBe(14); // t-x-d-d
    expect(pkt.how).toBe(3); // h-g-i-g-o
  });

  it("parses CASEVAC", () => {
    const pkt = parseCotXml(loadFixtureXml("casevac"));
    expect(pkt.cotTypeId).toBe(26); // b-r-f-h-c
    expect(pkt.callsign).toBe("CASEVAC-1");
  });

  it("parses alert TIC", () => {
    const pkt = parseCotXml(loadFixtureXml("alert_tic"));
    expect(pkt.cotTypeId).toBe(28); // b-a-o-opn
    expect(pkt.callsign).toBe("ALPHA-6");
  });

  it("parses PLI full with all fields", () => {
    const pkt = parseCotXml(loadFixtureXml("pli_full"));
    expect(pkt.cotTypeId).toBe(1); // a-f-G-U-C
    expect(pkt.callsign).toBeTruthy();
    expect(pkt.takVersion).toBeTruthy();
    expect(pkt.takPlatform).toBeTruthy();
    expect(Number(pkt.battery)).toBeGreaterThan(0);
  });

  it("handles uncompressed 0xFF payload", async () => {
    const { getTAKPacketV2Type } = await import("../src/proto.js");
    const TAKPacketV2 = await getTAKPacketV2Type();
    const msg = TAKPacketV2.create({
      cotTypeId: 1, how: 2, callsign: "TEST",
      latitudeI: 340522000, longitudeI: -1182437000, altitude: 100, pli: true,
    });
    const proto = Buffer.from(TAKPacketV2.encode(msg).finish());
    const wire = Buffer.concat([Buffer.from([0xff]), proto]);

    const decompressed = await compressor.decompress(wire);
    expect(decompressed.cotTypeId).toBe(1);
    expect(decompressed.callsign).toBe("TEST");
    expect(decompressed.latitudeI).toBe(340522000);
  });
});
