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
            else -> when {
                iconset.startsWith("COT_MAPPING_2525B") -> MARKER_KIND_SYMBOL_2525
                iconset.startsWith("COT_MAPPING_SPOTMAP") -> MARKER_KIND_SPOT_MAP
                iconset.isNotEmpty() -> MARKER_KIND_CUSTOM_ICON
                else -> MARKER_KIND_UNSPECIFIED
            }
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
                                val majorM = parser.getAttributeValue(null, "major")?.toDoubleOrNull() ?: 0.0
                                val minorM = parser.getAttributeValue(null, "minor")?.toDoubleOrNull() ?: 0.0
                                shapeMajorCm = (majorM * 100).roundToInt().coerceAtLeast(0)
                                shapeMinorCm = (minorM * 100).roundToInt().coerceAtLeast(0)
                                shapeAngleDeg = parser.getAttributeValue(null, "angle")?.toIntOrNull() ?: 360
                                hasShapeData = true
                            }
                        }
                        "strokeColor" -> {
                            sawStrokeColor = true
                            strokeColorArgb = parser.getAttributeValue(null, "value")?.toIntOrNull() ?: 0
                            hasShapeData = true
                        }
                        "strokeWeight" -> {
                            val w = parser.getAttributeValue(null, "value")?.toDoubleOrNull() ?: 0.0
                            strokeWeightX10 = (w * 10).roundToInt().coerceAtLeast(0)
                            hasShapeData = true
                        }
                        "fillColor" -> {
                            sawFillColor = true
                            fillColorArgb = parser.getAttributeValue(null, "value")?.toIntOrNull() ?: 0
                            hasShapeData = true
                        }
                        "labels_on" -> {
                            labelsOn = parser.getAttributeValue(null, "value") == "true"
                        }
                        "color" -> {
                            // Marker color: <color argb="-65536"/>. Distinct from
                            // <strokeColor>/<fillColor> which use `value=`.
                            markerColorArgb = parser.getAttributeValue(null, "argb")?.toIntOrNull() ?: 0
                            hasMarkerData = true
                        }
                        "usericon" -> {
                            markerIconset = parser.getAttributeValue(null, "iconsetpath") ?: ""
                            if (markerIconset.isNotEmpty()) hasMarkerData = true
                        }
                        // --- Bullseye -------------------------------------
                        "bullseye" -> {
                            hasBullseyeData = true
                            hasShapeData = true
                            val distStr = parser.getAttributeValue(null, "distance")
                            bullseyeDistanceDm = ((distStr?.toDoubleOrNull() ?: 0.0) * 10).roundToInt().coerceAtLeast(0)
                            bullseyeBearingRef = bearingRefMap[parser.getAttributeValue(null, "bearingRef")] ?: 0
                            var flags = 0
                            if (parser.getAttributeValue(null, "rangeRingVisible") == "true") flags = flags or 0x01
                            if (parser.getAttributeValue(null, "hasRangeRings") == "true") flags = flags or 0x02
                            if (parser.getAttributeValue(null, "edgeToCenter") == "true") flags = flags or 0x04
                            if (parser.getAttributeValue(null, "mils") == "true") flags = flags or 0x08
                            bullseyeFlags = flags
                            bullseyeUidRef = parser.getAttributeValue(null, "bullseyeUID") ?: ""
                        }
                        // --- Range and bearing ----------------------------
                        "range" -> {
                            val v = parser.getAttributeValue(null, "value")?.toDoubleOrNull() ?: 0.0
                            rabRangeCm = (v * 100).roundToInt().coerceAtLeast(0)
                            hasRabData = true
                        }
                        "bearing" -> {
                            val v = parser.getAttributeValue(null, "value")?.toDoubleOrNull() ?: 0.0
                            rabBearingCdeg = (v * 100).roundToInt().coerceAtLeast(0)
                            hasRabData = true
                        }
                        // --- Route ----------------------------------------
                        "__routeinfo" -> {
                            hasRouteData = true
                        }
                        "link_attr" -> {
                            hasRouteData = true
                            routePrefix = parser.getAttributeValue(null, "prefix") ?: ""
                            routeMethod = routeMethodMap[parser.getAttributeValue(null, "method")] ?: 0
                            routeDirection = routeDirectionMap[parser.getAttributeValue(null, "direction")] ?: 0
                            val sw = parser.getAttributeValue(null, "stroke")?.toIntOrNull() ?: 0
                            if (sw > 0) strokeWeightX10 = sw * 10
                        }
                        // --- Generic link: the most overloaded element in CoT
                        "link" -> {
                            val linkUidAttr = parser.getAttributeValue(null, "uid")
                            val pointAttr = parser.getAttributeValue(null, "point")
                            val linkType = parser.getAttributeValue(null, "type") ?: ""
                            val relation = parser.getAttributeValue(null, "relation") ?: ""
                            val linkCallsign = parser.getAttributeValue(null, "callsign") ?: ""
                            val parentCallsignAttr = parser.getAttributeValue(null, "parent_callsign") ?: ""

                            // Preserve the original "first link uid wins" behavior
                            // used by the chat path for linking back to senders.
                            if (linkUidAttr != null && linkUid.isEmpty()) {
                                linkUid = linkUidAttr
                            }

                            // Ignore style links nested inside <shape> (type="b-x-KmlStyle").
                            // Their `uid` ends in ".Style" and they carry styling, not
                            // geometry or relationships we care about.
                            val isStyleLink = linkType.startsWith("b-x-KmlStyle") ||
                                (linkUidAttr != null && linkUidAttr.endsWith(".Style"))

                            if (!isStyleLink && pointAttr != null) {
                                val parts = pointAttr.split(",")
                                if (parts.size >= 2) {
                                    val plat = parts[0].toDoubleOrNull() ?: 0.0
                                    val plon = parts[1].toDoubleOrNull() ?: 0.0
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
                XmlPullParser.END_TAG -> {
                    if (parser.name == "shape") inShape = false
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

        // Derive the stroke/fill/both discriminator from what we observed.
        val shapeStyle = when {
            sawStrokeColor && sawFillColor -> STYLE_STROKE_AND_FILL
            sawStrokeColor -> STYLE_STROKE_ONLY
            sawFillColor -> STYLE_FILL_ONLY
            else -> STYLE_UNSPECIFIED
        }

        // Payload priority: chat > aircraft > route > rab > shape > marker > pli.
        //
        // Rationale for ordering:
        //   * Chat and aircraft win first for backward compatibility with the
        //     pre-v2 behavior.
        //   * Route wins over RAB because a route also has <link type="b-m-p-w">
        //     entries that could otherwise look like RAB anchors.
        //   * Shape wins over marker because a shape event can have a <contact>
        //     (that looks marker-ish) alongside real shape geometry.
        //   * Markers win over the Pli fallback whenever we saw marker-specific
        //     detail like <usericon> or <color argb="...">.
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
        val match = Regex(
            """<detail\b[^>]*>(.*?)</detail\s*>""",
            setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE),
        ).find(cotXml)
        val inner = match?.groupValues?.get(1) ?: return ByteArray(0)
        return inner.toByteArray(Charsets.UTF_8)
    }
}
