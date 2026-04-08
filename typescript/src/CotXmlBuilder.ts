import { typeToString, howToString } from "./CotTypeMapper.js";

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

function esc(s: string): string {
  return s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
          .replace(/"/g, "&quot;").replace(/'/g, "&apos;");
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
  const chat = packet.chat as Record<string, string> | undefined;
  const aircraft = packet.aircraft as Record<string, unknown> | undefined;
  if (chat) {
    lines.push(`    <remarks>${esc(chat.message ?? "")}</remarks>`);
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
  }

  lines.push("  </detail>");
  lines.push("</event>");
  return lines.join("\n");
}
