import { typeToString, howToString } from "./CotTypeMapper.js";
import { resolveColor } from "./AtakPalette.js";
import type {
  TAKPacketV2, GeoChat, AircraftTrack, DrawnShape, Marker,
  RangeAndBearing, Route, RouteLink, CasevacReport, EmergencyAlert,
  TaskRequest, CotGeoPoint, ZMistEntry,
} from "./types.js";

const TEAM_NAMES: Record<number, string> = {
  1: "White", 2: "Yellow", 3: "Orange", 4: "Magenta", 5: "Red", 6: "Maroon",
  7: "Purple", 8: "Dark Blue", 9: "Blue", 10: "Cyan", 11: "Teal", 12: "Green",
  13: "Dark Green", 14: "Brown",
};

const ROLE_NAMES: Record<number, string> = {
  1: "Team Member", 2: "Team Lead", 3: "HQ", 4: "Sniper", 5: "Medic",
  6: "ForwardObserver", 7: "RTO", 8: "K9",
};

const GEO_SRC_NAMES: Record<number, string> = { 1: "GPS", 2: "USER", 3: "NETWORK" };

const ROUTE_METHOD_NAMES: Record<number, string> = {
  1: "Driving", 2: "Walking", 3: "Flying", 4: "Swimming", 5: "Watercraft",
};
const ROUTE_DIRECTION_NAMES: Record<number, string> = { 1: "Infil", 2: "Exfil" };
const BEARING_REF_NAMES: Record<number, string> = { 1: "M", 2: "T", 3: "G" };

// DrawnShape.Kind
const SHAPE_KIND_CIRCLE = 1;
const SHAPE_KIND_RANGING_CIRCLE = 6;
const SHAPE_KIND_BULLSEYE = 7;
const SHAPE_KIND_ELLIPSE = 8;

// DrawnShape.StyleMode
const STYLE_UNSPECIFIED = 0;
const STYLE_STROKE_ONLY = 1;
const STYLE_FILL_ONLY = 2;
const STYLE_STROKE_AND_FILL = 3;

// --- CasevacReport reverse lookups (mirror CotXmlParser maps) -----------
const PRECEDENCE_INT_TO_NAME: Record<number, string> = {
  1: "Urgent", 2: "Urgent Surgical", 3: "Priority",
  4: "Routine", 5: "Convenience",
};
const HLZ_MARKING_INT_TO_NAME: Record<number, string> = {
  1: "Panels", 2: "Pyro", 3: "Smoke", 4: "None", 5: "Other",
};
const SECURITY_INT_TO_NAME: Record<number, string> = {
  1: "N", 2: "P", 3: "E", 4: "X",
};

// --- EmergencyAlert reverse lookups ------------------------------------
const EMERGENCY_TYPE_INT_TO_NAME: Record<number, string> = {
  1: "911 Alert", 2: "Ring The Bell", 3: "In Contact",
  4: "Geo-fence Breached", 5: "Custom", 6: "Cancel",
};

// --- TaskRequest reverse lookups ---------------------------------------
const TASK_PRIORITY_INT_TO_NAME: Record<number, string> = {
  1: "Low", 2: "Normal", 3: "High", 4: "Critical",
};
const TASK_STATUS_INT_TO_NAME: Record<number, string> = {
  1: "Pending", 2: "Acknowledged", 3: "In Progress",
  4: "Completed", 5: "Cancelled",
};

// --- GeoChat ReceiptType -----------------------------------------------
const RECEIPT_TYPE_NONE = 0;

function esc(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
          .replace(/"/g, "&quot;").replace(/'/g, "&apos;");
}

/** Convert ARGB int to ABGR hex string (KML color format). */
function argbToAbgrHex(argb: number): string {
  const a = (argb >>> 24) & 0xFF;
  const r = (argb >>> 16) & 0xFF;
  const g = (argb >>> 8) & 0xFF;
  const b = argb & 0xFF;
  return [a, b, g, r].map(c => c.toString(16).padStart(2, "0")).join("");
}

/** Convert unsigned 32-bit ARGB back to ATAK's signed Int32 XML form. */
function argbToSigned(argb: number): number {
  return argb | 0;
}

export function buildCotXml(packet: TAKPacketV2): string {
  const now = new Date().toISOString();
  const staleSecs = Math.max(packet.staleSeconds ?? 0, 45);
  const stale = new Date(Date.now() + staleSecs * 1000).toISOString();

  const cotType = typeToString(packet.cotTypeId ?? 0) ?? packet.cotTypeStr ?? "";
  const how = howToString(packet.how ?? 0) ?? "m-g";
  let lat = (packet.latitudeI ?? 0) / 1e7;
  let lon = (packet.longitudeI ?? 0) / 1e7;
  const routePayload = packet.route;
  if (routePayload && (packet.latitudeI ?? 0) === 0 && (packet.longitudeI ?? 0) === 0) {
    const routeLinks = routePayload.links ?? [];
    if (routeLinks.length > 0) {
      const firstPoint = routeLinks[0].point ?? {};
      lat = (firstPoint.latDeltaI ?? 0) / 1e7;
      lon = (firstPoint.lonDeltaI ?? 0) / 1e7;
    }
  }
  const alt = packet.altitude ?? 0;

  const lines: string[] = [
    `<?xml version="1.0" encoding="UTF-8"?>`,
    `<event version="2.0" uid="${esc(String(packet.uid ?? ""))}" type="${esc(cotType)}" how="${esc(how)}" time="${now}" start="${now}" stale="${stale}">`,
    `  <point lat="${lat}" lon="${lon}" hae="${alt}" ce="9999999" le="9999999"/>`,
    `  <detail>`,
  ];

  const callsign = packet.callsign ?? "";
  const isRoute = packet.route != null;
  if (callsign && !isRoute) {
    const ep = packet.endpoint || "0.0.0.0:4242:tcp";
    let tag = `    <contact callsign="${esc(callsign)}" endpoint="${esc(ep)}"`;
    if (packet.phone) tag += ` phone="${esc(packet.phone)}"`;
    lines.push(tag + "/>");
  }

  const teamName = TEAM_NAMES[packet.team ?? 0];
  const roleName = ROLE_NAMES[packet.role ?? 0];
  if (teamName || roleName) {
    let tag = "    <__group";
    if (roleName) tag += ` role="${roleName}"`;
    if (teamName) tag += ` name="${teamName}"`;
    lines.push(tag + "/>");
  }

  const battery = packet.battery ?? 0;
  if (battery > 0) lines.push(`    <status battery="${battery}"/>`);

  const speed = packet.speed ?? 0;
  const course = packet.course ?? 0;
  if (speed > 0 || course > 0) {
    lines.push(`    <track speed="${speed / 100}" course="${course / 100}"/>`);
  }

  const takVersion = packet.takVersion ?? "";
  const takPlatform = packet.takPlatform ?? "";
  if (takVersion || takPlatform) {
    let tag = "    <takv";
    if (packet.takDevice) tag += ` device="${esc(packet.takDevice)}"`;
    if (takPlatform) tag += ` platform="${esc(takPlatform)}"`;
    if (packet.takOs) tag += ` os="${esc(packet.takOs)}"`;
    if (takVersion) tag += ` version="${esc(takVersion)}"`;
    lines.push(tag + "/>");
  }

  const geoSrc = packet.geoSrc ?? 0;
  const altSrc = packet.altSrc ?? 0;
  if (geoSrc > 0 || altSrc > 0) {
    lines.push(`    <precisionlocation geopointsrc="${GEO_SRC_NAMES[geoSrc] ?? "???"}" altsrc="${GEO_SRC_NAMES[altSrc] ?? "???"}"/>`);
  }

  const deviceCallsign = packet.deviceCallsign ?? "";
  if (deviceCallsign) lines.push(`    <uid Droid="${esc(deviceCallsign)}"/>`);

  // Payload-specific
  const chat = packet.chat;
  const aircraft = packet.aircraft;
  const shape = packet.shape;
  const marker = packet.marker;
  const rab = packet.rab;
  const route = packet.route;
  const casevac = packet.casevac;
  const emergency = packet.emergency;
  const task = packet.task;
  const eventLatI = packet.latitudeI ?? 0;
  const eventLonI = packet.longitudeI ?? 0;

  if (chat) {
    const receiptType = chat.receiptType ?? RECEIPT_TYPE_NONE;
    const receiptForUid = chat.receiptForUid ?? "";
    if (receiptType !== RECEIPT_TYPE_NONE && receiptForUid) {
      // Delivered / read receipt: emit a <link> pointing at the
      // original message UID. The envelope cot_type_id already
      // distinguishes delivered (b-t-f-d) vs read (b-t-f-r).
      lines.push(`    <link uid="${esc(receiptForUid)}" relation="p-p" type="b-t-f"/>`);
    } else {
      // Reconstruct the full __chat element that ATAK/iTAK needs
      // for routing and display. GeoChat event UID format:
      // GeoChat.{senderUid}.{chatroom}.{messageId}
      const uid = packet.uid ?? "";
      const gcParts = uid.split(".");
      // split(".") on "GeoChat.sender.chatroom.msgId" → exactly 4 parts
      // when the chatroom name contains no dots (the standard case).
      if (gcParts.length >= 4 && gcParts[0] === "GeoChat") {
        const senderUid = gcParts[1];
        const msgId = gcParts[gcParts.length - 1];
        const chatroom = gcParts.slice(2, -1).join(".");
        const senderCs = chat.toCallsign || packet.callsign || "UNKNOWN";
        const msg = chat.message ?? "";
        lines.push(`    <__chat parent="RootContactGroup" groupOwner="false" messageId="${esc(msgId)}" chatroom="${esc(chatroom)}" id="${esc(chatroom)}" senderCallsign="${esc(senderCs)}">`);
        lines.push(`      <chatgrp uid0="${esc(senderUid)}" uid1="${esc(chatroom)}" id="${esc(chatroom)}"/>`);
        lines.push(`    </__chat>`);
        lines.push(`    <link uid="${esc(senderUid)}" type="a-f-G-U-C" relation="p-p"/>`);
        lines.push(`    <__serverdestination destinations="0.0.0.0:4242:tcp:${esc(senderUid)}"/>`);
        lines.push(`    <remarks source="BAO.F.ATAK.${esc(senderUid)}" to="${esc(chatroom)}" time="${now}">${esc(msg)}</remarks>`);
      } else {
        lines.push(`    <remarks>${esc(chat.message ?? "")}</remarks>`);
      }
    }
  } else if (aircraft) {
    const icao = aircraft.icao ?? "";
    if (icao) {
      let tag = `    <_aircot_ icao="${esc(icao)}"`;
      if (aircraft.registration) tag += ` reg="${esc(aircraft.registration)}"`;
      if (aircraft.flight) tag += ` flight="${esc(aircraft.flight)}"`;
      if (aircraft.category) tag += ` cat="${esc(aircraft.category)}"`;
      if (aircraft.cotHostId) tag += ` cot_host_id="${esc(aircraft.cotHostId)}"`;
      lines.push(tag + "/>");
    }
    // Squawk and aircraft metadata as remarks text (ATAK parses these from remarks)
    const squawk = aircraft.squawk ?? 0;
    if (squawk > 0) {
      const parts: string[] = [];
      if (icao) parts.push(`ICAO: ${icao}`);
      if (aircraft.registration) parts.push(`REG: ${aircraft.registration}`);
      if (aircraft.aircraftType) parts.push(`Type: ${aircraft.aircraftType}`);
      parts.push(`Squawk: ${squawk}`);
      if (aircraft.flight) parts.push(`Flight: ${aircraft.flight}`);
      lines.push(`    <remarks>${esc(parts.join(" "))}</remarks>`);
    }
    // ADS-B receiver metadata
    const rssiX10 = aircraft.rssiX10 ?? 0;
    if (rssiX10 !== 0) {
      const rssi = rssiX10 / 10.0;
      let radioTag = `    <_radio rssi="${rssi}"`;
      if (aircraft.gps) radioTag += ` gps="true"`;
      lines.push(radioTag + "/>");
    }
  } else if (shape) {
    emitShape(lines, shape, eventLatI, eventLonI, packet.uid ?? "");
  } else if (marker) {
    emitMarker(lines, marker);
  } else if (rab) {
    emitRab(lines, rab, eventLatI, eventLonI);
  } else if (route) {
    emitRoute(lines, route, eventLatI, eventLonI, packet.uid ?? "", packet.remarks ?? "", callsign);
  } else if (casevac) {
    emitCasevac(lines, casevac);
  } else if (emergency) {
    emitEmergency(lines, emergency);
  } else if (task) {
    emitTask(lines, task);
  } else if (packet.rawDetail) {
    // Raw-detail fallback path: raw bytes of the original <detail>
    // element are shipped verbatim and re-emitted without any
    // normalization so the receiver round trip stays byte-exact with
    // the source XML.
    const raw = packet.rawDetail;
    const text = typeof raw === "string"
      ? raw
      : Buffer.from(raw as Uint8Array).toString("utf-8");
    if (text.length > 0) lines.push(text);
  }

  // Emit <remarks> for non-Chat/non-Aircraft/non-Route types that carried remarks text.
  // Chat uses GeoChat.message; Aircraft synthesizes from ICAO fields; Route handles
  // remarks in its own block above. All other types emit here.
  const remarksStr = packet.remarks ?? "";
  if (remarksStr && !chat && !aircraft && !route) {
    lines.push(`    <remarks>${esc(remarksStr)}</remarks>`);
  }

  lines.push("  </detail>");
  lines.push("</event>");
  return lines.join("\n");
}

// --- Typed geometry emitters -------------------------------------------

function emitShape(lines: string[], shape: DrawnShape, eventLatI: number, eventLonI: number, uid: string = ""): void {
  const kind = shape.kind ?? 0;
  const style = shape.style ?? STYLE_UNSPECIFIED;
  const strokeArgb = resolveColor(shape.strokeColor ?? 0, shape.strokeArgb ?? 0);
  const fillArgb = resolveColor(shape.fillColor ?? 0, shape.fillArgb ?? 0);
  const strokeVal = argbToSigned(strokeArgb);
  const fillVal = argbToSigned(fillArgb);
  const strokeWeightX10 = shape.strokeWeightX10 ?? 0;
  const labelsOn = shape.labelsOn ?? false;

  const emitStroke = style === STYLE_STROKE_ONLY || style === STYLE_STROKE_AND_FILL ||
    (style === STYLE_UNSPECIFIED && strokeVal !== 0);
  const emitFill = style === STYLE_FILL_ONLY || style === STYLE_STROKE_AND_FILL ||
    (style === STYLE_UNSPECIFIED && fillVal !== 0);

  const majorCm = shape.majorCm ?? 0;
  const minorCm = shape.minorCm ?? 0;
  const angleDeg = shape.angleDeg ?? 360;

  if (
    kind === SHAPE_KIND_CIRCLE ||
    kind === SHAPE_KIND_RANGING_CIRCLE ||
    kind === SHAPE_KIND_BULLSEYE ||
    kind === SHAPE_KIND_ELLIPSE
  ) {
    if (majorCm > 0 || minorCm > 0) {
      const strokeW = strokeWeightX10 / 10;
      lines.push("    <shape>");
      lines.push(`      <ellipse major="${majorCm / 100}" minor="${minorCm / 100}" angle="${angleDeg}"/>`);
      // KML style link — iTAK requires this to render circles/ellipses
      let kml = `      <link uid="${esc(uid)}.Style" type="b-x-KmlStyle" relation="p-c">`;
      kml += `<Style><LineStyle><color>${argbToAbgrHex(strokeVal)}</color><width>${strokeW}</width></LineStyle>`;
      if (fillVal !== 0) kml += `<PolyStyle><color>${argbToAbgrHex(fillVal)}</color></PolyStyle>`;
      kml += `</Style></link>`;
      lines.push(kml);
      lines.push("    </shape>");
    }
  } else {
    const vertices = shape.vertices ?? [];
    for (const v of vertices) {
      const vlat = (eventLatI + (v.latDeltaI ?? 0)) / 1e7;
      const vlon = (eventLonI + (v.lonDeltaI ?? 0)) / 1e7;
      lines.push(`    <link point="${vlat},${vlon}"/>`);
    }
  }

  if (kind === SHAPE_KIND_BULLSEYE) {
    const bullseyeDistanceDm = shape.bullseyeDistanceDm ?? 0;
    const bullseyeBearingRef = shape.bullseyeBearingRef ?? 0;
    const bullseyeFlags = shape.bullseyeFlags ?? 0;
    const bullseyeUidRef = shape.bullseyeUidRef ?? "";
    const parts: string[] = [];
    if (bullseyeDistanceDm > 0) parts.push(`distance="${bullseyeDistanceDm / 10}"`);
    const ref = BEARING_REF_NAMES[bullseyeBearingRef];
    if (ref) parts.push(`bearingRef="${ref}"`);
    if (bullseyeFlags & 0x01) parts.push(`rangeRingVisible="true"`);
    if (bullseyeFlags & 0x02) parts.push(`hasRangeRings="true"`);
    if (bullseyeFlags & 0x04) parts.push(`edgeToCenter="true"`);
    if (bullseyeFlags & 0x08) parts.push(`mils="true"`);
    if (bullseyeUidRef) parts.push(`bullseyeUID="${esc(bullseyeUidRef)}"`);
    lines.push(parts.length > 0 ? `    <bullseye ${parts.join(" ")}/>` : "    <bullseye/>");
  }

  if (emitStroke) {
    lines.push(`    <strokeColor value="${strokeVal}"/>`);
    if (strokeWeightX10 > 0) {
      lines.push(`    <strokeWeight value="${strokeWeightX10 / 10}"/>`);
    }
  }
  if (emitFill) {
    lines.push(`    <fillColor value="${fillVal}"/>`);
  }
  lines.push(`    <labels_on value="${labelsOn}"/>`);
}

function emitMarker(lines: string[], marker: Marker): void {
  if (marker.readiness === true) {
    lines.push(`    <status readiness="true"/>`);
  }
  const parentUid = marker.parentUid ?? "";
  if (parentUid) {
    const parts = [`uid="${esc(parentUid)}"`];
    const parentType = marker.parentType ?? "";
    if (parentType) parts.push(`type="${esc(parentType)}"`);
    const parentCallsign = marker.parentCallsign ?? "";
    if (parentCallsign) parts.push(`parent_callsign="${esc(parentCallsign)}"`);
    parts.push(`relation="p-p"`);
    lines.push(`    <link ${parts.join(" ")}/>`);
  }
  const colorArgb = resolveColor(marker.color ?? 0, marker.colorArgb ?? 0);
  const colorVal = argbToSigned(colorArgb);
  if (colorVal !== 0) {
    lines.push(`    <color argb="${colorVal}"/>`);
  }
  const iconset = marker.iconset ?? "";
  if (iconset) {
    lines.push(`    <usericon iconsetpath="${esc(iconset)}"/>`);
  }
}

function emitRab(lines: string[], rab: RangeAndBearing, eventLatI: number, eventLonI: number): void {
  const anchor = rab.anchor ?? {};
  const anchorLatI = eventLatI + (anchor.latDeltaI ?? 0);
  const anchorLonI = eventLonI + (anchor.lonDeltaI ?? 0);
  if (anchorLatI !== 0 || anchorLonI !== 0) {
    const alat = anchorLatI / 1e7;
    const alon = anchorLonI / 1e7;
    const parts: string[] = [];
    const anchorUid = rab.anchorUid ?? "";
    if (anchorUid) parts.push(`uid="${esc(anchorUid)}"`);
    parts.push(`relation="p-p"`, `type="b-m-p-w"`, `point="${alat},${alon}"`);
    lines.push(`    <link ${parts.join(" ")}/>`);
  }
  const rangeCm = rab.rangeCm ?? 0;
  if (rangeCm > 0) lines.push(`    <range value="${rangeCm / 100}"/>`);
  const bearingCdeg = rab.bearingCdeg ?? 0;
  if (bearingCdeg > 0) lines.push(`    <bearing value="${bearingCdeg / 100}"/>`);
  const strokeArgb = resolveColor(rab.strokeColor ?? 0, rab.strokeArgb ?? 0);
  const strokeVal = argbToSigned(strokeArgb);
  if (strokeVal !== 0) lines.push(`    <strokeColor value="${strokeVal}"/>`);
  const strokeWeightX10 = rab.strokeWeightX10 ?? 0;
  if (strokeWeightX10 > 0) lines.push(`    <strokeWeight value="${strokeWeightX10 / 10}"/>`);
}

function emitRoute(lines: string[], route: Route, eventLatI: number, eventLonI: number, eventUid: string = "", remarks: string = "", callsign: string = ""): void {
  // Emit <link> elements BEFORE <link_attr> (ATAK expects waypoints first)
  const links = route.links ?? [];
  for (let idx = 0; idx < links.length; idx++) {
    const link = links[idx];
    const point = link.point ?? {};
    const llat = (eventLatI + (point.latDeltaI ?? 0)) / 1e7;
    const llon = (eventLonI + (point.lonDeltaI ?? 0)) / 1e7;
    const linkType = link.linkType === 1 ? "b-m-p-c" : "b-m-p-w";
    const linkParts: string[] = [];
    // Generate deterministic uid when not present
    const rawUid = link.uid ?? "";
    const uid = rawUid || `${eventUid}-${idx}`;
    linkParts.push(`uid="${esc(uid)}"`);
    linkParts.push(`type="${linkType}"`);
    const linkCallsign = link.callsign ?? "";
    if (linkCallsign) linkParts.push(`callsign="${esc(linkCallsign)}"`);
    // ATAK expects 3-component point: lat,lon,hae
    linkParts.push(`point="${llat},${llon},0" relation="c"`);
    lines.push(`    <link ${linkParts.join(" ")}/>`);
  }
  const parts: string[] = [];
  const method = ROUTE_METHOD_NAMES[route.method ?? 0];
  if (method) parts.push(`method="${method}"`);
  const direction = ROUTE_DIRECTION_NAMES[route.direction ?? 0];
  if (direction) parts.push(`direction="${direction}"`);
  const prefix = route.prefix ?? "";
  if (prefix) parts.push(`prefix="${esc(prefix)}"`);
  const strokeWeightX10 = route.strokeWeightX10 ?? 0;
  if (strokeWeightX10 > 0) parts.push(`stroke="${strokeWeightX10 / 10}"`);
  lines.push(parts.length > 0 ? `    <link_attr ${parts.join(" ")}/>` : "    <link_attr/>");
  // Conditional remarks element (route block handles its own remarks)
  if (remarks) {
    lines.push(`    <remarks>${esc(remarks)}</remarks>`);
  } else {
    lines.push("    <remarks/>");
  }
  // routeinfo with navcues child (after link_attr)
  lines.push("    <__routeinfo><__navcues/></__routeinfo>");
  lines.push(`    <strokeColor value="-1"/>`);
  const routeStrokeW = route.strokeWeightX10 ?? 0;
  lines.push(`    <strokeWeight value="${routeStrokeW > 0 ? routeStrokeW / 10 : 3}"/>`);
  lines.push(`    <strokeStyle value="solid"/>`);
  if (callsign) {
    lines.push(`    <contact callsign="${esc(callsign)}"/>`);
  }
  lines.push(`    <labels_on value="false"/>`);
  lines.push(`    <color value="-1"/>`);
}

function emitCasevac(lines: string[], casevac: CasevacReport): void {
  const parts: string[] = [];

  // Metadata (emit first to match ATAK's attribute ordering)
  const title = casevac.title ?? "";
  if (title) parts.push(`title="${esc(title)}"`);
  const medlineRemarks = casevac.medlineRemarks ?? "";
  if (medlineRemarks) parts.push(`medline_remarks="${esc(medlineRemarks)}"`);
  const frequency = casevac.frequency ?? "";
  if (frequency) parts.push(`freq="${esc(frequency)}"`);

  const precedence = casevac.precedence ?? 0;
  const precedenceName = PRECEDENCE_INT_TO_NAME[precedence];
  if (precedenceName) parts.push(`precedence="${precedenceName}"`);

  // Precedence patient counts (newer ATAK format)
  const urgent = casevac.urgentCount ?? 0;
  if (urgent > 0) parts.push(`urgent="${urgent}"`);
  const urgentSurgical = casevac.urgentSurgicalCount ?? 0;
  if (urgentSurgical > 0) parts.push(`urgent_surgical="${urgentSurgical}"`);
  const priority = casevac.priorityCount ?? 0;
  if (priority > 0) parts.push(`priority="${priority}"`);
  const routine = casevac.routineCount ?? 0;
  if (routine > 0) parts.push(`routine="${routine}"`);
  const convenience = casevac.convenienceCount ?? 0;
  if (convenience > 0) parts.push(`convenience="${convenience}"`);

  // Equipment bitfield flags
  const eq = casevac.equipmentFlags ?? 0;
  if (eq & 0x01) parts.push(`none="true"`);
  if (eq & 0x02) parts.push(`hoist="true"`);
  if (eq & 0x04) parts.push(`extraction_equipment="true"`);
  if (eq & 0x08) parts.push(`ventilator="true"`);
  if (eq & 0x10) parts.push(`blood="true"`);
  if (eq & 0x20) parts.push(`equipment_other="true"`);
  const equipmentDetail = casevac.equipmentDetail ?? "";
  if (equipmentDetail) parts.push(`equipment_detail="${esc(equipmentDetail)}"`);

  const litter = casevac.litterPatients ?? 0;
  if (litter > 0) parts.push(`litter="${litter}"`);
  const ambulatory = casevac.ambulatoryPatients ?? 0;
  if (ambulatory > 0) parts.push(`ambulatory="${ambulatory}"`);

  const security = casevac.security ?? 0;
  const securityName = SECURITY_INT_TO_NAME[security];
  if (securityName) parts.push(`security="${securityName}"`);

  const hlz = casevac.hlzMarking ?? 0;
  const hlzName = HLZ_MARKING_INT_TO_NAME[hlz];
  if (hlzName) parts.push(`hlz_marking="${hlzName}"`);

  const usMil = casevac.usMilitary ?? 0;
  if (usMil > 0) parts.push(`us_military="${usMil}"`);
  const usCiv = casevac.usCivilian ?? 0;
  if (usCiv > 0) parts.push(`us_civilian="${usCiv}"`);
  const nonUsMil = casevac.nonUsMilitary ?? 0;
  if (nonUsMil > 0) parts.push(`non_us_military="${nonUsMil}"`);
  const nonUsCiv = casevac.nonUsCivilian ?? 0;
  if (nonUsCiv > 0) parts.push(`non_us_civilian="${nonUsCiv}"`);
  const epw = casevac.epw ?? 0;
  if (epw > 0) parts.push(`epw="${epw}"`);
  const child = casevac.child ?? 0;
  if (child > 0) parts.push(`child="${child}"`);

  // Terrain bitfield flags + extended detail
  const tf = casevac.terrainFlags ?? 0;
  if (tf & 0x01) parts.push(`terrain_slope="true"`);
  const terrainSlopeDir = casevac.terrainSlopeDir ?? "";
  if (terrainSlopeDir) parts.push(`terrain_slope_dir="${esc(terrainSlopeDir)}"`);
  if (tf & 0x02) parts.push(`terrain_rough="true"`);
  if (tf & 0x04) parts.push(`terrain_loose="true"`);
  if (tf & 0x08) parts.push(`terrain_trees="true"`);
  if (tf & 0x10) parts.push(`terrain_wires="true"`);
  if (tf & 0x20) parts.push(`terrain_other="true"`);
  const terrainOtherDetail = casevac.terrainOtherDetail ?? "";
  if (terrainOtherDetail) parts.push(`terrain_other_detail="${esc(terrainOtherDetail)}"`);

  // Location / marking
  const zoneProtectedCoord = casevac.zoneProtectedCoord ?? "";
  if (zoneProtectedCoord) parts.push(`zone_protected_coord="${esc(zoneProtectedCoord)}"`);
  const zoneMarker = casevac.zoneMarker ?? "";
  if (zoneMarker) parts.push(`zone_prot_marker="${esc(zoneMarker)}"`);
  const markedBy = casevac.markedBy ?? "";
  if (markedBy) parts.push(`marked_by="${esc(markedBy)}"`);

  // Situational awareness (tier-2 free-text)
  const obstacles = casevac.obstacles ?? "";
  if (obstacles) parts.push(`obstacles="${esc(obstacles)}"`);
  const windsAreFrom = casevac.windsAreFrom ?? "";
  if (windsAreFrom) parts.push(`winds_are_from="${esc(windsAreFrom)}"`);
  const friendlies = casevac.friendlies ?? "";
  if (friendlies) parts.push(`friendlies="${esc(friendlies)}"`);
  const enemy = casevac.enemy ?? "";
  if (enemy) parts.push(`enemy="${esc(enemy)}"`);
  const hlzRemarks = casevac.hlzRemarks ?? "";
  if (hlzRemarks) parts.push(`hlz_remarks="${esc(hlzRemarks)}"`);

  const zmist = casevac.zmist ?? [];
  if (!zmist || zmist.length === 0) {
    lines.push(parts.length > 0 ? `    <_medevac_ ${parts.join(" ")}/>` : "    <_medevac_/>");
  } else {
    lines.push(parts.length > 0 ? `    <_medevac_ ${parts.join(" ")}>` : "    <_medevac_>");
    lines.push(`      <zMistsMap>`);
    for (const z of zmist) {
      const zParts: string[] = [];
      const zt = z.title ?? "";
      if (zt) zParts.push(`title="${esc(zt)}"`);
      const zz = z.z ?? "";
      if (zz) zParts.push(`z="${esc(zz)}"`);
      const zm = z.m ?? "";
      if (zm) zParts.push(`m="${esc(zm)}"`);
      const zi = z.i ?? "";
      if (zi) zParts.push(`i="${esc(zi)}"`);
      const zs = z.s ?? "";
      if (zs) zParts.push(`s="${esc(zs)}"`);
      const zti = z.t ?? "";
      if (zti) zParts.push(`t="${esc(zti)}"`);
      lines.push(zParts.length > 0 ? `        <zMist ${zParts.join(" ")}/>` : `        <zMist/>`);
    }
    lines.push(`      </zMistsMap>`);
    lines.push(`    </_medevac_>`);
  }
}

function emitEmergency(lines: string[], emergency: EmergencyAlert): void {
  const type = emergency.type ?? 0;
  const parts: string[] = [];
  if (type === 6) {
    // Cancel: ATAK writes <emergency cancel="true"/> rather than
    // type="Cancel" so receivers can branch on a boolean.
    parts.push(`cancel="true"`);
  } else {
    const name = EMERGENCY_TYPE_INT_TO_NAME[type];
    if (name) parts.push(`type="${name}"`);
  }
  lines.push(parts.length > 0 ? `    <emergency ${parts.join(" ")}/>` : "    <emergency/>");

  const authoringUid = emergency.authoringUid ?? "";
  if (authoringUid) {
    lines.push(`    <link uid="${esc(authoringUid)}" relation="p-p" type="a-f-G-U-C"/>`);
  }
  const cancelReferenceUid = emergency.cancelReferenceUid ?? "";
  if (cancelReferenceUid) {
    lines.push(`    <link uid="${esc(cancelReferenceUid)}" relation="p-p" type="b-a-o-tbl"/>`);
  }
}

function emitTask(lines: string[], task: TaskRequest): void {
  const parts: string[] = [];
  const taskType = task.taskType ?? "";
  if (taskType) parts.push(`type="${esc(taskType)}"`);

  const priority = task.priority ?? 0;
  const priorityName = TASK_PRIORITY_INT_TO_NAME[priority];
  if (priorityName) parts.push(`priority="${priorityName}"`);

  const status = task.status ?? 0;
  const statusName = TASK_STATUS_INT_TO_NAME[status];
  if (statusName) parts.push(`status="${statusName}"`);

  const assigneeUid = task.assigneeUid ?? "";
  if (assigneeUid) parts.push(`assignee="${esc(assigneeUid)}"`);

  const note = task.note ?? "";
  if (note) parts.push(`note="${esc(note)}"`);

  lines.push(parts.length > 0 ? `    <task ${parts.join(" ")}/>` : "    <task/>");

  // Target link
  const targetUid = task.targetUid ?? "";
  if (targetUid) {
    lines.push(`    <link uid="${esc(targetUid)}" relation="p-p" type="a-f-G"/>`);
  }
}
