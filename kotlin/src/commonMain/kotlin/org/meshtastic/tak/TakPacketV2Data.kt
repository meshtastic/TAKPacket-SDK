package org.meshtastic.tak

/**
 * Platform-agnostic data class representing a parsed TAKPacketV2 CoT event.
 *
 * This is the SDK's canonical in-memory representation, fully decoupled from
 * both the source XML and the Wire KMP protobuf classes. All coordinates are
 * stored as fixed-point integers (degrees * 1e7) for lossless round-trip
 * through the protobuf wire format.
 *
 * ## Lifecycle
 *
 * ```
 * CoT XML ──► CotXmlParser.parse() ──► TakPacketV2Data
 *     ──► TakCompressor.compress()  ──► wire payload (bytes)
 *     ──► TakCompressor.decompress()──► TakPacketV2Data
 *     ──► CotXmlBuilder.build()     ──► CoT XML
 * ```
 *
 * @property cotTypeId   Enum-mapped CoT type (e.g. [CotTypeMapper.COTTYPE_A_F_G_U_C]).
 *                       [CotTypeMapper.COTTYPE_OTHER] when the type string has no known mapping.
 * @property cotTypeStr  Original CoT type string, set only when [cotTypeId] is
 *                       [CotTypeMapper.COTTYPE_OTHER]. `null` otherwise to save wire bytes.
 * @property how         Enum-mapped CoT "how" attribute (e.g. [CotTypeMapper.COTHOW_M_G]).
 * @property callsign    Human-readable station identifier from `<contact callsign="…"/>`.
 * @property team        `Team` enum value (1..14) from `<__group name="…"/>`, or 0 if absent.
 * @property role        `MemberRole` enum value from `<__group role="…"/>`, or 0 if absent.
 * @property latitudeI   Latitude in degrees * 1e7 (e.g. 377749000 = 37.7749°N).
 * @property longitudeI  Longitude in degrees * 1e7.
 * @property altitude    Altitude in meters above the WGS-84 ellipsoid (HAE).
 * @property speed       Ground speed in centimeters per second.
 * @property course      Heading in degrees * 100 (0..36000).
 * @property battery     Battery percentage (0..100), from `<status battery="…"/>`.
 * @property geoSrc      `GeoPointSource` enum for the position fix source.
 * @property altSrc      `GeoPointSource` enum for the altitude fix source.
 * @property uid         Unique event identifier from the `<event uid="…">` attribute.
 * @property deviceCallsign  Device-level callsign from `<uid Droid="…"/>`.
 * @property staleSeconds    Seconds between the event's `time` and `stale` timestamps.
 * @property takVersion  TAK client version string from `<takv version="…"/>`.
 * @property takDevice   TAK client device model from `<takv device="…"/>`.
 * @property takPlatform TAK client platform name from `<takv platform="…"/>`.
 * @property takOs       TAK client OS version from `<takv os="…"/>`.
 * @property endpoint    TAK server endpoint from `<contact endpoint="…"/>`.
 *                       Empty when the source uses a default endpoint.
 * @property phone       Phone number from `<contact phone="…"/>`, if present.
 * @property remarks     Free-text remarks from `<remarks>` (empty for chat payloads).
 * @property payload     Typed payload variant — see [Payload] sealed interface.
 */
public data class TakPacketV2Data(
    val cotTypeId: Int = CotTypeMapper.COTTYPE_OTHER,
    val cotTypeStr: String? = null,
    val how: Int = CotTypeMapper.COTHOW_UNSPECIFIED,
    val callsign: String = "",
    val team: Int = 0,
    val role: Int = 0,
    val latitudeI: Int = 0,
    val longitudeI: Int = 0,
    val altitude: Int = 0,
    val speed: Int = 0,
    val course: Int = 0,
    val battery: Int = 0,
    val geoSrc: Int = 0,
    val altSrc: Int = 0,
    val uid: String = "",
    val deviceCallsign: String = "",
    val staleSeconds: Int = 0,
    val takVersion: String = "",
    val takDevice: String = "",
    val takPlatform: String = "",
    val takOs: String = "",
    val endpoint: String = "",
    val phone: String = "",
    val remarks: String = "",
    val payload: Payload = Payload.None,
) {
    /**
     * Discriminated payload variant carried by a [TakPacketV2Data].
     *
     * The parser selects the most specific variant that matches the incoming
     * CoT event. Priority (highest to lowest): [Chat] > [Aircraft] > [Route] >
     * [RangeAndBearing] > [DrawnShape] > [Marker] > [CasevacReport] >
     * [EmergencyAlert] > [TaskRequest] > [Pli].
     *
     * [RawDetail] is never produced by the parser; it is used by
     * [TakCompressor.compressBestOf] as a lossless fallback that carries
     * the original `<detail>` bytes verbatim.
     */
    public sealed interface Payload {
        /** No payload data — used as a default sentinel. */
        public data object None : Payload

        /** Position Location Information (PLI) — the most common CoT event type. */
        public data class Pli(val value: Boolean = true) : Payload
        /**
         * ATAK GeoChat message — both regular chat (b-t-f) and delivered /
         * read receipts (b-t-f-d / b-t-f-r). Receipts leave [message] empty
         * and set [receiptForUid] + [receiptType] to link back to the
         * outbound message's event UID.
         */
        public data class Chat(
            val message: String = "",
            val to: String? = null,
            val toCallsign: String? = null,
            /** UID of the chat message this event is acknowledging. */
            val receiptForUid: String = "",
            /** Receipt kind: 0 = none (regular chat), 1 = delivered, 2 = read. */
            val receiptType: Int = 0,
        ) : Payload
        /**
         * ADS-B aircraft track (aircot feed).
         *
         * Represents an aircraft position report received via ADS-B and
         * forwarded into the TAK ecosystem. Maps to the `AirCot` protobuf
         * message at payload_variant tag 33.
         */
        public data class Aircraft(
            val icao: String = "",
            val registration: String = "",
            val flight: String = "",
            val aircraftType: String = "",
            val squawk: Int = 0,
            val category: String = "",
            val rssiX10: Int = 0,
            val gps: Boolean = false,
            val cotHostId: String = "",
        ) : Payload
        public data class RawDetail(val bytes: ByteArray) : Payload {
            override fun equals(other: Any?): Boolean =
                other is RawDetail && bytes.contentEquals(other.bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
        }

        // --- Typed geometry payloads (v2 protocol extension) ---------------
        //
        // Every color is a pair of parallel fields: a palette enum value
        // (matching the Team enum in atak.proto) plus an exact ARGB bit
        // pattern fallback. See AtakPalette for the bidirectional lookup.

        /**
         * Compact vertex used by polyline / polygon / rectangle shapes. Stored
         * at the SDK level as `Int` pairs to match the 1e7-scaled coordinate
         * convention used by TakPacketV2Data.latitudeI / longitudeI.
         *
         * @property latI Latitude in degrees * 1e7 (e.g. 377749000 = 37.7749°N).
         * @property lonI Longitude in degrees * 1e7.
         */
        public data class Vertex(val latI: Int, val lonI: Int)

        /**
         * User-drawn tactical graphic: circle, rectangle, polygon, polyline,
         * telestration, ranging circle, or bullseye.
         *
         * Maps to the `DrawnShape` protobuf message at payload_variant tag 34.
         * See atak.proto for the full field-by-field documentation.
         */
        public data class DrawnShape(
            /** One of the Kind_* constants (1..7); see atak.proto. */
            val kind: Int = 0,
            /**
             * Explicit stroke/fill/both discriminator. One of:
             *   0 = Unspecified (infer from which color fields are non-zero)
             *   1 = StrokeOnly
             *   2 = FillOnly
             *   3 = StrokeAndFill
             */
            val style: Int = 0,
            /** Major axis/radius in centimeters (circle radius, rectangle half-width). */
            val majorCm: Int = 0,
            /** Minor axis/radius in centimeters (0 for non-ellipse shapes). */
            val minorCm: Int = 0,
            /** Rotation angle in degrees (0..360); defaults to 360 (= no rotation). */
            val angleDeg: Int = 360,
            /** Team enum value, or AtakPalette.UNSPECIFIED if not in palette. */
            val strokeColor: Int = 0,
            /** Exact 32-bit ARGB bit pattern (always set by the parser). */
            val strokeArgb: Int = 0,
            val strokeWeightX10: Int = 0,
            /** Team enum value; AtakPalette.UNSPECIFIED for custom fill. */
            val fillColor: Int = 0,
            val fillArgb: Int = 0,
            val labelsOn: Boolean = false,
            /** Capped at MAX_VERTICES in the parser; sender sets [truncated] when exceeded. */
            val vertices: List<Vertex> = emptyList(),
            val truncated: Boolean = false,
            // --- Bullseye-only fields (ignored unless kind == Kind_Bullseye) ---
            val bullseyeDistanceDm: Int = 0,
            /** 0 unset, 1 Magnetic, 2 True, 3 Grid. */
            val bullseyeBearingRef: Int = 0,
            /** bit0 rangeRingVisible, bit1 hasRangeRings, bit2 edgeToCenter, bit3 mils. */
            val bullseyeFlags: Int = 0,
            val bullseyeUidRef: String = "",
        ) : Payload

        /**
         * Fixed marker: spot, waypoint, checkpoint, 2525 symbol, or custom icon.
         * Maps to the `Marker` protobuf message at payload_variant tag 35.
         */
        public data class Marker(
            /** One of Marker.Kind values in atak.proto (1..7). */
            val kind: Int = 0,
            val color: Int = 0,
            val colorArgb: Int = 0,
            val readiness: Boolean = false,
            val parentUid: String = "",
            val parentType: String = "",
            val parentCallsign: String = "",
            val iconset: String = "",
        ) : Payload

        /**
         * Range and bearing measurement line. Maps to `RangeAndBearing` proto
         * at payload_variant tag 36. Anchor endpoint is absolute lat/lon, not
         * a delta from the event point.
         */
        public data class RangeAndBearing(
            val anchorLatI: Int = 0,
            val anchorLonI: Int = 0,
            val anchorUid: String = "",
            /** Range in centimeters. */
            val rangeCm: Int = 0,
            /** Bearing in degrees * 100 (0..36000). */
            val bearingCdeg: Int = 0,
            val strokeColor: Int = 0,
            val strokeArgb: Int = 0,
            val strokeWeightX10: Int = 0,
        ) : Payload

        /**
         * Named route consisting of ordered waypoints and control points.
         * Maps to `Route` at payload_variant tag 37.
         *
         * Link count is capped at MAX_ROUTE_LINKS (16) by the parser; longer
         * routes are truncated with [truncated] set true.
         */
        public data class Route(
            /** Travel method: 0 unspec, 1 Driving, 2 Walking, 3 Flying, 4 Swimming, 5 Watercraft. */
            val method: Int = 0,
            /** Direction: 0 unspec, 1 Infil, 2 Exfil. */
            val direction: Int = 0,
            val prefix: String = "",
            val strokeWeightX10: Int = 0,
            val links: List<Link> = emptyList(),
            val truncated: Boolean = false,
        ) : Payload {
            /** Route waypoint or control point. */
            public data class Link(
                val latI: Int = 0,
                val lonI: Int = 0,
                val uid: String = "",
                val callsign: String = "",
                /** 0 = waypoint (b-m-p-w), 1 = checkpoint (b-m-p-c). */
                val linkType: Int = 0,
            )
        }

        // --- Expanded structured payloads (tag 38/39/40) -------------------

        /**
         * 9-line MEDEVAC request (CoT type `b-r-f-h-c`).
         *
         * Maps to the `CasevacReport` protobuf message at payload_variant
         * tag 38. The envelope (uid, cot_type_id, latitude_i/longitude_i,
         * altitude, callsign) carries Line 1 (location) and Line 2 (callsign);
         * the fields below carry the structured 9-line record.
         *
         * Every numeric field defaults to 0 (proto3 default); senders omit
         * lines they don't have. `precedence`, `hlzMarking`, `security`
         * are enum int values matching `CasevacReport.*` in atak.proto.
         */
        public data class CasevacReport(
            /** One of Precedence_* constants (1..5). */
            val precedence: Int = 0,
            /**
             * Line 4 equipment bitfield.
             * bit 0 = none, 1 = hoist, 2 = extraction, 3 = ventilator, 4 = blood.
             */
            val equipmentFlags: Int = 0,
            /** Line 5 litter (stretcher-bound) patient count. */
            val litterPatients: Int = 0,
            /** Line 5 ambulatory (walking) patient count. */
            val ambulatoryPatients: Int = 0,
            /** One of Security_* constants (1..4). */
            val security: Int = 0,
            /** One of HlzMarking_* constants (1..5). */
            val hlzMarking: Int = 0,
            /** Line 7 supplementary zone marker text (e.g. "Green smoke"). */
            val zoneMarker: String = "",
            // Line 8 patient nationality counts
            val usMilitary: Int = 0,
            val usCivilian: Int = 0,
            val nonUsMilitary: Int = 0,
            val nonUsCivilian: Int = 0,
            val epw: Int = 0,
            val child: Int = 0,
            /**
             * Line 9 terrain/obstacles bitfield.
             * bit 0 = slope, 1 = rough, 2 = loose, 3 = trees, 4 = wires, 5 = other.
             */
            val terrainFlags: Int = 0,
            /** Line 2 radio frequency / callsign metadata. */
            val frequency: String = "",
        ) : Payload

        /**
         * Emergency alert / 911 beacon. Covers CoT types b-a-o-tbl (911),
         * b-a-o-pan (ring the bell), b-a-o-opn (in contact), b-a-g (geo-fence
         * breached), b-a-o-c (custom), b-a-o-can (cancel).
         *
         * Maps to the `EmergencyAlert` protobuf message at payload_variant
         * tag 39.
         */
        public data class EmergencyAlert(
            /** One of Type_* constants (1..6): 911, RingTheBell, InContact, GeoFenceBreached, Custom, Cancel. */
            val type: Int = 0,
            /** UID of the unit raising the alert. */
            val authoringUid: String = "",
            /** For Type_Cancel: UID of the alert being cancelled. Empty otherwise. */
            val cancelReferenceUid: String = "",
        ) : Payload

        /**
         * Task / engage request (CoT type `t-s`).
         *
         * Maps to the `TaskRequest` protobuf message at payload_variant
         * tag 40. The requester UID is implicit from TAKPacketV2.uid.
         */
        public data class TaskRequest(
            /** Short task category tag (e.g. "engage", "observe", "recon"). */
            val taskType: String = "",
            /** UID of the map item being tasked. */
            val targetUid: String = "",
            /** UID of the assigned unit. Empty = unassigned / broadcast. */
            val assigneeUid: String = "",
            /** One of Priority_* constants (1..4). */
            val priority: Int = 0,
            /** One of Status_* constants (1..5). */
            val status: Int = 0,
            val note: String = "",
        ) : Payload
    }

    /** Convenience: get the CoT type as a string, resolving enum or fallback. */
    public fun cotTypeString(): String =
        CotTypeMapper.typeToString(cotTypeId) ?: cotTypeStr ?: ""

    /** Convenience: get the how as a string. */
    public fun howString(): String =
        CotTypeMapper.howToString(how) ?: ""
}
