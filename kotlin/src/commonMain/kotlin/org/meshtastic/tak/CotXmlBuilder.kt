package org.meshtastic.tak

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.roundToInt
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

        private fun geoSrcToString(src: Int): String = when (src) {
            1 -> "GPS"
            2 -> "USER"
            3 -> "NETWORK"
            else -> "???"
        }
    }

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
            else -> { /* PLI and delete events have no extra detail elements */ }
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
