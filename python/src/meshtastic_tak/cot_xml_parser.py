"""Parses CoT XML event strings into TAKPacketV2 protobuf messages."""

import re
import xml.etree.ElementTree as ET
from datetime import datetime, timezone
from . import atak_pb2
from . import atak_palette
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

_ROUTE_METHOD_MAP = {
    "Driving": 1, "Walking": 2, "Flying": 3, "Swimming": 4, "Watercraft": 5,
}
_ROUTE_DIRECTION_MAP = {"Infil": 1, "Exfil": 2}
_BEARING_REF_MAP = {"M": 1, "T": 2, "G": 3}

#: Vertex pool cap — matches *DrawnShape.vertices max_count:32 in atak.options.
MAX_VERTICES = 32
#: Route link pool cap — matches *Route.links max_count:16 in atak.options.
MAX_ROUTE_LINKS = 16

# DrawnShape.Kind constants (mirror atak.proto)
_SHAPE_KIND_UNSPECIFIED = 0
_SHAPE_KIND_CIRCLE = 1
_SHAPE_KIND_RECTANGLE = 2
_SHAPE_KIND_FREEFORM = 3
_SHAPE_KIND_TELESTRATION = 4
_SHAPE_KIND_POLYGON = 5
_SHAPE_KIND_RANGING_CIRCLE = 6
_SHAPE_KIND_BULLSEYE = 7

_SHAPE_KIND_BY_COT_TYPE = {
    "u-d-c-c": _SHAPE_KIND_CIRCLE,
    "u-d-r": _SHAPE_KIND_RECTANGLE,
    "u-d-f": _SHAPE_KIND_FREEFORM,
    "u-d-f-m": _SHAPE_KIND_TELESTRATION,
    "u-d-p": _SHAPE_KIND_POLYGON,
    "u-r-b-c-c": _SHAPE_KIND_RANGING_CIRCLE,
    "u-r-b-bullseye": _SHAPE_KIND_BULLSEYE,
}

# DrawnShape.StyleMode constants
_STYLE_UNSPECIFIED = 0
_STYLE_STROKE_ONLY = 1
_STYLE_FILL_ONLY = 2
_STYLE_STROKE_AND_FILL = 3

# Marker.Kind constants
_MARKER_KIND_UNSPECIFIED = 0
_MARKER_KIND_SPOT = 1
_MARKER_KIND_WAYPOINT = 2
_MARKER_KIND_CHECKPOINT = 3
_MARKER_KIND_SELF_POSITION = 4
_MARKER_KIND_SYMBOL_2525 = 5
_MARKER_KIND_SPOT_MAP = 6
_MARKER_KIND_CUSTOM_ICON = 7


def _marker_kind_from_cot_type(cot_type: str, iconset: str) -> int:
    """Derive a Marker.Kind from CoT type + iconset path."""
    if cot_type == "b-m-p-s-m":
        return _MARKER_KIND_SPOT
    if cot_type == "b-m-p-w":
        return _MARKER_KIND_WAYPOINT
    if cot_type == "b-m-p-c":
        return _MARKER_KIND_CHECKPOINT
    if cot_type in ("b-m-p-s-p-i", "b-m-p-s-p-loc"):
        return _MARKER_KIND_SELF_POSITION
    if iconset.startswith("COT_MAPPING_2525B"):
        return _MARKER_KIND_SYMBOL_2525
    if iconset.startswith("COT_MAPPING_SPOTMAP"):
        return _MARKER_KIND_SPOT_MAP
    if iconset:
        return _MARKER_KIND_CUSTOM_ICON
    return _MARKER_KIND_UNSPECIFIED


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
        cot_type_str = root.get("type", "")
        pkt.cot_type_id = CotTypeMapper.type_to_enum(cot_type_str)
        if pkt.cot_type_id == 0:
            pkt.cot_type_str = cot_type_str
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

        # --- Drawn shape accumulators ---
        has_shape_data = False
        shape_major_cm = 0
        shape_minor_cm = 0
        shape_angle_deg = 360
        saw_stroke_color = False
        saw_fill_color = False
        stroke_color_argb = 0
        stroke_weight_x10 = 0
        fill_color_argb = 0
        labels_on = False
        vertices_abs: list[tuple[int, int]] = []  # absolute lat/lon
        vertices_truncated = False

        # --- Bullseye accumulators ---
        bullseye_distance_dm = 0
        bullseye_bearing_ref = 0
        bullseye_flags = 0
        bullseye_uid_ref = ""

        # --- Marker accumulators ---
        has_marker_data = False
        marker_color_argb = 0
        marker_readiness = False
        marker_parent_uid = ""
        marker_parent_type = ""
        marker_parent_callsign = ""
        marker_iconset = ""

        # --- Range and bearing accumulators ---
        has_rab_data = False
        rab_anchor_lat_i = 0
        rab_anchor_lon_i = 0
        rab_anchor_uid = ""
        rab_range_cm = 0
        rab_bearing_cdeg = 0

        # --- Route accumulators ---
        has_route_data = False
        route_links_abs: list[dict] = []
        route_truncated = False
        route_prefix = ""
        route_method = 0
        route_direction = 0

        def handle_link(elem):
            nonlocal has_rab_data, rab_anchor_lat_i, rab_anchor_lon_i, rab_anchor_uid
            nonlocal has_route_data, route_truncated
            nonlocal has_shape_data, vertices_truncated
            nonlocal has_marker_data, marker_parent_uid, marker_parent_type, marker_parent_callsign
            link_uid = elem.get("uid")
            point_attr = elem.get("point")
            link_type = elem.get("type", "")
            relation = elem.get("relation", "")
            link_callsign = elem.get("callsign", "")
            parent_callsign = elem.get("parent_callsign", "")

            # Ignore style links nested under <shape>
            is_style_link = link_type.startswith("b-x-KmlStyle") or (
                link_uid is not None and link_uid.endswith(".Style")
            )
            if is_style_link:
                return

            if point_attr is not None:
                parts = point_attr.split(",")
                if len(parts) < 2:
                    return
                try:
                    plat = float(parts[0])
                    plon = float(parts[1])
                except ValueError:
                    return
                plati = int(plat * 1e7)
                ploni = int(plon * 1e7)

                # u-rb-a is the ONLY type whose <link> is the range/bearing
                # anchor. Checked first so a ranging-line link never
                # escalates to a one-waypoint "route".
                if cot_type_str == "u-rb-a":
                    if rab_anchor_lat_i == 0 and rab_anchor_lon_i == 0:
                        rab_anchor_lat_i = plati
                        rab_anchor_lon_i = ploni
                        if link_uid is not None:
                            rab_anchor_uid = link_uid
                    has_rab_data = True
                elif link_type in ("b-m-p-w", "b-m-p-c") and cot_type_str == "b-m-r":
                    if len(route_links_abs) < MAX_ROUTE_LINKS:
                        route_links_abs.append({
                            "lat_i": plati,
                            "lon_i": ploni,
                            "uid": link_uid or "",
                            "callsign": link_callsign,
                            "link_type": 1 if link_type == "b-m-p-c" else 0,
                        })
                    else:
                        route_truncated = True
                    has_route_data = True
                else:
                    if len(vertices_abs) < MAX_VERTICES:
                        vertices_abs.append((plati, ploni))
                        has_shape_data = True
                    else:
                        vertices_truncated = True
            elif link_uid is not None and relation == "p-p" and link_type:
                # Marker parent link
                marker_parent_uid = link_uid
                marker_parent_type = link_type
                if parent_callsign:
                    marker_parent_callsign = parent_callsign
                has_marker_data = True

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
                battery = elem.get("battery")
                if battery is not None:
                    try:
                        pkt.battery = int(battery)
                    except ValueError:
                        pass
                if elem.get("readiness") == "true":
                    marker_readiness = True
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
            elif tag == "link":
                handle_link(elem)
            elif tag == "shape":
                has_shape_data = True
                # <shape> may contain a nested <ellipse> or <link>s with KmlStyle.
                ellipse = elem.find("ellipse")
                if ellipse is not None:
                    try:
                        major_m = float(ellipse.get("major", "0"))
                        minor_m = float(ellipse.get("minor", "0"))
                        shape_major_cm = max(0, int(major_m * 100))
                        shape_minor_cm = max(0, int(minor_m * 100))
                        shape_angle_deg = int(ellipse.get("angle", "360"))
                    except ValueError:
                        pass
            elif tag == "strokeColor":
                saw_stroke_color = True
                try:
                    stroke_color_argb = int(elem.get("value", "0")) & 0xFFFFFFFF
                except ValueError:
                    pass
                has_shape_data = True
            elif tag == "strokeWeight":
                try:
                    w = float(elem.get("value", "0"))
                    stroke_weight_x10 = max(0, int(w * 10))
                except ValueError:
                    pass
                has_shape_data = True
            elif tag == "fillColor":
                saw_fill_color = True
                try:
                    fill_color_argb = int(elem.get("value", "0")) & 0xFFFFFFFF
                except ValueError:
                    pass
                has_shape_data = True
            elif tag == "labels_on":
                labels_on = elem.get("value") == "true"
            elif tag == "color":
                try:
                    marker_color_argb = int(elem.get("argb", "0")) & 0xFFFFFFFF
                except ValueError:
                    pass
                has_marker_data = True
            elif tag == "usericon":
                marker_iconset = elem.get("iconsetpath", "")
                if marker_iconset:
                    has_marker_data = True
            elif tag == "bullseye":
                has_shape_data = True
                try:
                    dist = float(elem.get("distance", "0"))
                    bullseye_distance_dm = max(0, int(dist * 10))
                except ValueError:
                    pass
                bullseye_bearing_ref = _BEARING_REF_MAP.get(elem.get("bearingRef", ""), 0)
                flags = 0
                if elem.get("rangeRingVisible") == "true":
                    flags |= 0x01
                if elem.get("hasRangeRings") == "true":
                    flags |= 0x02
                if elem.get("edgeToCenter") == "true":
                    flags |= 0x04
                if elem.get("mils") == "true":
                    flags |= 0x08
                bullseye_flags = flags
                bullseye_uid_ref = elem.get("bullseyeUID", "")
            elif tag == "range":
                try:
                    v = float(elem.get("value", "0"))
                    rab_range_cm = max(0, int(v * 100))
                except ValueError:
                    pass
                has_rab_data = True
            elif tag == "bearing":
                try:
                    v = float(elem.get("value", "0"))
                    rab_bearing_cdeg = max(0, int(v * 100))
                except ValueError:
                    pass
                has_rab_data = True
            elif tag == "__routeinfo":
                has_route_data = True
            elif tag == "link_attr":
                has_route_data = True
                route_prefix = elem.get("prefix", "")
                route_method = _ROUTE_METHOD_MAP.get(elem.get("method", ""), 0)
                route_direction = _ROUTE_DIRECTION_MAP.get(elem.get("direction", ""), 0)
                stroke_attr = elem.get("stroke")
                if stroke_attr:
                    try:
                        sw = int(stroke_attr)
                        if sw > 0:
                            stroke_weight_x10 = sw * 10
                    except ValueError:
                        pass

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

        # Derive DrawnShape.style from the presence flags we observed.
        if saw_stroke_color and saw_fill_color:
            shape_style = _STYLE_STROKE_AND_FILL
        elif saw_stroke_color:
            shape_style = _STYLE_STROKE_ONLY
        elif saw_fill_color:
            shape_style = _STYLE_FILL_ONLY
        else:
            shape_style = _STYLE_UNSPECIFIED

        # Payload priority: chat > aircraft > route > rab > shape > marker > pli.
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
        elif has_route_data and route_links_abs:
            route = pkt.route
            route.method = route_method
            route.direction = route_direction
            route.prefix = route_prefix
            route.stroke_weight_x10 = stroke_weight_x10
            for link in route_links_abs:
                link_msg = route.links.add()
                link_msg.point.lat_delta_i = link["lat_i"] - pkt.latitude_i
                link_msg.point.lon_delta_i = link["lon_i"] - pkt.longitude_i
                link_msg.uid = link["uid"]
                link_msg.callsign = link["callsign"]
                link_msg.link_type = link["link_type"]
            route.truncated = route_truncated
        elif has_rab_data:
            rab = pkt.rab
            rab.anchor.lat_delta_i = rab_anchor_lat_i - pkt.latitude_i
            rab.anchor.lon_delta_i = rab_anchor_lon_i - pkt.longitude_i
            rab.anchor_uid = rab_anchor_uid
            rab.range_cm = rab_range_cm
            rab.bearing_cdeg = rab_bearing_cdeg
            rab.stroke_color = atak_palette.argb_to_team(stroke_color_argb)
            rab.stroke_argb = stroke_color_argb
            rab.stroke_weight_x10 = stroke_weight_x10
        elif has_shape_data:
            shape = pkt.shape
            shape.kind = _SHAPE_KIND_BY_COT_TYPE.get(cot_type_str, _SHAPE_KIND_UNSPECIFIED)
            shape.style = shape_style
            shape.major_cm = shape_major_cm
            shape.minor_cm = shape_minor_cm
            shape.angle_deg = shape_angle_deg
            shape.stroke_color = atak_palette.argb_to_team(stroke_color_argb)
            shape.stroke_argb = stroke_color_argb
            shape.stroke_weight_x10 = stroke_weight_x10
            shape.fill_color = atak_palette.argb_to_team(fill_color_argb)
            shape.fill_argb = fill_color_argb
            shape.labels_on = labels_on
            for (abs_lat, abs_lon) in vertices_abs:
                v = shape.vertices.add()
                v.lat_delta_i = abs_lat - pkt.latitude_i
                v.lon_delta_i = abs_lon - pkt.longitude_i
            shape.truncated = vertices_truncated
            shape.bullseye_distance_dm = bullseye_distance_dm
            shape.bullseye_bearing_ref = bullseye_bearing_ref
            shape.bullseye_flags = bullseye_flags
            shape.bullseye_uid_ref = bullseye_uid_ref
        elif has_marker_data:
            marker = pkt.marker
            marker.kind = _marker_kind_from_cot_type(cot_type_str, marker_iconset)
            marker.color = atak_palette.argb_to_team(marker_color_argb)
            marker.color_argb = marker_color_argb
            marker.readiness = marker_readiness
            marker.parent_uid = marker_parent_uid
            marker.parent_type = marker_parent_type
            marker.parent_callsign = marker_parent_callsign
            marker.iconset = marker_iconset
        else:
            pkt.pli = True

        return pkt

    @staticmethod
    def extract_raw_detail_bytes(cot_xml: str) -> bytes:
        """Extract the inner bytes of ``<detail>`` from a CoT XML event.

        Returns the bytes between ``<detail>`` and ``</detail>`` exactly as
        they appear in the source — no XML normalization, no re-escaping.
        Used by :meth:`TakCompressor.compress_best_of` to build a
        ``raw_detail`` fallback packet alongside the typed packet.

        Returns an empty ``bytes`` object for events with a self-closing
        ``<detail/>`` or no ``<detail>`` element at all.  Receivers
        rehydrate the full event by wrapping these bytes in
        ``<detail>…</detail>``, so a byte-for-byte extraction is required
        to keep the round trip loss-free.
        """
        match = re.search(
            r"<detail\b[^>]*>(.*?)</detail\s*>",
            cot_xml,
            re.DOTALL | re.IGNORECASE,
        )
        if not match:
            return b""
        return match.group(1).encode("utf-8")

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
