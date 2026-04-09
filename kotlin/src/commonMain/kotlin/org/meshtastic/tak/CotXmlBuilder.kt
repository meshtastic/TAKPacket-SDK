package org.meshtastic.tak

import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.seconds

/**
 * Builds a CoT XML event string from a [TakPacketV2Data].
 * Reconstructs a standards-compliant CoT XML event that can be consumed by ATAK
 * and other CoT-compatible systems.
 */
class CotXmlBuilder {

    companion object {
        private val teamEnumToName = mapOf(
            1 to "White", 2 to "Yellow", 3 to "Orange", 4 to "Magenta",
            5 to "Red", 6 to "Maroon", 7 to "Purple", 8 to "Dark Blue",
            9 to "Blue", 10 to "Cyan", 11 to "Teal", 12 to "Green",
            13 to "Dark Green", 14 to "Brown",
        )

        private val roleEnumToName = mapOf(
            1 to "Team Member", 2 to "Team Lead", 3 to "HQ",
            4 to "Sniper", 5 to "Medic", 6 to "ForwardObserver",
            7 to "RTO", 8 to "K9",
        )

        // --- Reverse lookups for route/bullseye fields -------------------
        // Mirrors CotXmlParser's forward maps — the parser → builder round
        // trip must preserve the original attribute strings.
        private val routeMethodIntToName = mapOf(
            1 to "Driving", 2 to "Walking", 3 to "Flying",
            4 to "Swimming", 5 to "Watercraft",
        )
        private val routeDirectionIntToName = mapOf(
            1 to "Infil", 2 to "Exfil",
        )
        private val bearingRefIntToName = mapOf(
            1 to "M", 2 to "T", 3 to "G",
        )

        // --- DrawnShape Kind values (mirror atak.proto) ------------------
        private const val SHAPE_KIND_CIRCLE = 1
        private const val SHAPE_KIND_RANGING_CIRCLE = 6
        private const val SHAPE_KIND_BULLSEYE = 7

        // --- DrawnShape StyleMode values (mirror atak.proto) -------------
        private const val STYLE_UNSPECIFIED = 0
        private const val STYLE_STROKE_ONLY = 1
        private const val STYLE_FILL_ONLY = 2
        private const val STYLE_STROKE_AND_FILL = 3

        private fun geoSrcToString(src: Int): String = when (src) {
            1 -> "GPS"
            2 -> "USER"
            3 -> "NETWORK"
            else -> "???"
        }
    }

    /**
     * Resolve the ARGB int to emit in XML. When [paletteTeam] references
     * one of the 14 palette entries we emit its canonical ARGB constant;
     * otherwise we fall back to the exact [argb] bits carried on the wire
     * so custom user-picked colors round-trip byte-for-byte.
     */
    private fun resolveColor(paletteTeam: Int, argb: Int): Int =
        AtakPalette.teamToArgb(paletteTeam) ?: argb

    /**
     * Build a CoT XML event string from a [TakPacketV2Data].
     */
    fun build(packet: TakPacketV2Data): String {
        val sb = StringBuilder()
        val now = Clock.System.now()
        val timeStr = now.toString()
        val staleSeconds = packet.staleSeconds.toLong().coerceAtLeast(45)
        val stale = now + staleSeconds.seconds
        val staleStr = stale.toString()

        val cotType = packet.cotTypeString()
        val how = packet.howString().ifEmpty { "m-g" }

        val lat = packet.latitudeI / 1e7
        val lon = packet.longitudeI / 1e7
        val hae = packet.altitude

        sb.append("""<?xml version="1.0" encoding="UTF-8"?>""")
        sb.append("\n")
        sb.append("""<event version="2.0" uid="${esc(packet.uid)}" type="${esc(cotType)}" how="${esc(how)}" """)
        sb.append("""time="$timeStr" start="$timeStr" stale="$staleStr">""")
        sb.append("\n")
        sb.append("""  <point lat="$lat" lon="$lon" hae="$hae" ce="9999999" le="9999999"/>""")
        sb.append("\n")
        sb.append("  <detail>\n")

        // Contact
        if (packet.callsign.isNotEmpty()) {
            sb.append("""    <contact callsign="${esc(packet.callsign)}"""")
            if (packet.endpoint.isNotEmpty()) sb.append(""" endpoint="${esc(packet.endpoint)}"""")
            if (packet.phone.isNotEmpty()) sb.append(""" phone="${esc(packet.phone)}"""")
            sb.append("/>\n")
        }

        // Group
        val teamName = teamEnumToName[packet.team]
        val roleName = roleEnumToName[packet.role]
        if (teamName != null || roleName != null) {
            sb.append("    <__group")
            if (roleName != null) sb.append(""" role="$roleName"""")
            if (teamName != null) sb.append(""" name="$teamName"""")
            sb.append("/>\n")
        }

        // Status
        if (packet.battery > 0) {
            sb.append("""    <status battery="${packet.battery}"/>""")
            sb.append("\n")
        }

        // Track
        val speedMs = packet.speed / 100.0
        val courseDeg = packet.course / 100.0
        if (packet.speed > 0 || packet.course > 0) {
            sb.append("""    <track speed="$speedMs" course="$courseDeg"/>""")
            sb.append("\n")
        }

        // TAK version info
        if (packet.takVersion.isNotEmpty() || packet.takPlatform.isNotEmpty()) {
            sb.append("    <takv")
            if (packet.takDevice.isNotEmpty()) sb.append(""" device="${esc(packet.takDevice)}"""")
            if (packet.takPlatform.isNotEmpty()) sb.append(""" platform="${esc(packet.takPlatform)}"""")
            if (packet.takOs.isNotEmpty()) sb.append(""" os="${esc(packet.takOs)}"""")
            if (packet.takVersion.isNotEmpty()) sb.append(""" version="${esc(packet.takVersion)}"""")
            sb.append("/>\n")
        }

        // Precision location
        if (packet.geoSrc > 0 || packet.altSrc > 0) {
            sb.append("""    <precisionlocation geopointsrc="${geoSrcToString(packet.geoSrc)}" altsrc="${geoSrcToString(packet.altSrc)}"/>""")
            sb.append("\n")
        }

        // UID/Droid
        if (packet.deviceCallsign.isNotEmpty()) {
            sb.append("""    <uid Droid="${esc(packet.deviceCallsign)}"/>""")
            sb.append("\n")
        }

        // Payload-specific detail elements
        when (val payload = packet.payload) {
            is TakPacketV2Data.Payload.Chat -> {
                sb.append("""    <remarks>${esc(payload.message)}</remarks>""")
                sb.append("\n")
            }
            is TakPacketV2Data.Payload.Aircraft -> {
                if (payload.icao.isNotEmpty()) {
                    sb.append("    <_aircot_")
                    sb.append(""" icao="${esc(payload.icao)}"""")
                    if (payload.registration.isNotEmpty()) sb.append(""" reg="${esc(payload.registration)}"""")
                    if (payload.flight.isNotEmpty()) sb.append(""" flight="${esc(payload.flight)}"""")
                    if (payload.category.isNotEmpty()) sb.append(""" cat="${esc(payload.category)}"""")
                    if (payload.cotHostId.isNotEmpty()) sb.append(""" cot_host_id="${esc(payload.cotHostId)}"""")
                    sb.append("/>\n")
                }
            }
            is TakPacketV2Data.Payload.DrawnShape -> {
                val strokeVal = resolveColor(payload.strokeColor, payload.strokeArgb)
                val fillVal = resolveColor(payload.fillColor, payload.fillArgb)
                val emitStroke = payload.style == STYLE_STROKE_ONLY ||
                    payload.style == STYLE_STROKE_AND_FILL ||
                    (payload.style == STYLE_UNSPECIFIED && strokeVal != 0)
                val emitFill = payload.style == STYLE_FILL_ONLY ||
                    payload.style == STYLE_STROKE_AND_FILL ||
                    (payload.style == STYLE_UNSPECIFIED && fillVal != 0)

                // Circle-like kinds (circle, ranging circle, bullseye) use <shape><ellipse/></shape>.
                // Polyline-like kinds (rectangle, polygon, freeform, telestration) use
                // top-level <link point="lat,lon"/> siblings that the parser picks up as vertices.
                val kind = payload.kind
                if (kind == SHAPE_KIND_CIRCLE || kind == SHAPE_KIND_RANGING_CIRCLE || kind == SHAPE_KIND_BULLSEYE) {
                    if (payload.majorCm > 0 || payload.minorCm > 0) {
                        val majorM = payload.majorCm / 100.0
                        val minorM = payload.minorCm / 100.0
                        sb.append("    <shape>\n")
                        sb.append("""      <ellipse major="$majorM" minor="$minorM" angle="${payload.angleDeg}"/>""")
                        sb.append("\n")
                        sb.append("    </shape>\n")
                    }
                } else {
                    for (v in payload.vertices) {
                        val vlat = v.latI / 1e7
                        val vlon = v.lonI / 1e7
                        sb.append("""    <link point="$vlat,$vlon"/>""")
                        sb.append("\n")
                    }
                }

                // Bullseye-specific element — lives inside <detail>, not <shape>.
                if (kind == SHAPE_KIND_BULLSEYE) {
                    sb.append("    <bullseye")
                    if (payload.bullseyeDistanceDm > 0) {
                        val distM = payload.bullseyeDistanceDm / 10.0
                        sb.append(""" distance="$distM"""")
                    }
                    bearingRefIntToName[payload.bullseyeBearingRef]?.let {
                        sb.append(""" bearingRef="$it"""")
                    }
                    if (payload.bullseyeFlags and 0x01 != 0) sb.append(""" rangeRingVisible="true"""")
                    if (payload.bullseyeFlags and 0x02 != 0) sb.append(""" hasRangeRings="true"""")
                    if (payload.bullseyeFlags and 0x04 != 0) sb.append(""" edgeToCenter="true"""")
                    if (payload.bullseyeFlags and 0x08 != 0) sb.append(""" mils="true"""")
                    if (payload.bullseyeUidRef.isNotEmpty()) {
                        sb.append(""" bullseyeUID="${esc(payload.bullseyeUidRef)}"""")
                    }
                    sb.append("/>\n")
                }

                if (emitStroke) {
                    sb.append("""    <strokeColor value="$strokeVal"/>""")
                    sb.append("\n")
                    if (payload.strokeWeightX10 > 0) {
                        val w = payload.strokeWeightX10 / 10.0
                        sb.append("""    <strokeWeight value="$w"/>""")
                        sb.append("\n")
                    }
                }
                if (emitFill) {
                    sb.append("""    <fillColor value="$fillVal"/>""")
                    sb.append("\n")
                }
                sb.append("""    <labels_on value="${payload.labelsOn}"/>""")
                sb.append("\n")
            }
            is TakPacketV2Data.Payload.Marker -> {
                if (payload.readiness) {
                    sb.append("""    <status readiness="true"/>""")
                    sb.append("\n")
                }
                if (payload.parentUid.isNotEmpty()) {
                    sb.append("    <link")
                    sb.append(""" uid="${esc(payload.parentUid)}"""")
                    if (payload.parentType.isNotEmpty()) {
                        sb.append(""" type="${esc(payload.parentType)}"""")
                    }
                    if (payload.parentCallsign.isNotEmpty()) {
                        sb.append(""" parent_callsign="${esc(payload.parentCallsign)}"""")
                    }
                    sb.append(""" relation="p-p"/>""")
                    sb.append("\n")
                }
                val colorVal = resolveColor(payload.color, payload.colorArgb)
                if (colorVal != 0) {
                    sb.append("""    <color argb="$colorVal"/>""")
                    sb.append("\n")
                }
                if (payload.iconset.isNotEmpty()) {
                    sb.append("""    <usericon iconsetpath="${esc(payload.iconset)}"/>""")
                    sb.append("\n")
                }
            }
            is TakPacketV2Data.Payload.RangeAndBearing -> {
                if (payload.anchorLatI != 0 || payload.anchorLonI != 0) {
                    val alat = payload.anchorLatI / 1e7
                    val alon = payload.anchorLonI / 1e7
                    sb.append("    <link")
                    if (payload.anchorUid.isNotEmpty()) {
                        sb.append(""" uid="${esc(payload.anchorUid)}"""")
                    }
                    sb.append(""" relation="p-p" type="b-m-p-w" point="$alat,$alon"/>""")
                    sb.append("\n")
                }
                if (payload.rangeCm > 0) {
                    val rangeM = payload.rangeCm / 100.0
                    sb.append("""    <range value="$rangeM"/>""")
                    sb.append("\n")
                }
                if (payload.bearingCdeg > 0) {
                    val bearingDeg = payload.bearingCdeg / 100.0
                    sb.append("""    <bearing value="$bearingDeg"/>""")
                    sb.append("\n")
                }
                val strokeVal = resolveColor(payload.strokeColor, payload.strokeArgb)
                if (strokeVal != 0) {
                    sb.append("""    <strokeColor value="$strokeVal"/>""")
                    sb.append("\n")
                }
                if (payload.strokeWeightX10 > 0) {
                    val w = payload.strokeWeightX10 / 10.0
                    sb.append("""    <strokeWeight value="$w"/>""")
                    sb.append("\n")
                }
            }
            is TakPacketV2Data.Payload.RawDetail -> {
                // Fallback path (`TakCompressor.compressBestOf`): raw bytes
                // of the original <detail> element are shipped verbatim.
                // Emit them inside the <detail>…</detail> wrapper without
                // any normalization so the receiver's round trip preserves
                // attribute order and whitespace identically to the source.
                if (payload.bytes.isNotEmpty()) {
                    sb.append(payload.bytes.decodeToString())
                    if (!sb.endsWith("\n")) sb.append("\n")
                }
            }
            is TakPacketV2Data.Payload.Route -> {
                sb.append("    <__routeinfo/>\n")
                sb.append("    <link_attr")
                routeMethodIntToName[payload.method]?.let { sb.append(""" method="$it"""") }
                routeDirectionIntToName[payload.direction]?.let { sb.append(""" direction="$it"""") }
                if (payload.prefix.isNotEmpty()) {
                    sb.append(""" prefix="${esc(payload.prefix)}"""")
                }
                if (payload.strokeWeightX10 > 0) {
                    val sw = payload.strokeWeightX10 / 10
                    sb.append(""" stroke="$sw"""")
                }
                sb.append("/>\n")
                for (link in payload.links) {
                    val llat = link.latI / 1e7
                    val llon = link.lonI / 1e7
                    sb.append("    <link")
                    if (link.uid.isNotEmpty()) {
                        sb.append(""" uid="${esc(link.uid)}"""")
                    }
                    val linkType = if (link.linkType == 1) "b-m-p-c" else "b-m-p-w"
                    sb.append(""" type="$linkType"""")
                    if (link.callsign.isNotEmpty()) {
                        sb.append(""" callsign="${esc(link.callsign)}"""")
                    }
                    sb.append(""" point="$llat,$llon"/>""")
                    sb.append("\n")
                }
            }
            else -> { /* PLI, None, RawDetail — no extra detail elements */ }
        }

        sb.append("  </detail>\n")
        sb.append("</event>")

        return sb.toString()
    }

    private fun esc(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")
}
