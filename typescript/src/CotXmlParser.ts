import { XMLParser } from "fast-xml-parser";
import { typeToEnum, howToEnum, COTTYPE_OTHER } from "./CotTypeMapper.js";
import { argbToTeam } from "./AtakPalette.js";

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

const ROUTE_METHOD_MAP: Record<string, number> = {
  Driving: 1, Walking: 2, Flying: 3, Swimming: 4, Watercraft: 5,
};
const ROUTE_DIRECTION_MAP: Record<string, number> = { Infil: 1, Exfil: 2 };
const BEARING_REF_MAP: Record<string, number> = { M: 1, T: 2, G: 3 };

/** Vertex pool cap — matches *DrawnShape.vertices max_count:32. */
const MAX_VERTICES = 32;
/** Route link pool cap — matches *Route.links max_count:16. */
const MAX_ROUTE_LINKS = 16;

// DrawnShape.Kind constants
const SHAPE_KIND_UNSPECIFIED = 0;
const SHAPE_KIND_BY_COT_TYPE: Record<string, number> = {
  "u-d-c-c": 1,        // Circle
  "u-d-r": 2,          // Rectangle
  "u-d-f": 3,          // Freeform
  "u-d-f-m": 4,        // Telestration
  "u-d-p": 5,          // Polygon
  "u-r-b-c-c": 6,      // RangingCircle
  "u-r-b-bullseye": 7, // Bullseye
};

// DrawnShape.StyleMode constants
const STYLE_UNSPECIFIED = 0;
const STYLE_STROKE_ONLY = 1;
const STYLE_FILL_ONLY = 2;
const STYLE_STROKE_AND_FILL = 3;

// Marker.Kind constants
const MARKER_KIND_UNSPECIFIED = 0;
const MARKER_KIND_SPOT = 1;
const MARKER_KIND_WAYPOINT = 2;
const MARKER_KIND_CHECKPOINT = 3;
const MARKER_KIND_SELF_POSITION = 4;
const MARKER_KIND_SYMBOL_2525 = 5;
const MARKER_KIND_SPOT_MAP = 6;
const MARKER_KIND_CUSTOM_ICON = 7;

function markerKindFromCotType(cotType: string, iconset: string): number {
  if (cotType === "b-m-p-s-m") return MARKER_KIND_SPOT;
  if (cotType === "b-m-p-w") return MARKER_KIND_WAYPOINT;
  if (cotType === "b-m-p-c") return MARKER_KIND_CHECKPOINT;
  if (cotType === "b-m-p-s-p-i" || cotType === "b-m-p-s-p-loc") return MARKER_KIND_SELF_POSITION;
  if (iconset.startsWith("COT_MAPPING_2525B")) return MARKER_KIND_SYMBOL_2525;
  if (iconset.startsWith("COT_MAPPING_SPOTMAP")) return MARKER_KIND_SPOT_MAP;
  if (iconset.length > 0) return MARKER_KIND_CUSTOM_ICON;
  return MARKER_KIND_UNSPECIFIED;
}

/**
 * Extract the inner bytes of the `<detail>` element from a CoT XML event,
 * exactly as they appear in the source — no XML normalization, no
 * re-escaping.  Used by {@link TakCompressor.compressBestOf} to build a
 * `raw_detail` fallback packet alongside the typed packet.
 *
 * Returns an empty `Buffer` for events with a self-closing `<detail/>` or
 * no `<detail>` at all.  Receivers rehydrate the full event by wrapping
 * these bytes in `<detail>…</detail>`, so a byte-for-byte extraction is
 * required to keep the round trip loss-free.
 */
export function extractRawDetailBytes(cotXml: string): Buffer {
  const match = cotXml.match(/<detail\b[^>]*>([\s\S]*?)<\/detail\s*>/i);
  if (!match) return Buffer.alloc(0);
  return Buffer.from(match[1], "utf-8");
}

/** fast-xml-parser returns a single object for a lone element and an array
 * when there are 2+. Coerce to an array so callers can always iterate. */
function toArray<T>(x: T | T[] | undefined | null): T[] {
  if (x === undefined || x === null) return [];
  return Array.isArray(x) ? x : [x];
}

/**
 * protobufjs serializes scalar fields that are explicitly passed to
 * `create()`, even when they hold the proto3 default value (0, "", false).
 * Proto3 wire format elides defaults; this helper mimics that behavior by
 * removing keys whose values are the zero-value for their type, so the
 * resulting byte stream matches what a proto3-native encoder produces.
 *
 * Only touches the top-level keys — nested objects are left to the caller.
 */
function stripZeros<T extends Record<string, unknown>>(obj: T): T {
  const result: Record<string, unknown> = {};
  for (const [k, v] of Object.entries(obj)) {
    if (v === 0 || v === "" || v === false) continue;
    if (Array.isArray(v) && v.length === 0) continue;
    result[k] = v;
  }
  return result as T;
}

/**
 * Build a GeoPoint object with zero-valued delta fields elided. Avoids
 * the 2-bytes-per-zero-vertex overhead that protobufjs would otherwise
 * add when `{ latDeltaI: 0, lonDeltaI: ... }` is passed to create().
 */
function makeGeoPoint(latDeltaI: number, lonDeltaI: number): Record<string, number> {
  const gp: Record<string, number> = {};
  if (latDeltaI !== 0) gp.latDeltaI = latDeltaI;
  if (lonDeltaI !== 0) gp.lonDeltaI = lonDeltaI;
  return gp;
}

export function parseCotXml(cotXml: string): Record<string, unknown> {
  // Reject XML with DOCTYPE or ENTITY declarations to prevent XXE and entity expansion
  const lower = cotXml.toLowerCase();
  if (lower.includes("<!doctype") || lower.includes("<!entity")) {
    throw new Error("XML contains prohibited DOCTYPE or ENTITY declaration");
  }

  const parser = new XMLParser({
    ignoreAttributes: false,
    attributeNamePrefix: "@_",
    textNodeName: "#text",
    processEntities: false,
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

  const latitudeI = Math.round(parseFloat(point["@_lat"] ?? "0") * 1e7);
  const longitudeI = Math.round(parseFloat(point["@_lon"] ?? "0") * 1e7);

  const pkt: Record<string, unknown> = {
    cotTypeId,
    how: howToEnum(event["@_how"] ?? ""),
    callsign: contact["@_callsign"] ?? "",
    team: TEAM_NAME_TO_ENUM[group["@_name"]] ?? 0,
    role: ROLE_NAME_TO_ENUM[group["@_role"]] ?? 0,
    latitudeI,
    longitudeI,
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

  // --- Typed geometry extraction (shape / marker / rab / route) --------

  // Style presence flags — discriminate StrokeOnly vs FillOnly vs both.
  let sawStrokeColor = false;
  let sawFillColor = false;
  let strokeColorArgb = 0;
  let fillColorArgb = 0;
  let strokeWeightX10 = 0;
  let labelsOn = false;

  const strokeColorElem = detail.strokeColor;
  if (strokeColorElem) {
    sawStrokeColor = true;
    const v = strokeColorElem["@_value"];
    if (v !== undefined) strokeColorArgb = (parseInt(v, 10) | 0) >>> 0;
  }
  const fillColorElem = detail.fillColor;
  if (fillColorElem) {
    sawFillColor = true;
    const v = fillColorElem["@_value"];
    if (v !== undefined) fillColorArgb = (parseInt(v, 10) | 0) >>> 0;
  }
  const strokeWeightElem = detail.strokeWeight;
  if (strokeWeightElem) {
    const v = strokeWeightElem["@_value"];
    if (v !== undefined) strokeWeightX10 = Math.max(0, Math.round(parseFloat(v) * 10));
  }
  const labelsOnElem = detail.labels_on;
  if (labelsOnElem) labelsOn = labelsOnElem["@_value"] === "true";

  // Shape ellipse
  let shapeMajorCm = 0;
  let shapeMinorCm = 0;
  let shapeAngleDeg = 360;
  const shapeElem = detail.shape;
  if (shapeElem) {
    const ellipse = shapeElem.ellipse;
    if (ellipse) {
      shapeMajorCm = Math.max(0, Math.round(parseFloat(ellipse["@_major"] ?? "0") * 100));
      shapeMinorCm = Math.max(0, Math.round(parseFloat(ellipse["@_minor"] ?? "0") * 100));
      shapeAngleDeg = parseInt(ellipse["@_angle"] ?? "360", 10) || 360;
    }
  }

  // Bullseye
  let bullseyeDistanceDm = 0;
  let bullseyeBearingRef = 0;
  let bullseyeFlags = 0;
  let bullseyeUidRef = "";
  const bullseyeElem = detail.bullseye;
  if (bullseyeElem) {
    bullseyeDistanceDm = Math.max(0, Math.round(parseFloat(bullseyeElem["@_distance"] ?? "0") * 10));
    bullseyeBearingRef = BEARING_REF_MAP[bullseyeElem["@_bearingRef"] ?? ""] ?? 0;
    let flags = 0;
    if (bullseyeElem["@_rangeRingVisible"] === "true") flags |= 0x01;
    if (bullseyeElem["@_hasRangeRings"] === "true") flags |= 0x02;
    if (bullseyeElem["@_edgeToCenter"] === "true") flags |= 0x04;
    if (bullseyeElem["@_mils"] === "true") flags |= 0x08;
    bullseyeFlags = flags;
    bullseyeUidRef = bullseyeElem["@_bullseyeUID"] ?? "";
  }

  // Range/bearing
  let rabRangeCm = 0;
  let rabBearingCdeg = 0;
  const rangeElem = detail.range;
  if (rangeElem) {
    rabRangeCm = Math.max(0, Math.round(parseFloat(rangeElem["@_value"] ?? "0") * 100));
  }
  const bearingElem = detail.bearing;
  if (bearingElem) {
    rabBearingCdeg = Math.max(0, Math.round(parseFloat(bearingElem["@_value"] ?? "0") * 100));
  }

  // Marker color + icon
  let markerColorArgb = 0;
  let markerIconset = "";
  let markerReadiness = false;
  const colorElem = detail.color;
  if (colorElem) {
    const v = colorElem["@_argb"];
    if (v !== undefined) markerColorArgb = (parseInt(v, 10) | 0) >>> 0;
  }
  const usericonElem = detail.usericon;
  if (usericonElem) markerIconset = usericonElem["@_iconsetpath"] ?? "";
  if (status["@_readiness"] === "true") markerReadiness = true;

  // Route info
  const hasRouteInfo = detail.__routeinfo !== undefined;
  const linkAttrElem = detail.link_attr;
  let routeMethod = 0;
  let routeDirection = 0;
  let routePrefix = "";
  if (linkAttrElem) {
    routeMethod = ROUTE_METHOD_MAP[linkAttrElem["@_method"] ?? ""] ?? 0;
    routeDirection = ROUTE_DIRECTION_MAP[linkAttrElem["@_direction"] ?? ""] ?? 0;
    routePrefix = linkAttrElem["@_prefix"] ?? "";
    const strokeAttr = linkAttrElem["@_stroke"];
    if (strokeAttr !== undefined) {
      const sw = parseInt(strokeAttr, 10);
      if (sw > 0) strokeWeightX10 = sw * 10;
    }
  }

  // Walk <link> children to collect shape vertices, route waypoints,
  // RAB anchor, and marker parent links.
  const verticesAbs: { lat: number; lon: number }[] = [];
  let verticesTruncated = false;
  const routeLinksAbs: {
    latI: number; lonI: number; uid: string; callsign: string; linkType: number;
  }[] = [];
  let routeTruncated = false;
  let rabAnchorLatI = 0;
  let rabAnchorLonI = 0;
  let rabAnchorUid = "";
  let markerParentUid = "";
  let markerParentType = "";
  let markerParentCallsign = "";
  let hasShapeLinks = false;
  let hasRouteLinks = false;
  let hasRabAnchorLink = false;
  let hasMarkerParentLink = false;

  for (const link of toArray<Record<string, string>>(detail.link)) {
    const linkUid = link["@_uid"];
    const pointAttr = link["@_point"];
    const linkType = link["@_type"] ?? "";
    const relation = link["@_relation"] ?? "";
    const linkCallsign = link["@_callsign"] ?? "";
    const parentCallsign = link["@_parent_callsign"] ?? "";

    // Ignore style links nested inside <shape>
    const isStyleLink = linkType.startsWith("b-x-KmlStyle") ||
      (linkUid !== undefined && linkUid.endsWith(".Style"));
    if (isStyleLink) continue;

    if (pointAttr !== undefined) {
      const parts = pointAttr.split(",");
      if (parts.length < 2) continue;
      const plat = parseFloat(parts[0]);
      const plon = parseFloat(parts[1]);
      if (!Number.isFinite(plat) || !Number.isFinite(plon)) continue;
      const plati = Math.round(plat * 1e7);
      const ploni = Math.round(plon * 1e7);

      if (typeStr === "u-rb-a") {
        if (!hasRabAnchorLink) {
          rabAnchorLatI = plati;
          rabAnchorLonI = ploni;
          if (linkUid) rabAnchorUid = linkUid;
          hasRabAnchorLink = true;
        }
      } else if ((linkType === "b-m-p-w" || linkType === "b-m-p-c") && typeStr === "b-m-r") {
        if (routeLinksAbs.length < MAX_ROUTE_LINKS) {
          routeLinksAbs.push({
            latI: plati, lonI: ploni,
            uid: linkUid ?? "",
            callsign: linkCallsign,
            linkType: linkType === "b-m-p-c" ? 1 : 0,
          });
        } else {
          routeTruncated = true;
        }
        hasRouteLinks = true;
      } else {
        if (verticesAbs.length < MAX_VERTICES) {
          verticesAbs.push({ lat: plati, lon: ploni });
          hasShapeLinks = true;
        } else {
          verticesTruncated = true;
        }
      }
    } else if (linkUid && relation === "p-p" && linkType) {
      markerParentUid = linkUid;
      markerParentType = linkType;
      if (parentCallsign) markerParentCallsign = parentCallsign;
      hasMarkerParentLink = true;
    }
  }

  const hasShapeData = !!shapeElem || hasShapeLinks || sawStrokeColor || sawFillColor;
  const hasMarkerData = markerColorArgb !== 0 || markerIconset.length > 0 || hasMarkerParentLink;
  const hasRabData = rabRangeCm > 0 || rabBearingCdeg > 0 || hasRabAnchorLink;
  const hasRouteData = hasRouteInfo || !!linkAttrElem || hasRouteLinks;

  // Derive style mode from presence flags
  let shapeStyle: number = STYLE_UNSPECIFIED;
  if (sawStrokeColor && sawFillColor) shapeStyle = STYLE_STROKE_AND_FILL;
  else if (sawStrokeColor) shapeStyle = STYLE_STROKE_ONLY;
  else if (sawFillColor) shapeStyle = STYLE_FILL_ONLY;

  // Payload priority: chat > aircraft > route > rab > shape > marker > pli.
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
  } else if (hasRouteData && routeLinksAbs.length > 0) {
    pkt.route = stripZeros({
      method: routeMethod,
      direction: routeDirection,
      prefix: routePrefix,
      strokeWeightX10,
      links: routeLinksAbs.map(l => ({
        point: makeGeoPoint(l.latI - latitudeI, l.lonI - longitudeI),
        uid: l.uid,
        callsign: l.callsign,
        linkType: l.linkType,
      })),
      truncated: routeTruncated,
    });
  } else if (hasRabData) {
    pkt.rab = stripZeros({
      anchor: makeGeoPoint(rabAnchorLatI - latitudeI, rabAnchorLonI - longitudeI),
      anchorUid: rabAnchorUid,
      rangeCm: rabRangeCm,
      bearingCdeg: rabBearingCdeg,
      strokeColor: argbToTeam(strokeColorArgb),
      strokeArgb: strokeColorArgb,
      strokeWeightX10,
    });
  } else if (hasShapeData) {
    pkt.shape = stripZeros({
      kind: SHAPE_KIND_BY_COT_TYPE[typeStr] ?? SHAPE_KIND_UNSPECIFIED,
      style: shapeStyle,
      majorCm: shapeMajorCm,
      minorCm: shapeMinorCm,
      angleDeg: shapeAngleDeg,
      strokeColor: argbToTeam(strokeColorArgb),
      strokeArgb: strokeColorArgb,
      strokeWeightX10,
      fillColor: argbToTeam(fillColorArgb),
      fillArgb: fillColorArgb,
      labelsOn,
      vertices: verticesAbs.map(v => makeGeoPoint(v.lat - latitudeI, v.lon - longitudeI)),
      truncated: verticesTruncated,
      bullseyeDistanceDm,
      bullseyeBearingRef,
      bullseyeFlags,
      bullseyeUidRef,
    });
  } else if (hasMarkerData) {
    pkt.marker = stripZeros({
      kind: markerKindFromCotType(typeStr, markerIconset),
      color: argbToTeam(markerColorArgb),
      colorArgb: markerColorArgb,
      readiness: markerReadiness,
      parentUid: markerParentUid,
      parentType: markerParentType,
      parentCallsign: markerParentCallsign,
      iconset: markerIconset,
    });
  } else {
    pkt.pli = true;
  }

  return stripZeros(pkt);
}
