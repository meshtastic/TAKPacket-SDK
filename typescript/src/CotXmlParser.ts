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
  "u-d-c-e": 8,        // Ellipse
  "u-d-v": 9,          // Vehicle2D
  "u-d-v-m": 10,       // Vehicle3D
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
const MARKER_KIND_GO_TO_POINT = 8;
const MARKER_KIND_INITIAL_POINT = 9;
const MARKER_KIND_CONTACT_POINT = 10;
const MARKER_KIND_OBSERVATION_POST = 11;
const MARKER_KIND_IMAGE_MARKER = 12;

function markerKindFromCotType(cotType: string, iconset: string): number {
  if (cotType === "b-m-p-s-m") return MARKER_KIND_SPOT;
  if (cotType === "b-m-p-w") return MARKER_KIND_WAYPOINT;
  if (cotType === "b-m-p-c") return MARKER_KIND_CHECKPOINT;
  if (cotType === "b-m-p-s-p-i" || cotType === "b-m-p-s-p-loc") return MARKER_KIND_SELF_POSITION;
  if (cotType === "b-m-p-w-GOTO") return MARKER_KIND_GO_TO_POINT;
  if (cotType === "b-m-p-c-ip") return MARKER_KIND_INITIAL_POINT;
  if (cotType === "b-m-p-c-cp") return MARKER_KIND_CONTACT_POINT;
  if (cotType === "b-m-p-s-p-op") return MARKER_KIND_OBSERVATION_POST;
  if (cotType === "b-i-x-i") return MARKER_KIND_IMAGE_MARKER;
  if (iconset.startsWith("COT_MAPPING_2525B")) return MARKER_KIND_SYMBOL_2525;
  if (iconset.startsWith("COT_MAPPING_SPOTMAP")) return MARKER_KIND_SPOT_MAP;
  if (iconset.length > 0) return MARKER_KIND_CUSTOM_ICON;
  return MARKER_KIND_UNSPECIFIED;
}

// --- CasevacReport / EmergencyAlert / TaskRequest mappings -------------

const PRECEDENCE_MAP: Record<string, number> = {
  A: 1, URGENT: 1, Urgent: 1,
  B: 2, "URGENT SURGICAL": 2, "Urgent Surgical": 2,
  C: 3, PRIORITY: 3, Priority: 3,
  D: 4, ROUTINE: 4, Routine: 4,
  E: 5, CONVENIENCE: 5, Convenience: 5,
};
const HLZ_MARKING_MAP: Record<string, number> = {
  Panels: 1, Pyro: 2, Pyrotechnic: 2,
  Smoke: 3, None: 4, Other: 5,
};
const SECURITY_MAP: Record<string, number> = {
  N: 1, "No Enemy": 1,
  P: 2, "Possible Enemy": 2,
  E: 3, "Enemy In Area": 3,
  X: 4, "Enemy In Armed Contact": 4,
};
const EMERGENCY_TYPE_MAP: Record<string, number> = {
  "911 Alert": 1, "911": 1,
  "Ring The Bell": 2, "Ring the Bell": 2,
  "In Contact": 3, "Troops In Contact": 3,
  "Geo-fence Breached": 4, "Geo Fence Breached": 4,
  Custom: 5, Cancel: 6,
};
const EMERGENCY_TYPE_BY_COT: Record<string, number> = {
  "b-a-o-tbl": 1, "b-a-o-pan": 2, "b-a-o-opn": 3,
  "b-a-g": 4, "b-a-o-c": 5, "b-a-o-can": 6,
};
const TASK_PRIORITY_MAP: Record<string, number> = {
  Low: 1, Normal: 2, Medium: 2, High: 3, Critical: 4,
};
const TASK_STATUS_MAP: Record<string, number> = {
  Pending: 1, Acknowledged: 2,
  InProgress: 3, "In Progress": 3,
  Completed: 4, Done: 4,
  Cancelled: 5, Canceled: 5,
};

// GeoChat ReceiptType
const RECEIPT_TYPE_NONE = 0;
const RECEIPT_TYPE_DELIVERED = 1;
const RECEIPT_TYPE_READ = 2;

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
    // Proto field is uint32 (cm/s for speed, deg*100 for course). ATAK
    // writes speed="-1.0" for stationary / unknown targets; protobufjs
    // silently wraps a negative number to 2^32 - N on the wire, which
    // corrupts round-trip. Clamp here instead.
    speed: Math.max(0, Math.round(parseFloat(track["@_speed"] ?? "0") * 100)),
    course: Math.max(0, Math.round(parseFloat(track["@_course"] ?? "0") * 100)),
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

  // --- CasevacReport extraction ----------------------------------------
  let hasCasevacData = false;
  let casevacPrecedence = 0;
  let casevacEquipmentFlags = 0;
  let casevacLitterPatients = 0;
  let casevacAmbulatoryPatients = 0;
  let casevacSecurity = 0;
  let casevacHlzMarking = 0;
  let casevacZoneMarker = "";
  let casevacUsMilitary = 0;
  let casevacUsCivilian = 0;
  let casevacNonUsMilitary = 0;
  let casevacNonUsCivilian = 0;
  let casevacEpw = 0;
  let casevacChild = 0;
  let casevacTerrainFlags = 0;
  let casevacFrequency = "";
  const medevacElem = detail._medevac_;
  if (medevacElem) {
    hasCasevacData = true;
    casevacPrecedence = PRECEDENCE_MAP[medevacElem["@_precedence"] ?? ""] ?? 0;
    let eq = 0;
    if (medevacElem["@_none"] === "true") eq |= 0x01;
    if (medevacElem["@_hoist"] === "true") eq |= 0x02;
    if (medevacElem["@_extraction_equipment"] === "true") eq |= 0x04;
    if (medevacElem["@_ventilator"] === "true") eq |= 0x08;
    if (medevacElem["@_blood"] === "true") eq |= 0x10;
    casevacEquipmentFlags = eq;
    casevacLitterPatients = parseInt(medevacElem["@_litter"] ?? "0", 10) || 0;
    casevacAmbulatoryPatients = parseInt(medevacElem["@_ambulatory"] ?? "0", 10) || 0;
    casevacSecurity = SECURITY_MAP[medevacElem["@_security"] ?? ""] ?? 0;
    casevacHlzMarking = HLZ_MARKING_MAP[medevacElem["@_hlz_marking"] ?? ""] ?? 0;
    casevacZoneMarker = medevacElem["@_zone_prot_marker"] ?? "";
    casevacUsMilitary = parseInt(medevacElem["@_us_military"] ?? "0", 10) || 0;
    casevacUsCivilian = parseInt(medevacElem["@_us_civilian"] ?? "0", 10) || 0;
    casevacNonUsMilitary = parseInt(medevacElem["@_non_us_military"] ?? "0", 10) || 0;
    casevacNonUsCivilian = parseInt(medevacElem["@_non_us_civilian"] ?? "0", 10) || 0;
    casevacEpw = parseInt(medevacElem["@_epw"] ?? "0", 10) || 0;
    casevacChild = parseInt(medevacElem["@_child"] ?? "0", 10) || 0;
    let tf = 0;
    if (medevacElem["@_terrain_slope"] === "true") tf |= 0x01;
    if (medevacElem["@_terrain_rough"] === "true") tf |= 0x02;
    if (medevacElem["@_terrain_loose"] === "true") tf |= 0x04;
    if (medevacElem["@_terrain_trees"] === "true") tf |= 0x08;
    if (medevacElem["@_terrain_wires"] === "true") tf |= 0x10;
    if (medevacElem["@_terrain_other"] === "true") tf |= 0x20;
    casevacTerrainFlags = tf;
    casevacFrequency = medevacElem["@_freq"] ?? "";
  }

  // --- EmergencyAlert extraction ---------------------------------------
  let hasEmergencyData = false;
  let emergencyType = 0;
  const emergencyElem = detail.emergency;
  if (emergencyElem) {
    hasEmergencyData = true;
    const typeAttr = emergencyElem["@_type"] ?? "";
    emergencyType = EMERGENCY_TYPE_MAP[typeAttr] ?? EMERGENCY_TYPE_BY_COT[typeStr] ?? 0;
    if (emergencyElem["@_cancel"] === "true") {
      emergencyType = 6;
    }
  }

  // --- TaskRequest extraction ------------------------------------------
  let hasTaskData = false;
  let taskTypeTag = "";
  let taskPriority = 0;
  let taskStatus = 0;
  let taskNote = "";
  let taskAssigneeUid = "";
  // Task target UID is populated later by the link walker below from
  // the first p-p link on a t-s event — it references the map item
  // being tasked, not the task element itself.
  let taskTargetUid = "";
  const taskElem = detail.task ?? detail._task_;
  if (taskElem) {
    hasTaskData = true;
    taskTypeTag = taskElem["@_type"] ?? "";
    taskPriority = TASK_PRIORITY_MAP[taskElem["@_priority"] ?? ""] ?? 0;
    taskStatus = TASK_STATUS_MAP[taskElem["@_status"] ?? ""] ?? 0;
    taskNote = taskElem["@_note"] ?? "";
    taskAssigneeUid = taskElem["@_assignee"] ?? "";
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

  // Chat receipt / task target / emergency authoring state: populated
  // from link elements whose context depends on the outer CoT type.
  let chatReceiptForUid = "";
  let chatReceiptType = RECEIPT_TYPE_NONE;
  let emergencyAuthoringUid = "";
  const emergencyCancelReferenceUid = "";

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
      // Chat receipts: <link uid="…original-message-uid…"
      // relation="p-p" type="b-t-f"/> on a b-t-f-d or b-t-f-r
      // event is a receipt pointing at the acknowledged message.
      if (typeStr === "b-t-f-d" || typeStr === "b-t-f-r") {
        if (chatReceiptForUid === "") chatReceiptForUid = linkUid;
        chatReceiptType = typeStr === "b-t-f-d"
          ? RECEIPT_TYPE_DELIVERED
          : RECEIPT_TYPE_READ;
        hasChat = true;
      } else if (typeStr === "t-s") {
        // Task target link: first non-self-ref p-p link on
        // a t-s event is the target being tasked.
        if (taskTargetUid === "") taskTargetUid = linkUid;
        hasTaskData = true;
      } else if (typeStr.startsWith("b-a-")) {
        // Emergency authoring link: the p-p link on a b-a-*
        // event references the unit that raised the alert.
        if (emergencyAuthoringUid === "") emergencyAuthoringUid = linkUid;
        hasEmergencyData = true;
      } else {
        // Marker parent link: no point attribute, p-p relation,
        // references a parent TAK user by UID + cot type.
        markerParentUid = linkUid;
        markerParentType = linkType;
        if (parentCallsign) markerParentCallsign = parentCallsign;
        hasMarkerParentLink = true;
      }
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

  // Payload priority: chat > aircraft > route > rab > shape > marker >
  // casevac > emergency > task > pli.
  //
  // Chat wins first to keep parity with pre-v2 behavior; chat receipts
  // (b-t-f-d / b-t-f-r) ride on the same Chat variant with receipt fields
  // populated. CASEVAC / emergency / task fall below shape/marker so a
  // drawing that happens to carry stray medevac attributes doesn't
  // mis-dispatch.
  if (hasChat) {
    const chat: Record<string, unknown> = { message: remarksText };
    if (chatTo) chat.to = chatTo;
    if (chatToCs) chat.toCallsign = chatToCs;
    if (chatReceiptForUid) chat.receiptForUid = chatReceiptForUid;
    if (chatReceiptType !== RECEIPT_TYPE_NONE) chat.receiptType = chatReceiptType;
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
  } else if (hasCasevacData) {
    pkt.casevac = stripZeros({
      precedence: casevacPrecedence,
      equipmentFlags: casevacEquipmentFlags,
      litterPatients: casevacLitterPatients,
      ambulatoryPatients: casevacAmbulatoryPatients,
      security: casevacSecurity,
      hlzMarking: casevacHlzMarking,
      zoneMarker: casevacZoneMarker,
      usMilitary: casevacUsMilitary,
      usCivilian: casevacUsCivilian,
      nonUsMilitary: casevacNonUsMilitary,
      nonUsCivilian: casevacNonUsCivilian,
      epw: casevacEpw,
      child: casevacChild,
      terrainFlags: casevacTerrainFlags,
      frequency: casevacFrequency,
    });
  } else if (hasEmergencyData) {
    pkt.emergency = stripZeros({
      type: emergencyType !== 0
        ? emergencyType
        : (EMERGENCY_TYPE_BY_COT[typeStr] ?? 0),
      authoringUid: emergencyAuthoringUid,
      cancelReferenceUid: emergencyCancelReferenceUid,
    });
  } else if (hasTaskData) {
    pkt.task = stripZeros({
      taskType: taskTypeTag,
      targetUid: taskTargetUid,
      assigneeUid: taskAssigneeUid,
      priority: taskPriority,
      status: taskStatus,
      note: taskNote,
    });
  } else {
    pkt.pli = true;
  }

  return stripZeros(pkt);
}
