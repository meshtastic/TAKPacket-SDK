package org.meshtastic.tak

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.time.Instant
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Parses a CoT XML event string into a TakPacketV2Data.
 *
 * Extracts all fields supported by the TAKPacketV2 protobuf schema, including
 * event envelope (type, how, uid, time/stale), point (position), and detail
 * sub-elements (contact, group, status, track, takv, precisionlocation, remarks,
 * aircot, link, chat).
 */
class CotXmlParser {

    companion object {
        // GeoPointSource enum values
        const val GEOSRC_UNSPECIFIED = 0
        const val GEOSRC_GPS = 1
        const val GEOSRC_USER = 2
        const val GEOSRC_NETWORK = 3

        // Team enum values matching atak.proto
        private val teamNameToEnum = mapOf(
            "White" to 1, "Yellow" to 2, "Orange" to 3, "Magenta" to 4,
            "Red" to 5, "Maroon" to 6, "Purple" to 7, "Dark Blue" to 8,
            "Blue" to 9, "Cyan" to 10, "Teal" to 11, "Green" to 12,
            "Dark Green" to 13, "Brown" to 14,
        )

        // MemberRole enum values matching atak.proto
        private val roleNameToEnum = mapOf(
            "Team Member" to 1, "Team Lead" to 2, "HQ" to 3,
            "Sniper" to 4, "Medic" to 5, "ForwardObserver" to 6,
            "RTO" to 7, "K9" to 8,
        )

        private fun parseGeoSrc(src: String?): Int = when (src) {
            "GPS" -> GEOSRC_GPS
            "USER" -> GEOSRC_USER
            "NETWORK" -> GEOSRC_NETWORK
            else -> GEOSRC_UNSPECIFIED
        }
    }

    /**
     * Parse a full CoT XML event string into a TakPacketV2Data.
     */
    fun parse(cotXml: String): TakPacketV2Data {
        // Reject XML with DOCTYPE or ENTITY declarations to prevent XXE and entity expansion attacks
        if (cotXml.contains("<!DOCTYPE", ignoreCase = true) || cotXml.contains("<!ENTITY", ignoreCase = true)) {
            throw IllegalArgumentException("XML contains prohibited DOCTYPE or ENTITY declaration")
        }

        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(StringReader(cotXml))

        var uid = ""
        var cotTypeStr = ""
        var howStr = ""
        var timeStr = ""
        var staleStr = ""
        var lat = 0.0
        var lon = 0.0
        var hae = 0.0
        var callsign = ""
        var deviceCallsign = ""
        var endpoint = ""
        var phone = ""
        var teamName = ""
        var roleName = ""
        var battery = 0
        var speed = 0.0
        var course = 0.0
        var takVersion = ""
        var takDevice = ""
        var takPlatform = ""
        var takOs = ""
        var geoSrc = ""
        var altSrc = ""
        var chatMessage = ""
        var chatTo: String? = null
        var chatToCallsign: String? = null
        var icao = ""
        var registration = ""
        var flight = ""
        var aircraftType = ""
        var squawk = 0
        var category = ""
        var rssiX10 = 0
        var gps = false
        var cotHostId = ""
        var hasAircraftData = false
        var hasChatData = false
        var isDeleteEvent = false
        var linkUid = ""
        var remarksText = ""
        var rawDetailXml = StringBuilder()
        var inDetail = false
        var detailDepth = 0

        var eventType = parser.eventType
        while (eventType != XmlPullParser.END_DOCUMENT) {
            when (eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "event" -> {
                            uid = parser.getAttributeValue(null, "uid") ?: ""
                            cotTypeStr = parser.getAttributeValue(null, "type") ?: ""
                            howStr = parser.getAttributeValue(null, "how") ?: ""
                            timeStr = parser.getAttributeValue(null, "time") ?: ""
                            staleStr = parser.getAttributeValue(null, "stale") ?: ""
                            isDeleteEvent = cotTypeStr == "t-x-d-d"
                        }
                        "point" -> {
                            lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                            lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                            hae = parser.getAttributeValue(null, "hae")?.toDoubleOrNull() ?: 0.0
                        }
                        "detail" -> {
                            inDetail = true
                            detailDepth = 1
                        }
                        "contact" -> {
                            callsign = parser.getAttributeValue(null, "callsign") ?: ""
                            endpoint = parser.getAttributeValue(null, "endpoint") ?: endpoint
                            phone = parser.getAttributeValue(null, "phone") ?: phone
                        }
                        "__group" -> {
                            teamName = parser.getAttributeValue(null, "name") ?: ""
                            roleName = parser.getAttributeValue(null, "role") ?: ""
                        }
                        "status" -> {
                            battery = parser.getAttributeValue(null, "battery")?.toIntOrNull() ?: 0
                        }
                        "track" -> {
                            speed = parser.getAttributeValue(null, "speed")?.toDoubleOrNull() ?: 0.0
                            course = parser.getAttributeValue(null, "course")?.toDoubleOrNull() ?: 0.0
                        }
                        "takv" -> {
                            takVersion = parser.getAttributeValue(null, "version") ?: ""
                            takDevice = parser.getAttributeValue(null, "device") ?: ""
                            takPlatform = parser.getAttributeValue(null, "platform") ?: ""
                            takOs = parser.getAttributeValue(null, "os") ?: ""
                        }
                        "precisionlocation" -> {
                            geoSrc = parser.getAttributeValue(null, "geopointsrc") ?: ""
                            altSrc = parser.getAttributeValue(null, "altsrc") ?: ""
                        }
                        "uid", "UID" -> {
                            val droid = parser.getAttributeValue(null, "Droid")
                            if (droid != null) deviceCallsign = droid
                        }
                        "_radio" -> {
                            val rssiStr = parser.getAttributeValue(null, "rssi")
                            if (rssiStr != null) {
                                rssiX10 = (rssiStr.toDoubleOrNull()?.times(10))?.roundToInt() ?: 0
                                hasAircraftData = true
                            }
                            gps = parser.getAttributeValue(null, "gps")?.toBooleanStrictOrNull() ?: false
                        }
                        "_aircot_" -> {
                            icao = parser.getAttributeValue(null, "icao") ?: ""
                            registration = parser.getAttributeValue(null, "reg") ?: ""
                            flight = parser.getAttributeValue(null, "flight") ?: ""
                            category = parser.getAttributeValue(null, "cat") ?: ""
                            cotHostId = parser.getAttributeValue(null, "cot_host_id") ?: ""
                            hasAircraftData = true
                        }
                        "__chat" -> {
                            hasChatData = true
                            chatToCallsign = parser.getAttributeValue(null, "senderCallsign")
                            chatTo = parser.getAttributeValue(null, "id")
                        }
                        "link" -> {
                            linkUid = parser.getAttributeValue(null, "uid") ?: linkUid
                        }
                    }
                }
                XmlPullParser.TEXT -> {
                    // Capture remarks text
                    if (inDetail) {
                        val text = parser.text?.trim() ?: ""
                        if (text.isNotEmpty()) {
                            remarksText = text
                        }
                    }
                }
            }
            eventType = parser.next()
        }

        // Parse ICAO/aircraft data from remarks (always try, remarks may supplement _aircot_ or _radio)
        if (remarksText.isNotEmpty() && icao.isEmpty()) {
            val icaoMatch = Regex("""ICAO:\s*([A-Fa-f0-9]{6})""").find(remarksText)
            if (icaoMatch != null) {
                hasAircraftData = true
                icao = icaoMatch.groupValues[1]
                if (registration.isEmpty()) registration = Regex("""REG:\s*(\S+)""").find(remarksText)?.groupValues?.get(1) ?: ""
                if (flight.isEmpty()) flight = Regex("""Flight:\s*(\S+)""").find(remarksText)?.groupValues?.get(1) ?: ""
                if (aircraftType.isEmpty()) aircraftType = Regex("""Type:\s*(\S+)""").find(remarksText)?.groupValues?.get(1) ?: ""
                val squawkMatch = Regex("""Squawk:\s*(\d+)""").find(remarksText)
                if (squawk == 0) squawk = squawkMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val catMatch = Regex("""Category:\s*(\S+)""").find(remarksText)
                if (catMatch != null && category.isEmpty()) category = catMatch.groupValues[1]
            }
        }

        // Compute stale seconds
        val staleSeconds = computeStaleSeconds(timeStr, staleStr)

        // Determine CoT type enum
        val cotTypeId = CotTypeMapper.typeToEnum(cotTypeStr)
        val cotTypeStrField = if (cotTypeId == CotTypeMapper.COTTYPE_OTHER) cotTypeStr else null

        // Determine payload type
        val payload = when {
            hasChatData -> TakPacketV2Data.Payload.Chat(
                message = remarksText,
                to = chatTo,
                toCallsign = chatToCallsign,
            )
            hasAircraftData -> TakPacketV2Data.Payload.Aircraft(
                icao = icao,
                registration = registration,
                flight = flight,
                aircraftType = aircraftType,
                squawk = squawk,
                category = category,
                rssiX10 = rssiX10,
                gps = gps,
                cotHostId = cotHostId,
            )
            isDeleteEvent -> TakPacketV2Data.Payload.Pli(true) // delete events use minimal payload
            else -> TakPacketV2Data.Payload.Pli(true) // PLI (position)
        }

        return TakPacketV2Data(
            cotTypeId = cotTypeId,
            cotTypeStr = cotTypeStrField,
            how = CotTypeMapper.howToEnum(howStr),
            callsign = callsign,
            team = teamNameToEnum[teamName] ?: 0,
            role = roleNameToEnum[roleName] ?: 0,
            latitudeI = (lat * 1e7).roundToInt(),
            longitudeI = (lon * 1e7).roundToInt(),
            altitude = hae.roundToInt(),
            speed = (speed * 100).roundToInt(),   // m/s -> cm/s
            course = (course * 100).roundToInt(),  // degrees -> degrees*100
            battery = battery,
            geoSrc = parseGeoSrc(geoSrc),
            altSrc = parseGeoSrc(altSrc),
            uid = uid,
            deviceCallsign = deviceCallsign,
            staleSeconds = staleSeconds,
            takVersion = takVersion,
            takDevice = takDevice,
            takPlatform = takPlatform,
            takOs = takOs,
            endpoint = endpoint,
            phone = phone,
            payload = payload,
        )
    }

    private fun computeStaleSeconds(timeStr: String, staleStr: String): Int {
        if (timeStr.isEmpty() || staleStr.isEmpty()) return 0
        return try {
            val time = Instant.from(DateTimeFormatter.ISO_INSTANT.parse(timeStr))
            val stale = Instant.from(DateTimeFormatter.ISO_INSTANT.parse(staleStr))
            val diff = stale.epochSecond - time.epochSecond
            if (diff > 0) diff.toInt() else 0
        } catch (e: Exception) {
            0
        }
    }
}
