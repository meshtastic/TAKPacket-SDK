package org.meshtastic.tak

import kotlin.time.Instant
import nl.adaptivity.xmlutil.EventType
import nl.adaptivity.xmlutil.XmlReader
import nl.adaptivity.xmlutil.xmlStreaming
import kotlin.math.roundToInt

/**
 * Parses a CoT XML event string into a [TakPacketV2Data].
 *
 * Uses xmlutil's streaming [XmlReader] API (KMP-compatible) for cross-platform
 * XML parsing. Extracts all fields supported by the TAKPacketV2 protobuf schema,
 * including event envelope (type, how, uid, time/stale), point (position), and
 * detail sub-elements (contact, group, status, track, takv, precisionlocation,
 * remarks, aircot, radio, link, chat, shape, bullseye, route, marker, medevac,
 * emergency, task).
 *
 * The [parse] method rejects XML with `<!DOCTYPE>` or `<!ENTITY>` declarations
 * to prevent XXE and entity-expansion attacks.
 *
 * This class holds no mutable state and is safe for concurrent use from multiple threads.
 */
public class CotXmlParser {

    public companion object {
        // ── GeoPointSource enum values ──────────────────────────────────────
        /** GeoPointSource: unspecified / unknown. */
        public const val GEOSRC_UNSPECIFIED: Int = 0
        /** GeoPointSource: GPS fix. */
        public const val GEOSRC_GPS: Int = 1
        /** GeoPointSource: user-entered / manual. */
        public const val GEOSRC_USER: Int = 2
        /** GeoPointSource: network-derived position. */
        public const val GEOSRC_NETWORK: Int = 3

        /**
         * Maximum number of vertices kept in a [TakPacketV2Data.Payload.DrawnShape].
         * Matches `*DrawnShape.vertices max_count:32` in atak.options. Longer
         * vertex lists are silently truncated and `truncated = true` is set.
         */
        public const val MAX_VERTICES: Int = 32

        /**
         * Maximum number of links kept in a [TakPacketV2Data.Payload.Route].
         * Matches `*Route.links max_count:16` in atak.options. Longer routes
         * are truncated and `truncated = true` is set.
         */
        public const val MAX_ROUTE_LINKS: Int = 16

        // ── DrawnShape.Kind enum values ─────────────────────────────────────
        internal const val SHAPE_KIND_UNSPECIFIED = 0
        internal const val SHAPE_KIND_CIRCLE = 1
        internal const val SHAPE_KIND_RECTANGLE = 2
        internal const val SHAPE_KIND_FREEFORM = 3
        internal const val SHAPE_KIND_TELESTRATION = 4
        internal const val SHAPE_KIND_POLYGON = 5
        internal const val SHAPE_KIND_RANGING_CIRCLE = 6
        internal const val SHAPE_KIND_BULLSEYE = 7
        internal const val SHAPE_KIND_ELLIPSE = 8
        internal const val SHAPE_KIND_VEHICLE_2D = 9
        internal const val SHAPE_KIND_VEHICLE_3D = 10

        // ── DrawnShape.StyleMode values ─────────────────────────────────────
        internal const val STYLE_UNSPECIFIED = 0
        internal const val STYLE_STROKE_ONLY = 1
        internal const val STYLE_FILL_ONLY = 2
        internal const val STYLE_STROKE_AND_FILL = 3

        // ── Marker.Kind values ──────────────────────────────────────────────
        internal const val MARKER_KIND_UNSPECIFIED = 0
        internal const val MARKER_KIND_SPOT = 1
        internal const val MARKER_KIND_WAYPOINT = 2
        internal const val MARKER_KIND_CHECKPOINT = 3
        internal const val MARKER_KIND_SELF_POSITION = 4
        internal const val MARKER_KIND_SYMBOL_2525 = 5
        internal const val MARKER_KIND_SPOT_MAP = 6
        internal const val MARKER_KIND_CUSTOM_ICON = 7
        internal const val MARKER_KIND_GO_TO_POINT = 8
        internal const val MARKER_KIND_INITIAL_POINT = 9
        internal const val MARKER_KIND_CONTACT_POINT = 10
        internal const val MARKER_KIND_OBSERVATION_POST = 11
        internal const val MARKER_KIND_IMAGE_MARKER = 12

        // ── CasevacReport.Precedence enum values ────────────────────────────
        internal const val PRECEDENCE_UNSPECIFIED = 0
        internal const val PRECEDENCE_URGENT = 1
        internal const val PRECEDENCE_URGENT_SURGICAL = 2
        internal const val PRECEDENCE_PRIORITY = 3
        internal const val PRECEDENCE_ROUTINE = 4
        internal const val PRECEDENCE_CONVENIENCE = 5

        // ── CasevacReport.HlzMarking enum values ───────────────────────────
        internal const val HLZ_MARKING_UNSPECIFIED = 0
        internal const val HLZ_MARKING_PANELS = 1
        internal const val HLZ_MARKING_PYRO_SIGNAL = 2
        internal const val HLZ_MARKING_SMOKE = 3
        internal const val HLZ_MARKING_NONE = 4
        internal const val HLZ_MARKING_OTHER = 5

        // ── CasevacReport.Security enum values ──────────────────────────────
        internal const val SECURITY_UNSPECIFIED = 0
        internal const val SECURITY_NO_ENEMY = 1
        internal const val SECURITY_POSSIBLE_ENEMY = 2
        internal const val SECURITY_ENEMY_IN_AREA = 3
        internal const val SECURITY_ENEMY_IN_ARMED_CONTACT = 4

        // ── EmergencyAlert.Type enum values ─────────────────────────────────
        internal const val EMERGENCY_TYPE_UNSPECIFIED = 0
        internal const val EMERGENCY_TYPE_ALERT_911 = 1
        internal const val EMERGENCY_TYPE_RING_THE_BELL = 2
        internal const val EMERGENCY_TYPE_IN_CONTACT = 3
        internal const val EMERGENCY_TYPE_GEO_FENCE_BREACHED = 4
        internal const val EMERGENCY_TYPE_CUSTOM = 5
        internal const val EMERGENCY_TYPE_CANCEL = 6

        // ── TaskRequest.Priority enum values ────────────────────────────────
        internal const val TASK_PRIORITY_UNSPECIFIED = 0
        internal const val TASK_PRIORITY_LOW = 1
        internal const val TASK_PRIORITY_NORMAL = 2
        internal const val TASK_PRIORITY_HIGH = 3
        internal const val TASK_PRIORITY_CRITICAL = 4

        // ── TaskRequest.Status enum values ──────────────────────────────────
        internal const val TASK_STATUS_UNSPECIFIED = 0
        internal const val TASK_STATUS_PENDING = 1
        internal const val TASK_STATUS_ACKNOWLEDGED = 2
        internal const val TASK_STATUS_IN_PROGRESS = 3
        internal const val TASK_STATUS_COMPLETED = 4
        internal const val TASK_STATUS_CANCELLED = 5

        // ── GeoChat.ReceiptType enum values ─────────────────────────────────
        internal const val RECEIPT_TYPE_NONE = 0
        internal const val RECEIPT_TYPE_DELIVERED = 1
        internal const val RECEIPT_TYPE_READ = 2

        // ── Lookup maps ─────────────────────────────────────────────────────

        /** Route.Method values (mirrors proto). */
        private val routeMethodMap = mapOf(
            "Driving" to 1, "Walking" to 2, "Flying" to 3,
            "Swimming" to 4, "Watercraft" to 5,
        )

        /** Route.Direction values (mirrors proto). */
        private val routeDirectionMap = mapOf(
            "Infil" to 1, "Exfil" to 2,
        )

        /** Bullseye bearing reference attribute to enum tag. */
        private val bearingRefMap = mapOf("M" to 1, "T" to 2, "G" to 3)

        /** CasevacReport.Precedence string to enum int. Covers letter codes and spelled-out forms. */
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

        /** CasevacReport.HlzMarking string to enum int. */
        private val hlzMarkingMap = mapOf(
            "Panels" to HLZ_MARKING_PANELS,
            "Pyro" to HLZ_MARKING_PYRO_SIGNAL,
            "Pyrotechnic" to HLZ_MARKING_PYRO_SIGNAL,
            "Smoke" to HLZ_MARKING_SMOKE,
            "None" to HLZ_MARKING_NONE,
            "Other" to HLZ_MARKING_OTHER,
        )

        /** CasevacReport.Security string to enum int. */
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

        /** EmergencyAlert.Type string to enum int. Covers both ATAK and user-typed variants. */
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

        /** EmergencyAlert.Type derived from CoT type atom — fallback when `<emergency type>` is missing. */
        private fun emergencyTypeFromCotType(cotType: String): Int = when (cotType) {
            "b-a-o-tbl" -> EMERGENCY_TYPE_ALERT_911
            "b-a-o-pan" -> EMERGENCY_TYPE_RING_THE_BELL
            "b-a-o-opn" -> EMERGENCY_TYPE_IN_CONTACT
            "b-a-g" -> EMERGENCY_TYPE_GEO_FENCE_BREACHED
            "b-a-o-c" -> EMERGENCY_TYPE_CUSTOM
            "b-a-o-can" -> EMERGENCY_TYPE_CANCEL
            else -> EMERGENCY_TYPE_UNSPECIFIED
        }

        /** TaskRequest.Priority string to enum int. */
        private val taskPriorityMap = mapOf(
            "Low" to TASK_PRIORITY_LOW,
            "Normal" to TASK_PRIORITY_NORMAL,
            "Medium" to TASK_PRIORITY_NORMAL,
            "High" to TASK_PRIORITY_HIGH,
            "Critical" to TASK_PRIORITY_CRITICAL,
        )

        /** TaskRequest.Status string to enum int. */
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

        /** Team name to proto enum value. */
        private val teamNameToEnum = mapOf(
            "White" to 1, "Yellow" to 2, "Orange" to 3, "Magenta" to 4,
            "Red" to 5, "Maroon" to 6, "Purple" to 7, "Dark Blue" to 8,
            "Blue" to 9, "Cyan" to 10, "Teal" to 11, "Green" to 12,
            "Dark Green" to 13, "Brown" to 14,
        )

        /** MemberRole name to proto enum value. */
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
         * Derive a [DrawnShape.Kind][TakPacketV2Data.Payload.DrawnShape] from a
         * CoT type string. Returns [SHAPE_KIND_UNSPECIFIED] for non-shape types.
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
         * Derive a [Marker.Kind][TakPacketV2Data.Payload.Marker] from CoT type +
         * iconset path. When the CoT type alone is ambiguous (`a-u-G` could be a
         * 2525 symbol OR a custom icon), the iconset path disambiguates.
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
            """<detail\b[^>]*>([\s\S]*?)</detail\s*>""",
            RegexOption.IGNORE_CASE,
        )
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Parse state — mutable accumulators used during a single parse() call
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Holds all mutable state accumulated while walking the XML event stream.
     * Scoped to a single [parse] invocation to keep the parser stateless
     * across calls.
     */
    private class ParseState {
        // ── Envelope ────────────────────────────────────────────────────
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
        var isDeleteEvent = false
        var remarksText = ""
        var inDetail = false

        // ── Chat ────────────────────────────────────────────────────────
        var hasChatData = false
        var chatTo: String? = null
        var chatToCallsign: String? = null
        var chatReceiptForUid = ""
        var chatReceiptType = 0

        // ── Aircraft ────────────────────────────────────────────────────
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

        // ── Drawn shape ─────────────────────────────────────────────────
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

        // ── Bullseye ────────────────────────────────────────────────────
        var hasBullseyeData = false
        var bullseyeDistanceDm = 0
        var bullseyeBearingRef = 0
        var bullseyeFlags = 0
        var bullseyeUidRef = ""

        // ── Marker ──────────────────────────────────────────────────────
        var hasMarkerData = false
        var markerColorArgb = 0
        var markerReadiness = false
        var markerParentUid = ""
        var markerParentType = ""
        var markerParentCallsign = ""
        var markerIconset = ""

        // ── Range and bearing ───────────────────────────────────────────
        var hasRabData = false
        var rabAnchorLatI = 0
        var rabAnchorLonI = 0
        var rabAnchorUid = ""
        var rabRangeCm = 0
        var rabBearingCdeg = 0

        // ── Route ───────────────────────────────────────────────────────
        var hasRouteData = false
        val routeLinks = mutableListOf<TakPacketV2Data.Payload.Route.Link>()
        var routeTruncated = false
        var routePrefix = ""
        var routeMethod = 0
        var routeDirection = 0

        // ── CASEVAC ─────────────────────────────────────────────────────
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

        // ── Emergency ───────────────────────────────────────────────────
        var hasEmergencyData = false
        var emergencyTypeInt = 0
        var emergencyAuthoringUid = ""
        var emergencyCancelReferenceUid = ""

        // ── Task ────────────────────────────────────────────────────────
        var hasTaskData = false
        var taskTypeTag = ""
        var taskTargetUid = ""
        var taskAssigneeUid = ""
        var taskPriority = 0
        var taskStatus = 0
        var taskNote = ""

        // ── Text capture ────────────────────────────────────────────────
        var currentElement = ""
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Public API
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Parse a full CoT XML event string into a [TakPacketV2Data].
     *
     * @param cotXml the raw CoT XML string to parse
     * @return the parsed [TakPacketV2Data]
     * @throws IllegalArgumentException if the XML contains prohibited
     *         `<!DOCTYPE>` or `<!ENTITY>` declarations
     */
    @Throws(IllegalArgumentException::class)
    public fun parse(cotXml: String): TakPacketV2Data {
        // Reject XML with DOCTYPE or ENTITY declarations to prevent XXE
        if (cotXml.contains("<!DOCTYPE", ignoreCase = true) ||
            cotXml.contains("<!ENTITY", ignoreCase = true)
        ) {
            throw IllegalArgumentException("XML contains prohibited DOCTYPE or ENTITY declaration")
        }

        val s = ParseState()
        val reader = xmlStreaming.newReader(cotXml)

        try {
            while (reader.hasNext()) {
                when (reader.next()) {
                    EventType.START_ELEMENT -> handleStartElement(reader, s)
                    EventType.END_ELEMENT -> handleEndElement(reader, s)
                    EventType.TEXT, EventType.CDSECT -> handleText(reader, s)
                    else -> { /* ignore processing instructions, comments, etc. */ }
                }
            }
        } finally {
            reader.close()
        }

        enrichAircraftFromRemarks(s)
        return buildResult(s)
    }

    /**
     * Extract the inner bytes of the `<detail>` element from a CoT XML event,
     * exactly as they appear in the source (no XML normalization, no
     * re-escaping). Used by [TakCompressor.compressBestOf] to build a
     * `raw_detail` fallback packet alongside the typed-variant packet.
     *
     * For events with a self-closing `<detail/>` or no `<detail>` element at
     * all, returns an empty [ByteArray]. Receivers rehydrate the full event
     * by wrapping these bytes in `<detail>...</detail>`, so a byte-for-byte
     * extraction is required to keep the round trip loss-free.
     *
     * @param cotXml the raw CoT XML string
     * @return the inner bytes of the `<detail>` element, or empty if absent
     */
    @Throws(IllegalArgumentException::class)
    public fun extractRawDetailBytes(cotXml: String): ByteArray {
        val match = RAW_DETAIL_REGEX.find(cotXml)
        val inner = match?.groupValues?.get(1) ?: return ByteArray(0)
        return inner.encodeToByteArray()
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Element handlers — each covers one or more related XML elements
    // ════════════════════════════════════════════════════════════════════════

    /** Dispatch an [EventType.START_ELEMENT] to the appropriate handler. */
    private fun handleStartElement(reader: XmlReader, s: ParseState) {
        val name = reader.localName
        s.currentElement = name
        when (name) {
            "event" -> parseEvent(reader, s)
            "point" -> parsePoint(reader, s)
            "detail" -> { s.inDetail = true }
            "contact" -> parseContact(reader, s)
            "__group" -> parseGroup(reader, s)
            "status" -> parseStatus(reader, s)
            "track" -> parseTrack(reader, s)
            "takv" -> parseTakVersion(reader, s)
            "precisionlocation" -> parsePrecisionLocation(reader, s)
            "uid", "UID" -> parseUidDroid(reader, s)
            "_radio" -> parseRadio(reader, s)
            "_aircot_" -> parseAircot(reader, s)
            "__chat" -> parseChat(reader, s)
            "shape" -> { s.hasShapeData = true; s.inShape = true }
            "ellipse" -> parseEllipse(reader, s)
            "strokeColor" -> parseStrokeColor(reader, s)
            "strokeWeight" -> parseStrokeWeight(reader, s)
            "fillColor" -> parseFillColor(reader, s)
            "labels_on" -> { s.labelsOn = reader.getAttributeValue(null, "value") == "true" }
            "color" -> parseMarkerColor(reader, s)
            "usericon" -> parseUserIcon(reader, s)
            "bullseye" -> parseBullseye(reader, s)
            "range" -> parseRange(reader, s)
            "bearing" -> parseBearing(reader, s)
            "__routeinfo" -> { s.hasRouteData = true }
            "link_attr" -> parseLinkAttr(reader, s)
            "_medevac_" -> parseMedevac(reader, s)
            "emergency" -> parseEmergency(reader, s)
            "task", "_task_" -> parseTask(reader, s)
            "link" -> parseLink(reader, s)
        }
    }

    /** Handle [EventType.END_ELEMENT]: reset shape nesting and current-element tracking. */
    private fun handleEndElement(reader: XmlReader, s: ParseState) {
        if (reader.localName == "shape") s.inShape = false
        s.currentElement = ""
    }

    /** Capture text content inside `<remarks>` elements only. */
    private fun handleText(reader: XmlReader, s: ParseState) {
        if (s.inDetail && s.currentElement == "remarks") {
            val text = reader.text.trim()
            if (text.isNotEmpty()) s.remarksText = text
        }
    }

    // ── Envelope elements ───────────────────────────────────────────────────

    private fun parseEvent(reader: XmlReader, s: ParseState) {
        s.uid = reader.getAttributeValue(null, "uid") ?: ""
        s.cotTypeStr = reader.getAttributeValue(null, "type") ?: ""
        s.howStr = reader.getAttributeValue(null, "how") ?: ""
        s.timeStr = reader.getAttributeValue(null, "time") ?: ""
        s.staleStr = reader.getAttributeValue(null, "stale") ?: ""
        s.isDeleteEvent = s.cotTypeStr == "t-x-d-d"
    }

    private fun parsePoint(reader: XmlReader, s: ParseState) {
        s.lat = reader.getAttributeValue(null, "lat")?.toDoubleOrNull() ?: 0.0
        s.lon = reader.getAttributeValue(null, "lon")?.toDoubleOrNull() ?: 0.0
        s.hae = reader.getAttributeValue(null, "hae")?.toDoubleOrNull() ?: 0.0
    }

    // ── Detail sub-elements ─────────────────────────────────────────────────

    private fun parseContact(reader: XmlReader, s: ParseState) {
        s.callsign = reader.getAttributeValue(null, "callsign") ?: ""
        // Normalize default TAK endpoints to empty — saves ~20 bytes on the wire
        val ep = reader.getAttributeValue(null, "endpoint") ?: ""
        s.endpoint = if (ep == "0.0.0.0:4242:tcp" || ep == "*:-1:stcp") "" else ep
        s.phone = reader.getAttributeValue(null, "phone") ?: s.phone
    }

    private fun parseGroup(reader: XmlReader, s: ParseState) {
        s.teamName = reader.getAttributeValue(null, "name") ?: ""
        s.roleName = reader.getAttributeValue(null, "role") ?: ""
    }

    private fun parseStatus(reader: XmlReader, s: ParseState) {
        val bat = reader.getAttributeValue(null, "battery")?.toIntOrNull()
        if (bat != null && bat > 0) s.battery = bat
        if (reader.getAttributeValue(null, "readiness")
                ?.equals("true", ignoreCase = true) == true
        ) {
            s.markerReadiness = true
        }
    }

    private fun parseTrack(reader: XmlReader, s: ParseState) {
        s.speed = reader.getAttributeValue(null, "speed")?.toDoubleOrNull() ?: 0.0
        s.course = reader.getAttributeValue(null, "course")?.toDoubleOrNull() ?: 0.0
    }

    private fun parseTakVersion(reader: XmlReader, s: ParseState) {
        s.takVersion = reader.getAttributeValue(null, "version") ?: ""
        s.takDevice = reader.getAttributeValue(null, "device") ?: ""
        s.takPlatform = reader.getAttributeValue(null, "platform") ?: ""
        s.takOs = reader.getAttributeValue(null, "os") ?: ""
    }

    private fun parsePrecisionLocation(reader: XmlReader, s: ParseState) {
        s.geoSrc = reader.getAttributeValue(null, "geopointsrc") ?: ""
        s.altSrc = reader.getAttributeValue(null, "altsrc") ?: ""
    }

    private fun parseUidDroid(reader: XmlReader, s: ParseState) {
        val droid = reader.getAttributeValue(null, "Droid")
        if (droid != null) s.deviceCallsign = droid
    }

    // ── Aircraft elements ───────────────────────────────────────────────────

    private fun parseRadio(reader: XmlReader, s: ParseState) {
        val rssiStr = reader.getAttributeValue(null, "rssi")
        if (rssiStr != null) {
            s.rssiX10 = (rssiStr.toDoubleOrNull()?.times(10))?.roundToInt() ?: 0
            s.hasAircraftData = true
        }
        s.gps = reader.getAttributeValue(null, "gps")?.toBooleanStrictOrNull() ?: false
    }

    private fun parseAircot(reader: XmlReader, s: ParseState) {
        s.icao = reader.getAttributeValue(null, "icao") ?: ""
        s.registration = reader.getAttributeValue(null, "reg") ?: ""
        s.flight = reader.getAttributeValue(null, "flight") ?: ""
        s.category = reader.getAttributeValue(null, "cat") ?: ""
        s.cotHostId = reader.getAttributeValue(null, "cot_host_id") ?: ""
        s.hasAircraftData = true
    }

    // ── Chat element ────────────────────────────────────────────────────────

    private fun parseChat(reader: XmlReader, s: ParseState) {
        s.hasChatData = true
        s.chatToCallsign = reader.getAttributeValue(null, "senderCallsign")
        val chatId = reader.getAttributeValue(null, "id")
        // "All Chat Rooms" is the broadcast sentinel — omit from proto so the
        // field costs 0 bytes on the wire instead of 16.
        s.chatTo = if (chatId == "All Chat Rooms") null else chatId
    }

    // ── Drawn shape elements ────────────────────────────────────────────────

    private fun parseEllipse(reader: XmlReader, s: ParseState) {
        if (!s.inShape) return
        val majorM = reader.getAttributeValue(null, "major")?.toDoubleOrNull() ?: 0.0
        val minorM = reader.getAttributeValue(null, "minor")?.toDoubleOrNull() ?: 0.0
        s.shapeMajorCm = (majorM * 100).roundToInt().coerceAtLeast(0)
        s.shapeMinorCm = (minorM * 100).roundToInt().coerceAtLeast(0)
        s.shapeAngleDeg = reader.getAttributeValue(null, "angle")?.toIntOrNull() ?: 360
        s.hasShapeData = true
    }

    private fun parseStrokeColor(reader: XmlReader, s: ParseState) {
        s.sawStrokeColor = true
        s.strokeColorArgb = reader.getAttributeValue(null, "value")?.toIntOrNull() ?: 0
        s.hasShapeData = true
    }

    private fun parseStrokeWeight(reader: XmlReader, s: ParseState) {
        val w = reader.getAttributeValue(null, "value")?.toDoubleOrNull() ?: 0.0
        s.strokeWeightX10 = (w * 10).roundToInt().coerceAtLeast(0)
        s.hasShapeData = true
    }

    private fun parseFillColor(reader: XmlReader, s: ParseState) {
        s.sawFillColor = true
        s.fillColorArgb = reader.getAttributeValue(null, "value")?.toIntOrNull() ?: 0
        s.hasShapeData = true
    }

    private fun parseMarkerColor(reader: XmlReader, s: ParseState) {
        s.markerColorArgb = reader.getAttributeValue(null, "argb")?.toIntOrNull() ?: 0
        s.hasMarkerData = true
    }

    private fun parseUserIcon(reader: XmlReader, s: ParseState) {
        s.markerIconset = reader.getAttributeValue(null, "iconsetpath") ?: ""
        if (s.markerIconset.isNotEmpty()) s.hasMarkerData = true
    }

    // ── Bullseye element ────────────────────────────────────────────────────

    private fun parseBullseye(reader: XmlReader, s: ParseState) {
        s.hasBullseyeData = true
        s.hasShapeData = true
        val distStr = reader.getAttributeValue(null, "distance")
        s.bullseyeDistanceDm = ((distStr?.toDoubleOrNull() ?: 0.0) * 10).roundToInt().coerceAtLeast(0)
        s.bullseyeBearingRef = bearingRefMap[reader.getAttributeValue(null, "bearingRef")] ?: 0
        var flags = 0
        if (reader.getAttributeValue(null, "rangeRingVisible") == "true") flags = flags or 0x01
        if (reader.getAttributeValue(null, "hasRangeRings") == "true") flags = flags or 0x02
        if (reader.getAttributeValue(null, "edgeToCenter") == "true") flags = flags or 0x04
        if (reader.getAttributeValue(null, "mils") == "true") flags = flags or 0x08
        s.bullseyeFlags = flags
        s.bullseyeUidRef = reader.getAttributeValue(null, "bullseyeUID") ?: ""
    }

    // ── Range and bearing elements ──────────────────────────────────────────

    private fun parseRange(reader: XmlReader, s: ParseState) {
        val v = reader.getAttributeValue(null, "value")?.toDoubleOrNull() ?: 0.0
        s.rabRangeCm = (v * 100).roundToInt().coerceAtLeast(0)
        s.hasRabData = true
    }

    private fun parseBearing(reader: XmlReader, s: ParseState) {
        val v = reader.getAttributeValue(null, "value")?.toDoubleOrNull() ?: 0.0
        s.rabBearingCdeg = (v * 100).roundToInt().coerceAtLeast(0)
        s.hasRabData = true
    }

    // ── Route elements ──────────────────────────────────────────────────────

    private fun parseLinkAttr(reader: XmlReader, s: ParseState) {
        s.hasRouteData = true
        s.routePrefix = reader.getAttributeValue(null, "prefix") ?: ""
        s.routeMethod = routeMethodMap[reader.getAttributeValue(null, "method")] ?: 0
        s.routeDirection = routeDirectionMap[reader.getAttributeValue(null, "direction")] ?: 0
        val sw = reader.getAttributeValue(null, "stroke")?.toIntOrNull() ?: 0
        if (sw > 0) s.strokeWeightX10 = sw * 10
    }

    // ── CASEVAC element ─────────────────────────────────────────────────────

    private fun parseMedevac(reader: XmlReader, s: ParseState) {
        s.hasCasevacData = true
        s.casevacPrecedence = precedenceMap[reader.getAttributeValue(null, "precedence") ?: ""] ?: 0

        // Equipment flags bitfield
        var eq = 0
        if (reader.getAttributeValue(null, "hoist") == "true") eq = eq or 0x02
        if (reader.getAttributeValue(null, "extraction_equipment") == "true") eq = eq or 0x04
        if (reader.getAttributeValue(null, "ventilator") == "true") eq = eq or 0x08
        if (reader.getAttributeValue(null, "blood") == "true") eq = eq or 0x10
        if (reader.getAttributeValue(null, "none") == "true") eq = eq or 0x01
        s.casevacEquipmentFlags = eq

        s.casevacLitterPatients = reader.getAttributeValue(null, "litter")?.toIntOrNull() ?: 0
        s.casevacAmbulatoryPatients = reader.getAttributeValue(null, "ambulatory")?.toIntOrNull() ?: 0
        s.casevacSecurity = securityMap[reader.getAttributeValue(null, "security") ?: ""] ?: 0
        s.casevacHlzMarking = hlzMarkingMap[reader.getAttributeValue(null, "hlz_marking") ?: ""] ?: 0
        s.casevacZoneMarker = reader.getAttributeValue(null, "zone_prot_marker") ?: ""
        s.casevacUsMilitary = reader.getAttributeValue(null, "us_military")?.toIntOrNull() ?: 0
        s.casevacUsCivilian = reader.getAttributeValue(null, "us_civilian")?.toIntOrNull() ?: 0
        s.casevacNonUsMilitary = reader.getAttributeValue(null, "non_us_military")?.toIntOrNull() ?: 0
        s.casevacNonUsCivilian = reader.getAttributeValue(null, "non_us_civilian")?.toIntOrNull() ?: 0
        s.casevacEpw = reader.getAttributeValue(null, "epw")?.toIntOrNull() ?: 0
        s.casevacChild = reader.getAttributeValue(null, "child")?.toIntOrNull() ?: 0

        // Terrain flags bitfield
        var tf = 0
        if (reader.getAttributeValue(null, "terrain_slope") == "true") tf = tf or 0x01
        if (reader.getAttributeValue(null, "terrain_rough") == "true") tf = tf or 0x02
        if (reader.getAttributeValue(null, "terrain_loose") == "true") tf = tf or 0x04
        if (reader.getAttributeValue(null, "terrain_trees") == "true") tf = tf or 0x08
        if (reader.getAttributeValue(null, "terrain_wires") == "true") tf = tf or 0x10
        if (reader.getAttributeValue(null, "terrain_other") == "true") tf = tf or 0x20
        s.casevacTerrainFlags = tf
        s.casevacFrequency = reader.getAttributeValue(null, "freq") ?: ""
    }

    // ── Emergency element ───────────────────────────────────────────────────

    private fun parseEmergency(reader: XmlReader, s: ParseState) {
        s.hasEmergencyData = true
        val typeAttr = reader.getAttributeValue(null, "type") ?: ""
        s.emergencyTypeInt = emergencyTypeMap[typeAttr] ?: emergencyTypeFromCotType(s.cotTypeStr)
        if (reader.getAttributeValue(null, "cancel") == "true") {
            s.emergencyTypeInt = EMERGENCY_TYPE_CANCEL
        }
    }

    // ── Task element ────────────────────────────────────────────────────────

    private fun parseTask(reader: XmlReader, s: ParseState) {
        s.hasTaskData = true
        s.taskTypeTag = reader.getAttributeValue(null, "type") ?: ""
        s.taskPriority = taskPriorityMap[reader.getAttributeValue(null, "priority") ?: ""] ?: 0
        s.taskStatus = taskStatusMap[reader.getAttributeValue(null, "status") ?: ""] ?: 0
        val noteAttr = reader.getAttributeValue(null, "note") ?: ""
        if (noteAttr.isNotEmpty()) s.taskNote = noteAttr
        val assigneeAttr = reader.getAttributeValue(null, "assignee") ?: ""
        if (assigneeAttr.isNotEmpty()) s.taskAssigneeUid = assigneeAttr
    }

    // ── Link element — the most overloaded element in CoT ───────────────────

    /**
     * Parse a `<link>` element, dispatching to the correct sub-handler based
     * on its attributes. CoT overloads `<link>` for:
     * - Shape vertices (`point="lat,lon"`)
     * - Route waypoints (`point="lat,lon"` + `type="b-m-p-w"`)
     * - RAB anchors (`point="lat,lon"` on `u-rb-a` events)
     * - Chat receipts (`relation="p-p"` + `type="b-t-f"` on `b-t-f-d/r` events)
     * - Emergency authoring/cancel links (`relation="p-p"` on `b-a-*` events)
     * - Task target links (`relation="p-p"` on `t-s` events)
     * - Marker parent links (`relation="p-p"` on marker events)
     * - KML style links (filtered out by `type="b-x-KmlStyle"` or `.Style` suffix)
     */
    private fun parseLink(reader: XmlReader, s: ParseState) {
        val linkUidAttr = reader.getAttributeValue(null, "uid")
        val pointAttr = reader.getAttributeValue(null, "point")
        val linkType = reader.getAttributeValue(null, "type") ?: ""
        val relation = reader.getAttributeValue(null, "relation") ?: ""
        val linkCallsign = reader.getAttributeValue(null, "callsign") ?: ""
        val parentCallsignAttr = reader.getAttributeValue(null, "parent_callsign") ?: ""

        // Ignore style links nested inside <shape>
        val isStyleLink = linkType.startsWith("b-x-KmlStyle") ||
            (linkUidAttr != null && linkUidAttr.endsWith(".Style"))
        if (isStyleLink) return

        if (pointAttr != null) {
            parseLinkWithPoint(s, pointAttr, linkUidAttr, linkType, linkCallsign)
        } else if (linkUidAttr != null && relation == "p-p" && linkType.isNotEmpty()) {
            parseLinkRelation(s, linkUidAttr, linkType, parentCallsignAttr)
        }
    }

    /** Handle a `<link>` element that carries a `point="lat,lon"` attribute. */
    private fun parseLinkWithPoint(
        s: ParseState,
        pointAttr: String,
        linkUidAttr: String?,
        linkType: String,
        linkCallsign: String,
    ) {
        val parts = pointAttr.split(",")
        if (parts.size < 2) return

        // Trim whitespace — iTAK uses "lat, lon" with a space after comma
        val plat = parts[0].trim().toDoubleOrNull() ?: 0.0
        val plon = parts[1].trim().toDoubleOrNull() ?: 0.0
        val plati = (plat * 1e7).roundToInt()
        val ploni = (plon * 1e7).roundToInt()

        when {
            // RAB anchor: u-rb-a is the ONLY event type that uses <link>
            // as its anchor. Check cotType first so a ranging-line link
            // never escalates to a one-waypoint "route".
            s.cotTypeStr == "u-rb-a" -> {
                if (s.rabAnchorLatI == 0 && s.rabAnchorLonI == 0) {
                    s.rabAnchorLatI = plati
                    s.rabAnchorLonI = ploni
                    if (linkUidAttr != null) s.rabAnchorUid = linkUidAttr
                }
                s.hasRabData = true
            }
            // Route waypoint/checkpoint — only for b-m-r events
            (linkType == "b-m-p-w" || linkType == "b-m-p-c") && s.cotTypeStr == "b-m-r" -> {
                if (s.routeLinks.size < MAX_ROUTE_LINKS) {
                    s.routeLinks.add(
                        TakPacketV2Data.Payload.Route.Link(
                            latI = plati, lonI = ploni,
                            uid = linkUidAttr ?: "",
                            callsign = linkCallsign,
                            linkType = if (linkType == "b-m-p-c") 1 else 0,
                        )
                    )
                } else {
                    s.routeTruncated = true
                }
                s.hasRouteData = true
            }
            // Shape vertex (rectangle, freeform, polygon, telestration)
            else -> {
                if (s.vertices.size < MAX_VERTICES) {
                    s.vertices.add(TakPacketV2Data.Payload.Vertex(plati, ploni))
                    s.hasShapeData = true
                } else {
                    s.verticesTruncated = true
                }
            }
        }
    }

    /**
     * Handle a `<link>` element with `relation="p-p"` and no `point=` attr.
     * These carry relationship references: chat receipts, emergency authoring,
     * task targets, and marker parents.
     */
    private fun parseLinkRelation(
        s: ParseState,
        linkUidAttr: String,
        linkType: String,
        parentCallsignAttr: String,
    ) {
        when {
            // Chat receipt
            s.cotTypeStr == "b-t-f-d" || s.cotTypeStr == "b-t-f-r" -> {
                if (s.chatReceiptForUid.isEmpty()) s.chatReceiptForUid = linkUidAttr
                s.chatReceiptType = if (s.cotTypeStr == "b-t-f-d") {
                    RECEIPT_TYPE_DELIVERED
                } else {
                    RECEIPT_TYPE_READ
                }
                s.hasChatData = true
            }
            // Task target link
            s.cotTypeStr == "t-s" -> {
                if (s.taskTargetUid.isEmpty()) s.taskTargetUid = linkUidAttr
                s.hasTaskData = true
            }
            // Emergency authoring / cancel-reference
            s.cotTypeStr.startsWith("b-a-") -> {
                if (linkType.startsWith("b-a-")) {
                    if (s.emergencyCancelReferenceUid.isEmpty()) {
                        s.emergencyCancelReferenceUid = linkUidAttr
                    }
                } else {
                    if (s.emergencyAuthoringUid.isEmpty()) {
                        s.emergencyAuthoringUid = linkUidAttr
                    }
                }
                s.hasEmergencyData = true
            }
            // Marker parent link
            else -> {
                s.markerParentUid = linkUidAttr
                s.markerParentType = linkType
                if (parentCallsignAttr.isNotEmpty()) s.markerParentCallsign = parentCallsignAttr
                s.hasMarkerData = true
            }
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    //  Post-processing and result construction
    // ════════════════════════════════════════════════════════════════════════

    /**
     * Enrich aircraft data from remarks text when `<_aircot_>` is absent.
     * ICAO, registration, flight, squawk, etc. may be embedded in remarks
     * by older ATAK versions or third-party ADS-B plugins.
     */
    private fun enrichAircraftFromRemarks(s: ParseState) {
        if (s.remarksText.isEmpty() || s.icao.isNotEmpty()) return

        val icaoMatch = ICAO_REGEX.find(s.remarksText) ?: return
        s.hasAircraftData = true
        s.icao = icaoMatch.groupValues[1]
        if (s.registration.isEmpty()) s.registration = REG_REGEX.find(s.remarksText)?.groupValues?.get(1) ?: ""
        if (s.flight.isEmpty()) s.flight = FLIGHT_REGEX.find(s.remarksText)?.groupValues?.get(1) ?: ""
        if (s.aircraftType.isEmpty()) s.aircraftType = TYPE_REGEX.find(s.remarksText)?.groupValues?.get(1) ?: ""
        if (s.squawk == 0) s.squawk = SQUAWK_REGEX.find(s.remarksText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val catMatch = CATEGORY_REGEX.find(s.remarksText)
        if (catMatch != null && s.category.isEmpty()) s.category = catMatch.groupValues[1]
    }

    /**
     * Build the final [TakPacketV2Data] from accumulated [ParseState].
     *
     * Payload priority: chat > aircraft > route > rab > shape > marker >
     * casevac > emergency > task > pli.
     */
    private fun buildResult(s: ParseState): TakPacketV2Data {
        val staleSeconds = computeStaleSeconds(s.timeStr, s.staleStr)
        val cotTypeId = CotTypeMapper.typeToEnum(s.cotTypeStr)
        val cotTypeStrField = if (cotTypeId == CotTypeMapper.COTTYPE_OTHER) s.cotTypeStr else null
        val payload = resolvePayload(s)

        return TakPacketV2Data(
            cotTypeId = cotTypeId,
            cotTypeStr = cotTypeStrField,
            how = CotTypeMapper.howToEnum(s.howStr),
            callsign = s.callsign,
            team = teamNameToEnum[s.teamName] ?: 0,
            role = roleNameToEnum[s.roleName] ?: 0,
            latitudeI = (s.lat * 1e7).roundToInt(),
            longitudeI = (s.lon * 1e7).roundToInt(),
            altitude = s.hae.roundToInt(),
            // Proto type is uint32 (cm/s / deg*100). ATAK emits speed=-1.0
            // as a stationary/unknown sentinel — clamp negatives to 0.
            speed = maxOf(0, (s.speed * 100).roundToInt()),
            course = maxOf(0, (s.course * 100).roundToInt()),
            battery = s.battery,
            geoSrc = parseGeoSrc(s.geoSrc),
            altSrc = parseGeoSrc(s.altSrc),
            uid = s.uid,
            deviceCallsign = s.deviceCallsign,
            staleSeconds = staleSeconds,
            takVersion = s.takVersion,
            takDevice = s.takDevice,
            takPlatform = s.takPlatform,
            takOs = s.takOs,
            endpoint = s.endpoint,
            phone = s.phone,
            remarks = if (s.hasChatData) "" else s.remarksText,
            payload = payload,
        )
    }

    /**
     * Select the correct [TakPacketV2Data.Payload] variant from the flags and
     * accumulators in [ParseState], following the canonical priority order.
     */
    private fun resolvePayload(s: ParseState): TakPacketV2Data.Payload {
        // Derive the stroke/fill/both discriminator from what we observed
        val shapeStyle = when {
            s.sawStrokeColor && s.sawFillColor -> STYLE_STROKE_AND_FILL
            s.sawStrokeColor -> STYLE_STROKE_ONLY
            s.sawFillColor -> STYLE_FILL_ONLY
            else -> STYLE_UNSPECIFIED
        }

        return when {
            s.hasChatData -> TakPacketV2Data.Payload.Chat(
                message = s.remarksText,
                to = s.chatTo,
                toCallsign = s.chatToCallsign,
                receiptForUid = s.chatReceiptForUid,
                receiptType = s.chatReceiptType,
            )
            s.hasAircraftData -> TakPacketV2Data.Payload.Aircraft(
                icao = s.icao,
                registration = s.registration,
                flight = s.flight,
                aircraftType = s.aircraftType,
                squawk = s.squawk,
                category = s.category,
                rssiX10 = s.rssiX10,
                gps = s.gps,
                cotHostId = s.cotHostId,
            )
            s.hasRouteData && s.routeLinks.isNotEmpty() -> TakPacketV2Data.Payload.Route(
                method = s.routeMethod,
                direction = s.routeDirection,
                prefix = s.routePrefix,
                strokeWeightX10 = s.strokeWeightX10,
                links = s.routeLinks.toList(),
                truncated = s.routeTruncated,
            )
            s.hasRabData -> TakPacketV2Data.Payload.RangeAndBearing(
                anchorLatI = s.rabAnchorLatI,
                anchorLonI = s.rabAnchorLonI,
                anchorUid = s.rabAnchorUid,
                rangeCm = s.rabRangeCm,
                bearingCdeg = s.rabBearingCdeg,
                strokeColor = AtakPalette.argbToTeam(s.strokeColorArgb),
                strokeArgb = s.strokeColorArgb,
                strokeWeightX10 = s.strokeWeightX10,
            )
            s.hasShapeData -> TakPacketV2Data.Payload.DrawnShape(
                kind = shapeKindFromCotType(s.cotTypeStr),
                style = shapeStyle,
                majorCm = s.shapeMajorCm,
                minorCm = s.shapeMinorCm,
                angleDeg = s.shapeAngleDeg,
                strokeColor = AtakPalette.argbToTeam(s.strokeColorArgb),
                strokeArgb = s.strokeColorArgb,
                strokeWeightX10 = s.strokeWeightX10,
                fillColor = AtakPalette.argbToTeam(s.fillColorArgb),
                fillArgb = s.fillColorArgb,
                labelsOn = s.labelsOn,
                vertices = s.vertices.toList(),
                truncated = s.verticesTruncated,
                bullseyeDistanceDm = s.bullseyeDistanceDm,
                bullseyeBearingRef = s.bullseyeBearingRef,
                bullseyeFlags = s.bullseyeFlags,
                bullseyeUidRef = s.bullseyeUidRef,
            )
            s.hasMarkerData -> TakPacketV2Data.Payload.Marker(
                kind = markerKindFromCotType(s.cotTypeStr, s.markerIconset),
                color = AtakPalette.argbToTeam(s.markerColorArgb),
                colorArgb = s.markerColorArgb,
                readiness = s.markerReadiness,
                parentUid = s.markerParentUid,
                parentType = s.markerParentType,
                parentCallsign = s.markerParentCallsign,
                iconset = s.markerIconset,
            )
            s.hasCasevacData -> TakPacketV2Data.Payload.CasevacReport(
                precedence = s.casevacPrecedence,
                equipmentFlags = s.casevacEquipmentFlags,
                litterPatients = s.casevacLitterPatients,
                ambulatoryPatients = s.casevacAmbulatoryPatients,
                security = s.casevacSecurity,
                hlzMarking = s.casevacHlzMarking,
                zoneMarker = s.casevacZoneMarker,
                usMilitary = s.casevacUsMilitary,
                usCivilian = s.casevacUsCivilian,
                nonUsMilitary = s.casevacNonUsMilitary,
                nonUsCivilian = s.casevacNonUsCivilian,
                epw = s.casevacEpw,
                child = s.casevacChild,
                terrainFlags = s.casevacTerrainFlags,
                frequency = s.casevacFrequency,
            )
            s.hasEmergencyData -> TakPacketV2Data.Payload.EmergencyAlert(
                type = if (s.emergencyTypeInt != 0) s.emergencyTypeInt
                       else emergencyTypeFromCotType(s.cotTypeStr),
                authoringUid = s.emergencyAuthoringUid,
                cancelReferenceUid = s.emergencyCancelReferenceUid,
            )
            s.hasTaskData -> TakPacketV2Data.Payload.TaskRequest(
                taskType = s.taskTypeTag,
                targetUid = s.taskTargetUid,
                assigneeUid = s.taskAssigneeUid,
                priority = s.taskPriority,
                status = s.taskStatus,
                note = s.taskNote,
            )
            s.isDeleteEvent -> TakPacketV2Data.Payload.Pli(true)
            else -> TakPacketV2Data.Payload.Pli(true)
        }
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
