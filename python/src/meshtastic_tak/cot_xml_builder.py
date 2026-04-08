"""Builds CoT XML event strings from TAKPacketV2 protobuf messages."""

from datetime import datetime, timezone, timedelta
from xml.sax.saxutils import escape
from . import atak_pb2
from .cot_type_mapper import CotTypeMapper

_TEAM_ENUM_TO_NAME = {
    1: "White", 2: "Yellow", 3: "Orange", 4: "Magenta",
    5: "Red", 6: "Maroon", 7: "Purple", 8: "Dark Blue",
    9: "Blue", 10: "Cyan", 11: "Teal", 12: "Green",
    13: "Dark Green", 14: "Brown",
}

_ROLE_ENUM_TO_NAME = {
    1: "Team Member", 2: "Team Lead", 3: "HQ",
    4: "Sniper", 5: "Medic", 6: "ForwardObserver",
    7: "RTO", 8: "K9",
}

_GEO_SRC_NAME = {1: "GPS", 2: "USER", 3: "NETWORK"}


class CotXmlBuilder:
    def build(self, packet: atak_pb2.TAKPacketV2) -> str:
        now = datetime.now(timezone.utc)
        stale_secs = max(packet.stale_seconds, 45)
        stale = now + timedelta(seconds=stale_secs)
        time_str = now.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"
        stale_str = stale.strftime("%Y-%m-%dT%H:%M:%S.%f")[:-3] + "Z"

        cot_type = CotTypeMapper.type_to_string(packet.cot_type_id) or packet.cot_type_str or ""
        how = CotTypeMapper.how_to_string(packet.how) or "m-g"
        lat = packet.latitude_i / 1e7
        lon = packet.longitude_i / 1e7

        lines = [
            '<?xml version="1.0" encoding="UTF-8"?>',
            f'<event version="2.0" uid="{escape(packet.uid)}" type="{escape(cot_type)}" '
            f'how="{escape(how)}" time="{time_str}" start="{time_str}" stale="{stale_str}">',
            f'  <point lat="{lat}" lon="{lon}" hae="{packet.altitude}" ce="9999999" le="9999999"/>',
            '  <detail>',
        ]

        if packet.callsign:
            parts = [f'callsign="{escape(packet.callsign)}"']
            if packet.endpoint: parts.append(f'endpoint="{escape(packet.endpoint)}"')
            if packet.phone: parts.append(f'phone="{escape(packet.phone)}"')
            lines.append(f'    <contact {" ".join(parts)}/>')

        team_name = _TEAM_ENUM_TO_NAME.get(packet.team)
        role_name = _ROLE_ENUM_TO_NAME.get(packet.role)
        if team_name or role_name:
            parts = []
            if role_name: parts.append(f'role="{role_name}"')
            if team_name: parts.append(f'name="{team_name}"')
            lines.append(f'    <__group {" ".join(parts)}/>')

        if packet.battery > 0:
            lines.append(f'    <status battery="{packet.battery}"/>')

        if packet.speed > 0 or packet.course > 0:
            lines.append(f'    <track speed="{packet.speed / 100.0}" course="{packet.course / 100.0}"/>')

        if packet.tak_version or packet.tak_platform:
            parts = []
            if packet.tak_device: parts.append(f'device="{escape(packet.tak_device)}"')
            if packet.tak_platform: parts.append(f'platform="{escape(packet.tak_platform)}"')
            if packet.tak_os: parts.append(f'os="{escape(packet.tak_os)}"')
            if packet.tak_version: parts.append(f'version="{escape(packet.tak_version)}"')
            lines.append(f'    <takv {" ".join(parts)}/>')

        if packet.geo_src or packet.alt_src:
            gs = _GEO_SRC_NAME.get(packet.geo_src, "???")
            als = _GEO_SRC_NAME.get(packet.alt_src, "???")
            lines.append(f'    <precisionlocation geopointsrc="{gs}" altsrc="{als}"/>')

        if packet.device_callsign:
            lines.append(f'    <uid Droid="{escape(packet.device_callsign)}"/>')

        # Payload-specific
        which = packet.WhichOneof("payload_variant")
        if which == "chat":
            lines.append(f'    <remarks>{escape(packet.chat.message)}</remarks>')
        elif which == "aircraft":
            ac = packet.aircraft
            if ac.icao:
                parts = [f'icao="{escape(ac.icao)}"']
                if ac.registration: parts.append(f'reg="{escape(ac.registration)}"')
                if ac.flight: parts.append(f'flight="{escape(ac.flight)}"')
                if ac.category: parts.append(f'cat="{escape(ac.category)}"')
                if ac.cot_host_id: parts.append(f'cot_host_id="{escape(ac.cot_host_id)}"')
                lines.append(f'    <_aircot_ {" ".join(parts)}/>')

        lines.append('  </detail>')
        lines.append('</event>')

        return "\n".join(lines)
