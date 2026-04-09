"""Builds CoT XML event strings from TAKPacketV2 protobuf messages."""

from datetime import datetime, timezone, timedelta
from xml.sax.saxutils import escape
from . import atak_pb2
from . import atak_palette
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

_ROUTE_METHOD_INT_TO_NAME = {
    1: "Driving", 2: "Walking", 3: "Flying", 4: "Swimming", 5: "Watercraft",
}
_ROUTE_DIRECTION_INT_TO_NAME = {1: "Infil", 2: "Exfil"}
_BEARING_REF_INT_TO_NAME = {1: "M", 2: "T", 3: "G"}

# DrawnShape.Kind
_SHAPE_KIND_CIRCLE = 1
_SHAPE_KIND_RANGING_CIRCLE = 6
_SHAPE_KIND_BULLSEYE = 7

# DrawnShape.StyleMode
_STYLE_UNSPECIFIED = 0
_STYLE_STROKE_ONLY = 1
_STYLE_FILL_ONLY = 2
_STYLE_STROKE_AND_FILL = 3


def _argb_to_signed(argb: int) -> int:
    """Convert unsigned 32-bit ARGB to signed int32 for ATAK XML serialization.

    ATAK writes `<strokeColor value="-1"/>` for white (0xFFFFFFFF), so the
    builder must emit values in signed form. Python ints are arbitrary
    precision; this just reinterprets the low 32 bits as signed.
    """
    argb &= 0xFFFFFFFF
    return argb - 0x100000000 if argb >= 0x80000000 else argb


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
        elif which == "shape":
            self._emit_shape(lines, packet.shape, packet.latitude_i, packet.longitude_i)
        elif which == "marker":
            self._emit_marker(lines, packet.marker)
        elif which == "rab":
            self._emit_rab(lines, packet.rab, packet.latitude_i, packet.longitude_i)
        elif which == "route":
            self._emit_route(lines, packet.route, packet.latitude_i, packet.longitude_i)
        elif which == "raw_detail":
            # Fallback path (``TakCompressor.compress_best_of``): emit the
            # raw bytes verbatim as the inner content of <detail>.  No
            # re-escaping — bytes pass through so the receiver round trip
            # stays byte-exact with the source XML.
            if packet.raw_detail:
                text = packet.raw_detail.decode("utf-8", errors="replace")
                lines.append(text)

        lines.append('  </detail>')
        lines.append('</event>')

        return "\n".join(lines)

    # --- Typed geometry emitters ----------------------------------------

    def _emit_shape(self, lines: list, shape, event_lat_i: int, event_lon_i: int) -> None:
        stroke_argb = atak_palette.resolve_color(shape.stroke_color, shape.stroke_argb)
        fill_argb = atak_palette.resolve_color(shape.fill_color, shape.fill_argb)
        stroke_val = _argb_to_signed(stroke_argb)
        fill_val = _argb_to_signed(fill_argb)

        emit_stroke = (
            shape.style == _STYLE_STROKE_ONLY
            or shape.style == _STYLE_STROKE_AND_FILL
            or (shape.style == _STYLE_UNSPECIFIED and stroke_val != 0)
        )
        emit_fill = (
            shape.style == _STYLE_FILL_ONLY
            or shape.style == _STYLE_STROKE_AND_FILL
            or (shape.style == _STYLE_UNSPECIFIED and fill_val != 0)
        )

        kind = shape.kind
        if kind in (_SHAPE_KIND_CIRCLE, _SHAPE_KIND_RANGING_CIRCLE, _SHAPE_KIND_BULLSEYE):
            if shape.major_cm > 0 or shape.minor_cm > 0:
                major_m = shape.major_cm / 100.0
                minor_m = shape.minor_cm / 100.0
                lines.append("    <shape>")
                lines.append(f'      <ellipse major="{major_m}" minor="{minor_m}" angle="{shape.angle_deg}"/>')
                lines.append("    </shape>")
        else:
            # Rectangle, polygon, freeform, telestration: vertices as <link point>
            for v in shape.vertices:
                vlat = (event_lat_i + v.lat_delta_i) / 1e7
                vlon = (event_lon_i + v.lon_delta_i) / 1e7
                lines.append(f'    <link point="{vlat},{vlon}"/>')

        if kind == _SHAPE_KIND_BULLSEYE:
            parts = []
            if shape.bullseye_distance_dm > 0:
                dist_m = shape.bullseye_distance_dm / 10.0
                parts.append(f'distance="{dist_m}"')
            ref = _BEARING_REF_INT_TO_NAME.get(shape.bullseye_bearing_ref)
            if ref:
                parts.append(f'bearingRef="{ref}"')
            if shape.bullseye_flags & 0x01:
                parts.append('rangeRingVisible="true"')
            if shape.bullseye_flags & 0x02:
                parts.append('hasRangeRings="true"')
            if shape.bullseye_flags & 0x04:
                parts.append('edgeToCenter="true"')
            if shape.bullseye_flags & 0x08:
                parts.append('mils="true"')
            if shape.bullseye_uid_ref:
                parts.append(f'bullseyeUID="{escape(shape.bullseye_uid_ref)}"')
            if parts:
                lines.append(f'    <bullseye {" ".join(parts)}/>')
            else:
                lines.append('    <bullseye/>')

        if emit_stroke:
            lines.append(f'    <strokeColor value="{stroke_val}"/>')
            if shape.stroke_weight_x10 > 0:
                w = shape.stroke_weight_x10 / 10.0
                lines.append(f'    <strokeWeight value="{w}"/>')
        if emit_fill:
            lines.append(f'    <fillColor value="{fill_val}"/>')
        labels_str = "true" if shape.labels_on else "false"
        lines.append(f'    <labels_on value="{labels_str}"/>')

    def _emit_marker(self, lines: list, marker) -> None:
        if marker.readiness:
            lines.append('    <status readiness="true"/>')
        if marker.parent_uid:
            parts = [f'uid="{escape(marker.parent_uid)}"']
            if marker.parent_type:
                parts.append(f'type="{escape(marker.parent_type)}"')
            if marker.parent_callsign:
                parts.append(f'parent_callsign="{escape(marker.parent_callsign)}"')
            parts.append('relation="p-p"')
            lines.append(f'    <link {" ".join(parts)}/>')
        color_argb = atak_palette.resolve_color(marker.color, marker.color_argb)
        color_val = _argb_to_signed(color_argb)
        if color_val != 0:
            lines.append(f'    <color argb="{color_val}"/>')
        if marker.iconset:
            lines.append(f'    <usericon iconsetpath="{escape(marker.iconset)}"/>')

    def _emit_rab(self, lines: list, rab, event_lat_i: int, event_lon_i: int) -> None:
        anchor_lat_i = event_lat_i + rab.anchor.lat_delta_i
        anchor_lon_i = event_lon_i + rab.anchor.lon_delta_i
        if anchor_lat_i != 0 or anchor_lon_i != 0:
            alat = anchor_lat_i / 1e7
            alon = anchor_lon_i / 1e7
            parts = []
            if rab.anchor_uid:
                parts.append(f'uid="{escape(rab.anchor_uid)}"')
            parts.append('relation="p-p"')
            parts.append('type="b-m-p-w"')
            parts.append(f'point="{alat},{alon}"')
            lines.append(f'    <link {" ".join(parts)}/>')
        if rab.range_cm > 0:
            range_m = rab.range_cm / 100.0
            lines.append(f'    <range value="{range_m}"/>')
        if rab.bearing_cdeg > 0:
            bearing_deg = rab.bearing_cdeg / 100.0
            lines.append(f'    <bearing value="{bearing_deg}"/>')
        stroke_argb = atak_palette.resolve_color(rab.stroke_color, rab.stroke_argb)
        stroke_val = _argb_to_signed(stroke_argb)
        if stroke_val != 0:
            lines.append(f'    <strokeColor value="{stroke_val}"/>')
        if rab.stroke_weight_x10 > 0:
            w = rab.stroke_weight_x10 / 10.0
            lines.append(f'    <strokeWeight value="{w}"/>')

    def _emit_route(self, lines: list, route, event_lat_i: int, event_lon_i: int) -> None:
        lines.append('    <__routeinfo/>')
        parts = []
        method_name = _ROUTE_METHOD_INT_TO_NAME.get(route.method)
        if method_name:
            parts.append(f'method="{method_name}"')
        direction_name = _ROUTE_DIRECTION_INT_TO_NAME.get(route.direction)
        if direction_name:
            parts.append(f'direction="{direction_name}"')
        if route.prefix:
            parts.append(f'prefix="{escape(route.prefix)}"')
        if route.stroke_weight_x10 > 0:
            sw = route.stroke_weight_x10 // 10
            parts.append(f'stroke="{sw}"')
        if parts:
            lines.append(f'    <link_attr {" ".join(parts)}/>')
        else:
            lines.append('    <link_attr/>')
        for link in route.links:
            llat = (event_lat_i + link.point.lat_delta_i) / 1e7
            llon = (event_lon_i + link.point.lon_delta_i) / 1e7
            link_type = "b-m-p-c" if link.link_type == 1 else "b-m-p-w"
            parts = []
            if link.uid:
                parts.append(f'uid="{escape(link.uid)}"')
            parts.append(f'type="{link_type}"')
            if link.callsign:
                parts.append(f'callsign="{escape(link.callsign)}"')
            parts.append(f'point="{llat},{llon}"')
            lines.append(f'    <link {" ".join(parts)}/>')
