package org.meshtastic.tak

import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.time.Duration.Companion.seconds

/**
 * Builds a CoT XML event string from a [TakPacketV2Data].
 * Reconstructs a standards-compliant CoT XML event that can be consumed by ATAK
 * and other CoT-compatible systems.
 *
 * This class holds no mutable state and is safe for concurrent use from multiple threads.
 */
public class CotXmlBuilder {

    private companion object {
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
        private const val SHAPE_KIND_ELLIPSE = 8

        // --- CasevacReport reverse lookups (mirror CotXmlParser maps) ----
        private val precedenceIntToName = mapOf(
            1 to "Urgent", 2 to "Urgent Surgical", 3 to "Priority",
            4 to "Routine", 5 to "Convenience",
        )
        private val hlzMarkingIntToName = mapOf(
            1 to "Panels", 2 to "Pyro", 3 to "Smoke",
            4 to "None", 5 to "Other",
        )
        private val securityIntToName = mapOf(
            1 to "N", 2 to "P", 3 to "E", 4 to "X",
        )

        // --- EmergencyAlert reverse lookups ------------------------------
        private val emergencyTypeIntToName = mapOf(
            1 to "911 Alert", 2 to "Ring The Bell", 3 to "In Contact",
            4 to "Geo-fence Breached", 5 to "Custom", 6 to "Cancel",
        )

        // --- TaskRequest reverse lookups ---------------------------------
        private val taskPriorityIntToName = mapOf(
            1 to "Low", 2 to "Normal", 3 to "High", 4 to "Critical",
        )
        private val taskStatusIntToName = mapOf(
            1 to "Pending", 2 to "Acknowledged", 3 to "In Progress",
            4 to "Completed", 5 to "Cancelled",
        )

        // --- GeoChat ReceiptType ----------------------------------------
        private const val RECEIPT_TYPE_NONE = 0
        private const val RECEIPT_TYPE_DELIVERED = 1
        private const val RECEIPT_TYPE_READ = 2

        /** Default endpoint emitted when the proto field is empty (normalized at parse time). */
        private const val DEFAULT_ENDPOINT = "0.0.0.0:4242:tcp"

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

        /** Convert an ARGB int to ABGR hex string (KML color format). */
        private fun argbToAbgrHex(argb: Int): String {
            val a = (argb ushr 24) and 0xFF
            val r = (argb ushr 16) and 0xFF
            val g = (argb ushr 8) and 0xFF
            val b = argb and 0xFF
            return hex2(a) + hex2(b) + hex2(g) + hex2(r)
        }

        /** Format a byte value as a zero-padded two-character lowercase hex string. */
        private fun hex2(v: Int): String {
            val s = v.toString(16)
            return if (s.length < 2) "0$s" else s
        }

        /** Escape XML special characters in attribute values and text content. */
        private fun esc(s: String): String = s
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
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
     *
     * @param now The timestamp used for `time`, `start`, and `stale` attributes.
     *            Defaults to the current wall-clock time. Pass an explicit
     *            [Instant] for deterministic/reproducible output in tests.
     */
    @kotlin.jvm.JvmOverloads
    @Throws(IllegalStateException::class)
    public fun build(packet: TakPacketV2Data, now: Instant = Clock.System.now()): String {
        trace { "CotXmlBuilder.build: uid=${packet.uid}, callsign=${packet.callsign}" }
        val xml = buildString {
        val timeStr = now.toString()
        val staleSeconds = packet.staleSeconds.toLong().coerceAtLeast(45)
        val stale = now + staleSeconds.seconds
        val staleStr = stale.toString()

        val cotType = packet.cotTypeString()
        val how = packet.howString().ifEmpty { "m-g" }

        var lat = packet.latitudeI / 1e7
        var lon = packet.longitudeI / 1e7
        val hae = packet.altitude

        // Routes from ATAK use 0,0 as the event anchor. Use the first
        // waypoint's coordinates so the receiving TAK client can locate
        // the route on the map.
        val routePayload = packet.payload as? TakPacketV2Data.Payload.Route
        if (routePayload != null && packet.latitudeI == 0 && packet.longitudeI == 0 && routePayload.links.isNotEmpty()) {
            lat = routePayload.links.first().latI / 1e7
            lon = routePayload.links.first().lonI / 1e7
        }

        append("""<?xml version="1.0" encoding="UTF-8"?>""")
        append("\n")
        append("""<event version="2.0" uid="${esc(packet.uid)}" type="${esc(cotType)}" how="${esc(how)}" """)
        append("""time="$timeStr" start="$timeStr" stale="$staleStr">""")
        append("\n")
        append("""  <point lat="$lat" lon="$lon" hae="$hae" ce="9999999" le="9999999"/>""")
        append("\n")
        append("  <detail>\n")

        // Contact — skip for routes (ATAK expects <contact> after __routeinfo, no endpoint)
        val isRoute = packet.payload is TakPacketV2Data.Payload.Route
        if (packet.callsign.isNotEmpty() && !isRoute) {
            val ep = packet.endpoint.ifEmpty { DEFAULT_ENDPOINT }
            append("""    <contact callsign="${esc(packet.callsign)}" endpoint="$ep"""")
            if (packet.phone.isNotEmpty()) append(""" phone="${esc(packet.phone)}"""")
            append("/>\n")
        }

        // Group
        val teamName = teamEnumToName[packet.team]
        val roleName = roleEnumToName[packet.role]
        if (teamName != null || roleName != null) {
            append("    <__group")
            if (roleName != null) append(""" role="$roleName"""")
            if (teamName != null) append(""" name="$teamName"""")
            append("/>\n")
        }

        // Status
        if (packet.battery > 0) {
            append("""    <status battery="${packet.battery}"/>""")
            append("\n")
        }

        // Track
        val speedMs = packet.speed / 100.0
        val courseDeg = packet.course / 100.0
        if (packet.speed > 0 || packet.course > 0) {
            append("""    <track speed="$speedMs" course="$courseDeg"/>""")
            append("\n")
        }

        // TAK version info
        if (packet.takVersion.isNotEmpty() || packet.takPlatform.isNotEmpty()) {
            append("    <takv")
            if (packet.takDevice.isNotEmpty()) append(""" device="${esc(packet.takDevice)}"""")
            if (packet.takPlatform.isNotEmpty()) append(""" platform="${esc(packet.takPlatform)}"""")
            if (packet.takOs.isNotEmpty()) append(""" os="${esc(packet.takOs)}"""")
            if (packet.takVersion.isNotEmpty()) append(""" version="${esc(packet.takVersion)}"""")
            append("/>\n")
        }

        // Precision location
        if (packet.geoSrc > 0 || packet.altSrc > 0) {
            append("""    <precisionlocation geopointsrc="${geoSrcToString(packet.geoSrc)}" altsrc="${geoSrcToString(packet.altSrc)}"/>""")
            append("\n")
        }

        // UID/Droid
        if (packet.deviceCallsign.isNotEmpty()) {
            append("""    <uid Droid="${esc(packet.deviceCallsign)}"/>""")
            append("\n")
        }

        // Payload-specific detail elements
        when (val payload = packet.payload) {
            is TakPacketV2Data.Payload.Chat -> {
                if (payload.receiptType != RECEIPT_TYPE_NONE && payload.receiptForUid.isNotEmpty()) {
                    // Delivered / read receipt: emit a <link> pointing at the
                    // original message UID. The envelope cot_type_id already
                    // distinguishes delivered (b-t-f-d) vs read (b-t-f-r).
                    append("""    <link uid="${esc(payload.receiptForUid)}" relation="p-p" type="b-t-f"/>""")
                    append("\n")
                } else {
                    // Reconstruct the full __chat element that ATAK/iTAK
                    // needs for routing and display. The GeoChat event UID
                    // encodes: GeoChat.{senderUid}.{chatroom}.{messageId}
                    val gcParts = packet.uid.split(".", limit = 4)
                    if (gcParts.size == 4 && gcParts[0] == "GeoChat") {
                        val senderUid = gcParts[1]
                        val chatroom = gcParts[2]
                        val msgId = gcParts[3]
                        val senderCs = payload.toCallsign?.ifEmpty { null }
                            ?: packet.callsign.ifEmpty { "UNKNOWN" }
                        append("    <__chat parent=\"RootContactGroup\" groupOwner=\"false\"")
                        append(""" messageId="${esc(msgId)}" chatroom="${esc(chatroom)}"""")
                        append(""" id="${esc(chatroom)}" senderCallsign="${esc(senderCs)}">""")
                        append("\n")
                        append("""      <chatgrp uid0="${esc(senderUid)}" uid1="${esc(chatroom)}" id="${esc(chatroom)}"/>""")
                        append("\n")
                        append("    </__chat>\n")
                        append("""    <link uid="${esc(senderUid)}" type="a-f-G-U-C" relation="p-p"/>""")
                        append("\n")
                        append("""    <__serverdestination destinations="0.0.0.0:4242:tcp:${esc(senderUid)}"/>""")
                        append("\n")
                        append("""    <remarks source="BAO.F.ATAK.${esc(senderUid)}" to="${esc(chatroom)}" time="$timeStr">${esc(payload.message)}</remarks>""")
                        append("\n")
                    } else {
                        append("""    <remarks>${esc(payload.message)}</remarks>""")
                        append("\n")
                    }
                }
            }
            is TakPacketV2Data.Payload.Aircraft -> {
                if (payload.icao.isNotEmpty()) {
                    append("    <_aircot_")
                    append(""" icao="${esc(payload.icao)}"""")
                    if (payload.registration.isNotEmpty()) append(""" reg="${esc(payload.registration)}"""")
                    if (payload.flight.isNotEmpty()) append(""" flight="${esc(payload.flight)}"""")
                    if (payload.category.isNotEmpty()) append(""" cat="${esc(payload.category)}"""")
                    if (payload.cotHostId.isNotEmpty()) append(""" cot_host_id="${esc(payload.cotHostId)}"""")
                    append("/>\n")
                }
                // Squawk (transponder code) — emitted as a remarks field since
                // ATAK parses it from remarks text when no _aircot_ squawk attr.
                if (payload.squawk > 0) {
                    val rem = buildString {
                        if (payload.icao.isNotEmpty()) append("ICAO: ${payload.icao}")
                        if (payload.registration.isNotEmpty()) append(" REG: ${payload.registration}")
                        if (payload.aircraftType.isNotEmpty()) append(" Type: ${payload.aircraftType}")
                        append(" Squawk: ${payload.squawk}")
                        if (payload.flight.isNotEmpty()) append(" Flight: ${payload.flight}")
                    }
                    append("""    <remarks>${esc(rem.trim())}</remarks>""")
                    append("\n")
                }
                // ADS-B receiver metadata
                if (payload.rssiX10 != 0) {
                    val rssi = payload.rssiX10 / 10.0
                    append("""    <_radio rssi="$rssi"""")
                    if (payload.gps) append(""" gps="true"""")
                    append("/>\n")
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
                if (kind == SHAPE_KIND_CIRCLE || kind == SHAPE_KIND_RANGING_CIRCLE ||
                    kind == SHAPE_KIND_BULLSEYE || kind == SHAPE_KIND_ELLIPSE) {
                    if (payload.majorCm > 0 || payload.minorCm > 0) {
                        val majorM = payload.majorCm / 100.0
                        val minorM = payload.minorCm / 100.0
                        val strokeW = payload.strokeWeightX10 / 10.0
                        append("    <shape>\n")
                        append("""      <ellipse major="$majorM" minor="$minorM" angle="${payload.angleDeg}"/>""")
                        append("\n")
                        // KML style link — iTAK requires this to render circles/ellipses.
                        // ATAK uses ABGR hex encoding for KML colors, not ARGB.
                        append("""      <link uid="${esc(packet.uid)}.Style" type="b-x-KmlStyle" relation="p-c">""")
                        append("""<Style><LineStyle><color>${argbToAbgrHex(strokeVal)}</color><width>$strokeW</width></LineStyle>""")
                        if (fillVal != 0) {
                            append("""<PolyStyle><color>${argbToAbgrHex(fillVal)}</color></PolyStyle>""")
                        }
                        append("</Style></link>\n")
                        append("    </shape>\n")
                    }
                } else {
                    for (v in payload.vertices) {
                        val vlat = v.latI / 1e7
                        val vlon = v.lonI / 1e7
                        append("""    <link point="$vlat,$vlon"/>""")
                        append("\n")
                    }
                }

                // Bullseye-specific element — lives inside <detail>, not <shape>.
                if (kind == SHAPE_KIND_BULLSEYE) {
                    append("    <bullseye")
                    if (payload.bullseyeDistanceDm > 0) {
                        val distM = payload.bullseyeDistanceDm / 10.0
                        append(""" distance="$distM"""")
                    }
                    bearingRefIntToName[payload.bullseyeBearingRef]?.let {
                        append(""" bearingRef="$it"""")
                    }
                    if (payload.bullseyeFlags and 0x01 != 0) append(""" rangeRingVisible="true"""")
                    if (payload.bullseyeFlags and 0x02 != 0) append(""" hasRangeRings="true"""")
                    if (payload.bullseyeFlags and 0x04 != 0) append(""" edgeToCenter="true"""")
                    if (payload.bullseyeFlags and 0x08 != 0) append(""" mils="true"""")
                    if (payload.bullseyeUidRef.isNotEmpty()) {
                        append(""" bullseyeUID="${esc(payload.bullseyeUidRef)}"""")
                    }
                    append("/>\n")
                }

                if (emitStroke) {
                    append("""    <strokeColor value="$strokeVal"/>""")
                    append("\n")
                    if (payload.strokeWeightX10 > 0) {
                        val w = payload.strokeWeightX10 / 10.0
                        append("""    <strokeWeight value="$w"/>""")
                        append("\n")
                    }
                }
                if (emitFill) {
                    append("""    <fillColor value="$fillVal"/>""")
                    append("\n")
                }
                append("""    <labels_on value="${payload.labelsOn}"/>""")
                append("\n")
            }
            is TakPacketV2Data.Payload.Marker -> {
                if (payload.readiness) {
                    append("""    <status readiness="true"/>""")
                    append("\n")
                }
                if (payload.parentUid.isNotEmpty()) {
                    append("    <link")
                    append(""" uid="${esc(payload.parentUid)}"""")
                    if (payload.parentType.isNotEmpty()) {
                        append(""" type="${esc(payload.parentType)}"""")
                    }
                    if (payload.parentCallsign.isNotEmpty()) {
                        append(""" parent_callsign="${esc(payload.parentCallsign)}"""")
                    }
                    append(""" relation="p-p"/>""")
                    append("\n")
                }
                val colorVal = resolveColor(payload.color, payload.colorArgb)
                if (colorVal != 0) {
                    append("""    <color argb="$colorVal"/>""")
                    append("\n")
                }
                if (payload.iconset.isNotEmpty()) {
                    append("""    <usericon iconsetpath="${esc(payload.iconset)}"/>""")
                    append("\n")
                }
            }
            is TakPacketV2Data.Payload.RangeAndBearing -> {
                if (payload.anchorLatI != 0 || payload.anchorLonI != 0) {
                    val alat = payload.anchorLatI / 1e7
                    val alon = payload.anchorLonI / 1e7
                    append("    <link")
                    if (payload.anchorUid.isNotEmpty()) {
                        append(""" uid="${esc(payload.anchorUid)}"""")
                    }
                    append(""" relation="p-p" type="b-m-p-w" point="$alat,$alon"/>""")
                    append("\n")
                }
                if (payload.rangeCm > 0) {
                    val rangeM = payload.rangeCm / 100.0
                    append("""    <range value="$rangeM"/>""")
                    append("\n")
                }
                if (payload.bearingCdeg > 0) {
                    val bearingDeg = payload.bearingCdeg / 100.0
                    append("""    <bearing value="$bearingDeg"/>""")
                    append("\n")
                }
                val strokeVal = resolveColor(payload.strokeColor, payload.strokeArgb)
                if (strokeVal != 0) {
                    append("""    <strokeColor value="$strokeVal"/>""")
                    append("\n")
                }
                if (payload.strokeWeightX10 > 0) {
                    val w = payload.strokeWeightX10 / 10.0
                    append("""    <strokeWeight value="$w"/>""")
                    append("\n")
                }
            }
            is TakPacketV2Data.Payload.RawDetail -> {
                // Fallback path (`TakCompressor.compressBestOf`): raw bytes
                // of the original <detail> element are shipped verbatim.
                // Emit them inside the <detail>…</detail> wrapper without
                // any normalization so the receiver's round trip preserves
                // attribute order and whitespace identically to the source.
                if (payload.bytes.isNotEmpty()) {
                    append(payload.bytes.decodeToString())
                    if (!endsWith("\n")) append("\n")
                }
            }
            is TakPacketV2Data.Payload.Route -> {
                // Emit <link> elements BEFORE <link_attr> and <__routeinfo> —
                // ATAK's parser expects waypoints first, then route metadata.
                for ((idx, link) in payload.links.withIndex()) {
                    val llat = link.latI / 1e7
                    val llon = link.lonI / 1e7
                    append("    <link")
                    // ATAK requires uid on waypoint links for internal marker
                    // creation. Generate a deterministic one when not present.
                    val uid = link.uid.ifEmpty { "${packet.uid}-$idx" }
                    append(""" uid="${esc(uid)}"""")
                    val linkType = if (link.linkType == 1) "b-m-p-c" else "b-m-p-w"
                    append(""" type="$linkType"""")
                    if (link.callsign.isNotEmpty()) {
                        append(""" callsign="${esc(link.callsign)}"""")
                    }
                    // ATAK expects 3-component point: lat,lon,hae
                    append(""" point="$llat,$llon,0" relation="c"/>""")
                    append("\n")
                }
                append("    <link_attr")
                routeMethodIntToName[payload.method]?.let { append(""" method="$it"""") }
                routeDirectionIntToName[payload.direction]?.let { append(""" direction="$it"""") }
                if (payload.prefix.isNotEmpty()) {
                    append(""" prefix="${esc(payload.prefix)}"""")
                }
                if (payload.strokeWeightX10 > 0) {
                    val sw = payload.strokeWeightX10 / 10.0
                    append(""" stroke="$sw"""")
                }
                append("/>\n")
                // ATAK requires these elements after link_attr for route rendering
                if (packet.remarks.isNotEmpty()) {
                    append("    <remarks>${esc(packet.remarks)}</remarks>\n")
                } else {
                    append("    <remarks/>\n")
                }
                append("    <__routeinfo><__navcues/></__routeinfo>\n")
                append("    <strokeColor value=\"-1\"/>\n")
                append("    <strokeWeight value=\"${if (payload.strokeWeightX10 > 0) payload.strokeWeightX10 / 10.0 else 3.0}\"/>\n")
                append("    <strokeStyle value=\"solid\"/>\n")
                // ATAK expects <contact> AFTER __routeinfo, with NO endpoint
                append("    <contact callsign=\"${esc(packet.callsign)}\"/>\n")
                append("    <labels_on value=\"false\"/>\n")
                append("    <color value=\"-1\"/>\n")
            }
            is TakPacketV2Data.Payload.CasevacReport -> {
                append("    <_medevac_")
                precedenceIntToName[payload.precedence]?.let {
                    append(""" precedence="$it"""")
                }
                // Equipment bitfield flags
                if (payload.equipmentFlags and 0x01 != 0) append(""" none="true"""")
                if (payload.equipmentFlags and 0x02 != 0) append(""" hoist="true"""")
                if (payload.equipmentFlags and 0x04 != 0) append(""" extraction_equipment="true"""")
                if (payload.equipmentFlags and 0x08 != 0) append(""" ventilator="true"""")
                if (payload.equipmentFlags and 0x10 != 0) append(""" blood="true"""")
                if (payload.litterPatients > 0) append(""" litter="${payload.litterPatients}"""")
                if (payload.ambulatoryPatients > 0) append(""" ambulatory="${payload.ambulatoryPatients}"""")
                securityIntToName[payload.security]?.let {
                    append(""" security="$it"""")
                }
                hlzMarkingIntToName[payload.hlzMarking]?.let {
                    append(""" hlz_marking="$it"""")
                }
                if (payload.zoneMarker.isNotEmpty()) {
                    append(""" zone_prot_marker="${esc(payload.zoneMarker)}"""")
                }
                if (payload.usMilitary > 0) append(""" us_military="${payload.usMilitary}"""")
                if (payload.usCivilian > 0) append(""" us_civilian="${payload.usCivilian}"""")
                if (payload.nonUsMilitary > 0) append(""" non_us_military="${payload.nonUsMilitary}"""")
                if (payload.nonUsCivilian > 0) append(""" non_us_civilian="${payload.nonUsCivilian}"""")
                if (payload.epw > 0) append(""" epw="${payload.epw}"""")
                if (payload.child > 0) append(""" child="${payload.child}"""")
                // Terrain bitfield flags
                if (payload.terrainFlags and 0x01 != 0) append(""" terrain_slope="true"""")
                if (payload.terrainFlags and 0x02 != 0) append(""" terrain_rough="true"""")
                if (payload.terrainFlags and 0x04 != 0) append(""" terrain_loose="true"""")
                if (payload.terrainFlags and 0x08 != 0) append(""" terrain_trees="true"""")
                if (payload.terrainFlags and 0x10 != 0) append(""" terrain_wires="true"""")
                if (payload.terrainFlags and 0x20 != 0) append(""" terrain_other="true"""")
                if (payload.frequency.isNotEmpty()) {
                    append(""" freq="${esc(payload.frequency)}"""")
                }
                append("/>\n")
            }
            is TakPacketV2Data.Payload.EmergencyAlert -> {
                // <emergency type="…"/> element carries the alert type; if
                // the EmergencyAlert.type is Cancel (6) we emit cancel="true"
                // to match ATAK's cancel encoding.
                append("    <emergency")
                if (payload.type == 6) {
                    append(""" cancel="true"""")
                } else {
                    emergencyTypeIntToName[payload.type]?.let {
                        append(""" type="$it"""")
                    }
                }
                append("/>\n")
                // Authoring link — <link uid="…" relation="p-p" type="a-f-G-U-C"/>
                if (payload.authoringUid.isNotEmpty()) {
                    append("    <link")
                    append(""" uid="${esc(payload.authoringUid)}"""")
                    append(""" relation="p-p" type="a-f-G-U-C"/>""")
                    append("\n")
                }
                if (payload.cancelReferenceUid.isNotEmpty()) {
                    append("""    <link uid="${esc(payload.cancelReferenceUid)}" relation="p-p" type="b-a-o-tbl"/>""")
                    append("\n")
                }
            }
            is TakPacketV2Data.Payload.TaskRequest -> {
                append("    <task")
                if (payload.taskType.isNotEmpty()) {
                    append(""" type="${esc(payload.taskType)}"""")
                }
                taskPriorityIntToName[payload.priority]?.let {
                    append(""" priority="$it"""")
                }
                taskStatusIntToName[payload.status]?.let {
                    append(""" status="$it"""")
                }
                if (payload.assigneeUid.isNotEmpty()) {
                    append(""" assignee="${esc(payload.assigneeUid)}"""")
                }
                if (payload.note.isNotEmpty()) {
                    append(""" note="${esc(payload.note)}"""")
                }
                append("/>\n")
                // Target link
                if (payload.targetUid.isNotEmpty()) {
                    append("""    <link uid="${esc(payload.targetUid)}" relation="p-p" type="a-f-G"/>""")
                    append("\n")
                }
            }
            is TakPacketV2Data.Payload.Pli,
            is TakPacketV2Data.Payload.None -> { /* no extra detail elements */ }
        }

        // Emit <remarks> for non-Chat/non-Aircraft/non-Route types that carried remarks text.
        // Chat uses GeoChat.message; Aircraft synthesizes from ICAO fields; Route handles
        // remarks in its own block above. All other types (shapes, markers, RAB, casevac,
        // emergency, task) emit here.
        if (packet.remarks.isNotEmpty()
            && packet.payload !is TakPacketV2Data.Payload.Chat
            && packet.payload !is TakPacketV2Data.Payload.Aircraft
            && packet.payload !is TakPacketV2Data.Payload.Route
        ) {
            append("    <remarks>${esc(packet.remarks)}</remarks>\n")
        }

        append("  </detail>\n")
        append("</event>")
        }
        trace { "CotXmlBuilder.build: output ${xml.length} chars" }
        return xml
    }
}
