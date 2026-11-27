import { describe, it, expect } from "vitest";
import { TakCompressor } from "../src/TakCompressor.js";
import { parseCotXml } from "../src/CotXmlParser.js";
import { buildCotXml } from "../src/CotXmlBuilder.js";
import { getTAKPacketV2Type } from "../src/proto.js";
import type { TAKPacketV2 } from "../src/types.js";
import { FIXTURES, loadFixtureXml, loadGoldenProtobuf } from "./helpers.js";

const compressor = new TakCompressor();

describe("RoundTrip", () => {
  it.each(FIXTURES)("full round-trip preserves fields: %s", async (fixture) => {
    const xml = loadFixtureXml(fixture);
    const packet = parseCotXml(xml);
    expect(packet.uid).toBeTruthy();

    const wire = await compressor.compress(packet);
    const decompressed = await compressor.decompress(wire);

    // proto3 wire format cannot distinguish an explicitly-set default (0, "")
    // from an absent field, and protobufjs 8 elides set-to-default values on
    // encode (7.x wrote zero-valued own properties). decompress() materializes
    // defaults — like the Wire/protobuf runtimes in the other bindings — so
    // compare under proto3 semantics: absent == default.
    const num = (v: unknown) => (v as number | undefined) ?? 0;
    const str = (v: unknown) => (v as string | undefined) ?? "";
    expect(num(decompressed.cotTypeId)).toBe(num(packet.cotTypeId));
    expect(num(decompressed.how)).toBe(num(packet.how));
    expect(str(decompressed.callsign)).toBe(str(packet.callsign));
    expect(num(decompressed.team)).toBe(num(packet.team));
    expect(num(decompressed.latitudeI)).toBe(num(packet.latitudeI));
    expect(num(decompressed.longitudeI)).toBe(num(packet.longitudeI));
    expect(num(decompressed.altitude)).toBe(num(packet.altitude));
    expect(num(decompressed.battery)).toBe(num(packet.battery));
    expect(str(decompressed.uid)).toBe(str(packet.uid));
    expect(num(decompressed.speed)).toBe(num(packet.speed));
    expect(num(decompressed.course)).toBe(num(packet.course));
    expect(num(decompressed.role)).toBe(num(packet.role));
    expect(str(decompressed.deviceCallsign)).toBe(str(packet.deviceCallsign));
    expect(str(decompressed.takVersion)).toBe(str(packet.takVersion));
    expect(str(decompressed.takPlatform)).toBe(str(packet.takPlatform));
    expect(str(decompressed.endpoint)).toBe(str(packet.endpoint));
    // v0.3.2 directed-routing recipients. Empty list = broadcast (default
    // for PLI / situational-awareness); populated for TAKTALK m-t-t and
    // directed b-t-f DMs. TAKTALK gates voice TTS on this list matching
    // the receiver's callsign — regressions silently break voice.
    expect(decompressed.marti?.destCallsign ?? []).toEqual(packet.marti?.destCallsign ?? []);
    // Payload-agnostic annotations; the parser fills every field, so the
    // decoded messages compare whole.
    expect(decompressed.environment).toEqual(packet.environment);
    expect(decompressed.sensorFov).toEqual(packet.sensorFov);
    expect(decompressed.detection).toEqual(packet.detection);

    // Payload-specific field assertions
    if (packet.chat) {
      const origChat = packet.chat as Record<string, unknown>;
      const decChat = decompressed.chat as Record<string, unknown>;
      expect(decChat).toBeTruthy();
      expect(str(decChat.message)).toBe(str(origChat.message));
    }
    if (packet.aircraft) {
      const origAc = packet.aircraft as Record<string, unknown>;
      const decAc = decompressed.aircraft as Record<string, unknown>;
      expect(decAc).toBeTruthy();
      expect(num(decAc.icao)).toBe(num(origAc.icao));
      expect(str(decAc.registration)).toBe(str(origAc.registration));
      expect(str(decAc.flight)).toBe(str(origAc.flight));
      expect(num(decAc.squawk)).toBe(num(origAc.squawk));
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
    expect(pkt.latitudeI).toBe(Math.round(33.1284 * 1e7));
    expect(pkt.longitudeI).toBe(Math.round(-107.2528 * 1e7));
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

  it("PLI stationary clamps negative speed and course to zero", () => {
    // Regression for an iOS crash where ATAK's <track speed="-1.0"
    // course="-1.0"/> sentinel for stationary / unknown targets tripped a
    // Double -> UInt32 conversion trap in the Swift parser. The proto
    // field is uint32 on all platforms, so the fix is to clamp negatives
    // to 0 rather than wrap them into huge unsigned values. The TS parser
    // additionally strips proto3 zero defaults from the returned object,
    // so a clamped-to-0 field is absent rather than literally 0 — both
    // representations encode to the same wire bytes.
    const pkt = parseCotXml(loadFixtureXml("pli_stationary"));
    expect((pkt.speed as number | undefined) ?? 0).toBe(0);
    expect((pkt.course as number | undefined) ?? 0).toBe(0);
    expect(pkt.callsign).toBe("iPadTAKAware");
    // PLI is the implicit payload since v0.4.0 — no payload variant is set.
    expect(pkt.pli).toBeUndefined();
    expect(pkt.chat).toBeUndefined();
  });

  it("handles uncompressed 0xFF payload", async () => {
    const { getTAKPacketV2Type } = await import("../src/proto.js");
    const TAKPacketV2 = await getTAKPacketV2Type();
    const msg = TAKPacketV2.create({
      cotTypeId: 1, how: 2, callsign: "TEST",
      latitudeI: 340522000, longitudeI: -1182437000, altitude: 100,
      // PLI implicit since v0.4.0 — no payload variant.
    });
    const proto = Buffer.from(TAKPacketV2.encode(msg).finish());
    const wire = Buffer.concat([Buffer.from([0xff]), proto]);

    const decompressed = await compressor.decompress(wire);
    expect(decompressed.cotTypeId).toBe(1);
    expect(decompressed.callsign).toBe("TEST");
    expect(decompressed.latitudeI).toBe(340522000);
  });
});

/**
 * `<environment>` and `<sensor>`, ported from Kotlin. The target is what a mesh
 * receiver sees, so most cases go through the wire before they are checked.
 */
describe("Environment and SensorFov", () => {
  // SensorFov.SensorType
  const UNSPECIFIED = 0, CAMERA = 1, THERMAL = 2, LASER = 3, NVG = 4, RF = 5, OTHER = 6;

  /** A minimal PLI whose <detail> carries `inner`. */
  const withDetail = (inner: string) =>
    `<event version="2.0" uid="ALPHA-1" type="a-f-G-U-C" how="m-g" ` +
    `time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-15T14:22:55Z">` +
    `<point lat="38.8895" lon="-77.0353" hae="0" ce="9999999" le="9999999"/>` +
    `<detail><contact callsign="ALPHA-1"/>${inner}</detail></event>`;

  const overTheWire = async (xml: string): Promise<TAKPacketV2> =>
    compressor.decompress(await compressor.compress(parseCotXml(xml)));

  function expectFixtureAnnotations(pkt: TAKPacketV2): void {
    expect(pkt.environment).toEqual({ temperatureCX10: 225, windDirectionDeg: 270, windSpeedCmS: 1200 });
    const s = pkt.sensorFov;
    expect(s).toBeDefined();
    expect(s!.type).toBe(THERMAL);
    expect(s!.azimuthDeg).toBe(45);
    expect(s!.rangeM).toBe(2500);
    expect(s!.fovHorizontalDeg).toBe(45);
    expect(s!.fovVerticalDeg).toBe(30);
    expect(s!.elevationDeg).toBe(-5);
    expect(s!.rollDeg).toBe(0);
    expect(s!.model).toBe("FLIR-Boson-640");
  }

  it("pli_with_sensor keeps both annotations through parse, wire and rebuild", async () => {
    const pkt = parseCotXml(loadFixtureXml("pli_with_sensor"));
    expectFixtureAnnotations(pkt);

    const rt = await compressor.decompress(await compressor.compress(pkt));
    expectFixtureAnnotations(rt);

    // Template interpolation prints 12 where Kotlin prints 12.0.
    const xml = buildCotXml(rt);
    expect(xml).toContain(`<environment temperature="22.5" windDirection="270" windSpeed="12"/>`);
    expect(xml).toContain(`<sensor azimuth="45" range="2500" fov="45" vfov="30" elevation="-5" model="FLIR-Boson-640"/>`);
    expect(xml).not.toMatch(/<sensor\b[^>]* type=/);

    expectFixtureAnnotations(parseCotXml(xml));
  });

  it("pli_with_sensor serializes byte-identical to Kotlin's .pb golden", async () => {
    const golden = loadGoldenProtobuf("pli_with_sensor");
    expect(golden, "testdata/protobuf/pli_with_sensor.pb is missing").not.toBeNull();
    const TAKPacketV2Type = await getTAKPacketV2Type();
    const pkt = parseCotXml(loadFixtureXml("pli_with_sensor"));
    const bytes = Buffer.from(TAKPacketV2Type.encode(TAKPacketV2Type.create(pkt)).finish());
    expect(bytes.toString("hex")).toBe(golden!.toString("hex"));
  });

  it("treats a bare <sensor/> as present with ATAK-CIV's defaults", async () => {
    const xml = withDetail("<sensor/>");
    const rt = await overTheWire(xml);
    for (const pkt of [parseCotXml(xml), rt]) {
      expect(pkt.sensorFov).toEqual({
        type: UNSPECIFIED, azimuthDeg: 270, fovHorizontalDeg: 45, fovVerticalDeg: 0,
        elevationDeg: 0, rollDeg: 0, model: "",
      });
      expect(pkt.sensorFov!.rangeM).toBeUndefined();
    }
    expect(buildCotXml(rt)).toContain(`<sensor azimuth="270" fov="45" elevation="0"/>`);
  });

  it("keeps range=\"0\" present through the wire and the rebuild", async () => {
    const xml = withDetail(`<sensor range="0"/>`);
    expect(parseCotXml(xml).sensorFov!.rangeM).toBe(0);
    const rt = await overTheWire(xml);
    expect(rt.sensorFov!.rangeM).toBe(0);
    expect(buildCotXml(rt)).toContain(`<sensor azimuth="270" range="0" fov="45" elevation="0"/>`);
  });

  it("keeps an <environment> with only a temperature", async () => {
    const xml = withDetail(`<environment temperature="-3"/>`);
    const rt = await overTheWire(xml);
    for (const pkt of [parseCotXml(xml), rt]) {
      expect(pkt.environment).toEqual({ temperatureCX10: -30, windDirectionDeg: 0, windSpeedCmS: 0 });
    }
    expect(buildCotXml(rt)).toContain(`<environment temperature="-3"/>`);
  });

  it.each([
    [`<environment windDirection="270.5"/>`, "wind direction must be a whole number"],
    [`<environment/>`, "no attributes"],
    [`<environment temperature="abc"/>`, "unparseable temperature"],
  ])("drops %s (%s)", (element, _why) => {
    const pkt = parseCotXml(withDetail(element));
    expect(pkt.environment).toBeUndefined();
    expect(buildCotXml(pkt)).not.toContain("<environment");
  });

  it("lets a later readable <environment> replace an earlier one, and the last <sensor> win", () => {
    const pkt = parseCotXml(withDetail(
      `<environment temperature="10"/><environment windDirection="90"/><environment temperature="abc"/>` +
      `<sensor azimuth="10" model="FLIR"/><sensor azimuth="20"/>`,
    ));
    expect(pkt.environment).toEqual({ temperatureCX10: 0, windDirectionDeg: 90, windSpeedCmS: 0 });
    expect(pkt.sensorFov!.azimuthDeg).toBe(20);
    expect(pkt.sensorFov!.model).toBe("");
  });

  it("rounds the way Kotlin's roundToInt does", () => {
    const s = parseCotXml(withDetail(`<sensor azimuth="44.5" elevation="-2.5"/>`)).sensorFov!;
    expect(s.azimuthDeg).toBe(45);
    expect(s.elevationDeg).toBe(-2); // halves go up, not away from zero
    // Java's Math.round gives 0 here; floor(d + 0.5) would give 1.
    expect(parseCotXml(withDetail(`<sensor roll="0.49999999999999994"/>`)).sensorFov!.rollDeg).toBe(0);
    // 22.45 * 10 is exactly 224.5 as a double, so it rounds up like any half.
    expect(parseCotXml(withDetail(`<environment temperature="22.45"/>`)).environment!.temperatureCX10).toBe(225);
    // 1.005 * 100 is 100.49999999999999 as a double, so this one rounds down.
    expect(parseCotXml(withDetail(`<environment windSpeed="1.005"/>`)).environment!.windSpeedCmS).toBe(100);
  });

  it("writes a negative uint32 as its two's-complement bits and rebuilds it signed", async () => {
    const TAKPacketV2Type = await getTAKPacketV2Type();
    const pkt = parseCotXml(withDetail(`<sensor azimuth="-10"/>`));
    const bytes = Buffer.from(TAKPacketV2Type.encode(TAKPacketV2Type.create(pkt)).finish());
    // sensor_fov (tag 26) = { azimuth_deg: 0xFFFFFFF6, fov_horizontal_deg: 45 }. The
    // azimuth varint is what Wire writes for Kotlin's Int -10.
    expect(bytes.toString("hex")).toContain("d2010810f6ffffff0f202d");
    expect(buildCotXml(await overTheWire(withDetail(`<sensor azimuth="-10"/>`)))).toContain(`<sensor azimuth="-10" fov="45"`);
  });

  const sensorTypes: Array<[string | undefined, number]> = [
    [undefined, UNSPECIFIED], ["", UNSPECIFIED],
    ["FLIR-Boson-640", THERMAL], ["thermal cam", THERMAL], ["Boson", THERMAL],
    ["LRF-3000", LASER], ["laser", LASER],
    ["NVG-21", NVG], ["night vision", NVG],
    ["radar", RF], ["rf-detector", RF], ["RF-Scanner", RF],
    ["rfscanner", OTHER], ["rf", OTHER],
    ["EO-IR", CAMERA], ["camera", CAMERA], ["optic", CAMERA],
    ["widget", OTHER],
    ["FLIR radar", THERMAL], // keyword order matters
  ];
  it.each(sensorTypes)("infers the sensor type from model %j", (model, expected) => {
    const element = model === undefined ? "<sensor/>" : `<sensor model="${model}"/>`;
    expect(parseCotXml(withDetail(element)).sensorFov!.type).toBe(expected);
  });
});
