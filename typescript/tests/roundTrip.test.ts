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
    if (packet.casevac) {
      const origC = packet.casevac as Record<string, unknown>;
      const decC = decompressed.casevac as Record<string, unknown>;
      expect(decC).toBeTruthy();
      expect(decC.precedence ?? 0).toBe(origC.precedence ?? 0);
      expect(decC.equipmentFlags ?? 0).toBe(origC.equipmentFlags ?? 0);
      expect(decC.litterPatients ?? 0).toBe(origC.litterPatients ?? 0);
      expect(decC.ambulatoryPatients ?? 0).toBe(origC.ambulatoryPatients ?? 0);
      expect(decC.security ?? 0).toBe(origC.security ?? 0);
      expect(decC.hlzMarking ?? 0).toBe(origC.hlzMarking ?? 0);
      expect(decC.terrainFlags ?? 0).toBe(origC.terrainFlags ?? 0);
      expect(decC.frequency ?? "").toBe(origC.frequency ?? "");
      expect(decC.zoneMarker ?? "").toBe(origC.zoneMarker ?? "");
    }
    if (packet.emergency) {
      const origE = packet.emergency as Record<string, unknown>;
      const decE = decompressed.emergency as Record<string, unknown>;
      expect(decE).toBeTruthy();
      expect(decE.type ?? 0).toBe(origE.type ?? 0);
      expect(decE.authoringUid ?? "").toBe(origE.authoringUid ?? "");
    }
    if (packet.task) {
      const origT = packet.task as Record<string, unknown>;
      const decT = decompressed.task as Record<string, unknown>;
      expect(decT).toBeTruthy();
      expect(decT.taskType ?? "").toBe(origT.taskType ?? "");
      expect(decT.targetUid ?? "").toBe(origT.targetUid ?? "");
      expect(decT.assigneeUid ?? "").toBe(origT.assigneeUid ?? "");
      expect(decT.priority ?? 0).toBe(origT.priority ?? 0);
      expect(decT.status ?? 0).toBe(origT.status ?? 0);
      expect(decT.note ?? "").toBe(origT.note ?? "");
    }

    const rebuiltXml = buildCotXml(decompressed as Record<string, unknown>);
    expect(rebuiltXml).toContain("<event");
  });

  it("casevac_medline extracts to CasevacReport with full field set", () => {
    const pkt = parseCotXml(loadFixtureXml("casevac_medline"));
    expect(pkt.casevac).toBeTruthy();
    const c = pkt.casevac as Record<string, unknown>;
    expect(c.precedence).toBe(1); // Urgent
    expect((c.litterPatients as number) ?? 0).toBeGreaterThan(0);
    // Equipment: none=false, hoist=true, extraction=true → 0x06
    expect((c.equipmentFlags as number) & 0x02).toBe(0x02); // hoist
    expect((c.hlzMarking as number) ?? 0).toBeGreaterThan(0);
    expect(c.frequency).toBe("38.90");
  });

  it("emergency_911 extracts to EmergencyAlert with Alert911 type", () => {
    const pkt = parseCotXml(loadFixtureXml("emergency_911"));
    expect(pkt.emergency).toBeTruthy();
    const e = pkt.emergency as Record<string, unknown>;
    expect(e.type).toBe(1); // Alert911
    expect(e.authoringUid).toBeTruthy();
  });

  it("emergency_cancel extracts to EmergencyAlert with Cancel type", () => {
    const pkt = parseCotXml(loadFixtureXml("emergency_cancel"));
    expect(pkt.emergency).toBeTruthy();
    const e = pkt.emergency as Record<string, unknown>;
    expect(e.type).toBe(6); // Cancel
  });

  it("task_engage extracts to TaskRequest with target and assignee", () => {
    const pkt = parseCotXml(loadFixtureXml("task_engage"));
    expect(pkt.task).toBeTruthy();
    const t = pkt.task as Record<string, unknown>;
    expect(t.taskType).toBe("engage");
    expect(t.targetUid).toBe("target-01");
    expect(t.assigneeUid).toBe("ANDROID-0000000000000005");
    expect(t.priority).toBe(3); // High
    expect(t.status).toBe(1); // Pending
    expect(t.note).toBe("cover by fire");
  });

  it("chat_receipt_delivered extracts as Chat with receiptType=Delivered", () => {
    const pkt = parseCotXml(loadFixtureXml("chat_receipt_delivered"));
    expect(pkt.chat).toBeTruthy();
    const chat = pkt.chat as Record<string, unknown>;
    expect(chat.receiptType).toBe(1); // Delivered
    expect(chat.receiptForUid).toBeTruthy();
  });

  it("chat_receipt_read extracts as Chat with receiptType=Read", () => {
    const pkt = parseCotXml(loadFixtureXml("chat_receipt_read"));
    expect(pkt.chat).toBeTruthy();
    const chat = pkt.chat as Record<string, unknown>;
    expect(chat.receiptType).toBe(2); // Read
    expect(chat.receiptForUid).toBeTruthy();
  });

  it("drawing_ellipse extracts to DrawnShape with Kind_Ellipse", () => {
    const pkt = parseCotXml(loadFixtureXml("drawing_ellipse"));
    expect(pkt.shape).toBeTruthy();
    const s = pkt.shape as Record<string, unknown>;
    expect(s.kind).toBe(8); // Ellipse
    expect((s.majorCm as number) ?? 0).toBeGreaterThan(0);
    expect((s.minorCm as number) ?? 0).toBeGreaterThan(0);
  });

  it("marker_goto extracts to Marker with Kind_GoToPoint", () => {
    const pkt = parseCotXml(loadFixtureXml("marker_goto"));
    expect(pkt.marker).toBeTruthy();
    const m = pkt.marker as Record<string, unknown>;
    expect(m.kind).toBe(8); // GoToPoint
  });

  it("marker_tank maps cot_type_id to the tank enum entry", () => {
    const pkt = parseCotXml(loadFixtureXml("marker_tank"));
    // a-h-G-E-V-A-T → one of the new 2525 tank entries
    expect((pkt.cotTypeId as number) ?? 0).toBeGreaterThanOrEqual(82);
    expect(pkt.cotTypeStr).toBeUndefined();
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
