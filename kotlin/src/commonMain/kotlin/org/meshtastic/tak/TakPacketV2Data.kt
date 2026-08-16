package org.meshtastic.tak

/**
 * Platform-agnostic data model for a `TAKPacketV2` — the SDK's internal
 * representation, decoupled from any specific protobuf library.
 *
 * The parser ([CotXmlParser]) produces it from CoT XML; the builder
 * ([CotXmlBuilder]) renders it back; [TakPacketV2Serializer] converts it
 * to/from protobuf wire bytes (each platform uses its native protobuf library).
 *
 * Shape: 26 envelope fields (the always-present scalars below) plus a single
 * [payload] of type [Payload] (a sealed hierarchy with one variant per `oneof`
 * arm). Two optional top-level annotations — [environment] and [sensorFov] —
 * attach to any payload variant. **PLI is implicit:** there is no `bool pli`
 * field; a packet whose [payload] is [Payload.None] (modeled here as
 * [Payload.Pli]) and whose [cotTypeId] is an `a-f-*` friendly type IS a PLI
 * position report.
 *
 * All numeric fields carry the wire units documented per-property below
 * (degrees×1e7 coordinates, cm/s speed, degrees×100 course, meters-HAE
 * altitude); the data model stores already-converted integers, not floats.
 *
 * @property cotTypeId well-known CoT type as a `CotType` enum int (see
 *   [CotTypeMapper]); [CotTypeMapper.COTTYPE_OTHER] when the type is unknown.
 * @property cotTypeStr raw CoT type string, set only when [cotTypeId] is
 *   [CotTypeMapper.COTTYPE_OTHER] so unknown types round-trip losslessly; `null` otherwise.
 * @property how coordinate-generation method as a `CotHow` enum int (see [CotTypeMapper]).
 * @property callsign the unit / operator callsign.
 * @property team `Team` palette enum value (1..14; see [AtakPalette]), 0 = unspecified.
 * @property role `MemberRole` enum value, 0 = unspecified.
 * @property latitudeI latitude in degrees × 1e7 (sfixed32 wire convention).
 * @property longitudeI longitude in degrees × 1e7 (sfixed32 wire convention).
 * @property altitude altitude in meters HAE (height above ellipsoid); may be negative.
 * @property speed ground speed in cm/s (ATAK m/s × 100); clamped to ≥ 0.
 * @property course course over ground in degrees × 100 (0..36000); clamped to ≥ 0.
 * @property battery battery charge, 0–100 percent (0 = not reported).
 * @property geoSrc position source as a `GeoPointSource` enum int
 *   (see the `GEOSRC_*` constants in [CotXmlParser]).
 * @property altSrc altitude source as a `GeoPointSource` enum int.
 * @property uid the CoT event UID (kept as a string — never packed to raw bytes).
 * @property deviceCallsign device identifier from `<uid Droid="…"/>`.
 * @property staleSeconds seconds from the event's `time` to its `stale`
 *   timestamp; the builder floors this at 45 s on emit.
 * @property takVersion TAK client version string.
 * @property takDevice TAK device model string.
 * @property takPlatform TAK platform (`ATAK-CIV`, `iTAK`, `WinTAK`).
 * @property takOs TAK client OS string.
 * @property endpoint contact endpoint; not carried over the mesh (never stored on
 *   parse, always emitted as the TAK server-reply form on build).
 * @property phone contact phone number.
 * @property remarks optional free-text `<remarks>` for non-chat payload types;
 *   stripped by [TakCompressor.compressWithRemarksFallback] when over MTU.
 */
public data class TakPacketV2Data(
    val cotTypeId: Int = CotTypeMapper.COTTYPE_OTHER,
    val cotTypeStr: String? = null,
    val how: Int = CotTypeMapper.COTHOW_UNSPECIFIED,
    val callsign: String = "",
    val team: Int = 0, // Team enum value
    val role: Int = 0, // MemberRole enum value
    val latitudeI: Int = 0, // degrees * 1e7
    val longitudeI: Int = 0, // degrees * 1e7
    val altitude: Int = 0, // meters HAE
    val speed: Int = 0, // cm/s
    val course: Int = 0, // degrees * 100
    val battery: Int = 0, // 0-100
    val geoSrc: Int = 0, // GeoPointSource enum
    val altSrc: Int = 0, // GeoPointSource enum
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
    /**
     * Observed weather annotation from `<environment>` CoT detail element.
     * Attaches to any payload variant. `null` means the source XML had no
     * `<environment>` element — preserved as proto3 `optional` on the wire.
     */
    val environment: EnvironmentData? = null,
    /**
     * Sensor field-of-view cone from `<sensor>` CoT detail element.
     * Attaches to any payload variant. `null` means the source XML had no
     * `<sensor>` element.
     */
    val sensorFov: SensorFovData? = null,
    /**
     * Directed-routing recipient callsigns from `<marti><dest callsign='…'/>…</marti>`.
     *
     * Empty (proto3 default) = broadcast to all peers, the common case for
     * PLI / situational-awareness. Populated when ATAK addresses the event to
     * specific recipients — TAKTALK m-t-t voice / text, directed b-t-f DMs,
     * and any other CoT shape that should reach a named subset of peers.
     *
     * TAKTALK gates voice TTS playback on this list containing the receiver's
     * callsign; dropping `<marti>` on the wire breaks voice messaging end-to-end.
     */
    val marti: List<String> = emptyList(),
    val payload: Payload = Payload.None,
) {
    /**
     * Weather annotation from a CoT `<environment>` detail element.
     *
     * Every field is nullable so senders can emit a partial element (e.g.
     * just `temperature=` when no wind data is available). On the wire the
     * proto message is present whenever any sub-field is non-null; absent
     * scalars encode as the proto3 default (0) and decode back to null via
     * the serializer's unit-conversion path — see [TakPacketV2Serializer].
     */
    public data class EnvironmentData(
        /** Temperature in °C (one-decimal precision on the wire). `null` = not set. */
        val temperatureCelsius: Double? = null,
        /** Wind direction "from" in whole degrees, 0–359. `null` = not set. */
        val windDirectionDeg: Int? = null,
        /** Wind speed in m/s (two-decimal precision on the wire). `null` = not set. */
        val windSpeedMetersPerSec: Double? = null,
    )

    /**
     * Sensor field-of-view cone from a CoT `<sensor>` detail element.
     *
     * Mirrors the 8 geometry attributes that ATAK-CIV's `SensorDetailHandler`
     * reads from the wire. Visual styling attributes (`fovAlpha`, RGB fill,
     * stroke color/weight, etc.) are intentionally dropped — the receiving
     * ATAK client restores them from its own defaults.
     */
    public data class SensorFovData(
        /** Coarse sensor category. See [SensorType]. */
        val type: SensorType = SensorType.Unspecified,
        /** Cone axis azimuth in whole degrees, 0–359, clockwise from true north. */
        val azimuthDeg: Int = 270,
        /**
         * Maximum cone range in whole meters. `null` = not set on the wire —
         * receivers should fall back to the ATAK-CIV default of 100. Tracks the
         * proto's `optional uint32 range_m` so absence and explicit-zero / -100
         * remain distinguishable end-to-end.
         */
        val rangeMeters: Int? = null,
        /** Horizontal field of view in whole degrees. ATAK-CIV default is 45. */
        val fovHorizontalDeg: Int = 45,
        /** Vertical FOV in whole degrees. `null` = not set / use horizontal FOV. */
        val fovVerticalDeg: Int? = null,
        /** Elevation angle in whole degrees, -90..+90 (positive = up). */
        val elevationDeg: Int = 0,
        /** Roll angle in whole degrees, -180..+180. `null` = not set. */
        val rollDeg: Int? = null,
        /** Free-form device model identifier ("FLIR-Boson-640"). `null` = unknown. */
        val model: String? = null,
    ) {
        /**
         * Coarse sensor category inferred from `model` when the source XML
         * doesn't label it.
         *
         * @property value the wire enum int carried in the proto `SensorFov` message.
         */
        public enum class SensorType(
            public val value: Int,
        ) {
            /** Category unknown / not set. */
            Unspecified(0),

            /** Electro-optical camera. */
            Camera(1),

            /** Thermal / IR imager (FLIR, Boson, …). */
            Thermal(2),

            /** Laser range finder / designator. */
            Laser(3),

            /** Night-vision device. */
            Nvg(4),

            /** Radio-frequency / radar sensor. */
            Rf(5),

            /** Some other sensor type. */
            Other(6),
            ;

            public companion object {
                /**
                 * Map a wire enum int back to a [SensorType], defaulting to
                 * [Unspecified] for any unknown value (forward-compatible).
                 */
                public fun fromValue(value: Int): SensorType = entries.firstOrNull { it.value == value } ?: Unspecified
            }
        }
    }

    /**
     * The packet's payload variant — the SDK-side model of the `TAKPacketV2`
     * `payload_variant` oneof. Exactly one subtype is present per packet.
     *
     * [None] and [Pli] both mean "no typed payload": a position report. PLI is
     * implicit on the wire (there is no `bool pli` field), so the serializer
     * emits no oneof arm for either; [Pli] exists only so the parser can flag a
     * deliberate position/delete report distinctly from a default-constructed
     * packet.
     */
    public sealed class Payload {
        /** No payload variant. Serializes to an empty oneof — an implicit PLI. */
        public object None : Payload()

        /**
         * An explicit position-location-information report.
         *
         * Modeled separately from [None] for parser clarity, but identical on
         * the wire: PLI is implicit (no `bool pli` arm), so this serializes to
         * an empty oneof. The parser emits it for plain `a-f-*` position reports
         * and for delete events.
         *
         * @property value retained for source compatibility; always `true` and
         *   not encoded on the wire.
         */
        public data class Pli(
            val value: Boolean = true,
        ) : Payload()

        /**
         * ATAK GeoChat message — both regular chat (b-t-f) and delivered /
         * read receipts (b-t-f-d / b-t-f-r). Receipts leave [message] empty
         * and set [receiptForUid] + [receiptType] to link back to the
         * outbound message's event UID.
         */
        public data class Chat(
            /** The chat message body. Empty for receipt-only events. */
            val message: String = "",
            /** Destination chatroom / group id, or `null` for "All Chat Rooms" (broadcast). */
            val to: String? = null,
            /** Sender's callsign as carried in `__chat[senderCallsign]`; `null` if absent. */
            val toCallsign: String? = null,
            /** UID of the chat message this event is acknowledging. */
            val receiptForUid: String = "",
            /** Receipt kind: 0 = none (regular chat), 1 = delivered, 2 = read. */
            val receiptType: Int = 0,
            /**
             * TAKTALK-flavored b-t-f sidecar: TAKTALK language tag from <Ea>
             * (e.g. "English"). Empty = absent.
             *
             * This and the following [roomId] / [voiceProfileId] fields are
             * empty / false for regular ATAK GeoChat. When the originator's ATAK
             * runs the TAKTALK plugin, they mirror the <Ea>, <roomId>,
             * <voice_profile_id> elements ATAK appends to the chat detail.
             */
            val lang: String = "",
            /** TAKTALK room UUID from <roomId>. Empty = absent. */
            val roomId: String = "",
            /**
             * TAKTALK voice profile pointer from <voice_profile_id>. Often
             * empty even when present (the empty element marker still signals
             * TAKTALK origination). Use [hasVoiceProfile] to distinguish
             * "empty marker present" from "field absent entirely".
             */
            val voiceProfileId: String = "",
            /**
             * True when the source CoT carried a `<voice_profile_id/>` element
             * (with or without content). Lets the builder re-emit the marker
             * faithfully even when [voiceProfileId] is empty. False on
             * non-TAKTALK chats.
             */
            val hasVoiceProfile: Boolean = false,
        ) : Payload()

        /**
         * TAKTALK voice/text chat message (CoT type m-t-t). The voice audio
         * itself rides UDP/RTP outside the mesh; this carries the text
         * envelope plus a from_voice marker for receiver UX.
         */
        public data class TakTalk(
            /** Message text envelope (the spoken/typed content). */
            val text: String = "",
            /** TAKTALK room UUID this message belongs to. Empty = direct/unscoped. */
            val chatroomId: String = "",
            /** Language tag of the message (e.g. "English"). Empty = unspecified. */
            val lang: String = "",
            /** True when the source CoT had a `<voice/>` marker (speech-to-text origin). */
            val fromVoice: Boolean = false,
        ) : Payload()

        /**
         * TAKTALK room/membership broadcast (CoT type y-). Not a chat message;
         * receivers cache these to resolve room UUIDs (used in
         * [TakTalk.chatroomId] and [Chat.roomId]) to a friendly name +
         * roster for display.
         *
         * Note: [senderCallsign] is DEPRECATED in v0.3.2. The sender's
         * callsign is always equal to [TakPacketV2Data.callsign] (the
         * envelope-level field), so the duplicate wire byte is redundant.
         * The builder reconstitutes the `<sender-callsign>` XML element
         * from the envelope callsign on emit; the parser still reads the
         * element so v0.3.1-encoded packets decode cleanly. To be removed
         * outright in v0.4.x.
         */
        public data class TakTalkRoom(
            @Deprecated(
                "Always equals TakPacketV2Data.callsign in valid packets; the builder " +
                    "reconstitutes <sender-callsign> from the envelope callsign. " +
                    "Field will be removed in v0.4.x.",
            )
            val senderCallsign: String = "",
            /** Room UUID being broadcast. */
            val roomId: String = "",
            /** Human-friendly room name for display. */
            val roomName: String = "",
            /** Current member callsigns in the room. */
            val participants: List<String> = emptyList(),
        ) : Payload()

        /**
         * ADS-B / military air track. Maps to the `AircraftTrack` protobuf
         * message (payload_variant tag 32). The structured fields are
         * synthesized into a `<remarks>` line on rebuild when no `<_aircot_>`
         * element fits.
         */
        public data class Aircraft(
            /** 6-hex-digit ICAO 24-bit address (e.g. "A1B2C3"). */
            val icao: String = "",
            /** Tail / registration (e.g. "N12345"). */
            val registration: String = "",
            /** Flight number / callsign (e.g. "UAL123"). */
            val flight: String = "",
            /** Aircraft type designator (e.g. "B738"). */
            val aircraftType: String = "",
            /** Transponder squawk code (0 = not reported). */
            val squawk: Int = 0,
            /** ADS-B emitter category string. */
            val category: String = "",
            /** Receiver RSSI in dBm × 10 (e.g. -85 dBm → -850); 0 = not reported. */
            val rssiX10: Int = 0,
            /** True if the track has a valid GPS fix. */
            val gps: Boolean = false,
            /** UID of the receiving CoT host (sensor gateway). */
            val cotHostId: String = "",
        ) : Payload()

        /**
         * Raw `<detail>` bytes shipped verbatim — the fallback for callers
         * building packets directly with detail content the structured parser
         * doesn't model. Maps to `bytes raw_detail` (payload_variant tag 33).
         * [CotXmlBuilder] re-emits [bytes] inside `<detail>…</detail>` unchanged.
         *
         * @property bytes the exact UTF-8 bytes between the `<detail>` tags.
         */
        public data class RawDetail(
            val bytes: ByteArray,
        ) : Payload() {
            override fun equals(other: Any?): Boolean = other is RawDetail && bytes.contentEquals(other.bytes)

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
         * @property latI latitude in degrees × 1e7.
         * @property lonI longitude in degrees × 1e7.
         */
        public data class Vertex(
            val latI: Int,
            val lonI: Int,
        )

        /**
         * User-drawn tactical graphic: circle, rectangle, polygon, polyline,
         * telestration, ranging circle, bullseye, ellipse, Vehicle2D, or Vehicle3D.
         *
         * Maps to the `DrawnShape` protobuf message at payload_variant tag 34.
         * See atak.proto for the full field-by-field documentation.
         */
        public data class DrawnShape(
            /** One of the Kind_* constants (1..10); see atak.proto. */
            val kind: Int = 0,
            /**
             * Explicit stroke/fill/both discriminator. One of:
             *   0 = Unspecified (infer from which color fields are non-zero)
             *   1 = StrokeOnly
             *   2 = FillOnly
             *   3 = StrokeAndFill
             */
            val style: Int = 0,
            /** Major axis / circle radius in centimeters (ATAK meters × 100). */
            val majorCm: Int = 0,
            /** Minor axis in centimeters (ellipses); 0 for circles. */
            val minorCm: Int = 0,
            /** Ellipse rotation in whole degrees; 360 = unrotated default. */
            val angleDeg: Int = 360,
            /** Team enum value, or AtakPalette.UNSPECIFIED if not in palette. */
            val strokeColor: Int = 0,
            /** Exact 32-bit ARGB bit pattern (always set by the parser). */
            val strokeArgb: Int = 0,
            /** Stroke (outline) weight × 10 (e.g. 3.0 px → 30). */
            val strokeWeightX10: Int = 0,
            /** Team enum value; AtakPalette.UNSPECIFIED for custom fill. */
            val fillColor: Int = 0,
            /** Exact 32-bit ARGB fill bit pattern. */
            val fillArgb: Int = 0,
            /** Whether shape labels are shown on the map. */
            val labelsOn: Boolean = false,
            /** Capped at MAX_VERTICES in the parser; sender sets [truncated] when exceeded. */
            val vertices: List<Vertex> = emptyList(),
            val truncated: Boolean = false,
            /**
             * Bullseye-only field (ignored unless kind == Kind_Bullseye):
             * range-ring spacing in decimeters (ATAK meters × 10).
             */
            val bullseyeDistanceDm: Int = 0,
            /** 0 unset, 1 Magnetic, 2 True, 3 Grid. */
            val bullseyeBearingRef: Int = 0,
            /** bit0 rangeRingVisible, bit1 hasRangeRings, bit2 edgeToCenter, bit3 mils. */
            val bullseyeFlags: Int = 0,
            /** UID this bullseye references for relative bearing, if any. */
            val bullseyeUidRef: String = "",
        ) : Payload()

        /**
         * Fixed marker: spot, waypoint, checkpoint, 2525 symbol, or custom icon.
         * Maps to the `Marker` protobuf message at payload_variant tag 35.
         */
        public data class Marker(
            /** One of the `MARKER_KIND_*` constants in [CotXmlParser]. */
            val kind: Int = 0,
            /** Team palette enum value, or AtakPalette.UNSPECIFIED for a custom color. */
            val color: Int = 0,
            /** Exact 32-bit ARGB bit pattern (always set by the parser). */
            val colorArgb: Int = 0,
            /** Readiness flag from `<status readiness="true"/>`. */
            val readiness: Boolean = false,
            /** UID of the parent map item this marker links to. */
            val parentUid: String = "",
            /** CoT type of the parent item. */
            val parentType: String = "",
            /** Callsign of the parent item. */
            val parentCallsign: String = "",
            /** Icon set path (e.g. `COT_MAPPING_2525B/…`); empty for built-in markers. */
            val iconset: String = "",
        ) : Payload()

        /**
         * Range and bearing measurement line. Maps to `RangeAndBearing` proto
         * at payload_variant tag 36. Anchor endpoint is absolute lat/lon, not
         * a delta from the event point.
         */
        public data class RangeAndBearing(
            /** Anchor endpoint latitude in degrees × 1e7 (absolute, not a delta). */
            val anchorLatI: Int = 0,
            /** Anchor endpoint longitude in degrees × 1e7 (absolute, not a delta). */
            val anchorLonI: Int = 0,
            /** UID of the anchor map item, if the line is anchored to one. */
            val anchorUid: String = "",
            /** Range in centimeters. */
            val rangeCm: Int = 0,
            /** Bearing in degrees * 100 (0..36000). */
            val bearingCdeg: Int = 0,
            /** Team palette enum value, or AtakPalette.UNSPECIFIED for a custom color. */
            val strokeColor: Int = 0,
            /** Exact 32-bit ARGB stroke bit pattern. */
            val strokeArgb: Int = 0,
            /** Stroke weight × 10 (e.g. 3.0 px → 30). */
            val strokeWeightX10: Int = 0,
        ) : Payload()

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
            /** Waypoint-label prefix (e.g. "CP" → CP1, CP2, …). */
            val prefix: String = "",
            /** Route line weight × 10 (e.g. 3.0 px → 30). */
            val strokeWeightX10: Int = 0,
            /** Ordered route waypoints / control points (capped at MAX_ROUTE_LINKS). */
            val links: List<Link> = emptyList(),
            /** True if links were dropped because the route exceeded MAX_ROUTE_LINKS. */
            val truncated: Boolean = false,
        ) : Payload() {
            /**
             * One route waypoint or control point.
             *
             * @property latI latitude in degrees × 1e7.
             * @property lonI longitude in degrees × 1e7.
             * @property uid the point's UID (a deterministic one is generated on
             *   build when empty).
             * @property callsign the point's display callsign.
             * @property linkType 0 = waypoint (`b-m-p-w`), 1 = checkpoint (`b-m-p-c`).
             */
            public data class Link(
                val latI: Int = 0,
                val lonI: Int = 0,
                val uid: String = "",
                val callsign: String = "",
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
            /**
             * v2.x medline extension: short MEDEVAC title / ops number
             * (e.g. "EAGLE.15.181230").
             */
            val title: String = "",
            /**
             * Primary medline free-text — single most clinically important
             * line on a MEDLINE form (e.g. "2 urgent litter patients, smoke
             * on approach"). Preserved under MTU pressure whenever possible.
             */
            val medlineRemarks: String = "",
            // Line 3 (newer ATAK format): patient counts by precedence level.
            val urgentCount: Int = 0,
            val urgentSurgicalCount: Int = 0,
            val priorityCount: Int = 0,
            val routineCount: Int = 0,
            val convenienceCount: Int = 0,
            /** Line 4 supplementary: free-text non-standard equipment ("Blood warmer"). */
            val equipmentDetail: String = "",
            /** Line 1 override: MGRS grid string ("34T CQ 12345 67890"). */
            val zoneProtectedCoord: String = "",
            /** Line 9 supplementary: slope direction ("N", "NE", …). */
            val terrainSlopeDir: String = "",
            /**
             * Line 9 supplementary: free-text "other" terrain detail.
             * Tier-2 strippable under MTU pressure.
             */
            val terrainOtherDetail: String = "",
            /** Line 7 supplementary: current marking ("Orange smoke"). */
            val markedBy: String = "",
            // --- Tier-2 situational awareness (stripped first under pressure) ---
            val obstacles: String = "",
            val windsAreFrom: String = "",
            val friendlies: String = "",
            val enemy: String = "",
            val hlzRemarks: String = "",
            /** Per-patient Z/M/I/S/T clinical records (repeatable). */
            val zmist: List<ZMistEntry> = emptyList(),
        ) : Payload() {
            /**
             * Per-patient ZMIST card — maps to <zMist> inside ATAK's
             * <zMistsMap>. All fields optional free-text.
             */
            public data class ZMistEntry(
                /** Patient identifier / sequence label ("ZMIST-1"). */
                val title: String = "",
                /** Zap number — unique patient tracking ID. */
                val z: String = "",
                /** Mechanism of injury. */
                val m: String = "",
                /** Injuries observed. */
                val i: String = "",
                /** Signs / vitals. */
                val s: String = "",
                /** Treatment given. */
                val t: String = "",
            )
        }

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
        ) : Payload()

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
        ) : Payload()
    }

    /** Convenience: get the CoT type as a string, resolving enum or fallback. */
    public fun cotTypeString(): String = CotTypeMapper.typeToString(cotTypeId) ?: cotTypeStr ?: ""

    /** Convenience: get the how as a string. */
    public fun howString(): String = CotTypeMapper.howToString(how) ?: ""
}
