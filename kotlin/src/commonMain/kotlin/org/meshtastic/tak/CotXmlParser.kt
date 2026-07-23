package org.meshtastic.tak

import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.xmlStreaming
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * Parses a CoT (Cursor-on-Target) XML event string into a [TakPacketV2Data].
 *
 * Multiplatform: built on the `io.github.pdvrieze.xmlutil:core` streaming reader
 * and `kotlin.time.Instant` (kotlin.time stdlib), so it runs on every KMP target.
 *
 * Text-handling note: xmlutil may split an element's text body across several
 * `TEXT`/`CDSECT` events (e.g. around entity references). Rather than assign on
 * each event (which would keep only the last fragment), this parser ACCUMULATES
 * the raw text run per active element into a buffer and commits the
 * coalesced + trimmed value on `END_ELEMENT`.
 */
public class CotXmlParser {
    /**
     * Parse a CoT XML event string into a [TakPacketV2Data].
     *
     * @throws IllegalArgumentException if the XML contains a prohibited DOCTYPE
     *         or ENTITY declaration (XXE / entity-expansion guard) or is
     *         otherwise unparseable.
     */
    @Throws(IllegalArgumentException::class)
    public fun parse(cotXml: String): TakPacketV2Data {
        // Reject XML with DOCTYPE or ENTITY declarations to prevent XXE and entity expansion attacks
        if (cotXml.contains("<!DOCTYPE", ignoreCase = true) || cotXml.contains("<!ENTITY", ignoreCase = true)) {
            throw IllegalArgumentException("XML contains prohibited DOCTYPE or ENTITY declaration")
        }

        val reader = xmlStreaming.newReader(cotXml)

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
        var remarksText = ""
        // True once the <detail> element opens; gates element-body text
        // accumulation (everything we consume lives inside <detail>).
        var inDetail = false

        // --- TAKTALK m-t-t accumulators ---------------------------------
        var hasTakTalkData = false
        var talkText = ""
        var talkChatroomId = ""
        var talkLang = ""
        var fromVoice = false
        var inTaktalkText = false
        var inTaktalkChatroomId = false
        var inTaktalkLang = false
        var inMttCallsign = false

        // --- TAKTALK b-t-f sidecar accumulators (decorate Payload.Chat) -
        var chatLang = ""
        var chatRoomId = ""
        var chatVoiceProfileId = ""
        var chatHasVoiceProfile = false
        var inChatEa = false
        var inChatRoomId = false
        var inChatVoiceProfileId = false

        // --- TAKTALK y- room broadcast accumulators ---------------------
        var hasRoomData = false
        var roomDataId = ""
        var roomName = ""
        val roomParticipants = mutableListOf<String>()
        var inRoomSender = false
        var inRoomDataId = false
        var inRoomName = false
        var inRoomParticipants = false

        // --- Directed-routing recipient list ----------------------------
        var inMarti = false
        val martiDests = mutableListOf<String>()

        // --- Remarks gate -----------------------------------------------
        var inRemarks = false

        // Per-element coalescing buffer for text runs. See class kdoc: xmlutil
        // may split a text body across multiple TEXT/CDSECT events; we append
        // raw fragments here and assign the trimmed result on END_ELEMENT so
        // the outcome matches xpp3's single-fragment assignment.
        val textBuf = StringBuilder()

        // --- Drawn shape accumulators -----------------------------------
        var hasShapeData = false
        var inShape = false
        var shapeMajorCm = 0
        var shapeMinorCm = 0
        var shapeAngleDeg = 360
        var sawStrokeColor = false
        var sawFillColor = false
        var strokeColorArgb = 0
        var strokeWeightX10 = 0
        var fillColorArgb = 0
        var labelsOn = false
        val vertices = mutableListOf<TakPacketV2Data.Payload.Vertex>()
        var verticesTruncated = false

        // --- Bullseye accumulators --------------------------------------
        var hasBullseyeData = false
        var bullseyeDistanceDm = 0
        var bullseyeBearingRef = 0
        var bullseyeFlags = 0
        var bullseyeUidRef = ""

        // --- Marker accumulators ----------------------------------------
        var hasMarkerData = false
        var markerColorArgb = 0
        var markerReadiness = false
        var markerParentUid = ""
        var markerParentType = ""
        var markerParentCallsign = ""
        var markerIconset = ""

        // --- Range and bearing accumulators -----------------------------
        var hasRabData = false
        var rabAnchorLatI = 0
        var rabAnchorLonI = 0
        var rabAnchorUid = ""
        var rabRangeCm = 0
        var rabBearingCdeg = 0

        // --- Route accumulators -----------------------------------------
        var hasRouteData = false
        val routeLinks = mutableListOf<TakPacketV2Data.Payload.Route.Link>()
        var routeTruncated = false
        var routePrefix = ""
        var routeMethod = 0
        var routeDirection = 0

        // --- CasevacReport accumulators ---------------------------------
        var hasCasevacData = false
        var casevacPrecedence = 0
        var casevacEquipmentFlags = 0
        var casevacLitterPatients = 0
        var casevacAmbulatoryPatients = 0
        var casevacSecurity = 0
        var casevacHlzMarking = 0
        var casevacZoneMarker = ""
        var casevacUsMilitary = 0
        var casevacUsCivilian = 0
        var casevacNonUsMilitary = 0
        var casevacNonUsCivilian = 0
        var casevacEpw = 0
        var casevacChild = 0
        var casevacTerrainFlags = 0
        var casevacFrequency = ""
        var casevacTitle = ""
        var casevacMedlineRemarks = ""
        var casevacUrgentCount = 0
        var casevacUrgentSurgicalCount = 0
        var casevacPriorityCount = 0
        var casevacRoutineCount = 0
        var casevacConvenienceCount = 0
        var casevacEquipmentDetail = ""
        var casevacZoneProtectedCoord = ""
        var casevacTerrainSlopeDir = ""
        var casevacTerrainOtherDetail = ""
        var casevacMarkedBy = ""
        var casevacObstacles = ""
        var casevacWindsAreFrom = ""
        var casevacFriendlies = ""
        var casevacEnemy = ""
        var casevacHlzRemarks = ""
        val casevacZmistEntries = mutableListOf<TakPacketV2Data.Payload.CasevacReport.ZMistEntry>()

        // --- EmergencyAlert accumulators --------------------------------
        var hasEmergencyData = false
        var emergencyTypeInt = 0
        var emergencyAuthoringUid = ""
        var emergencyCancelReferenceUid = ""

        // --- TaskRequest accumulators -----------------------------------
        var hasTaskData = false
        var taskTypeTag = ""
        var taskTargetUid = ""
        var taskAssigneeUid = ""
        var taskPriority = 0
        var taskStatus = 0
        var taskNote = ""

        // --- GeoChat receipt accumulators -------------------------------
        var chatReceiptForUid = ""
        var chatReceiptType = 0

        // --- Environment / SensorFov annotation accumulators ------------
        var environmentData: TakPacketV2Data.EnvironmentData? = null
        var sensorFovData: TakPacketV2Data.SensorFovData? = null

        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    EventType.START_ELEMENT -> {
                        // Reset the text buffer at every element start so a fresh
                        // element body accumulates from empty (mirrors xpp3, which
                        // emits a fresh TEXT event per element body).
                        textBuf.setLength(0)
                        when (reader.localName) {
                            "event" -> {
                                uid = reader.getAttributeValue(null, "uid") ?: ""
                                cotTypeStr = reader.getAttributeValue(null, "type") ?: ""
                                howStr = reader.getAttributeValue(null, "how") ?: ""
                                timeStr = reader.getAttributeValue(null, "time") ?: ""
                                staleStr = reader.getAttributeValue(null, "stale") ?: ""
                                isDeleteEvent = cotTypeStr == "t-x-d-d"
                            }

                            "point" -> {
                                lat = reader.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
                                lon = reader.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
                                hae = reader.getAttributeValue(null, "hae")?.toDoubleOrNull() ?: 0.0
                            }

                            "detail" -> {
                                inDetail = true
                            }

                            "contact" -> {
                                callsign = reader.getAttributeValue(null, "callsign") ?: ""
                                val ep = reader.getAttributeValue(null, "endpoint") ?: ""
                                endpoint = if (ep == "0.0.0.0:4242:tcp" || ep == "*:-1:stcp") "" else ep
                                phone = reader.getAttributeValue(null, "phone") ?: phone
                            }

                            "__group" -> {
                                teamName = reader.getAttributeValue(null, "name") ?: ""
                                roleName = reader.getAttributeValue(null, "role") ?: ""
                            }

                            "status" -> {
                                val bat = reader.getAttributeValue(null, "battery")?.toIntOrNull()
                                if (bat != null && bat > 0) battery = bat
                                if (reader.getAttributeValue(null, "readiness") == "true") markerReadiness = true
                            }

                            "track" -> {
                                speed = reader.getAttributeValue(null, "speed")?.toDoubleOrNull() ?: 0.0
                                course = reader.getAttributeValue(null, "course")?.toDoubleOrNull() ?: 0.0
                            }

                            "takv" -> {
                                takVersion = reader.getAttributeValue(null, "version") ?: ""
                                takDevice = reader.getAttributeValue(null, "device") ?: ""
                                takPlatform = reader.getAttributeValue(null, "platform") ?: ""
                                takOs = reader.getAttributeValue(null, "os") ?: ""
                            }

                            "precisionlocation" -> {
                                geoSrc = reader.getAttributeValue(null, "geopointsrc") ?: ""
                                altSrc = reader.getAttributeValue(null, "altsrc") ?: ""
                            }

                            "uid", "UID" -> {
                                val droid = reader.getAttributeValue(null, "Droid")
                                if (droid != null) deviceCallsign = droid
                            }

                            "_radio" -> {
                                val rssiStr = reader.getAttributeValue(null, "rssi")
                                if (rssiStr != null) {
                                    rssiX10 = (rssiStr.toDoubleOrNull()?.times(10))?.roundToInt() ?: 0
                                    hasAircraftData = true
                                }
                                gps = reader.getAttributeValue(null, "gps")?.toBooleanStrictOrNull() ?: false
                            }

                            "_aircot_" -> {
                                icao = reader.getAttributeValue(null, "icao") ?: ""
                                registration = reader.getAttributeValue(null, "reg") ?: ""
                                flight = reader.getAttributeValue(null, "flight") ?: ""
                                category = reader.getAttributeValue(null, "cat") ?: ""
                                cotHostId = reader.getAttributeValue(null, "cot_host_id") ?: ""
                                hasAircraftData = true
                            }

                            "__chat" -> {
                                hasChatData = true
                                chatToCallsign = reader.getAttributeValue(null, "senderCallsign")
                                val chatId = reader.getAttributeValue(null, "id")
                                chatTo = if (chatId == "All Chat Rooms") null else chatId
                            }

                            "text" -> {
                                if (cotTypeStr == "m-t-t") {
                                    hasTakTalkData = true
                                    inTaktalkText = true
                                }
                            }

                            "chatroom-id" -> {
                                when (cotTypeStr) {
                                    "m-t-t" -> {
                                        hasTakTalkData = true
                                        inTaktalkChatroomId = true
                                    }

                                    "y-" -> {
                                        hasRoomData = true
                                        inRoomDataId = true
                                    }
                                }
                            }

                            "lang" -> {
                                if (cotTypeStr == "m-t-t") {
                                    hasTakTalkData = true
                                    inTaktalkLang = true
                                }
                            }

                            "voice" -> {
                                if (cotTypeStr == "m-t-t") {
                                    hasTakTalkData = true
                                    fromVoice = true
                                }
                            }

                            "callsign" -> {
                                if (cotTypeStr == "m-t-t") {
                                    hasTakTalkData = true
                                    inMttCallsign = true
                                }
                            }

                            "Ea" -> {
                                if (hasChatData) inChatEa = true
                            }

                            "roomId" -> {
                                if (hasChatData) inChatRoomId = true
                            }

                            "voice_profile_id" -> {
                                if (hasChatData) {
                                    chatHasVoiceProfile = true
                                    inChatVoiceProfileId = true
                                }
                            }

                            "sender-callsign" -> {
                                if (cotTypeStr == "y-") {
                                    hasRoomData = true
                                    inRoomSender = true
                                }
                            }

                            "remarks" -> {
                                inRemarks = true
                            }

                            "chatroom-name" -> {
                                if (cotTypeStr == "y-") {
                                    hasRoomData = true
                                    inRoomName = true
                                }
                            }

                            "chatroom-participants" -> {
                                if (cotTypeStr == "y-") {
                                    hasRoomData = true
                                    inRoomParticipants = true
                                }
                            }

                            "marti" -> {
                                inMarti = true
                            }

                            "dest" -> {
                                if (inMarti) {
                                    reader
                                        .getAttributeValue(null, "callsign")
                                        ?.takeIf { it.isNotEmpty() }
                                        ?.let { martiDests.add(it) }
                                }
                            }

                            "shape" -> {
                                hasShapeData = true
                                inShape = true
                            }

                            "ellipse" -> {
                                if (inShape) {
                                    val majorM = reader.getAttributeValue(null, "major")?.toDoubleOrNull() ?: 0.0
                                    val minorM = reader.getAttributeValue(null, "minor")?.toDoubleOrNull() ?: 0.0
                                    shapeMajorCm = (majorM * 100).roundToInt().coerceAtLeast(0)
                                    shapeMinorCm = (minorM * 100).roundToInt().coerceAtLeast(0)
                                    shapeAngleDeg = reader.getAttributeValue(null, "angle")?.toIntOrNull() ?: 360
                                    hasShapeData = true
                                }
                            }

                            "strokeColor" -> {
                                sawStrokeColor = true
                                strokeColorArgb = reader.getAttributeValue(null, "value")?.toIntOrNull() ?: 0
                                hasShapeData = true
                            }

                            "strokeWeight" -> {
                                val w = reader.getAttributeValue(null, "value")?.toDoubleOrNull() ?: 0.0
                                strokeWeightX10 = (w * 10).roundToInt().coerceAtLeast(0)
                                hasShapeData = true
                            }

                            "fillColor" -> {
                                sawFillColor = true
                                fillColorArgb = reader.getAttributeValue(null, "value")?.toIntOrNull() ?: 0
                                hasShapeData = true
                            }

                            "labels_on" -> {
                                labelsOn = reader.getAttributeValue(null, "value") == "true"
                            }

                            "color" -> {
                                markerColorArgb = reader.getAttributeValue(null, "argb")?.toIntOrNull() ?: 0
                                hasMarkerData = true
                            }

                            "usericon" -> {
                                markerIconset = reader.getAttributeValue(null, "iconsetpath") ?: ""
                                if (markerIconset.isNotEmpty()) hasMarkerData = true
                            }

                            "bullseye" -> {
                                hasBullseyeData = true
                                hasShapeData = true
                                val distStr = reader.getAttributeValue(null, "distance")
                                bullseyeDistanceDm = ((distStr?.toDoubleOrNull() ?: 0.0) * 10).roundToInt().coerceAtLeast(0)
                                bullseyeBearingRef = bearingRefMap[reader.getAttributeValue(null, "bearingRef")] ?: 0
                                var flags = 0
                                if (reader.getAttributeValue(null, "rangeRingVisible") == "true") flags = flags or 0x01
                                if (reader.getAttributeValue(null, "hasRangeRings") == "true") flags = flags or 0x02
                                if (reader.getAttributeValue(null, "edgeToCenter") == "true") flags = flags or 0x04
                                if (reader.getAttributeValue(null, "mils") == "true") flags = flags or 0x08
                                bullseyeFlags = flags
                                bullseyeUidRef = reader.getAttributeValue(null, "bullseyeUID") ?: ""
                            }

                            "range" -> {
                                val v = reader.getAttributeValue(null, "value")?.toDoubleOrNull() ?: 0.0
                                rabRangeCm = (v * 100).roundToInt().coerceAtLeast(0)
                                hasRabData = true
                            }

                            "bearing" -> {
                                val v = reader.getAttributeValue(null, "value")?.toDoubleOrNull() ?: 0.0
                                rabBearingCdeg = (v * 100).roundToInt().coerceAtLeast(0)
                                hasRabData = true
                            }

                            "__routeinfo" -> {
                                hasRouteData = true
                            }

                            "link_attr" -> {
                                hasRouteData = true
                                routePrefix = reader.getAttributeValue(null, "prefix") ?: ""
                                routeMethod = routeMethodMap[reader.getAttributeValue(null, "method")] ?: 0
                                routeDirection = routeDirectionMap[reader.getAttributeValue(null, "direction")] ?: 0
                                val sw = reader.getAttributeValue(null, "stroke")?.toIntOrNull() ?: 0
                                if (sw > 0) strokeWeightX10 = sw * 10
                            }

                            "_medevac_" -> {
                                hasCasevacData = true
                                casevacPrecedence = parseCasevacEnum(reader.getAttributeValue(null, "precedence"), precedenceMap, 5)
                                var eq = 0
                                if (reader.getAttributeValue(null, "hoist") == "true") eq = eq or 0x02
                                if (reader.getAttributeValue(null, "extraction_equipment") == "true") eq = eq or 0x04
                                if (reader.getAttributeValue(null, "ventilator") == "true") eq = eq or 0x08
                                if (reader.getAttributeValue(null, "blood") == "true") eq = eq or 0x10
                                if (reader.getAttributeValue(null, "none") == "true") eq = eq or 0x01
                                if (reader.getAttributeValue(null, "equipment_other") == "true") eq = eq or 0x20
                                casevacEquipmentFlags = eq
                                casevacLitterPatients =
                                    reader.getAttributeValue(null, "litter")?.toIntOrNull() ?: 0
                                casevacAmbulatoryPatients =
                                    reader.getAttributeValue(null, "ambulatory")?.toIntOrNull() ?: 0
                                casevacSecurity = parseCasevacEnum(reader.getAttributeValue(null, "security"), securityMap, 4)
                                casevacHlzMarking = parseCasevacEnum(reader.getAttributeValue(null, "hlz_marking"), hlzMarkingMap, 5)
                                casevacZoneMarker = reader.getAttributeValue(null, "zone_prot_marker") ?: ""
                                casevacUsMilitary = reader.getAttributeValue(null, "us_military")?.toIntOrNull() ?: 0
                                casevacUsCivilian = reader.getAttributeValue(null, "us_civilian")?.toIntOrNull() ?: 0
                                casevacNonUsMilitary = (
                                    reader.getAttributeValue(null, "non_us_military")
                                        ?: reader.getAttributeValue(null, "nonus_military")
                                )?.toIntOrNull() ?: 0
                                casevacNonUsCivilian = (
                                    reader.getAttributeValue(null, "non_us_civilian")
                                        ?: reader.getAttributeValue(null, "nonus_civilian")
                                )?.toIntOrNull() ?: 0
                                casevacEpw = reader.getAttributeValue(null, "epw")?.toIntOrNull() ?: 0
                                casevacChild = reader.getAttributeValue(null, "child")?.toIntOrNull() ?: 0
                                var tf = 0
                                if (reader.getAttributeValue(null, "terrain_slope") == "true") tf = tf or 0x01
                                if (reader.getAttributeValue(null, "terrain_rough") == "true") tf = tf or 0x02
                                if (reader.getAttributeValue(null, "terrain_loose") == "true") tf = tf or 0x04
                                if (reader.getAttributeValue(null, "terrain_trees") == "true") tf = tf or 0x08
                                if (reader.getAttributeValue(null, "terrain_wires") == "true") tf = tf or 0x10
                                if (reader.getAttributeValue(null, "terrain_other") == "true") tf = tf or 0x20
                                casevacTerrainFlags = tf
                                casevacFrequency = reader.getAttributeValue(null, "freq") ?: ""
                                casevacTitle = reader.getAttributeValue(null, "title") ?: ""
                                casevacMedlineRemarks = reader.getAttributeValue(null, "medline_remarks") ?: ""
                                casevacUrgentCount = reader.getAttributeValue(null, "urgent")?.toIntOrNull() ?: 0
                                casevacUrgentSurgicalCount = reader.getAttributeValue(null, "urgent_surgical")?.toIntOrNull() ?: 0
                                casevacPriorityCount = reader.getAttributeValue(null, "priority")?.toIntOrNull() ?: 0
                                casevacRoutineCount = reader.getAttributeValue(null, "routine")?.toIntOrNull() ?: 0
                                casevacConvenienceCount = reader.getAttributeValue(null, "convenience")?.toIntOrNull() ?: 0
                                casevacEquipmentDetail = reader.getAttributeValue(null, "equipment_detail") ?: ""
                                casevacZoneProtectedCoord = reader.getAttributeValue(null, "zone_protected_coord") ?: ""
                                casevacTerrainSlopeDir = reader.getAttributeValue(null, "terrain_slope_dir") ?: ""
                                casevacTerrainOtherDetail = reader.getAttributeValue(null, "terrain_other_detail") ?: ""
                                casevacMarkedBy = reader.getAttributeValue(null, "marked_by") ?: ""
                                casevacObstacles = reader.getAttributeValue(null, "obstacles") ?: ""
                                casevacWindsAreFrom = reader.getAttributeValue(null, "winds_are_from") ?: ""
                                casevacFriendlies = reader.getAttributeValue(null, "friendlies") ?: ""
                                casevacEnemy = reader.getAttributeValue(null, "enemy") ?: ""
                                casevacHlzRemarks = reader.getAttributeValue(null, "hlz_remarks") ?: ""
                            }

                            "zMist" -> {
                                if (hasCasevacData) {
                                    casevacZmistEntries.add(
                                        TakPacketV2Data.Payload.CasevacReport.ZMistEntry(
                                            title = reader.getAttributeValue(null, "title") ?: "",
                                            z = reader.getAttributeValue(null, "z") ?: "",
                                            m = reader.getAttributeValue(null, "m") ?: "",
                                            i = reader.getAttributeValue(null, "i") ?: "",
                                            s = reader.getAttributeValue(null, "s") ?: "",
                                            t = reader.getAttributeValue(null, "t") ?: "",
                                        ),
                                    )
                                }
                            }

                            "emergency" -> {
                                hasEmergencyData = true
                                val typeAttr = reader.getAttributeValue(null, "type") ?: ""
                                emergencyTypeInt = emergencyTypeMap[typeAttr]
                                    ?: emergencyTypeFromCotType(cotTypeStr)
                                if (reader.getAttributeValue(null, "cancel") == "true") {
                                    emergencyTypeInt = EMERGENCY_TYPE_CANCEL
                                }
                            }

                            "task", "_task_" -> {
                                hasTaskData = true
                                taskTypeTag = reader.getAttributeValue(null, "type") ?: ""
                                taskPriority = taskPriorityMap[reader.getAttributeValue(null, "priority") ?: ""] ?: 0
                                taskStatus = taskStatusMap[reader.getAttributeValue(null, "status") ?: ""] ?: 0
                                val noteAttr = reader.getAttributeValue(null, "note") ?: ""
                                if (noteAttr.isNotEmpty()) taskNote = noteAttr
                                val assigneeAttr = reader.getAttributeValue(null, "assignee") ?: ""
                                if (assigneeAttr.isNotEmpty()) taskAssigneeUid = assigneeAttr
                            }

                            "environment" -> {
                                val temp = reader.getAttributeValue(null, "temperature")?.toDoubleOrNull()
                                val windDir = reader.getAttributeValue(null, "windDirection")?.toIntOrNull()
                                val windSpeed = reader.getAttributeValue(null, "windSpeed")?.toDoubleOrNull()
                                if (temp != null || windDir != null || windSpeed != null) {
                                    environmentData =
                                        TakPacketV2Data.EnvironmentData(
                                            temperatureCelsius = temp,
                                            windDirectionDeg = windDir,
                                            windSpeedMetersPerSec = windSpeed,
                                        )
                                }
                            }

                            "sensor" -> {
                                val model = reader.getAttributeValue(null, "model")
                                sensorFovData =
                                    TakPacketV2Data.SensorFovData(
                                        type = inferSensorType(model),
                                        azimuthDeg =
                                            reader
                                                .getAttributeValue(null, "azimuth")
                                                ?.toDoubleOrNull()
                                                ?.roundToInt() ?: 270,
                                        rangeMeters =
                                            reader
                                                .getAttributeValue(null, "range")
                                                ?.toDoubleOrNull()
                                                ?.roundToInt(),
                                        fovHorizontalDeg =
                                            reader
                                                .getAttributeValue(null, "fov")
                                                ?.toDoubleOrNull()
                                                ?.roundToInt() ?: 45,
                                        fovVerticalDeg =
                                            reader
                                                .getAttributeValue(null, "vfov")
                                                ?.toDoubleOrNull()
                                                ?.roundToInt(),
                                        elevationDeg =
                                            reader
                                                .getAttributeValue(null, "elevation")
                                                ?.toDoubleOrNull()
                                                ?.roundToInt() ?: 0,
                                        rollDeg =
                                            reader
                                                .getAttributeValue(null, "roll")
                                                ?.toDoubleOrNull()
                                                ?.roundToInt(),
                                        model = model,
                                    )
                            }

                            "link" -> {
                                val linkUidAttr = reader.getAttributeValue(null, "uid")
                                val pointAttr = reader.getAttributeValue(null, "point")
                                val linkType = reader.getAttributeValue(null, "type") ?: ""
                                val relation = reader.getAttributeValue(null, "relation") ?: ""
                                val linkCallsign = reader.getAttributeValue(null, "callsign") ?: ""
                                val parentCallsignAttr = reader.getAttributeValue(null, "parent_callsign") ?: ""

                                val isStyleLink =
                                    linkType.startsWith("b-x-KmlStyle") ||
                                        (linkUidAttr != null && linkUidAttr.endsWith(".Style"))

                                if (!isStyleLink && pointAttr != null) {
                                    val parts = pointAttr.split(",")
                                    if (parts.size >= 2) {
                                        val plat = parts[0].trim().toDoubleOrNull() ?: 0.0
                                        val plon = parts[1].trim().toDoubleOrNull() ?: 0.0
                                        val plati = scaleLatitudeI(plat)
                                        val ploni = scaleLongitudeI(plon)

                                        when {
                                            cotTypeStr == "u-rb-a" -> {
                                                if (rabAnchorLatI == 0 && rabAnchorLonI == 0) {
                                                    rabAnchorLatI = plati
                                                    rabAnchorLonI = ploni
                                                    if (linkUidAttr != null) rabAnchorUid = linkUidAttr
                                                }
                                                hasRabData = true
                                            }

                                            (linkType == "b-m-p-w" || linkType == "b-m-p-c") &&
                                                cotTypeStr == "b-m-r" -> {
                                                if (routeLinks.size < MAX_ROUTE_LINKS) {
                                                    routeLinks.add(
                                                        TakPacketV2Data.Payload.Route.Link(
                                                            latI = plati,
                                                            lonI = ploni,
                                                            uid = linkUidAttr ?: "",
                                                            callsign = linkCallsign,
                                                            linkType = if (linkType == "b-m-p-c") 1 else 0,
                                                        ),
                                                    )
                                                } else {
                                                    routeTruncated = true
                                                }
                                                hasRouteData = true
                                            }

                                            else -> {
                                                if (vertices.size < MAX_VERTICES) {
                                                    vertices.add(TakPacketV2Data.Payload.Vertex(plati, ploni))
                                                    hasShapeData = true
                                                } else {
                                                    verticesTruncated = true
                                                }
                                            }
                                        }
                                    }
                                } else if (!isStyleLink && linkUidAttr != null && relation == "p-p" &&
                                    linkType.isNotEmpty()
                                ) {
                                    if (cotTypeStr == "b-t-f-d" || cotTypeStr == "b-t-f-r") {
                                        if (chatReceiptForUid.isEmpty()) {
                                            chatReceiptForUid = linkUidAttr
                                        }
                                        chatReceiptType =
                                            if (cotTypeStr == "b-t-f-d") {
                                                RECEIPT_TYPE_DELIVERED
                                            } else {
                                                RECEIPT_TYPE_READ
                                            }
                                        hasChatData = true
                                    } else if (cotTypeStr == "t-s") {
                                        if (taskTargetUid.isEmpty()) {
                                            taskTargetUid = linkUidAttr
                                        }
                                        hasTaskData = true
                                    } else if (cotTypeStr.startsWith("b-a-")) {
                                        if (linkType.startsWith("b-a-")) {
                                            if (emergencyCancelReferenceUid.isEmpty()) {
                                                emergencyCancelReferenceUid = linkUidAttr
                                            }
                                        } else {
                                            if (emergencyAuthoringUid.isEmpty()) {
                                                emergencyAuthoringUid = linkUidAttr
                                            }
                                        }
                                        hasEmergencyData = true
                                    } else if (
                                        cotTypeStr.startsWith("a-f-G-U-C") ||
                                        cotTypeStr == "t-x-d-d" ||
                                        cotTypeStr == "b-r-f-h-c"
                                    ) {
                                        // Don't set hasMarkerData — these types have their own classification
                                    } else {
                                        markerParentUid = linkUidAttr
                                        markerParentType = linkType
                                        if (parentCallsignAttr.isNotEmpty()) {
                                            markerParentCallsign = parentCallsignAttr
                                        }
                                        hasMarkerData = true
                                    }
                                }
                            }
                        }
                    }

                    EventType.TEXT, EventType.CDSECT -> {
                        // Accumulate raw text fragments for the active element body.
                        // The trimmed result is committed on END_ELEMENT. We only
                        // bother buffering when inside <detail> and an element body
                        // we actually consume (matches the original which only acted
                        // on text while inDetail).
                        if (inDetail) {
                            textBuf.append(reader.text)
                        }
                    }

                    EventType.END_ELEMENT -> {
                        // Commit any accumulated element-body text to the right
                        // destination based on which `in*` flag is active, mirroring
                        // the original TEXT dispatch. Done at END_ELEMENT so multi-
                        // fragment text runs coalesce correctly.
                        if (inDetail) {
                            val text = textBuf.toString().trim()
                            if (text.isNotEmpty()) {
                                when {
                                    inTaktalkText -> {
                                        talkText = text
                                    }

                                    inTaktalkChatroomId -> {
                                        talkChatroomId = text
                                    }

                                    inTaktalkLang -> {
                                        talkLang = text
                                    }

                                    inMttCallsign -> {
                                        callsign = text
                                    }

                                    inChatEa -> {
                                        chatLang = text
                                    }

                                    inChatRoomId -> {
                                        chatRoomId = text
                                    }

                                    inChatVoiceProfileId -> {
                                        chatVoiceProfileId = text
                                    }

                                    inRoomSender -> {
                                        callsign = text
                                    }

                                    inRoomDataId -> {
                                        roomDataId = text
                                    }

                                    inRoomName -> {
                                        roomName = text
                                    }

                                    inRoomParticipants -> {
                                        roomParticipants.addAll(
                                            text
                                                .split(",")
                                                .map { it.trim() }
                                                .filter { it.isNotEmpty() },
                                        )
                                    }

                                    inRemarks -> {
                                        remarksText = text
                                    }
                                }
                            }
                        }
                        textBuf.setLength(0)

                        when (reader.localName) {
                            "shape" -> {
                                inShape = false
                            }

                            "text" -> {
                                inTaktalkText = false
                            }

                            "chatroom-id" -> {
                                inTaktalkChatroomId = false
                                inRoomDataId = false
                            }

                            "lang" -> {
                                inTaktalkLang = false
                            }

                            "callsign" -> {
                                inMttCallsign = false
                            }

                            "Ea" -> {
                                inChatEa = false
                            }

                            "roomId" -> {
                                inChatRoomId = false
                            }

                            "voice_profile_id" -> {
                                inChatVoiceProfileId = false
                            }

                            "sender-callsign" -> {
                                inRoomSender = false
                            }

                            "chatroom-name" -> {
                                inRoomName = false
                            }

                            "chatroom-participants" -> {
                                inRoomParticipants = false
                            }

                            "remarks" -> {
                                inRemarks = false
                            }

                            "marti" -> {
                                inMarti = false
                            }
                        }
                    }

                    else -> { /* ignore PIs, comments, whitespace, doc start/end */ }
                }
            }
        } finally {
            reader.close()
        }

        // Parse ICAO/aircraft data from remarks (always try, remarks may supplement _aircot_ or _radio)
        if (remarksText.isNotEmpty() && icao.isEmpty()) {
            val icaoMatch = AIRCRAFT_ICAO_REGEX.find(remarksText)
            if (icaoMatch != null) {
                hasAircraftData = true
                icao = icaoMatch.groupValues[1]
                if (registration.isEmpty()) registration = AIRCRAFT_REG_REGEX.find(remarksText)?.groupValues?.get(1) ?: ""
                if (flight.isEmpty()) flight = AIRCRAFT_FLIGHT_REGEX.find(remarksText)?.groupValues?.get(1) ?: ""
                if (aircraftType.isEmpty()) aircraftType = AIRCRAFT_TYPE_REGEX.find(remarksText)?.groupValues?.get(1) ?: ""
                val squawkMatch = AIRCRAFT_SQUAWK_REGEX.find(remarksText)
                if (squawk == 0) squawk = squawkMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val catMatch = AIRCRAFT_CATEGORY_REGEX.find(remarksText)
                if (catMatch != null && category.isEmpty()) category = catMatch.groupValues[1]
            }
        }

        // Compute stale seconds
        val staleSeconds = computeStaleSeconds(timeStr, staleStr)

        // Determine CoT type enum
        val cotTypeId = CotTypeMapper.typeToEnum(cotTypeStr)
        val cotTypeStrField = if (cotTypeId == CotTypeMapper.COTTYPE_OTHER) cotTypeStr else null

        val shapeStyle =
            when {
                sawStrokeColor && sawFillColor -> STYLE_STROKE_AND_FILL
                sawStrokeColor -> STYLE_STROKE_ONLY
                sawFillColor -> STYLE_FILL_ONLY
                else -> STYLE_UNSPECIFIED
            }

        if (!hasEmergencyData && cotTypeStr.startsWith("b-a-")) {
            hasEmergencyData = true
            if (emergencyTypeInt == 0) emergencyTypeInt = emergencyTypeFromCotType(cotTypeStr)
        }
        if (!hasCasevacData && cotTypeStr == "b-r-f-h-c") {
            hasCasevacData = true
        }

        val payload =
            when {
                isDeleteEvent -> {
                    TakPacketV2Data.Payload.Pli(true)
                }

                hasRoomData && cotTypeStr == "y-" -> {
                    @Suppress("DEPRECATION")
                    TakPacketV2Data.Payload.TakTalkRoom(
                        roomId = roomDataId,
                        roomName = roomName,
                        participants = roomParticipants.toList(),
                    )
                }

                hasTakTalkData && cotTypeStr == "m-t-t" -> {
                    TakPacketV2Data.Payload.TakTalk(
                        text = talkText,
                        chatroomId = talkChatroomId,
                        lang = talkLang,
                        fromVoice = fromVoice,
                    )
                }

                hasChatData -> {
                    TakPacketV2Data.Payload.Chat(
                        message = remarksText,
                        to = chatTo,
                        toCallsign = chatToCallsign,
                        receiptForUid = chatReceiptForUid,
                        receiptType = chatReceiptType,
                        lang = chatLang,
                        roomId = chatRoomId,
                        voiceProfileId = chatVoiceProfileId,
                        hasVoiceProfile = chatHasVoiceProfile,
                    )
                }

                hasAircraftData -> {
                    TakPacketV2Data.Payload.Aircraft(
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
                }

                hasRouteData && routeLinks.isNotEmpty() -> {
                    TakPacketV2Data.Payload.Route(
                        method = routeMethod,
                        direction = routeDirection,
                        prefix = routePrefix,
                        strokeWeightX10 = strokeWeightX10,
                        links = routeLinks.toList(),
                        truncated = routeTruncated,
                    )
                }

                hasRabData -> {
                    TakPacketV2Data.Payload.RangeAndBearing(
                        anchorLatI = rabAnchorLatI,
                        anchorLonI = rabAnchorLonI,
                        anchorUid = rabAnchorUid,
                        rangeCm = rabRangeCm,
                        bearingCdeg = rabBearingCdeg,
                        strokeColor = AtakPalette.argbToTeam(strokeColorArgb),
                        strokeArgb = strokeColorArgb,
                        strokeWeightX10 = strokeWeightX10,
                    )
                }

                hasShapeData -> {
                    TakPacketV2Data.Payload.DrawnShape(
                        kind = shapeKindFromCotType(cotTypeStr),
                        style = shapeStyle,
                        majorCm = shapeMajorCm,
                        minorCm = shapeMinorCm,
                        angleDeg = shapeAngleDeg,
                        strokeColor = AtakPalette.argbToTeam(strokeColorArgb),
                        strokeArgb = strokeColorArgb,
                        strokeWeightX10 = strokeWeightX10,
                        fillColor = AtakPalette.argbToTeam(fillColorArgb),
                        fillArgb = fillColorArgb,
                        labelsOn = labelsOn,
                        vertices = vertices.toList(),
                        truncated = verticesTruncated,
                        bullseyeDistanceDm = bullseyeDistanceDm,
                        bullseyeBearingRef = bullseyeBearingRef,
                        bullseyeFlags = bullseyeFlags,
                        bullseyeUidRef = bullseyeUidRef,
                    )
                }

                hasMarkerData -> {
                    TakPacketV2Data.Payload.Marker(
                        kind = markerKindFromCotType(cotTypeStr, markerIconset),
                        color = AtakPalette.argbToTeam(markerColorArgb),
                        colorArgb = markerColorArgb,
                        readiness = markerReadiness,
                        parentUid = markerParentUid,
                        parentType = markerParentType,
                        parentCallsign = markerParentCallsign,
                        iconset = markerIconset,
                    )
                }

                hasCasevacData -> {
                    TakPacketV2Data.Payload.CasevacReport(
                        precedence = casevacPrecedence,
                        equipmentFlags = casevacEquipmentFlags,
                        litterPatients = casevacLitterPatients,
                        ambulatoryPatients = casevacAmbulatoryPatients,
                        security = casevacSecurity,
                        hlzMarking = casevacHlzMarking,
                        zoneMarker = casevacZoneMarker,
                        usMilitary = casevacUsMilitary,
                        usCivilian = casevacUsCivilian,
                        nonUsMilitary = casevacNonUsMilitary,
                        nonUsCivilian = casevacNonUsCivilian,
                        epw = casevacEpw,
                        child = casevacChild,
                        terrainFlags = casevacTerrainFlags,
                        frequency = casevacFrequency,
                        title = casevacTitle,
                        medlineRemarks = casevacMedlineRemarks,
                        urgentCount = casevacUrgentCount,
                        urgentSurgicalCount = casevacUrgentSurgicalCount,
                        priorityCount = casevacPriorityCount,
                        routineCount = casevacRoutineCount,
                        convenienceCount = casevacConvenienceCount,
                        equipmentDetail = casevacEquipmentDetail,
                        zoneProtectedCoord = casevacZoneProtectedCoord,
                        terrainSlopeDir = casevacTerrainSlopeDir,
                        terrainOtherDetail = casevacTerrainOtherDetail,
                        markedBy = casevacMarkedBy,
                        obstacles = casevacObstacles,
                        windsAreFrom = casevacWindsAreFrom,
                        friendlies = casevacFriendlies,
                        enemy = casevacEnemy,
                        hlzRemarks = casevacHlzRemarks,
                        zmist = casevacZmistEntries.toList(),
                    )
                }

                hasEmergencyData -> {
                    TakPacketV2Data.Payload.EmergencyAlert(
                        type =
                            if (emergencyTypeInt != 0) {
                                emergencyTypeInt
                            } else {
                                emergencyTypeFromCotType(cotTypeStr)
                            },
                        authoringUid = emergencyAuthoringUid,
                        cancelReferenceUid = emergencyCancelReferenceUid,
                    )
                }

                hasTaskData -> {
                    TakPacketV2Data.Payload.TaskRequest(
                        taskType = taskTypeTag,
                        targetUid = taskTargetUid,
                        assigneeUid = taskAssigneeUid,
                        priority = taskPriority,
                        status = taskStatus,
                        note = taskNote,
                    )
                }

                else -> {
                    TakPacketV2Data.Payload.Pli(true)
                }
            }

        return TakPacketV2Data(
            cotTypeId = cotTypeId,
            cotTypeStr = cotTypeStrField,
            how = CotTypeMapper.howToEnum(howStr),
            callsign = callsign,
            team = teamNameToEnum[teamName] ?: 0,
            role = roleNameToEnum[roleName] ?: 0,
            latitudeI = scaleLatitudeI(lat),
            longitudeI = scaleLongitudeI(lon),
            altitude = hae.roundToInt(),
            speed = maxOf(0, (speed * 100).roundToInt()),
            course = maxOf(0, (course * 100).roundToInt()),
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
            remarks = if (hasChatData) "" else remarksText,
            environment = environmentData,
            sensorFov = sensorFovData,
            marti = martiDests.toList(),
            payload = payload,
        )
    }

    /**
     * Compute stale-seconds from the event `time`/`stale` attributes.
     *
     * Ported from the xpp3 implementation's `java.time` path to
     * `kotlin.time.Instant` (kotlin.time stdlib). `Instant.parse` accepts the
     * ISO-8601 instant strings ATAK emits (`2026-03-15T19:00:00Z`, with or
     * without fractional seconds), matching `DateTimeFormatter.ISO_INSTANT`.
     * Any parse failure returns 0, identical to the original try/catch.
     */
    private fun computeStaleSeconds(
        timeStr: String,
        staleStr: String,
    ): Int {
        if (timeStr.isEmpty() || staleStr.isEmpty()) return 0
        return try {
            val time = Instant.parse(timeStr)
            val stale = Instant.parse(staleStr)
            val diff = stale.epochSeconds - time.epochSeconds
            if (diff > 0) diff.toInt() else 0
        } catch (e: Exception) {
            0
        }
    }

    /**
     * Extract the raw inner bytes of the `<detail>` element, verbatim.
     *
     * The escape hatch for callers that want to ship a `<detail>` block the
     * structured parser doesn't model — the bytes go into
     * [TakPacketV2Data.Payload.RawDetail] and are re-emitted unchanged by
     * [CotXmlBuilder], preserving attribute order and whitespace byte-for-byte.
     * Pure regex (no XML reader), matching the inside of the first
     * `<detail …>…</detail>`.
     *
     * @param cotXml a full CoT XML event string.
     * @return the UTF-8 bytes between the `<detail>` tags, or an empty array if
     *         there is no `<detail>` element.
     */
    public fun extractRawDetailBytes(cotXml: String): ByteArray {
        val match = DETAIL_REGEX.find(cotXml)
        val inner = match?.groupValues?.get(1) ?: return ByteArray(0)
        return inner.encodeToByteArray()
    }

    public companion object {
        /**
         * `GeoPointSource` enum values (position / altitude provenance), mirroring
         * atak.proto. Carried in the `geo_src` / `alt_src` proto fields and the
         * `<precisionlocation geopointsrc=… altsrc=…>` XML attributes.
         */
        public const val GEOSRC_UNSPECIFIED: Int = 0

        /** Position from a GPS fix (`geopointsrc="GPS"`). */
        public const val GEOSRC_GPS: Int = 1

        /** Position entered / placed by the user (`geopointsrc="USER"`). */
        public const val GEOSRC_USER: Int = 2

        /** Position derived from the network (`geopointsrc="NETWORK"`). */
        public const val GEOSRC_NETWORK: Int = 3

        /**
         * Maximum drawn-shape vertices retained per packet. Extra vertices are
         * dropped and the shape's `truncated` flag is set, bounding wire size.
         */
        public const val MAX_VERTICES: Int = 32

        /**
         * Maximum route waypoint/control-point links retained per packet. Extra
         * links are dropped and the route's `truncated` flag is set.
         */
        public const val MAX_ROUTE_LINKS: Int = 16

        /**
         * `DrawnShape.Kind` enum values (mirror atak.proto), set on
         * [TakPacketV2Data.Payload.DrawnShape.kind] from the CoT type string.
         */
        public const val SHAPE_KIND_UNSPECIFIED: Int = 0

        /** Circle drawing (`u-d-c-c`), sized by `majorCm` radius. */
        public const val SHAPE_KIND_CIRCLE: Int = 1

        /** Rectangle drawing (`u-d-r`), vertices as `<link point>` siblings. */
        public const val SHAPE_KIND_RECTANGLE: Int = 2

        /** Freehand drawing (`u-d-f`). */
        public const val SHAPE_KIND_FREEFORM: Int = 3

        /** Freehand telestration drawing (`u-d-f-m`). */
        public const val SHAPE_KIND_TELESTRATION: Int = 4

        /** Closed polygon drawing (`u-d-p`). */
        public const val SHAPE_KIND_POLYGON: Int = 5

        /** Ranging circle (`u-r-b-c-c`). */
        public const val SHAPE_KIND_RANGING_CIRCLE: Int = 6

        /** Bullseye with range rings (`u-r-b-bullseye`); enables the bullseye-only fields. */
        public const val SHAPE_KIND_BULLSEYE: Int = 7

        /** Ellipse drawing (`u-d-c-e`), sized by `majorCm`/`minorCm`/`angleDeg`. */
        public const val SHAPE_KIND_ELLIPSE: Int = 8

        /** 2D vehicle outline drawing (`u-d-v`). */
        public const val SHAPE_KIND_VEHICLE_2D: Int = 9

        /** 3D vehicle model drawing (`u-d-v-m`). */
        public const val SHAPE_KIND_VEHICLE_3D: Int = 10

        /**
         * `DrawnShape.StyleMode` discriminator (mirror atak.proto), set on
         * [TakPacketV2Data.Payload.DrawnShape.style]. Records whether the source
         * carried a stroke color, a fill color, or both.
         */
        public const val STYLE_UNSPECIFIED: Int = 0

        /** Stroke (outline) color only. */
        public const val STYLE_STROKE_ONLY: Int = 1

        /** Fill color only. */
        public const val STYLE_FILL_ONLY: Int = 2

        /** Both stroke and fill colors. */
        public const val STYLE_STROKE_AND_FILL: Int = 3

        /**
         * `Marker.Kind` enum values (mirror atak.proto), set on
         * [TakPacketV2Data.Payload.Marker.kind] from the CoT type / iconset.
         */
        public const val MARKER_KIND_UNSPECIFIED: Int = 0

        /** Spot marker (`b-m-p-s-m`). */
        public const val MARKER_KIND_SPOT: Int = 1

        /** Waypoint (`b-m-p-w`). */
        public const val MARKER_KIND_WAYPOINT: Int = 2

        /** Checkpoint (`b-m-p-c`). */
        public const val MARKER_KIND_CHECKPOINT: Int = 3

        /** Self-position marker (`b-m-p-s-p-i` / `b-m-p-s-p-loc`). */
        public const val MARKER_KIND_SELF_POSITION: Int = 4

        /** 2525 military symbol (iconset `COT_MAPPING_2525B`). */
        public const val MARKER_KIND_SYMBOL_2525: Int = 5

        /** Spot-map marker (iconset `COT_MAPPING_SPOTMAP`). */
        public const val MARKER_KIND_SPOT_MAP: Int = 6

        /** Custom user-icon marker (any other non-empty iconset). */
        public const val MARKER_KIND_CUSTOM_ICON: Int = 7

        /** Go-To / bloodhound point (`b-m-p-w-GOTO`). */
        public const val MARKER_KIND_GO_TO_POINT: Int = 8

        /** Initial point (`b-m-p-c-ip`). */
        public const val MARKER_KIND_INITIAL_POINT: Int = 9

        /** Contact point (`b-m-p-c-cp`). */
        public const val MARKER_KIND_CONTACT_POINT: Int = 10

        /** Observation post (`b-m-p-s-p-op`). */
        public const val MARKER_KIND_OBSERVATION_POST: Int = 11

        /** Quick-Pic image / media marker (`b-i-x-i`). */
        public const val MARKER_KIND_IMAGE_MARKER: Int = 12

        /**
         * `CasevacReport.Precedence` enum (9-line Line-1 patient precedence),
         * mirror atak.proto. ATAK encodes these as letters A–E or full words.
         */
        public const val PRECEDENCE_UNSPECIFIED: Int = 0

        /** Urgent (ATAK "A"). */
        public const val PRECEDENCE_URGENT: Int = 1

        /** Urgent Surgical (ATAK "B"). */
        public const val PRECEDENCE_URGENT_SURGICAL: Int = 2

        /** Priority (ATAK "C"). */
        public const val PRECEDENCE_PRIORITY: Int = 3

        /** Routine (ATAK "D"). */
        public const val PRECEDENCE_ROUTINE: Int = 4

        /** Convenience (ATAK "E"). */
        public const val PRECEDENCE_CONVENIENCE: Int = 5

        /**
         * `CasevacReport.HlzMarking` enum (9-line Line-7 landing-zone marking
         * method), mirror atak.proto.
         */
        public const val HLZ_MARKING_UNSPECIFIED: Int = 0

        /** Marked with panels. */
        public const val HLZ_MARKING_PANELS: Int = 1

        /** Marked with a pyrotechnic signal. */
        public const val HLZ_MARKING_PYRO_SIGNAL: Int = 2

        /** Marked with smoke. */
        public const val HLZ_MARKING_SMOKE: Int = 3

        /** Explicitly no marking. */
        public const val HLZ_MARKING_NONE: Int = 4

        /** Some other marking method (see `markedBy` free-text). */
        public const val HLZ_MARKING_OTHER: Int = 5

        /**
         * `CasevacReport.Security` enum (9-line Line-6 landing-zone security),
         * mirror atak.proto. ATAK encodes these as letters N/P/E/X or full words.
         */
        public const val SECURITY_UNSPECIFIED: Int = 0

        /** No enemy in area (ATAK "N"). */
        public const val SECURITY_NO_ENEMY: Int = 1

        /** Possible enemy in area (ATAK "P"). */
        public const val SECURITY_POSSIBLE_ENEMY: Int = 2

        /** Enemy in area (ATAK "E"). */
        public const val SECURITY_ENEMY_IN_AREA: Int = 3

        /** Enemy in area, armed escort required (ATAK "X"). */
        public const val SECURITY_ENEMY_IN_ARMED_CONTACT: Int = 4

        /**
         * `EmergencyAlert.Type` enum, mirror atak.proto. Set on
         * [TakPacketV2Data.Payload.EmergencyAlert.type] from the `b-a-*` CoT type
         * or the `<emergency type=…>` attribute.
         */
        public const val EMERGENCY_TYPE_UNSPECIFIED: Int = 0

        /** 911 alert (`b-a-o-tbl`). */
        public const val EMERGENCY_TYPE_ALERT_911: Int = 1

        /** Ring-the-bell alert (`b-a-o-pan`). */
        public const val EMERGENCY_TYPE_RING_THE_BELL: Int = 2

        /** Troops-in-contact alert (`b-a-o-opn`). */
        public const val EMERGENCY_TYPE_IN_CONTACT: Int = 3

        /** Geo-fence-breached alert (`b-a-g`). */
        public const val EMERGENCY_TYPE_GEO_FENCE_BREACHED: Int = 4

        /** Custom emergency beacon (`b-a-o-c`). */
        public const val EMERGENCY_TYPE_CUSTOM: Int = 5

        /** Cancel a prior alert (`b-a-o-can`); references the cancelled alert's UID. */
        public const val EMERGENCY_TYPE_CANCEL: Int = 6

        /**
         * `TaskRequest.Priority` enum, mirror atak.proto. Set on
         * [TakPacketV2Data.Payload.TaskRequest.priority].
         */
        public const val TASK_PRIORITY_UNSPECIFIED: Int = 0

        /** Low priority. */
        public const val TASK_PRIORITY_LOW: Int = 1

        /** Normal / medium priority. */
        public const val TASK_PRIORITY_NORMAL: Int = 2

        /** High priority. */
        public const val TASK_PRIORITY_HIGH: Int = 3

        /** Critical priority. */
        public const val TASK_PRIORITY_CRITICAL: Int = 4

        /**
         * `TaskRequest.Status` enum, mirror atak.proto. Set on
         * [TakPacketV2Data.Payload.TaskRequest.status].
         */
        public const val TASK_STATUS_UNSPECIFIED: Int = 0

        /** Task is pending / not yet acknowledged. */
        public const val TASK_STATUS_PENDING: Int = 1

        /** Task acknowledged by the assignee. */
        public const val TASK_STATUS_ACKNOWLEDGED: Int = 2

        /** Task in progress. */
        public const val TASK_STATUS_IN_PROGRESS: Int = 3

        /** Task completed / done. */
        public const val TASK_STATUS_COMPLETED: Int = 4

        /** Task cancelled. */
        public const val TASK_STATUS_CANCELLED: Int = 5

        /**
         * GeoChat receipt kind, set on [TakPacketV2Data.Payload.Chat.receiptType].
         * Distinguishes a regular chat message from a delivery / read receipt.
         */
        public const val RECEIPT_TYPE_NONE: Int = 0

        /** Delivered receipt (`b-t-f-d`). */
        public const val RECEIPT_TYPE_DELIVERED: Int = 1

        /** Read receipt (`b-t-f-r`). */
        public const val RECEIPT_TYPE_READ: Int = 2

        private val routeMethodMap =
            mapOf(
                "Driving" to 1,
                "Walking" to 2,
                "Flying" to 3,
                "Swimming" to 4,
                "Watercraft" to 5,
            )

        private val routeDirectionMap =
            mapOf(
                "Infil" to 1,
                "Exfil" to 2,
            )

        private val bearingRefMap = mapOf("M" to 1, "T" to 2, "G" to 3)

        private val teamNameToEnum =
            mapOf(
                "White" to 1,
                "Yellow" to 2,
                "Orange" to 3,
                "Magenta" to 4,
                "Red" to 5,
                "Maroon" to 6,
                "Purple" to 7,
                "Dark Blue" to 8,
                "Blue" to 9,
                "Cyan" to 10,
                "Teal" to 11,
                "Green" to 12,
                "Dark Green" to 13,
                "Brown" to 14,
            )

        private val roleNameToEnum =
            mapOf(
                "Team Member" to 1,
                "Team Lead" to 2,
                "HQ" to 3,
                "Sniper" to 4,
                "Medic" to 5,
                "ForwardObserver" to 6,
                "RTO" to 7,
                "K9" to 8,
            )

        private fun parseGeoSrc(src: String?): Int =
            when (src) {
                "GPS" -> GEOSRC_GPS
                "USER" -> GEOSRC_USER
                "NETWORK" -> GEOSRC_NETWORK
                else -> GEOSRC_UNSPECIFIED
            }

        // Inner bytes of the `<detail>` element. `([\s\S]*?)` matches any char
        // INCLUDING newlines on every Kotlin target; RegexOption.DOT_MATCHES_ALL
        // is JVM/Native-only (absent on Kotlin/JS + Wasm), so "dot-all" is encoded
        // in the pattern itself. Hoisted to a companion val so it compiles once.
        private val DETAIL_REGEX =
            Regex(
                """<detail\b[^>]*>([\s\S]*?)</detail\s*>""",
                RegexOption.IGNORE_CASE,
            )
        private val AIRCRAFT_ICAO_REGEX = Regex("""ICAO:\s*([A-Fa-f0-9]{6})""")
        private val AIRCRAFT_REG_REGEX = Regex("""REG:\s*(\S+)""")
        private val AIRCRAFT_FLIGHT_REGEX = Regex("""Flight:\s*(\S+)""")
        private val AIRCRAFT_TYPE_REGEX = Regex("""Type:\s*(\S+)""")
        private val AIRCRAFT_SQUAWK_REGEX = Regex("""Squawk:\s*(\d+)""")
        private val AIRCRAFT_CATEGORY_REGEX = Regex("""Category:\s*(\S+)""")

        private fun scaleLatitudeI(lat: Double): Int = (lat.coerceIn(-90.0, 90.0) * 1e7).roundToInt()

        private fun scaleLongitudeI(lon: Double): Int = (lon.coerceIn(-180.0, 180.0) * 1e7).roundToInt()

        private fun shapeKindFromCotType(t: String): Int =
            when (t) {
                "u-d-c-c" -> SHAPE_KIND_CIRCLE
                "u-d-r" -> SHAPE_KIND_RECTANGLE
                "u-d-f" -> SHAPE_KIND_FREEFORM
                "u-d-f-m" -> SHAPE_KIND_TELESTRATION
                "u-d-p" -> SHAPE_KIND_POLYGON
                "u-r-b-c-c" -> SHAPE_KIND_RANGING_CIRCLE
                "u-r-b-bullseye" -> SHAPE_KIND_BULLSEYE
                "u-d-c-e" -> SHAPE_KIND_ELLIPSE
                "u-d-v" -> SHAPE_KIND_VEHICLE_2D
                "u-d-v-m" -> SHAPE_KIND_VEHICLE_3D
                else -> SHAPE_KIND_UNSPECIFIED
            }

        private fun markerKindFromCotType(
            cotType: String,
            iconset: String,
        ): Int =
            when (cotType) {
                "b-m-p-s-m" -> {
                    MARKER_KIND_SPOT
                }

                "b-m-p-w" -> {
                    MARKER_KIND_WAYPOINT
                }

                "b-m-p-c" -> {
                    MARKER_KIND_CHECKPOINT
                }

                "b-m-p-s-p-i", "b-m-p-s-p-loc" -> {
                    MARKER_KIND_SELF_POSITION
                }

                "b-m-p-w-GOTO" -> {
                    MARKER_KIND_GO_TO_POINT
                }

                "b-m-p-c-ip" -> {
                    MARKER_KIND_INITIAL_POINT
                }

                "b-m-p-c-cp" -> {
                    MARKER_KIND_CONTACT_POINT
                }

                "b-m-p-s-p-op" -> {
                    MARKER_KIND_OBSERVATION_POST
                }

                "b-i-x-i" -> {
                    MARKER_KIND_IMAGE_MARKER
                }

                else -> {
                    when {
                        iconset.startsWith("COT_MAPPING_2525B") -> MARKER_KIND_SYMBOL_2525
                        iconset.startsWith("COT_MAPPING_SPOTMAP") -> MARKER_KIND_SPOT_MAP
                        iconset.isNotEmpty() -> MARKER_KIND_CUSTOM_ICON
                        else -> MARKER_KIND_UNSPECIFIED
                    }
                }
            }

        private val precedenceMap =
            mapOf(
                "A" to PRECEDENCE_URGENT,
                "URGENT" to PRECEDENCE_URGENT,
                "Urgent" to PRECEDENCE_URGENT,
                "B" to PRECEDENCE_URGENT_SURGICAL,
                "URGENT SURGICAL" to PRECEDENCE_URGENT_SURGICAL,
                "Urgent Surgical" to PRECEDENCE_URGENT_SURGICAL,
                "C" to PRECEDENCE_PRIORITY,
                "PRIORITY" to PRECEDENCE_PRIORITY,
                "Priority" to PRECEDENCE_PRIORITY,
                "D" to PRECEDENCE_ROUTINE,
                "ROUTINE" to PRECEDENCE_ROUTINE,
                "Routine" to PRECEDENCE_ROUTINE,
                "E" to PRECEDENCE_CONVENIENCE,
                "CONVENIENCE" to PRECEDENCE_CONVENIENCE,
                "Convenience" to PRECEDENCE_CONVENIENCE,
            )

        private val hlzMarkingMap =
            mapOf(
                "Panels" to HLZ_MARKING_PANELS,
                "Pyro" to HLZ_MARKING_PYRO_SIGNAL,
                "Pyrotechnic" to HLZ_MARKING_PYRO_SIGNAL,
                "Smoke" to HLZ_MARKING_SMOKE,
                "None" to HLZ_MARKING_NONE,
                "Other" to HLZ_MARKING_OTHER,
            )

        private val securityMap =
            mapOf(
                "N" to SECURITY_NO_ENEMY,
                "No Enemy" to SECURITY_NO_ENEMY,
                "P" to SECURITY_POSSIBLE_ENEMY,
                "Possible Enemy" to SECURITY_POSSIBLE_ENEMY,
                "E" to SECURITY_ENEMY_IN_AREA,
                "Enemy In Area" to SECURITY_ENEMY_IN_AREA,
                "X" to SECURITY_ENEMY_IN_ARMED_CONTACT,
                "Enemy In Armed Contact" to SECURITY_ENEMY_IN_ARMED_CONTACT,
            )

        private fun parseCasevacEnum(
            raw: String?,
            nameMap: Map<String, Int>,
            maxValue: Int,
        ): Int {
            if (raw == null || raw.isEmpty()) return 0
            nameMap[raw]?.let { return it }
            val asInt = raw.toIntOrNull() ?: return 0
            return if (asInt in 0..maxValue) asInt else 0
        }

        private val emergencyTypeMap =
            mapOf(
                "911 Alert" to EMERGENCY_TYPE_ALERT_911,
                "911" to EMERGENCY_TYPE_ALERT_911,
                "Ring The Bell" to EMERGENCY_TYPE_RING_THE_BELL,
                "Ring the Bell" to EMERGENCY_TYPE_RING_THE_BELL,
                "In Contact" to EMERGENCY_TYPE_IN_CONTACT,
                "Troops In Contact" to EMERGENCY_TYPE_IN_CONTACT,
                "Geo-fence Breached" to EMERGENCY_TYPE_GEO_FENCE_BREACHED,
                "Geo Fence Breached" to EMERGENCY_TYPE_GEO_FENCE_BREACHED,
                "Custom" to EMERGENCY_TYPE_CUSTOM,
                "Cancel" to EMERGENCY_TYPE_CANCEL,
            )

        private fun emergencyTypeFromCotType(cotType: String): Int =
            when (cotType) {
                "b-a-o-tbl" -> EMERGENCY_TYPE_ALERT_911
                "b-a-o-pan" -> EMERGENCY_TYPE_RING_THE_BELL
                "b-a-o-opn" -> EMERGENCY_TYPE_IN_CONTACT
                "b-a-g" -> EMERGENCY_TYPE_GEO_FENCE_BREACHED
                "b-a-o-c" -> EMERGENCY_TYPE_CUSTOM
                "b-a-o-can" -> EMERGENCY_TYPE_CANCEL
                else -> EMERGENCY_TYPE_UNSPECIFIED
            }

        private val taskPriorityMap =
            mapOf(
                "Low" to TASK_PRIORITY_LOW,
                "Normal" to TASK_PRIORITY_NORMAL,
                "Medium" to TASK_PRIORITY_NORMAL,
                "High" to TASK_PRIORITY_HIGH,
                "Critical" to TASK_PRIORITY_CRITICAL,
            )

        private val taskStatusMap =
            mapOf(
                "Pending" to TASK_STATUS_PENDING,
                "Acknowledged" to TASK_STATUS_ACKNOWLEDGED,
                "InProgress" to TASK_STATUS_IN_PROGRESS,
                "In Progress" to TASK_STATUS_IN_PROGRESS,
                "Completed" to TASK_STATUS_COMPLETED,
                "Done" to TASK_STATUS_COMPLETED,
                "Cancelled" to TASK_STATUS_CANCELLED,
                "Canceled" to TASK_STATUS_CANCELLED,
            )

        internal fun inferSensorType(model: String?): TakPacketV2Data.SensorFovData.SensorType {
            if (model.isNullOrEmpty()) return TakPacketV2Data.SensorFovData.SensorType.Unspecified
            val m = model.lowercase()
            return when {
                "flir" in m || "thermal" in m || "boson" in m -> {
                    TakPacketV2Data.SensorFovData.SensorType.Thermal
                }

                "laser" in m || "lrf" in m -> {
                    TakPacketV2Data.SensorFovData.SensorType.Laser
                }

                "nvg" in m || "night" in m -> {
                    TakPacketV2Data.SensorFovData.SensorType.Nvg
                }

                "radar" in m || "rf-" in m -> {
                    TakPacketV2Data.SensorFovData.SensorType.Rf
                }

                "cam" in m || "eo-" in m || "optic" in m -> {
                    TakPacketV2Data.SensorFovData.SensorType.Camera
                }

                else -> {
                    TakPacketV2Data.SensorFovData.SensorType.Other
                }
            }
        }
    }
}
