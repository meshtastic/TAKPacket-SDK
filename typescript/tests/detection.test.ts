import { describe, it, expect } from "vitest";
import { TakCompressor } from "../src/TakCompressor.js";
import { DICT_ID_AIRCRAFT, DICT_ID_NON_AIRCRAFT, selectDictId } from "../src/DictionaryProvider.js";
import { parseCotXml } from "../src/CotXmlParser.js";
import { buildCotXml } from "../src/CotXmlBuilder.js";
import { getTAKPacketV2Type, loadProto } from "../src/proto.js";
import type { TAKDetection, TAKPacketV2 } from "../src/types.js";
import { loadFixtureXml, loadGolden, loadGoldenProtobuf } from "./helpers.js";

/**
 * The passive-sensor detection annotation (TAKPacketV2 tag 47) as `<__detection>`
 * and on the wire, mirroring Kotlin's DetectionXmlTest.
 */

const compressor = new TakCompressor();

// A synthetic CTA-2063-A serial number: the placeholder manufacturer code ZZZZ,
// length code 8, then the eight-character serial.
const UAS_ID = "ZZZZ8TEST0001";

const FULL_ELEMENT =
  `<__detection kind="uas" sources="remote_id rf" confidence="87" state="active" trend="closing" count="2" ` +
  `sensorAnchored="true" cep="12" rssi="-71" uasId="${UAS_ID}" uasIdType="serial_number" uasStatus="airborne"/>`;

// TAKDetection enum values, as protobufjs carries them.
const Kind = { Unspecified: 0, Uas: 1, UasOperator: 2, UasHome: 3, Person: 4, Vehicle: 5, Other: 6 };
const Source = { RemoteId: 1, Rf: 2, Acoustic: 4, Radar: 8, Optical: 16, Thermal: 32, Seismic: 64, Magnetic: 128 };
const State = { Unspecified: 0, New: 1, Active: 2, Coasting: 3, Lost: 4 };
const Trend = { Unspecified: 0, Closing: 1, Steady: 2, Opening: 3 };
const UasIdType = { Unspecified: 0, SerialNumber: 1, CaaRegistration: 2, UtmAssigned: 3, SpecificSession: 4 };
const UasStatus = { Unspecified: 0, Ground: 1, Airborne: 2, Emergency: 3, SystemFailure: 4 };
const SENSOR_TYPE_RF = 5;

/** A present detection with every field at its default, the way it decodes. */
const DEFAULTS: TAKDetection = {
  kind: 0, sources: 0, confidence: 0, state: 0, trend: 0, count: 0, sensorAnchored: false,
  cepM: 0, rssiDbm: 0, uasId: Buffer.alloc(0), uasIdType: 0, uasStatus: 0,
};

/** A minimal event whose <detail> carries `detail`. */
function event(detail: string, { type = "a-u-A-M-H-Q", ce = "9999999" } = {}): string {
  return `<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="UAS-1" type="${type}" how="m-r" time="2026-03-15T15:00:00Z" start="2026-03-15T15:00:00Z" stale="2026-03-15T15:01:00Z">
  <point lat="33.1286" lon="-107.2470" hae="1452.0" ce="${ce}" le="9999999"/>
  <detail><contact callsign="UAS-1"/>${detail}</detail>
</event>`;
}

const detection = (element: string, ce = "9999999"): TAKDetection | undefined =>
  parseCotXml(event(element, { ce })).detection;

/** A packet built in code that carries `d`. */
const packet = (d: TAKDetection): TAKPacketV2 => ({ ...parseCotXml(event("")), detection: d });

const throughTheWire = async (p: TAKPacketV2): Promise<TAKPacketV2> =>
  compressor.decompress(await compressor.compress(p));

const detectionLine = (xml: string): string | undefined =>
  xml.split("\n").find((line) => line.startsWith("    <__detection"));

const pointCe = (xml: string): string | undefined => /<point\b[^>]*\bce="([^"]*)"/.exec(xml)?.[1];

// --- Tokens ----------------------------------------------------------------

// Spelled out rather than derived, so a renamed enum value can't quietly change the XML.
type TokenField = "kind" | "sources" | "state" | "trend" | "uasIdType" | "uasStatus";
const TOKEN_TABLES: Array<[TokenField, string, Array<[number, string]>]> = [
  ["kind", "Kind", [
    [Kind.Unspecified, "unspecified"], [Kind.Uas, "uas"], [Kind.UasOperator, "uas_operator"],
    [Kind.UasHome, "uas_home"], [Kind.Person, "person"], [Kind.Vehicle, "vehicle"], [Kind.Other, "other"],
  ]],
  ["sources", "Source", [
    [0, "unspecified"], [Source.RemoteId, "remote_id"], [Source.Rf, "rf"], [Source.Acoustic, "acoustic"],
    [Source.Radar, "radar"], [Source.Optical, "optical"], [Source.Thermal, "thermal"],
    [Source.Seismic, "seismic"], [Source.Magnetic, "magnetic"],
  ]],
  ["state", "State", [
    [State.Unspecified, "unspecified"], [State.New, "new"], [State.Active, "active"],
    [State.Coasting, "coasting"], [State.Lost, "lost"],
  ]],
  ["trend", "Trend", [
    [Trend.Unspecified, "unspecified"], [Trend.Closing, "closing"], [Trend.Steady, "steady"],
    [Trend.Opening, "opening"],
  ]],
  ["uasIdType", "UasIdType", [
    [UasIdType.Unspecified, "unspecified"], [UasIdType.SerialNumber, "serial_number"],
    [UasIdType.CaaRegistration, "caa_registration"], [UasIdType.UtmAssigned, "utm_assigned"],
    [UasIdType.SpecificSession, "specific_session"],
  ]],
  ["uasStatus", "UasStatus", [
    [UasStatus.Unspecified, "unspecified"], [UasStatus.Ground, "ground"], [UasStatus.Airborne, "airborne"],
    [UasStatus.Emergency, "emergency"], [UasStatus.SystemFailure, "system_failure"],
  ]],
];

describe.each(TOKEN_TABLES)("<__detection %s> tokens", (field, enumName, rows) => {
  it(`cover every TAKDetection.${enumName} value`, async () => {
    // A value added to the schema later without a token fails here.
    const root = await loadProto();
    const values = Object.values(root.lookupEnum(`meshtastic.TAKDetection.${enumName}`).values);
    expect(rows.map(([value]) => value).sort((a, b) => a - b)).toEqual(values.sort((a, b) => a - b));
  });

  it.each(rows)(`read and write %i as "%s"`, (value, token) => {
    expect(detection(`<__detection ${field}="${token}"/>`)![field]).toBe(value);
    const line = detectionLine(buildCotXml(packet({ [field]: value } as TAKDetection)));
    expect(line).toBe(value === 0 ? "    <__detection/>" : `    <__detection ${field}="${token}"/>`);
  });
});

describe("TAKDetection", () => {
  it("writes sources in bit order and reads them back as a bitmask", () => {
    const all = Object.values(Source).reduce((bits, bit) => bits | bit, 0);
    const xml = buildCotXml(packet({ sources: all }));
    expect(xml).toContain(`sources="remote_id rf acoustic radar optical thermal seismic magnetic"`);
    expect(parseCotXml(xml).detection!.sources).toBe(all);
  });

  // --- The element ---------------------------------------------------------

  it("keeps the full element through parse, wire, rebuild and reparse", async () => {
    const expected: TAKDetection = {
      kind: Kind.Uas, sources: Source.RemoteId | Source.Rf, confidence: 87, state: State.Active,
      trend: Trend.Closing, count: 2, sensorAnchored: true, cepM: 12, rssiDbm: -71, uasId: Buffer.from(UAS_ID),
      uasIdType: UasIdType.SerialNumber, uasStatus: UasStatus.Airborne,
    };
    const parsed = parseCotXml(event(FULL_ELEMENT));
    expect(parsed.detection).toEqual(expected);

    const received = await throughTheWire(parsed);
    expect(received.detection).toEqual(expected);

    const rebuilt = buildCotXml(received);
    expect(rebuilt).toContain(`    ${FULL_ELEMENT}\n`);
    expect(rebuilt).toContain(`ce="12" le="9999999"`);
    expect(parseCotXml(rebuilt).detection).toEqual(expected);
  });

  it("treats a bare <__detection/> as present with every default", async () => {
    expect(detection("<__detection/>")).toEqual(DEFAULTS);
    expect((await throughTheWire(parseCotXml(event("<__detection/>")))).detection).toEqual(DEFAULTS);
    const xml = buildCotXml(packet(DEFAULTS));
    expect(detectionLine(xml)).toBe("    <__detection/>");
    expect(pointCe(xml)).toBe("9999999");
  });

  it("leaves detection absent without the element and keeps the unknown ce", async () => {
    const parsed = parseCotXml(event("", { ce: "12.0" }));
    expect(parsed.detection).toBeUndefined();
    expect((await throughTheWire(parsed)).detection).toBeUndefined();
    const xml = buildCotXml(parsed);
    expect(xml).not.toContain("__detection");
    expect(pointCe(xml)).toBe("9999999");
  });

  it("lets the last <__detection> win", () => {
    expect(detection(`<__detection kind="person"/><__detection kind="vehicle"/>`)!.kind).toBe(Kind.Vehicle);
  });

  it("reads unknown tokens as unspecified and ignores case", () => {
    const d = detection(
      `<__detection kind="balloon" sources=" RF bogus  ACOUSTIC " state="LOST" trend="sideways" ` +
      `uasIdType="mystery" uasStatus="Ground" sensorAnchored="TRUE"/>`,
    )!;
    expect(d.kind).toBe(Kind.Unspecified);
    expect(d.sources).toBe(Source.Rf | Source.Acoustic);
    expect(d.state).toBe(State.Lost);
    expect(d.trend).toBe(Trend.Unspecified);
    expect(d.uasIdType).toBe(UasIdType.Unspecified);
    expect(d.uasStatus).toBe(UasStatus.Ground);
    expect(d.sensorAnchored).toBe(true);
    expect(detection(`<__detection sensorAnchored="yes"/>`)!.sensorAnchored).toBe(false);
  });

  it("rounds numbers half up and clamps them", () => {
    const read = (attrs: string) => detection(`<__detection ${attrs}/>`)!;
    expect(read(`confidence="250"`).confidence).toBe(100);
    expect(read(`confidence="-5"`).confidence).toBe(0);
    expect(read(`confidence="87.5"`).confidence).toBe(88);
    expect(read(`count="-3"`).count).toBe(0);
    expect(read(`cep="-1"`).cepM).toBe(0);
    expect(read(`cep=" 12 "`).cepM).toBe(12);
    expect(read(`rssi="-70.5"`).rssiDbm).toBe(-70);
    expect(read(`rssi="-70.6"`).rssiDbm).toBe(-71);
    expect(read(`count="1.5e3"`).count).toBe(1500);
    // Not plain decimals, or too large to be finite: read as absent.
    expect(read(`confidence="NaN" count="Infinity" cep="0x10" rssi="1e400"`)).toEqual(DEFAULTS);
    expect(read(`count="2f"`).count).toBe(0);
  });

  it("clamps values that arrive out of range when it builds", async () => {
    // Built in code, then as received: the uint32 fields decode unsigned and read back as int32.
    const built = packet({ confidence: 150, count: -2, cepM: -4 });
    for (const p of [built, await throughTheWire(built)]) {
      const xml = buildCotXml(p);
      expect(detectionLine(xml)).toBe(`    <__detection confidence="100"/>`);
      expect(pointCe(xml)).toBe("9999999");
    }
  });

  it("cuts the UAS ID to 20 bytes and keeps it only for a UAS", () => {
    const long = UAS_ID.repeat(2); // 26 characters
    expect(detection(`<__detection kind="uas" uasId="${long}"/>`)!.uasId).toEqual(Buffer.from(long.slice(0, 20)));
    // Text beyond ASCII counts in UTF-8 bytes.
    const accented = "A".repeat(19) + "é";
    expect(detection(`<__detection kind="uas" uasId="${accented}"/>`)!.uasId)
      .toEqual(Buffer.from(accented, "utf8").subarray(0, 20));

    for (const kind of ["uas_operator", "uas_home", "person", "vehicle", "other", "unspecified"]) {
      expect(detection(`<__detection kind="${kind}" uasId="${UAS_ID}"/>`)!.uasId, kind).toEqual(Buffer.alloc(0));
    }
    expect(detection(`<__detection uasId="${UAS_ID}"/>`)!.uasId).toEqual(Buffer.alloc(0));

    // The builder holds a packet built in code to the same rule.
    expect(buildCotXml(packet({ kind: Kind.Person, uasId: Buffer.from(UAS_ID) }))).not.toContain(UAS_ID);
    expect(buildCotXml(packet({ kind: Kind.Uas, uasId: Buffer.from(long) }))).toContain(`uasId="${long.slice(0, 20)}"`);
  });

  it("sends a binary UAS ID as hex", async () => {
    // A UTM-assigned UUID is 16 raw bytes, most of them outside the text set.
    const uuid = Buffer.from("123e4567e89b42d3a456426614174000", "hex");
    const d: TAKDetection = { ...DEFAULTS, kind: Kind.Uas, uasId: uuid, uasIdType: UasIdType.UtmAssigned };
    const xml = buildCotXml(packet(d));
    expect(xml).toContain(`uasId="hex:123e4567e89b42d3a456426614174000"`);
    expect(parseCotXml(xml).detection).toEqual(d);
    expect((await throughTheWire(packet(d))).detection).toEqual(d);

    // Hex reads in any case; a value that is not whole hex digits is dropped.
    expect(detection(`<__detection kind="uas" uasId="HEX:123E4567E89B42D3A456426614174000"/>`)!.uasId).toEqual(uuid);
    for (const bad of ["hex:123", "hex:12zz", "hex:+1", "hex:"]) {
      expect(detection(`<__detection kind="uas" uasId="${bad}"/>`)!.uasId, bad).toEqual(Buffer.alloc(0));
    }
    // Hex longer than 20 bytes is cut like text.
    expect(detection(`<__detection kind="uas" uasId="hex:${"11".repeat(25)}"/>`)!.uasId).toEqual(Buffer.alloc(20, 0x11));

    // One byte outside letters, digits, '-', '.' and '_' sends the whole ID as hex.
    expect(buildCotXml(packet({ kind: Kind.Uas, uasId: Buffer.from("ZZZZ8 TEST") })))
      .toContain(`uasId="hex:5a5a5a5a382054455354"`);
    const quoted = Buffer.from(`ZZZZ8"&<`);
    const quotedXml = buildCotXml(packet({ kind: Kind.Uas, uasId: quoted }));
    expect(quotedXml).toContain(`uasId="hex:5a5a5a5a3822263c"`);
    expect(parseCotXml(quotedXml).detection!.uasId).toEqual(quoted);
  });

  it("falls back to the point's ce for a missing cep", () => {
    expect(detection("<__detection/>", "15.0")!.cepM).toBe(15);
    expect(detection(`<__detection cep="12"/>`, "15.0")!.cepM).toBe(12);
    expect(detection(`<__detection cep="unknown"/>`, "15.0")!.cepM).toBe(15);
    expect(detection("<__detection/>", "9999999")!.cepM).toBe(0);
    expect(detection("<__detection/>", "9999999.0")!.cepM).toBe(0);
    expect(detection("<__detection/>", "-3")!.cepM).toBe(0);
  });

  it("leaves the payload variant alone", async () => {
    const parentLink = `<link uid="ANDROID-0000000000000012" type="a-f-G-E-S" parent_callsign="SENSOR-1" relation="p-p"/>`;
    const annotated = parseCotXml(event(parentLink + FULL_ELEMENT));
    expect(annotated.marker?.parentCallsign).toBe("SENSOR-1");
    expect(annotated.marker).toEqual(parseCotXml(event(parentLink)).marker);

    // A position report stays one: no payload_variant member is set.
    const pli = parseCotXml(event(FULL_ELEMENT, { type: "a-f-G-U-C" })) as Record<string, unknown>;
    const payloadVariant = (await getTAKPacketV2Type()).oneofs.payloadVariant.oneof;
    expect(payloadVariant.filter((member) => pli[member] !== undefined)).toEqual([]);
  });

  it("carries source bits with no token over the wire and names only the known ones", async () => {
    const unnamed = 1 << 9;
    const d: TAKDetection = { ...DEFAULTS, kind: Kind.Uas, sources: Source.Radar | unnamed };
    expect((await throughTheWire(packet(d))).detection).toEqual(d);
    // CoT XML can only name the bits it knows.
    expect(detectionLine(buildCotXml(packet(d)))).toBe(`    <__detection kind="uas" sources="radar"/>`);
  });
});

// --- Fixtures ----------------------------------------------------------------

const DETECTION_FIXTURES: Array<[string, TAKDetection]> = [
  ["detection_uas_remoteid", {
    ...DEFAULTS, kind: Kind.Uas, sources: Source.RemoteId | Source.Rf, confidence: 87, state: State.Active,
    trend: Trend.Closing, cepM: 12, rssiDbm: -71, uasId: Buffer.from(UAS_ID), uasIdType: UasIdType.SerialNumber,
    uasStatus: UasStatus.Airborne,
  }],
  ["detection_uas_sensor_anchored", {
    ...DEFAULTS, kind: Kind.Uas, sources: Source.Rf, confidence: 60, state: State.New, sensorAnchored: true,
    rssiDbm: -84,
  }],
  // cep comes from <point ce="15.0">.
  ["detection_uas_operator", {
    ...DEFAULTS, kind: Kind.UasOperator, sources: Source.RemoteId, confidence: 87, state: State.Active, cepM: 15,
  }],
  ["detection_ground_vehicle", {
    ...DEFAULTS, kind: Kind.Vehicle, sources: Source.Acoustic | Source.Seismic, confidence: 72,
    state: State.Active, trend: Trend.Opening, count: 2, cepM: 25,
  }],
];

describe("TAKDetection fixtures", () => {
  it.each(DETECTION_FIXTURES)("%s parses to the expected detection", (name, expected) => {
    expect(parseCotXml(loadFixtureXml(name)).detection).toEqual(expected);
  });

  it.each(DETECTION_FIXTURES)("%s equals Kotlin's .pb and .bin goldens", async (name) => {
    const parsed = parseCotXml(loadFixtureXml(name));
    // Compared as messages, not bytes: Wire writes fields in .proto declaration order,
    // where `detection` sits before `marti` and the payload oneof, while protobufjs
    // writes them in field-number order, so tag 47 lands in a different place.
    const T = await getTAKPacketV2Type();
    const asMessage = (bytes: Uint8Array) => T.toObject(T.decode(bytes), { defaults: true });
    const golden = loadGoldenProtobuf(name);
    expect(golden, `testdata/protobuf/${name}.pb is missing`).not.toBeNull();
    expect(asMessage(T.encode(T.create(parsed)).finish())).toEqual(asMessage(golden!));

    const frame = loadGolden(name);
    expect(frame, `testdata/golden/${name}.bin is missing`).not.toBeNull();
    expect((await compressor.decompress(frame!)).detection).toEqual(parsed.detection);
  });

  it("detection_uas_remoteid keeps its Marker payload", () => {
    const pkt = parseCotXml(loadFixtureXml("detection_uas_remoteid"));
    expect(pkt.marker?.parentUid).toBe("ANDROID-0000000000000012");
  });

  it("detection_uas_sensor_anchored keeps its sensor cone", () => {
    const s = parseCotXml(loadFixtureXml("detection_uas_sensor_anchored")).sensorFov!;
    expect(s.azimuthDeg).toBe(0);
    expect(s.fovHorizontalDeg).toBe(360);
    expect(s.rangeM).toBe(2000);
    expect(s.type).toBe(SENSOR_TYPE_RF);
  });
});

// selectDictId picks by CoT type, which would send an a-u-A-... UAS marker with the
// 4 KB aircraft dictionary. The compressor sends a detection that is not an aircraft
// track with the non-aircraft dictionary instead.
describe("detection dictionary choice", () => {
  const wireDictId = async (p: TAKPacketV2): Promise<number> => (await compressor.compress(p))[0];

  it("sends a detection marker with the non-aircraft dictionary whatever its type", async () => {
    const uas = parseCotXml(loadFixtureXml("detection_uas_remoteid"));
    expect(selectDictId(uas.cotTypeId ?? 0, uas.cotTypeStr ?? undefined)).toBe(DICT_ID_AIRCRAFT);
    expect(await wireDictId(uas)).toBe(DICT_ID_NON_AIRCRAFT);
  });

  it("keeps the aircraft dictionary for the same marker without its detection", async () => {
    const uas = { ...parseCotXml(loadFixtureXml("detection_uas_remoteid")), detection: undefined };
    expect(await wireDictId(uas)).toBe(DICT_ID_AIRCRAFT);
  });

  it("keeps the aircraft dictionary for an aircraft track with a detection", async () => {
    const { detection } = parseCotXml(loadFixtureXml("detection_uas_remoteid"));
    const track = parseCotXml(loadFixtureXml("aircraft_adsb"));
    expect(track.aircraft).toBeDefined();
    expect(await wireDictId({ ...track, detection })).toBe(DICT_ID_AIRCRAFT);
  });
});
