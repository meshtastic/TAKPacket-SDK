package org.meshtastic.tak

import kotlinx.datetime.Instant
import nl.adaptivity.xmlutil.serialization.XML
import kotlin.math.roundToInt

/**
 * Parses a CoT XML event string into a [TakPacketV2Data].
 *
 * Uses xmlutil (KMP) for cross-platform XML deserialization. Extracts all fields
 * supported by the TAKPacketV2 protobuf schema, including event envelope, point,
 * and detail sub-elements (contact, group, status, track, takv, precisionlocation,
 * remarks, aircot, radio, link, chat).
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

        private val xmlParser = XML {
            recommended {
                ignoreUnknownChildren()
            }
        }
    }

    /**
     * Parse a full CoT XML event string into a [TakPacketV2Data].
     *
     * @throws IllegalArgumentException if the XML contains prohibited DOCTYPE or ENTITY declarations
     */
    fun parse(cotXml: String): TakPacketV2Data {
        // Reject XML with DOCTYPE or ENTITY declarations to prevent XXE attacks
        if (cotXml.contains("<!DOCTYPE", ignoreCase = true) ||
            cotXml.contains("<!ENTITY", ignoreCase = true)
        ) {
            throw IllegalArgumentException("XML contains prohibited DOCTYPE or ENTITY declaration")
        }

        val event = xmlParser.decodeFromString(CoTEventXml.serializer(), cotXml)
        return mapEventToData(event)
    }

    private fun mapEventToData(event: CoTEventXml): TakPacketV2Data {
        val detail = event.detail
        val cotTypeStr = event.type
        val isDeleteEvent = cotTypeStr == "t-x-d-d"

        // Extract aircraft data
        var hasAircraftData = false
        var icao = ""
        var registration = ""
        var flight = ""
        var aircraftType = ""
        var squawk = 0
        var category = ""
        var rssiX10 = 0
        var gps = false
        var cotHostId = ""

        detail.aircot?.let { aircot ->
            hasAircraftData = true
            icao = aircot.icao
            registration = aircot.reg
            flight = aircot.flight
            category = aircot.cat
            cotHostId = aircot.cot_host_id
        }

        detail.radio?.let { radio ->
            if (radio.rssi.isNotEmpty()) {
                rssiX10 = (radio.rssi.toDoubleOrNull()?.times(10))?.roundToInt() ?: 0
                hasAircraftData = true
            }
            gps = radio.gps.toBooleanStrictOrNull() ?: false
        }

        // Extract remarks text
        val remarksText = detail.remarks?.text?.trim() ?: ""

        // Parse aircraft data from remarks if _aircot_ element is absent
        if (remarksText.isNotEmpty() && icao.isEmpty()) {
            val icaoMatch = Regex("""ICAO:\s*([A-Fa-f0-9]{6})""").find(remarksText)
            if (icaoMatch != null) {
                hasAircraftData = true
                icao = icaoMatch.groupValues[1]
                if (registration.isEmpty()) {
                    registration = Regex("""REG:\s*(\S+)""").find(remarksText)?.groupValues?.get(1) ?: ""
                }
                if (flight.isEmpty()) {
                    flight = Regex("""Flight:\s*(\S+)""").find(remarksText)?.groupValues?.get(1) ?: ""
                }
                if (aircraftType.isEmpty()) {
                    aircraftType = Regex("""Type:\s*(\S+)""").find(remarksText)?.groupValues?.get(1) ?: ""
                }
                if (squawk == 0) {
                    squawk = Regex("""Squawk:\s*(\d+)""").find(remarksText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
                }
                if (category.isEmpty()) {
                    val catMatch = Regex("""Category:\s*(\S+)""").find(remarksText)
                    if (catMatch != null) category = catMatch.groupValues[1]
                }
            }
        }

        // Chat data
        val hasChatData = detail.chat != null

        // Compute stale seconds
        val staleSeconds = computeStaleSeconds(event.time, event.stale)

        // Determine CoT type enum
        val cotTypeId = CotTypeMapper.typeToEnum(cotTypeStr)
        val cotTypeStrField = if (cotTypeId == CotTypeMapper.COTTYPE_OTHER) cotTypeStr else null

        // Determine payload type
        val payload = when {
            hasChatData -> TakPacketV2Data.Payload.Chat(
                message = remarksText,
                to = detail.chat?.id?.ifEmpty { null },
                toCallsign = detail.chat?.senderCallsign?.ifEmpty { null },
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
            isDeleteEvent -> TakPacketV2Data.Payload.Pli(true)
            else -> TakPacketV2Data.Payload.Pli(true)
        }

        return TakPacketV2Data(
            cotTypeId = cotTypeId,
            cotTypeStr = cotTypeStrField,
            how = CotTypeMapper.howToEnum(event.how),
            callsign = detail.contact?.callsign ?: "",
            team = teamNameToEnum[detail.group?.name ?: ""] ?: 0,
            role = roleNameToEnum[detail.group?.role ?: ""] ?: 0,
            latitudeI = (event.point.lat * 1e7).roundToInt(),
            longitudeI = (event.point.lon * 1e7).roundToInt(),
            altitude = event.point.hae.roundToInt(),
            speed = ((detail.track?.speed?.toDoubleOrNull() ?: 0.0) * 100).roundToInt(),
            course = ((detail.track?.course?.toDoubleOrNull() ?: 0.0) * 100).roundToInt(),
            battery = detail.status?.battery?.toIntOrNull() ?: 0,
            geoSrc = parseGeoSrc(detail.precisionlocation?.geopointsrc),
            altSrc = parseGeoSrc(detail.precisionlocation?.altsrc),
            uid = event.uid,
            deviceCallsign = detail.uidDetail?.Droid ?: "",
            staleSeconds = staleSeconds,
            takVersion = detail.takv?.version ?: "",
            takDevice = detail.takv?.device ?: "",
            takPlatform = detail.takv?.platform ?: "",
            takOs = detail.takv?.os ?: "",
            endpoint = detail.contact?.endpoint ?: "",
            phone = detail.contact?.phone ?: "",
            payload = payload,
        )
    }

    private fun computeStaleSeconds(timeStr: String, staleStr: String): Int {
        if (timeStr.isEmpty() || staleStr.isEmpty()) return 0
        return try {
            val time = Instant.parse(timeStr)
            val stale = Instant.parse(staleStr)
            val diff = (stale - time).inWholeSeconds
            if (diff > 0) diff.toInt() else 0
        } catch (_: Exception) {
            0
        }
    }
}
