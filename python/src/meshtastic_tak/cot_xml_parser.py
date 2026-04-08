"""Parses CoT XML event strings into TAKPacketV2 protobuf messages."""

import re
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from . import atak_pb2
from .cot_type_mapper import CotTypeMapper

_TEAM_NAME_TO_ENUM = {
    "White": 1, "Yellow": 2, "Orange": 3, "Magenta": 4,
    "Red": 5, "Maroon": 6, "Purple": 7, "Dark Blue": 8,
    "Blue": 9, "Cyan": 10, "Teal": 11, "Green": 12,
    "Dark Green": 13, "Brown": 14,
}

_ROLE_NAME_TO_ENUM = {
    "Team Member": 1, "Team Lead": 2, "HQ": 3,
    "Sniper": 4, "Medic": 5, "ForwardObserver": 6,
    "RTO": 7, "K9": 8,
}

_GEO_SRC_MAP = {"GPS": 1, "USER": 2, "NETWORK": 3}


class CotXmlParser:
    def parse(self, cot_xml: str) -> atak_pb2.TAKPacketV2:
        # Reject XML with DOCTYPE or ENTITY declarations to prevent XXE and entity expansion
        lower = cot_xml.lower()
        if "<!doctype" in lower or "<!entity" in lower:
            raise ValueError("XML contains prohibited DOCTYPE or ENTITY declaration")

        root = ET.fromstring(cot_xml)
        pkt = atak_pb2.TAKPacketV2()

        # Event envelope
        pkt.uid = root.get("uid", "")
        type_str = root.get("type", "")
        pkt.cot_type_id = CotTypeMapper.type_to_enum(type_str)
        if pkt.cot_type_id == 0:
            pkt.cot_type_str = type_str
        pkt.how = CotTypeMapper.how_to_enum(root.get("how", ""))

        time_str = root.get("time", "")
        stale_str = root.get("stale", "")
        pkt.stale_seconds = self._compute_stale_seconds(time_str, stale_str)

        # Point
        point = root.find("point")
        if point is not None:
            pkt.latitude_i = int(float(point.get("lat", "0")) * 1e7)
            pkt.longitude_i = int(float(point.get("lon", "0")) * 1e7)
            pkt.altitude = int(float(point.get("hae", "0")))

        # Detail elements
        detail = root.find("detail")
        if detail is None:
            pkt.pli = True
            return pkt

        has_aircraft = False
        has_chat = False
        icao = reg = flight = ac_type = category = cot_host_id = ""
        squawk = 0
        rssi_x10 = 0
        gps_flag = False
        remarks_text = ""
        chat_to = chat_to_cs = None

        for elem in detail:
            tag = elem.tag
            if tag == "contact":
                pkt.callsign = elem.get("callsign", "")
                ep = elem.get("endpoint")
                if ep: pkt.endpoint = ep
                ph = elem.get("phone")
                if ph: pkt.phone = ph
            elif tag == "__group":
                name = elem.get("name", "")
                role = elem.get("role", "")
                pkt.team = _TEAM_NAME_TO_ENUM.get(name, 0)
                pkt.role = _ROLE_NAME_TO_ENUM.get(role, 0)
            elif tag == "status":
                pkt.battery = int(elem.get("battery", "0"))
            elif tag == "track":
                pkt.speed = int(float(elem.get("speed", "0")) * 100)
                pkt.course = int(float(elem.get("course", "0")) * 100)
            elif tag == "takv":
                pkt.tak_version = elem.get("version", "")
                pkt.tak_device = elem.get("device", "")
                pkt.tak_platform = elem.get("platform", "")
                pkt.tak_os = elem.get("os", "")
            elif tag == "precisionlocation":
                pkt.geo_src = _GEO_SRC_MAP.get(elem.get("geopointsrc", ""), 0)
                pkt.alt_src = _GEO_SRC_MAP.get(elem.get("altsrc", ""), 0)
            elif tag in ("uid", "UID"):
                droid = elem.get("Droid")
                if droid: pkt.device_callsign = droid
            elif tag == "_radio":
                rssi_str = elem.get("rssi")
                if rssi_str:
                    rssi_x10 = int(float(rssi_str) * 10)
                    has_aircraft = True
                gps_flag = elem.get("gps") == "true"
            elif tag == "_aircot_":
                has_aircraft = True
                icao = elem.get("icao", "")
                reg = elem.get("reg", "")
                flight = elem.get("flight", "")
                category = elem.get("cat", "")
                cot_host_id = elem.get("cot_host_id", "")
            elif tag == "__chat":
                has_chat = True
                chat_to_cs = elem.get("senderCallsign")
                chat_to = elem.get("id")
            elif tag == "remarks":
                remarks_text = (elem.text or "").strip()

        # Parse aircraft from remarks if ICAO not yet found
        if not icao and remarks_text:
            m = re.search(r"ICAO:\s*([A-Fa-f0-9]{6})", remarks_text)
            if m:
                has_aircraft = True
                icao = m.group(1)
                rm = re.search(r"REG:\s*(\S+)", remarks_text)
                if rm: reg = rm.group(1)
                fm = re.search(r"Flight:\s*(\S+)", remarks_text)
                if fm: flight = fm.group(1)
                tm = re.search(r"Type:\s*(\S+)", remarks_text)
                if tm: ac_type = tm.group(1)
                sm = re.search(r"Squawk:\s*(\d+)", remarks_text)
                if sm: squawk = int(sm.group(1))
                cm = re.search(r"Category:\s*(\S+)", remarks_text)
                if cm and not category: category = cm.group(1)

        # Set payload
        if has_chat:
            chat = pkt.chat
            chat.message = remarks_text
            if chat_to: chat.to = chat_to
            if chat_to_cs: chat.to_callsign = chat_to_cs
        elif has_aircraft:
            ac = pkt.aircraft
            ac.icao = icao
            ac.registration = reg
            ac.flight = flight
            ac.aircraft_type = ac_type
            ac.squawk = squawk
            ac.category = category
            ac.rssi_x10 = rssi_x10
            ac.gps = gps_flag
            ac.cot_host_id = cot_host_id
        else:
            pkt.pli = True

        return pkt

    @staticmethod
    def _compute_stale_seconds(time_str: str, stale_str: str) -> int:
        if not time_str or not stale_str:
            return 0
        try:
            t = datetime.fromisoformat(time_str.replace("Z", "+00:00"))
            s = datetime.fromisoformat(stale_str.replace("Z", "+00:00"))
            diff = int((s - t).total_seconds())
            return diff if diff > 0 else 0
        except (ValueError, TypeError):
            return 0
