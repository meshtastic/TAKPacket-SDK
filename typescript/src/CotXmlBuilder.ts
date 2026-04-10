import { typeToString, howToString } from "./CotTypeMapper.js";
import { resolveColor } from "./AtakPalette.js";

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

/** Convert unsigned 32-bit ARGB back to ATAK's signed Int32 XML form. */
function argbToSigned(argb: number): number {
  return argb | 0;
}

export function buildCotXml(packet: Record<string, unknown>): string {
  const now = new Date().toISOString();
  const staleSecs = Math.max((packet.staleSeconds as number) ?? 0, 45);
  const stale = new Date(Date.now() + staleSecs * 1000).toISOString();

  const cotType = typeToString(packet.cotTypeId as number) ?? (packet.cotTypeStr as string) ?? "";
  const how = howToString(packet.how as number) ?? "m-g";
  const lat = ((packet.latitudeI as number) ?? 0) / 1e7;
  const lon = ((packet.longitudeI as number) ?? 0) / 1e7;
  const alt = (packet.altitude as number) ?? 0;

  const lines: string[] = [
    `<?xml version="1.0" encoding="UTF-8"?>`,
    `<event version="2.0" uid="${esc(String(packet.uid ?? ""))}" type="${esc(cotType)}" how="${esc(how)}" time="${now}" start="${now}" stale="${stale}">`,
    `  <point lat="${lat}" lon="${lon}" hae="${alt}" ce="9999999" le="9999999"/>`,
    `  <detail>`,
  ];

  const callsign = packet.callsign as string ?? "";
  if (callsign) {
    let tag = `    <contact callsign="${esc(callsign)}"`;
    if (packet.endpoint) tag += ` endpoint="${esc(String(packet.endpoint))}"`;
    if (packet.phone) tag += ` phone="${esc(String(packet.phone))}"`;
    lines.push(tag + "/>");
  }

  const teamName = TEAM_NAMES[packet.team as number];
  const roleName = ROLE_NAMES[packet.role as number];
  if (teamName || roleName) {
    let tag = "    <__group";
    if (roleName) tag += ` role="${roleName}"`;
    if (teamName) tag += ` name="${teamName}"`;
    lines.push(tag + "/>");
  }

  const battery = (packet.battery as number) ?? 0;
  if (battery > 0) lines.push(`    <status battery="${battery}"/>`);

  const speed = (packet.speed as number) ?? 0;
  const course = (packet.course as number) ?? 0;
  if (speed > 0 || course > 0) {
    lines.push(`    <track speed="${speed / 100}" course="${course / 100}"/>`);
  }

  const takVersion = packet.takVersion as string ?? "";
  const takPlatform = packet.takPlatform as string ?? "";
  if (takVersion || takPlatform) {
    let tag = "    <takv";
    if (packet.takDevice) tag += ` device="${esc(String(packet.takDevice))}"`;
    if (takPlatform) tag += ` platform="${esc(takPlatform)}"`;
    if (packet.takOs) tag += ` os="${esc(String(packet.takOs))}"`;
    if (takVersion) tag += ` version="${esc(takVersion)}"`;
    lines.push(tag + "/>");
  }

  const geoSrc = (packet.geoSrc as number) ?? 0;
  const altSrc = (packet.altSrc as number) ?? 0;
  if (geoSrc > 0 || altSrc > 0) {
    lines.push(`    <precisionlocation geopointsrc="${GEO_SRC_NAMES[geoSrc] ?? "???"}" altsrc="${GEO_SRC_NAMES[altSrc] ?? "???"}"/>`);
  }

  const deviceCallsign = packet.deviceCallsign as string ?? "";
  if (deviceCallsign) lines.push(`    <uid Droid="${esc(deviceCallsign)}"/>`);

  // Payload-specific
  const chat = packet.chat as Record<string, unknown> | undefined;
  const aircraft = packet.aircraft as Record<string, unknown> | undefined;
  const shape = packet.shape as Record<string, unknown> | undefined;
  const marker = packet.marker as Record<string, unknown> | undefined;
  const rab = packet.rab as Record<string, unknown> | undefined;
  const route = packet.route as Record<string, unknown> | undefined;
  const casevac = packet.casevac as Record<string, unknown> | undefined;
  const emergency = packet.emergency as Record<string, unknown> | undefined;
  const task = packet.task as Record<string, unknown> | undefined;
  const eventLatI = (packet.latitudeI as number) ?? 0;
  const eventLonI = (packet.longitudeI as number) ?? 0;

  if (chat) {
    const receiptType = (chat.receiptType as number) ?? RECEIPT_TYPE_NONE;
    const receiptForUid = (chat.receiptForUid as string) ?? "";
    if (receiptType !== RECEIPT_TYPE_NONE && receiptForUid) {
      // Delivered / read receipt: emit a <link> pointing at the
      // original message UID. The envelope cot_type_id already
      // distinguishes delivered (b-t-f-d) vs read (b-t-f-r).
      lines.push(`    <link uid="${esc(receiptForUid)}" relation="p-p" type="b-t-f"/>`);
    } else {
      lines.push(`    <remarks>${esc((chat.message as string) ?? "")}</remarks>`);
    }
  } else if (aircraft) {
    const icao = aircraft.icao as string ?? "";
    if (icao) {
      let tag = `    <_aircot_ icao="${esc(icao)}"`;
      if (aircraft.registration) tag += ` reg="${esc(String(aircraft.registration))}"`;
      if (aircraft.flight) tag += ` flight="${esc(String(aircraft.flight))}"`;
      if (aircraft.category) tag += ` cat="${esc(String(aircraft.category))}"`;
      if (aircraft.cotHostId) tag += ` cot_host_id="${esc(String(aircraft.cotHostId))}"`;
      lines.push(tag + "/>");
    }
  } else if (shape) {
    emitShape(lines, shape, eventLatI, eventLonI);
  } else if (marker) {
    emitMarker(lines, marker);
  } else if (rab) {
    emitRab(lines, rab, eventLatI, eventLonI);
  } else if (route) {
    emitRoute(lines, route, eventLatI, eventLonI);
  } else if (casevac) {
    emitCasevac(lines, casevac);
  } else if (emergency) {
    emitEmergency(lines, emergency);
  } else if (task) {
    emitTask(lines, task);
  } else if (packet.rawDetail) {
    // Fallback path (`TakCompressor.compressBestOf`): raw bytes of the
    // original <detail> element are shipped verbatim and re-emitted
    // without any normalization so the receiver round trip stays
    // byte-exact with the source XML.
    const raw = packet.rawDetail as Buffer | Uint8Array | string;
    const text = typeof raw === "string"
      ? raw
      : Buffer.from(raw as Uint8Array).toString("utf-8");
    if (text.length > 0) lines.push(text);
  }

  lines.push("  </detail>");
  lines.push("</event>");
  return lines.join("\n");
}

// --- Typed geometry emitters -------------------------------------------

function emitShape(lines: string[], shape: Record<string, unknown>, eventLatI: number, eventLonI: number): void {
  const kind = (shape.kind as number) ?? 0;
  const style = (shape.style as number) ?? STYLE_UNSPECIFIED;
  const strokeArgb = resolveColor((shape.strokeColor as number) ?? 0, (shape.strokeArgb as number) ?? 0);
  const fillArgb = resolveColor((shape.fillColor as number) ?? 0, (shape.fillArgb as number) ?? 0);
  const strokeVal = argbToSigned(strokeArgb);
  const fillVal = argbToSigned(fillArgb);
  const strokeWeightX10 = (shape.strokeWeightX10 as number) ?? 0;
  const labelsOn = (shape.labelsOn as boolean) ?? false;

  const emitStroke = style === STYLE_STROKE_ONLY || style === STYLE_STROKE_AND_FILL ||
    (style === STYLE_UNSPECIFIED && strokeVal !== 0);
  const emitFill = style === STYLE_FILL_ONLY || style === STYLE_STROKE_AND_FILL ||
    (style === STYLE_UNSPECIFIED && fillVal !== 0);

  const majorCm = (shape.majorCm as number) ?? 0;
  const minorCm = (shape.minorCm as number) ?? 0;
  const angleDeg = (shape.angleDeg as number) ?? 360;

  if (
    kind === SHAPE_KIND_CIRCLE ||
    kind === SHAPE_KIND_RANGING_CIRCLE ||
    kind === SHAPE_KIND_BULLSEYE ||
    kind === SHAPE_KIND_ELLIPSE
  ) {
    if (majorCm > 0 || minorCm > 0) {
      lines.push("    <shape>");
      lines.push(`      <ellipse major="${majorCm / 100}" minor="${minorCm / 100}" angle="${angleDeg}"/>`);
      lines.push("    </shape>");
    }
  } else {
    const vertices = (shape.vertices as Array<Record<string, number>>) ?? [];
    for (const v of vertices) {
      const vlat = (eventLatI + (v.latDeltaI ?? 0)) / 1e7;
      const vlon = (eventLonI + (v.lonDeltaI ?? 0)) / 1e7;
      lines.push(`    <link point="${vlat},${vlon}"/>`);
    }
  }

  if (kind === SHAPE_KIND_BULLSEYE) {
    const bullseyeDistanceDm = (shape.bullseyeDistanceDm as number) ?? 0;
    const bullseyeBearingRef = (shape.bullseyeBearingRef as number) ?? 0;
    const bullseyeFlags = (shape.bullseyeFlags as number) ?? 0;
    const bullseyeUidRef = (shape.bullseyeUidRef as string) ?? "";
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

function emitMarker(lines: string[], marker: Record<string, unknown>): void {
  if (marker.readiness === true) {
    lines.push(`    <status readiness="true"/>`);
  }
  const parentUid = (marker.parentUid as string) ?? "";
  if (parentUid) {
    const parts = [`uid="${esc(parentUid)}"`];
    const parentType = (marker.parentType as string) ?? "";
    if (parentType) parts.push(`type="${esc(parentType)}"`);
    const parentCallsign = (marker.parentCallsign as string) ?? "";
    if (parentCallsign) parts.push(`parent_callsign="${esc(parentCallsign)}"`);
    parts.push(`relation="p-p"`);
    lines.push(`    <link ${parts.join(" ")}/>`);
  }
  const colorArgb = resolveColor((marker.color as number) ?? 0, (marker.colorArgb as number) ?? 0);
  const colorVal = argbToSigned(colorArgb);
  if (colorVal !== 0) {
    lines.push(`    <color argb="${colorVal}"/>`);
  }
  const iconset = (marker.iconset as string) ?? "";
  if (iconset) {
    lines.push(`    <usericon iconsetpath="${esc(iconset)}"/>`);
  }
}

function emitRab(lines: string[], rab: Record<string, unknown>, eventLatI: number, eventLonI: number): void {
  const anchor = (rab.anchor as Record<string, number>) ?? {};
  const anchorLatI = eventLatI + (anchor.latDeltaI ?? 0);
  const anchorLonI = eventLonI + (anchor.lonDeltaI ?? 0);
  if (anchorLatI !== 0 || anchorLonI !== 0) {
    const alat = anchorLatI / 1e7;
    const alon = anchorLonI / 1e7;
    const parts: string[] = [];
    const anchorUid = (rab.anchorUid as string) ?? "";
    if (anchorUid) parts.push(`uid="${esc(anchorUid)}"`);
    parts.push(`relation="p-p"`, `type="b-m-p-w"`, `point="${alat},${alon}"`);
    lines.push(`    <link ${parts.join(" ")}/>`);
  }
  const rangeCm = (rab.rangeCm as number) ?? 0;
  if (rangeCm > 0) lines.push(`    <range value="${rangeCm / 100}"/>`);
  const bearingCdeg = (rab.bearingCdeg as number) ?? 0;
  if (bearingCdeg > 0) lines.push(`    <bearing value="${bearingCdeg / 100}"/>`);
  const strokeArgb = resolveColor((rab.strokeColor as number) ?? 0, (rab.strokeArgb as number) ?? 0);
  const strokeVal = argbToSigned(strokeArgb);
  if (strokeVal !== 0) lines.push(`    <strokeColor value="${strokeVal}"/>`);
  const strokeWeightX10 = (rab.strokeWeightX10 as number) ?? 0;
  if (strokeWeightX10 > 0) lines.push(`    <strokeWeight value="${strokeWeightX10 / 10}"/>`);
}

function emitRoute(lines: string[], route: Record<string, unknown>, eventLatI: number, eventLonI: number): void {
  lines.push("    <__routeinfo/>");
  const parts: string[] = [];
  const method = ROUTE_METHOD_NAMES[(route.method as number) ?? 0];
  if (method) parts.push(`method="${method}"`);
  const direction = ROUTE_DIRECTION_NAMES[(route.direction as number) ?? 0];
  if (direction) parts.push(`direction="${direction}"`);
  const prefix = (route.prefix as string) ?? "";
  if (prefix) parts.push(`prefix="${esc(prefix)}"`);
  const strokeWeightX10 = (route.strokeWeightX10 as number) ?? 0;
  if (strokeWeightX10 > 0) parts.push(`stroke="${Math.floor(strokeWeightX10 / 10)}"`);
  lines.push(parts.length > 0 ? `    <link_attr ${parts.join(" ")}/>` : "    <link_attr/>");

  const links = (route.links as Array<Record<string, unknown>>) ?? [];
  for (const link of links) {
    const point = (link.point as Record<string, number>) ?? {};
    const llat = (eventLatI + (point.latDeltaI ?? 0)) / 1e7;
    const llon = (eventLonI + (point.lonDeltaI ?? 0)) / 1e7;
    const linkType = (link.linkType as number) === 1 ? "b-m-p-c" : "b-m-p-w";
    const linkParts: string[] = [];
    const uid = (link.uid as string) ?? "";
    if (uid) linkParts.push(`uid="${esc(uid)}"`);
    linkParts.push(`type="${linkType}"`);
    const callsign = (link.callsign as string) ?? "";
    if (callsign) linkParts.push(`callsign="${esc(callsign)}"`);
    linkParts.push(`point="${llat},${llon}"`);
    lines.push(`    <link ${linkParts.join(" ")}/>`);
  }
}

function emitCasevac(lines: string[], casevac: Record<string, unknown>): void {
  const parts: string[] = [];
  const precedence = (casevac.precedence as number) ?? 0;
  const precedenceName = PRECEDENCE_INT_TO_NAME[precedence];
  if (precedenceName) parts.push(`precedence="${precedenceName}"`);

  // Equipment bitfield flags
  const eq = (casevac.equipmentFlags as number) ?? 0;
  if (eq & 0x01) parts.push(`none="true"`);
  if (eq & 0x02) parts.push(`hoist="true"`);
  if (eq & 0x04) parts.push(`extraction_equipment="true"`);
  if (eq & 0x08) parts.push(`ventilator="true"`);
  if (eq & 0x10) parts.push(`blood="true"`);

  const litter = (casevac.litterPatients as number) ?? 0;
  if (litter > 0) parts.push(`litter="${litter}"`);
  const ambulatory = (casevac.ambulatoryPatients as number) ?? 0;
  if (ambulatory > 0) parts.push(`ambulatory="${ambulatory}"`);

  const security = (casevac.security as number) ?? 0;
  const securityName = SECURITY_INT_TO_NAME[security];
  if (securityName) parts.push(`security="${securityName}"`);

  const hlz = (casevac.hlzMarking as number) ?? 0;
  const hlzName = HLZ_MARKING_INT_TO_NAME[hlz];
  if (hlzName) parts.push(`hlz_marking="${hlzName}"`);

  const zoneMarker = (casevac.zoneMarker as string) ?? "";
  if (zoneMarker) parts.push(`zone_prot_marker="${esc(zoneMarker)}"`);

  const usMil = (casevac.usMilitary as number) ?? 0;
  if (usMil > 0) parts.push(`us_military="${usMil}"`);
  const usCiv = (casevac.usCivilian as number) ?? 0;
  if (usCiv > 0) parts.push(`us_civilian="${usCiv}"`);
  const nonUsMil = (casevac.nonUsMilitary as number) ?? 0;
  if (nonUsMil > 0) parts.push(`non_us_military="${nonUsMil}"`);
  const nonUsCiv = (casevac.nonUsCivilian as number) ?? 0;
  if (nonUsCiv > 0) parts.push(`non_us_civilian="${nonUsCiv}"`);
  const epw = (casevac.epw as number) ?? 0;
  if (epw > 0) parts.push(`epw="${epw}"`);
  const child = (casevac.child as number) ?? 0;
  if (child > 0) parts.push(`child="${child}"`);

  // Terrain bitfield flags
  const tf = (casevac.terrainFlags as number) ?? 0;
  if (tf & 0x01) parts.push(`terrain_slope="true"`);
  if (tf & 0x02) parts.push(`terrain_rough="true"`);
  if (tf & 0x04) parts.push(`terrain_loose="true"`);
  if (tf & 0x08) parts.push(`terrain_trees="true"`);
  if (tf & 0x10) parts.push(`terrain_wires="true"`);
  if (tf & 0x20) parts.push(`terrain_other="true"`);

  const frequency = (casevac.frequency as string) ?? "";
  if (frequency) parts.push(`freq="${esc(frequency)}"`);

  lines.push(parts.length > 0 ? `    <_medevac_ ${parts.join(" ")}/>` : "    <_medevac_/>");
}

function emitEmergency(lines: string[], emergency: Record<string, unknown>): void {
  const type = (emergency.type as number) ?? 0;
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

  const authoringUid = (emergency.authoringUid as string) ?? "";
  if (authoringUid) {
    lines.push(`    <link uid="${esc(authoringUid)}" relation="p-p" type="a-f-G-U-C"/>`);
  }
  const cancelReferenceUid = (emergency.cancelReferenceUid as string) ?? "";
  if (cancelReferenceUid) {
    lines.push(`    <link uid="${esc(cancelReferenceUid)}" relation="p-p" type="b-a-o-tbl"/>`);
  }
}

function emitTask(lines: string[], task: Record<string, unknown>): void {
  const parts: string[] = [];
  const taskType = (task.taskType as string) ?? "";
  if (taskType) parts.push(`type="${esc(taskType)}"`);

  const priority = (task.priority as number) ?? 0;
  const priorityName = TASK_PRIORITY_INT_TO_NAME[priority];
  if (priorityName) parts.push(`priority="${priorityName}"`);

  const status = (task.status as number) ?? 0;
  const statusName = TASK_STATUS_INT_TO_NAME[status];
  if (statusName) parts.push(`status="${statusName}"`);

  const assigneeUid = (task.assigneeUid as string) ?? "";
  if (assigneeUid) parts.push(`assignee="${esc(assigneeUid)}"`);

  const note = (task.note as string) ?? "";
  if (note) parts.push(`note="${esc(note)}"`);

  lines.push(parts.length > 0 ? `    <task ${parts.join(" ")}/>` : "    <task/>");

  // Target link
  const targetUid = (task.targetUid as string) ?? "";
  if (targetUid) {
    lines.push(`    <link uid="${esc(targetUid)}" relation="p-p" type="a-f-G"/>`);
  }
}
