import { XMLParser } from "fast-xml-parser";
import { typeToEnum, howToEnum, COTTYPE_OTHER } from "./CotTypeMapper.js";

const TEAM_NAME_TO_ENUM: Record<string, number> = {
  White: 1, Yellow: 2, Orange: 3, Magenta: 4, Red: 5, Maroon: 6,
  Purple: 7, "Dark Blue": 8, Blue: 9, Cyan: 10, Teal: 11, Green: 12,
  "Dark Green": 13, Brown: 14,
};

const ROLE_NAME_TO_ENUM: Record<string, number> = {
  "Team Member": 1, "Team Lead": 2, HQ: 3, Sniper: 4, Medic: 5,
  ForwardObserver: 6, RTO: 7, K9: 8,
};

const GEO_SRC: Record<string, number> = { GPS: 1, USER: 2, NETWORK: 3 };

export function parseCotXml(cotXml: string): Record<string, unknown> {
  const parser = new XMLParser({
    ignoreAttributes: false,
    attributeNamePrefix: "@_",
    textNodeName: "#text",
  });
  const doc = parser.parse(cotXml);
  const event = doc.event;
  if (!event) throw new Error("No <event> element found");

  const typeStr = event["@_type"] ?? "";
  const cotTypeId = typeToEnum(typeStr);

  const point = event.point ?? {};
  const detail = event.detail ?? {};

  const contact = detail.contact ?? {};
  const group = detail["__group"] ?? {};
  const status = detail.status ?? {};
  const track = detail.track ?? {};
  const takv = detail.takv ?? {};
  const precision = detail.precisionlocation ?? {};
  const uidElem = detail.uid ?? detail.UID ?? {};
  const radio = detail["_radio"] ?? {};
  const aircot = detail["_aircot_"] ?? {};
  const chatElem = detail["__chat"] ?? {};
  const remarks = detail.remarks;

  let remarksText = "";
  if (typeof remarks === "string") remarksText = remarks;
  else if (remarks && remarks["#text"]) remarksText = String(remarks["#text"]);

  let hasAircraft = false;
  let hasChat = false;
  let icao = "", reg = "", flight = "", acType = "", category = "", cotHostId = "";
  let squawk = 0, rssiX10 = 0, gps = false;
  let chatTo: string | undefined, chatToCs: string | undefined;

  // _radio
  if (radio["@_rssi"]) {
    rssiX10 = Math.round(parseFloat(radio["@_rssi"]) * 10);
    hasAircraft = true;
    gps = radio["@_gps"] === "true";
  }

  // _aircot_
  if (aircot["@_icao"]) {
    hasAircraft = true;
    icao = aircot["@_icao"] ?? "";
    reg = aircot["@_reg"] ?? "";
    flight = aircot["@_flight"] ?? "";
    category = aircot["@_cat"] ?? "";
    cotHostId = aircot["@_cot_host_id"] ?? "";
  }

  // __chat
  if (chatElem["@_senderCallsign"] !== undefined || chatElem["@_id"] !== undefined) {
    hasChat = true;
    chatToCs = chatElem["@_senderCallsign"];
    chatTo = chatElem["@_id"];
  }

  // Parse ICAO from remarks
  if (!icao && remarksText) {
    const m = remarksText.match(/ICAO:\s*([A-Fa-f0-9]{6})/);
    if (m) {
      hasAircraft = true;
      icao = m[1];
      reg = remarksText.match(/REG:\s*(\S+)/)?.[1] ?? "";
      flight = remarksText.match(/Flight:\s*(\S+)/)?.[1] ?? "";
      acType = remarksText.match(/Type:\s*(\S+)/)?.[1] ?? "";
      const sq = remarksText.match(/Squawk:\s*(\d+)/);
      if (sq) squawk = parseInt(sq[1]);
      if (!category) category = remarksText.match(/Category:\s*(\S+)/)?.[1] ?? "";
    }
  }

  // Compute stale seconds
  const timeStr = event["@_time"] ?? "";
  const staleStr = event["@_stale"] ?? "";
  let staleSeconds = 0;
  if (timeStr && staleStr) {
    const t = new Date(timeStr).getTime();
    const s = new Date(staleStr).getTime();
    if (s > t) staleSeconds = Math.round((s - t) / 1000);
  }

  const pkt: Record<string, unknown> = {
    cotTypeId,
    how: howToEnum(event["@_how"] ?? ""),
    callsign: contact["@_callsign"] ?? "",
    team: TEAM_NAME_TO_ENUM[group["@_name"]] ?? 0,
    role: ROLE_NAME_TO_ENUM[group["@_role"]] ?? 0,
    latitudeI: Math.round(parseFloat(point["@_lat"] ?? "0") * 1e7),
    longitudeI: Math.round(parseFloat(point["@_lon"] ?? "0") * 1e7),
    altitude: Math.round(parseFloat(point["@_hae"] ?? "0")),
    speed: Math.round(parseFloat(track["@_speed"] ?? "0") * 100),
    course: Math.round(parseFloat(track["@_course"] ?? "0") * 100),
    battery: parseInt(status["@_battery"] ?? "0") || 0,
    geoSrc: GEO_SRC[precision["@_geopointsrc"]] ?? 0,
    altSrc: GEO_SRC[precision["@_altsrc"]] ?? 0,
    uid: event["@_uid"] ?? "",
    deviceCallsign: uidElem["@_Droid"] ?? "",
    staleSeconds,
    takVersion: takv["@_version"] ?? "",
    takDevice: takv["@_device"] ?? "",
    takPlatform: takv["@_platform"] ?? "",
    takOs: takv["@_os"] ?? "",
    endpoint: contact["@_endpoint"] ?? "",
    phone: contact["@_phone"] ?? "",
  };

  if (cotTypeId === COTTYPE_OTHER) pkt.cotTypeStr = typeStr;

  if (hasChat) {
    const chat: Record<string, string> = { message: remarksText };
    if (chatTo) chat.to = chatTo;
    if (chatToCs) chat.toCallsign = chatToCs;
    pkt.chat = chat;
  } else if (hasAircraft) {
    pkt.aircraft = {
      icao, registration: reg, flight, aircraftType: acType,
      squawk, category, rssiX10, gps, cotHostId,
    };
  } else {
    pkt.pli = true;
  }

  return pkt;
}
