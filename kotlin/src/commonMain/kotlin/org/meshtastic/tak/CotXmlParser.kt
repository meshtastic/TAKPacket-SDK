package org.meshtastic.tak

import kotlinx.datetime.Instant
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.xmlStreaming
import kotlin.math.roundToInt

/**
 * Parses a CoT XML event string into a [TakPacketV2Data].
 *
 * Uses xmlutil's streaming XmlReader API (KMP-compatible) for cross-platform
 * XML parsing. Extracts all fields supported by the TAKPacketV2 protobuf schema,
 * including event envelope (type, how, uid, time/stale), point (position), and
 * detail sub-elements (contact, group, status, track, takv, precisionlocation,
 * remarks, aircot, radio, link, chat, shape, bullseye, route, marker).
 */
class CotXmlParser {

    companion object {
        // GeoPointSource enum values
        const val GEOSRC_UNSPECIFIED = 0
        const val GEOSRC_GPS = 1
        const val GEOSRC_USER = 2
        const val GEOSRC_NETWORK = 3

        /**
         * Maximum number of vertices kept in a [TakPacketV2Data.Payload.DrawnShape].
         * Matches `*DrawnShape.vertices max_count:32` in atak.options. Longer
         * vertex lists are silently truncated and `truncated = true` is set.
         */
        const val MAX_VERTICES = 32

        /**
         * Maximum number of links kept in a [TakPacketV2Data.Payload.Route].
         * Matches `*Route.links max_count:16` in atak.options. Longer routes
         * are truncated and `truncated = true` is set.
         */
        const val MAX_ROUTE_LINKS = 16

        /**
         * DrawnShape.Kind enum values (mirrors the proto-generated
         * `DrawnShape.Kind_*` constants). Used when deriving shape kind from
         * the CoT type string at parse time.
         */
        const val SHAPE_KIND_UNSPECIFIED = 0
        const val SHAPE_KIND_CIRCLE = 1
        const val SHAPE_KIND_RECTANGLE = 2
        const val SHAPE_KIND_FREEFORM = 3
        const val SHAPE_KIND_TELESTRATION = 4
        const val SHAPE_KIND_POLYGON = 5
        const val SHAPE_KIND_RANGING_CIRCLE = 6
        const val SHAPE_KIND_BULLSEYE = 7
        const val SHAPE_KIND_ELLIPSE = 8
        const val SHAPE_KIND_VEHICLE_2D = 9
        const val SHAPE_KIND_VEHICLE_3D = 10

        /** DrawnShape.StyleMode values (mirrors proto `StyleMode_*`). */
        const val STYLE_UNSPECIFIED = 0
        const val STYLE_STROKE_ONLY = 1
        const val STYLE_FILL_ONLY = 2
        const val STYLE_STROKE_AND_FILL = 3

        /** Marker.Kind values (mirrors proto `Marker.Kind_*`). */
        const val MARKER_KIND_UNSPECIFIED = 0
        const val MARKER_KIND_SPOT = 1
        const val MARKER_KIND_WAYPOINT = 2
        const val MARKER_KIND_CHECKPOINT = 3
        const val MARKER_KIND_SELF_POSITION = 4
        const val MARKER_KIND_SYMBOL_2525 = 5
        const val MARKER_KIND_SPOT_MAP = 6
        const val MARKER_KIND_CUSTOM_ICON = 7
        const val MARKER_KIND_GO_TO_POINT = 8
        const val MARKER_KIND_INITIAL_POINT = 9
        const val MARKER_KIND_CONTACT_POINT = 10
        const val MARKER_KIND_OBSERVATION_POST = 11
        const val MARKER_KIND_IMAGE_MARKER = 12

        /** CasevacReport.Precedence enum values. */
        const val PRECEDENCE_UNSPECIFIED = 0
        const val PRECEDENCE_URGENT = 1
        const val PRECEDENCE_URGENT_SURGICAL = 2
        const val PRECEDENCE_PRIORITY = 3
        const val PRECEDENCE_ROUTINE = 4
        const val PRECEDENCE_CONVENIENCE = 5

        /** CasevacReport.HlzMarking enum values. */
        const val HLZ_MARKING_UNSPECIFIED = 0
        const val HLZ_MARKING_PANELS = 1
        const val HLZ_MARKING_PYRO_SIGNAL = 2
        const val HLZ_MARKING_SMOKE = 3
        const val HLZ_MARKING_NONE = 4
        const val HLZ_MARKING_OTHER = 5

        /** CasevacReport.Security enum values. */
        const val SECURITY_UNSPECIFIED = 0
        const val SECURITY_NO_ENEMY = 1
        const val SECURITY_POSSIBLE_ENEMY = 2
        const val SECURITY_ENEMY_IN_AREA = 3
        const val SECURITY_ENEMY_IN_ARMED_CONTACT = 4

        /** EmergencyAlert.Type enum values. */
        const val EMERGENCY_TYPE_UNSPECIFIED = 0
        const val EMERGENCY_TYPE_ALERT_911 = 1
        const val EMERGENCY_TYPE_RING_THE_BELL = 2
        const val EMERGENCY_TYPE_IN_CONTACT = 3
        const val EMERGENCY_TYPE_GEO_FENCE_BREACHED = 4
        const val EMERGENCY_TYPE_CUSTOM = 5
        const val EMERGENCY_TYPE_CANCEL = 6

        /** TaskRequest.Priority enum values. */
        const val TASK_PRIORITY_UNSPECIFIED = 0
        const val TASK_PRIORITY_LOW = 1
        const val TASK_PRIORITY_NORMAL = 2
        const val TASK_PRIORITY_HIGH = 3
        const val TASK_PRIORITY_CRITICAL = 4

        /** TaskRequest.Status enum values. */
        const val TASK_STATUS_UNSPECIFIED = 0
        const val TASK_STATUS_PENDING = 1
        const val TASK_STATUS_ACKNOWLEDGED = 2
        const val TASK_STATUS_IN_PROGRESS = 3
        const val TASK_STATUS_COMPLETED = 4
        const val TASK_STATUS_CANCELLED = 5

        /** GeoChat.ReceiptType enum values. */
        const val RECEIPT_TYPE_NONE = 0
        const val RECEIPT_TYPE_DELIVERED = 1
        const val RECEIPT_TYPE_READ = 2

        /** Route.Method values (mirrors proto). */
        private val routeMethodMap = mapOf(
            "Driving" to 1, "Walking" to 2, "Flying" to 3,
            "Swimming" to 4, "Watercraft" to 5,
        )

        /** Route.Direction values (mirrors proto). */
        private val routeDirectionMap = mapOf(
            "Infil" to 1, "Exfil" to 2,
        )

        /** Bullseye bearing reference attribute → enum tag. */
        private val bearingRefMap = mapOf("M" to 1, "T" to 2, "G" to 3)

        /** CasevacReport.Precedence string → enum int. ATAK writes these as letter codes. */
        private val precedenceMap = mapOf(
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

        /** CasevacReport.HlzMarking string → enum int. */
        private val hlzMarkingMap = mapOf(
            "Panels" to HLZ_MARKING_PANELS,
            "Pyro" to HLZ_MARKING_PYRO_SIGNAL,
            "Pyrotechnic" to HLZ_MARKING_PYRO_SIGNAL,
            "Smoke" to HLZ_MARKING_SMOKE,
            "None" to HLZ_MARKING_NONE,
            "Other" to HLZ_MARKING_OTHER,
        )

        /** CasevacReport.Security string → enum int. */
        private val securityMap = mapOf(
            "N" to SECURITY_NO_ENEMY,
            "No Enemy" to SECURITY_NO_ENEMY,
            "P" to SECURITY_POSSIBLE_ENEMY,
            "Possible Enemy" to SECURITY_POSSIBLE_ENEMY,
            "E" to SECURITY_ENEMY_IN_AREA,
            "Enemy In Area" to SECURITY_ENEMY_IN_AREA,
            "X" to SECURITY_ENEMY_IN_ARMED_CONTACT,
            "Enemy In Armed Contact" to SECURITY_ENEMY_IN_ARMED_CONTACT,
        )

        /** EmergencyAlert.Type string → enum int. Covers both ATAK and user-typed variants. */
        private val emergencyTypeMap = mapOf(
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

        /** EmergencyAlert.Type derived from CoT type atom — fallback when <emergency type> is missing. */
        private fun emergencyTypeFromCotType(cotType: String): Int = when (cotType) {
            "b-a-o-tbl" -> EMERGENCY_TYPE_ALERT_911
            "b-a-o-pan" -> EMERGENCY_TYPE_RING_THE_BELL
            "b-a-o-opn" -> EMERGENCY_TYPE_IN_CONTACT
            "b-a-g" -> EMERGENCY_TYPE_GEO_FENCE_BREACHED
            "b-a-o-c" -> EMERGENCY_TYPE_CUSTOM
            "b-a-o-can" -> EMERGENCY_TYPE_CANCEL
            else -> EMERGENCY_TYPE_UNSPECIFIED
        }

        /** TaskRequest.Priority string → enum int. */
        private val taskPriorityMap = mapOf(
            "Low" to TASK_PRIORITY_LOW,
            "Normal" to TASK_PRIORITY_NORMAL,
            "Medium" to TASK_PRIORITY_NORMAL,
            "High" to TASK_PRIORITY_HIGH,
            "Critical" to TASK_PRIORITY_CRITICAL,
        )

        /** TaskRequest.Status string → enum int. */
        private val taskStatusMap = mapOf(
            "Pending" to TASK_STATUS_PENDING,
            "Acknowledged" to TASK_STATUS_ACKNOWLEDGED,
            "InProgress" to TASK_STATUS_IN_PROGRESS,
            "In Progress" to TASK_STATUS_IN_PROGRESS,
            "Completed" to TASK_STATUS_COMPLETED,
            "Done" to TASK_STATUS_COMPLETED,
            "Cancelled" to TASK_STATUS_CANCELLED,
            "Canceled" to TASK_STATUS_CANCELLED,
        )

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

        /**
         * Derive a DrawnShape.Kind from a CoT type string. Returns
         * [SHAPE_KIND_UNSPECIFIED] for non-shape types.
         */
        private fun shapeKindFromCotType(t: String): Int = when (t) {
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

        /**
         * Derive a Marker.Kind from CoT type + iconset path. When the CoT
         * type alone is ambiguous (`a-u-G` could be a 2525 symbol OR a custom
         * icon), the iconset path disambiguates.
         */
        private fun markerKindFromCotType(cotType: String, iconset: String): Int = when (cotType) {
            "b-m-p-s-m" -> MARKER_KIND_SPOT
            "b-m-p-w" -> MARKER_KIND_WAYPOINT
            "b-m-p-c" -> MARKER_KIND_CHECKPOINT
            "b-m-p-s-p-i", "b-m-p-s-p-loc" -> MARKER_KIND_SELF_POSITION
            "b-m-p-w-GOTO" -> MARKER_KIND_GO_TO_POINT
            "b-m-p-c-ip" -> MARKER_KIND_INITIAL_POINT
            "b-m-p-c-cp" -> MARKER_KIND_CONTACT_POINT
            "b-m-p-s-p-op" -> MARKER_KIND_OBSERVATION_POST
            "b-i-x-i" -> MARKER_KIND_IMAGE_MARKER
            else -> when {
                iconset.startsWith("COT_MAPPING_2525B") -> MARKER_KIND_SYMBOL_2525
                iconset.startsWith("COT_MAPPING_SPOTMAP") -> MARKER_KIND_SPOT_MAP
                iconset.isNotEmpty() -> MARKER_KIND_CUSTOM_ICON
                else -> MARKER_KIND_UNSPECIFIED
            }
        }

        // Pre-compiled regex patterns for aircraft remarks parsing
        private val ICAO_REGEX = Regex("""ICAO:\s*([A-Fa-f0-9]{6})""")
        private val REG_REGEX = Regex("""REG:\s*(\S+)""")
        private val FLIGHT_REGEX = Regex("""Flight:\s*(\S+)""")
        private val TYPE_REGEX = Regex("""Type:\s*(\S+)""")
        private val SQUAWK_REGEX = Regex("""Squawk:\s*(\d+)""")
        private val CATEGORY_REGEX = Regex("""Category:\s*(\S+)""")
        private val RAW_DETAIL_REGEX = Regex(
            """<detail\b[^>]*>(.*?)</detail\s*>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        )
    }

    /**
     * Parse a full CoT XML event string into a [TakPacketV2Data].
     *
     * @throws IllegalArgumentException if the XML contains prohibited DOCTYPE or ENTITY declarations
     */
    fun parse(cotXml: String): TakPacketV2Data {
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
        var inDetail = false

        // --- Drawn shape accumulators -----------------------------------
        var hasShapeData = false
        var inShape = false
        var shapeMajorCm = 0
        var shapeMinorCm = 0
        var shapeAngleDeg = 360
        // Presence flags for <strokeColor>/<fillColor> — used at end of
        // parse to derive DrawnShape.style without losing the "stroke-only
        // polyline vs transparent-black-fill" distinction.
        var sawStrokeColor = false
        var sawFillColor = false
        var strokeColorArgb = 0
        var strokeWeightX10 = 0
        var fillColorArgb = 0
        var labelsOn = false
        // Vertex accumulator for rectangle, freeform, polygon, telestration.
        // Matches <link point="lat,lon"/> siblings inside <detail>. The
        // nested <link> under <shape> has `type="b-x-KmlStyle"` and no
        // `point` attribute so it's filtered naturally.
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

        // --- GeoChat receipt accumulators (extend existing chat path) ---
        var chatReceiptForUid = ""
        var chatReceiptType = 0

        // Track which element we're inside for text capture
        var currentElement = ""

        try {
            while (reader.hasNext()) {
                val eventType = reader.next()
                when (eventType) {
                    EventType.START_ELEMENT -> {
                        val name = reader.localName
                        currentElement = name
                        when (name) {
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
                                // Normalize default TAK endpoints to empty — saves ~20 bytes
                                // on the wire. The builder emits "0.0.0.0:4242:tcp" when empty.
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
                                if (reader.getAttributeValue(null, "readiness")
                                        ?.equals("true", ignoreCase = true) == true) {
                                    markerReadiness = true
                                }
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
                                // "All Chat Rooms" is the broadcast sentinel — omit from proto
                                // so the field costs 0 bytes on the wire instead of 16.
                                chatTo = if (chatId == "All Chat Rooms") null else chatId
                            }
                            // --- Drawn shape elements --------------------------
                            "shape" -> {
                                hasShapeData = true
                                inShape = true
                            }
                            "ellipse" -> {
                                // <ellipse major="500" minor="500" angle="360"/> inside <shape>.
                                // major/minor are meters (or meters * 100 — ATAK emits doubles).
                                // We store centimeters to keep integer arithmetic clean; a
                                // 4294 km radius overflows int32 so the full ATAK range fits.
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
                                // Marker color: <color argb="-65536"/>. Distinct from
                                // <strokeColor>/<fillColor> which use `value=`.
                                markerColorArgb = reader.getAttributeValue(null, "argb")?.toIntOrNull() ?: 0
                                hasMarkerData = true
                            }
                            "usericon" -> {
                                markerIconset = reader.getAttributeValue(null, "iconsetpath") ?: ""
                                if (markerIconset.isNotEmpty()) hasMarkerData = true
                            }
                            // --- Bullseye -------------------------------------
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
                            // --- Range and bearing ----------------------------
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
                            // --- Route ----------------------------------------
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
                            // --- CasevacReport --------------------------------
                            "_medevac_" -> {
                                hasCasevacData = true
                                casevacPrecedence = precedenceMap[reader.getAttributeValue(null, "precedence") ?: ""] ?: 0
                                // Equipment flags bitfield
                                var eq = 0
                                if (reader.getAttributeValue(null, "hoist") == "true") eq = eq or 0x02
                                if (reader.getAttributeValue(null, "extraction_equipment") == "true") eq = eq or 0x04
                                if (reader.getAttributeValue(null, "ventilator") == "true") eq = eq or 0x08
                                if (reader.getAttributeValue(null, "blood") == "true") eq = eq or 0x10
                                if (reader.getAttributeValue(null, "none") == "true") eq = eq or 0x01
                                casevacEquipmentFlags = eq
                                casevacLitterPatients =
                                    reader.getAttributeValue(null, "litter")?.toIntOrNull() ?: 0
                                casevacAmbulatoryPatients =
                                    reader.getAttributeValue(null, "ambulatory")?.toIntOrNull() ?: 0
                                casevacSecurity = securityMap[reader.getAttributeValue(null, "security") ?: ""] ?: 0
                                casevacHlzMarking = hlzMarkingMap[reader.getAttributeValue(null, "hlz_marking") ?: ""] ?: 0
                                casevacZoneMarker = reader.getAttributeValue(null, "zone_prot_marker") ?: ""
                                casevacUsMilitary = reader.getAttributeValue(null, "us_military")?.toIntOrNull() ?: 0
                                casevacUsCivilian = reader.getAttributeValue(null, "us_civilian")?.toIntOrNull() ?: 0
                                casevacNonUsMilitary = reader.getAttributeValue(null, "non_us_military")?.toIntOrNull() ?: 0
                                casevacNonUsCivilian = reader.getAttributeValue(null, "non_us_civilian")?.toIntOrNull() ?: 0
                                casevacEpw = reader.getAttributeValue(null, "epw")?.toIntOrNull() ?: 0
                                casevacChild = reader.getAttributeValue(null, "child")?.toIntOrNull() ?: 0
                                // Terrain flags bitfield
                                var tf = 0
                                if (reader.getAttributeValue(null, "terrain_slope") == "true") tf = tf or 0x01
                                if (reader.getAttributeValue(null, "terrain_rough") == "true") tf = tf or 0x02
                                if (reader.getAttributeValue(null, "terrain_loose") == "true") tf = tf or 0x04
                                if (reader.getAttributeValue(null, "terrain_trees") == "true") tf = tf or 0x08
                                if (reader.getAttributeValue(null, "terrain_wires") == "true") tf = tf or 0x10
                                if (reader.getAttributeValue(null, "terrain_other") == "true") tf = tf or 0x20
                                casevacTerrainFlags = tf
                                casevacFrequency = reader.getAttributeValue(null, "freq") ?: ""
                            }
                            // --- EmergencyAlert -------------------------------
                            "emergency" -> {
                                hasEmergencyData = true
                                val typeAttr = reader.getAttributeValue(null, "type") ?: ""
                                emergencyTypeInt = emergencyTypeMap[typeAttr]
                                    ?: emergencyTypeFromCotType(cotTypeStr)
                                if (reader.getAttributeValue(null, "cancel") == "true") {
                                    emergencyTypeInt = EMERGENCY_TYPE_CANCEL
                                }
                            }
                            // --- TaskRequest ----------------------------------
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
                            // --- Generic link: the most overloaded element in CoT
                            "link" -> {
                                val linkUidAttr = reader.getAttributeValue(null, "uid")
                                val pointAttr = reader.getAttributeValue(null, "point")
                                val linkType = reader.getAttributeValue(null, "type") ?: ""
                                val relation = reader.getAttributeValue(null, "relation") ?: ""
                                val linkCallsign = reader.getAttributeValue(null, "callsign") ?: ""
                                val parentCallsignAttr = reader.getAttributeValue(null, "parent_callsign") ?: ""

                                // Ignore style links nested inside <shape> (type="b-x-KmlStyle").
                                // Their `uid` ends in ".Style" and they carry styling, not
                                // geometry or relationships we care about.
                                val isStyleLink = linkType.startsWith("b-x-KmlStyle") ||
                                    (linkUidAttr != null && linkUidAttr.endsWith(".Style"))

                                if (!isStyleLink && pointAttr != null) {
                                    val parts = pointAttr.split(",")
                                    if (parts.size >= 2) {
                                        // Trim whitespace — iTAK uses "lat, lon" with a space after comma
                                        val plat = parts[0].trim().toDoubleOrNull() ?: 0.0
                                        val plon = parts[1].trim().toDoubleOrNull() ?: 0.0
                                        val plati = (plat * 1e7).roundToInt()
                                        val ploni = (plon * 1e7).roundToInt()

                                        when {
                                            // Range/bearing anchor: u-rb-a is the ONLY
                                            // event type that uses <link> as its anchor,
                                            // and the anchor's `type` is usually b-m-p-w —
                                            // which would otherwise collide with the route
                                            // waypoint branch below. Check cotType first
                                            // so a ranging-line link never escalates to a
                                            // one-waypoint "route".
                                            cotTypeStr == "u-rb-a" -> {
                                                if (rabAnchorLatI == 0 && rabAnchorLonI == 0) {
                                                    rabAnchorLatI = plati
                                                    rabAnchorLonI = ploni
                                                    if (linkUidAttr != null) rabAnchorUid = linkUidAttr
                                                }
                                                hasRabData = true
                                            }
                                            // Route waypoint or checkpoint — but only for
                                            // b-m-r events. Outside a route these atoms
                                            // still describe a single point and would be
                                            // mis-promoted to a Route payload with one link.
                                            (linkType == "b-m-p-w" || linkType == "b-m-p-c") &&
                                                cotTypeStr == "b-m-r" -> {
                                                if (routeLinks.size < MAX_ROUTE_LINKS) {
                                                    routeLinks.add(
                                                        TakPacketV2Data.Payload.Route.Link(
                                                            latI = plati, lonI = ploni,
                                                            uid = linkUidAttr ?: "",
                                                            callsign = linkCallsign,
                                                            linkType = if (linkType == "b-m-p-c") 1 else 0,
                                                        )
                                                    )
                                                } else {
                                                    routeTruncated = true
                                                }
                                                hasRouteData = true
                                            }
                                            // Otherwise: shape vertex (rectangle, freeform,
                                            // polygon, telestration). Truncate at MAX_VERTICES.
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
                                    // Chat receipts: <link uid="…original-message-uid…"
                                    // relation="p-p" type="b-t-f"/> on a b-t-f-d or b-t-f-r
                                    // event is a receipt pointing at the acknowledged message.
                                    if (cotTypeStr == "b-t-f-d" || cotTypeStr == "b-t-f-r") {
                                        if (chatReceiptForUid.isEmpty()) {
                                            chatReceiptForUid = linkUidAttr
                                        }
                                        chatReceiptType = if (cotTypeStr == "b-t-f-d") {
                                            RECEIPT_TYPE_DELIVERED
                                        } else {
                                            RECEIPT_TYPE_READ
                                        }
                                        hasChatData = true
                                    } else if (cotTypeStr == "t-s") {
                                        // Task target link: first non-self-ref p-p link on
                                        // a t-s event is the target being tasked.
                                        if (taskTargetUid.isEmpty()) {
                                            taskTargetUid = linkUidAttr
                                        }
                                        hasTaskData = true
                                    } else if (cotTypeStr.startsWith("b-a-")) {
                                        // Emergency links: a b-a-* event may carry two p-p links:
                                        //   1. authoring link (type a-f-*): who raised the alert
                                        //   2. cancel-reference link (type b-a-*): the alert being cancelled
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
                                    } else {
                                        // Marker parent link: no point attribute, p-p relation,
                                        // references a parent TAK user by UID + cot type.
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
                    EventType.END_ELEMENT -> {
                        val name = reader.localName
                        if (name == "shape") inShape = false
                        currentElement = ""
                    }
                    EventType.TEXT, EventType.CDSECT -> {
                        // Capture text only inside <remarks> — not arbitrary text
                        // nodes like <color>ffffffff</color> inside shape style blocks.
                        if (inDetail && currentElement == "remarks") {
                            val text = reader.text.trim()
                            if (text.isNotEmpty()) {
                                remarksText = text
                            }
                        }
                    }
                    else -> { /* ignore other event types */ }
                }
            }
        } finally {
            reader.close()
        }

        // Parse ICAO/aircraft data from remarks (always try, remarks may supplement _aircot_ or _radio)
        if (remarksText.isNotEmpty() && icao.isEmpty()) {
            val icaoMatch = ICAO_REGEX.find(remarksText)
            if (icaoMatch != null) {
                hasAircraftData = true
                icao = icaoMatch.groupValues[1]
                if (registration.isEmpty()) registration = REG_REGEX.find(remarksText)?.groupValues?.get(1) ?: ""
                if (flight.isEmpty()) flight = FLIGHT_REGEX.find(remarksText)?.groupValues?.get(1) ?: ""
                if (aircraftType.isEmpty()) aircraftType = TYPE_REGEX.find(remarksText)?.groupValues?.get(1) ?: ""
                val squawkMatch = SQUAWK_REGEX.find(remarksText)
                if (squawk == 0) squawk = squawkMatch?.groupValues?.get(1)?.toIntOrNull() ?: 0
                val catMatch = CATEGORY_REGEX.find(remarksText)
                if (catMatch != null && category.isEmpty()) category = catMatch.groupValues[1]
            }
        }

        // Compute stale seconds
        val staleSeconds = computeStaleSeconds(timeStr, staleStr)

        // Determine CoT type enum
        val cotTypeId = CotTypeMapper.typeToEnum(cotTypeStr)
        val cotTypeStrField = if (cotTypeId == CotTypeMapper.COTTYPE_OTHER) cotTypeStr else null

        // Derive the stroke/fill/both discriminator from what we observed.
        val shapeStyle = when {
            sawStrokeColor && sawFillColor -> STYLE_STROKE_AND_FILL
            sawStrokeColor -> STYLE_STROKE_ONLY
            sawFillColor -> STYLE_FILL_ONLY
            else -> STYLE_UNSPECIFIED
        }

        // Payload priority: chat > aircraft > route > rab > shape > marker >
        // casevac > emergency > task > pli.
        //
        // Rationale for ordering:
        //   * Chat and aircraft win first for backward compatibility with the
        //     pre-v2 behavior. Chat receipts (b-t-f-d / b-t-f-r) ride on the
        //     same Chat variant with receipt fields set.
        //   * Route wins over RAB because a route also has <link type="b-m-p-w">
        //     entries that could otherwise look like RAB anchors.
        //   * Shape wins over marker because a shape event can have a <contact>
        //     (that looks marker-ish) alongside real shape geometry.
        //   * Markers win over the Pli fallback whenever we saw marker-specific
        //     detail like <usericon> or <color argb="...">.
        //   * CASEVAC / emergency / task win over the Pli fallback but below
        //     shape/marker so a drawing that happens to carry stray medevac
        //     attributes doesn't mis-dispatch.
        val payload = when {
            hasChatData -> TakPacketV2Data.Payload.Chat(
                message = remarksText,
                to = chatTo,
                toCallsign = chatToCallsign,
                receiptForUid = chatReceiptForUid,
                receiptType = chatReceiptType,
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
            hasRouteData && routeLinks.isNotEmpty() -> TakPacketV2Data.Payload.Route(
                method = routeMethod,
                direction = routeDirection,
                prefix = routePrefix,
                strokeWeightX10 = strokeWeightX10,
                links = routeLinks.toList(),
                truncated = routeTruncated,
            )
            hasRabData -> TakPacketV2Data.Payload.RangeAndBearing(
                anchorLatI = rabAnchorLatI,
                anchorLonI = rabAnchorLonI,
                anchorUid = rabAnchorUid,
                rangeCm = rabRangeCm,
                bearingCdeg = rabBearingCdeg,
                strokeColor = AtakPalette.argbToTeam(strokeColorArgb),
                strokeArgb = strokeColorArgb,
                strokeWeightX10 = strokeWeightX10,
            )
            hasShapeData -> TakPacketV2Data.Payload.DrawnShape(
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
            hasMarkerData -> TakPacketV2Data.Payload.Marker(
                kind = markerKindFromCotType(cotTypeStr, markerIconset),
                color = AtakPalette.argbToTeam(markerColorArgb),
                colorArgb = markerColorArgb,
                readiness = markerReadiness,
                parentUid = markerParentUid,
                parentType = markerParentType,
                parentCallsign = markerParentCallsign,
                iconset = markerIconset,
            )
            hasCasevacData -> TakPacketV2Data.Payload.CasevacReport(
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
            )
            hasEmergencyData -> TakPacketV2Data.Payload.EmergencyAlert(
                type = if (emergencyTypeInt != 0) emergencyTypeInt
                       else emergencyTypeFromCotType(cotTypeStr),
                authoringUid = emergencyAuthoringUid,
                cancelReferenceUid = emergencyCancelReferenceUid,
            )
            hasTaskData -> TakPacketV2Data.Payload.TaskRequest(
                taskType = taskTypeTag,
                targetUid = taskTargetUid,
                assigneeUid = taskAssigneeUid,
                priority = taskPriority,
                status = taskStatus,
                note = taskNote,
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
            // Proto type is uint32 (cm/s / deg×100). ATAK emits `speed=-1.0`
            // as a stationary/unknown sentinel — negatives would corrupt the
            // wire as a huge unsigned value when the serializer crosses the
            // Int→uint32 boundary. Clamp here so only non-negative values
            // ever leave the parser.
            speed = maxOf(0, (speed * 100).roundToInt()),   // m/s -> cm/s
            course = maxOf(0, (course * 100).roundToInt()),  // degrees -> degrees*100
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

    /**
     * Extract the inner bytes of the `<detail>` element from a CoT XML event,
     * exactly as they appear in the source (no XML normalization, no
     * re-escaping).  Used by [TakCompressor.compressBestOf] to build a
     * `raw_detail` fallback packet alongside the typed-variant packet.
     *
     * For events with a self-closing `<detail/>` or no `<detail>` element at
     * all, returns an empty [ByteArray].  Receivers rehydrate the full event
     * by wrapping these bytes in `<detail>...</detail>`, so a byte-for-byte
     * extraction is required to keep the round trip loss-free.
     */
    fun extractRawDetailBytes(cotXml: String): ByteArray {
        val match = RAW_DETAIL_REGEX.find(cotXml)
        val inner = match?.groupValues?.get(1) ?: return ByteArray(0)
        return inner.encodeToByteArray()
    }
}
