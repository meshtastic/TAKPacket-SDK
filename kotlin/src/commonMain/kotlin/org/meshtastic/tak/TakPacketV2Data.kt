package org.meshtastic.tak

import kotlin.math.roundToInt

private const val MAX_LATITUDE: Double = 90.0
private const val MAX_LONGITUDE: Double = 180.0
private const val DEGREES_SCALE: Double = 1e7

/** Degrees to degrees × 1e7, clamped, rounded the way [CotXmlParser] does it. */
private fun scaleDegrees(
    degrees: Double,
    limit: Double,
): Int = (degrees.coerceIn(-limit, limit) * DEGREES_SCALE).roundToInt()

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
 * arm). Three optional top-level annotations — [environment], [sensorFov] and
 * [detection] — attach to any payload variant. **PLI is implicit:** there is no `bool pli`
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
 * @property startEpochLo14 the low 14 bits of the event's `start` as epoch
 *   seconds, or null when the sender did not supply one. Carried so a receiver
 *   can tell how old a report actually is: without it the builder has nothing to
 *   rebuild `start` from and stamps the decode time, which makes every packet
 *   look equally fresh however long it spent in transit. See [StartEpoch] for
 *   the reconstruction, which is not a plain bit-splice.
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
    // Deliberately last rather than beside staleSeconds, where it reads better. This is
    // a data class, so a parameter inserted in the middle shifts every componentN()
    // after it and rewrites the all-args constructor signature, which breaks a consumer
    // compiled against the previous version at runtime rather than at build time. Three
    // products pin this library. Appending also matches the proto, where the field is
    // tag 43, the highest.
    val startEpochLo14: Int? = null,
    /**
     * Passive-sensor metadata from a `<__detection>` CoT detail element, present when
     * this event reports an object a sensor found. Attaches to any payload variant.
     * `null` means the source XML had no `<__detection>` element. Last for the same
     * reason as [startEpochLo14]; the proto tag is 47.
     */
    val detection: DetectionData? = null,
) {
    /** Starts a [Builder] from this value. */
    public fun toBuilder(): Builder = Builder(this)

    /**
     * Fluent builder for [TakPacketV2Data], for Java callers that would otherwise have to pass
     * every constructor argument. Fields left unset keep [TakPacketV2Data]'s own defaults.
     */
    @Suppress("TooManyFunctions") // one setter per field
    public class Builder(
        private var current: TakPacketV2Data = TakPacketV2Data(),
    ) {
        public fun cotTypeId(cotTypeId: Int): Builder = apply { current = current.copy(cotTypeId = cotTypeId) }

        public fun cotTypeStr(cotTypeStr: String?): Builder = apply { current = current.copy(cotTypeStr = cotTypeStr) }

        public fun how(how: Int): Builder = apply { current = current.copy(how = how) }

        public fun callsign(callsign: String): Builder = apply { current = current.copy(callsign = callsign) }

        public fun team(team: Int): Builder = apply { current = current.copy(team = team) }

        public fun role(role: Int): Builder = apply { current = current.copy(role = role) }

        public fun latitudeI(latitudeI: Int): Builder = apply { current = current.copy(latitudeI = latitudeI) }

        public fun longitudeI(longitudeI: Int): Builder = apply { current = current.copy(longitudeI = longitudeI) }

        public fun altitude(altitude: Int): Builder = apply { current = current.copy(altitude = altitude) }

        public fun speed(speed: Int): Builder = apply { current = current.copy(speed = speed) }

        public fun course(course: Int): Builder = apply { current = current.copy(course = course) }

        public fun battery(battery: Int): Builder = apply { current = current.copy(battery = battery) }

        public fun geoSrc(geoSrc: Int): Builder = apply { current = current.copy(geoSrc = geoSrc) }

        public fun altSrc(altSrc: Int): Builder = apply { current = current.copy(altSrc = altSrc) }

        public fun uid(uid: String): Builder = apply { current = current.copy(uid = uid) }

        public fun deviceCallsign(deviceCallsign: String): Builder = apply { current = current.copy(deviceCallsign = deviceCallsign) }

        public fun staleSeconds(staleSeconds: Int): Builder = apply { current = current.copy(staleSeconds = staleSeconds) }

        public fun takVersion(takVersion: String): Builder = apply { current = current.copy(takVersion = takVersion) }

        public fun takDevice(takDevice: String): Builder = apply { current = current.copy(takDevice = takDevice) }

        public fun takPlatform(takPlatform: String): Builder = apply { current = current.copy(takPlatform = takPlatform) }

        public fun takOs(takOs: String): Builder = apply { current = current.copy(takOs = takOs) }

        public fun endpoint(endpoint: String): Builder = apply { current = current.copy(endpoint = endpoint) }

        public fun phone(phone: String): Builder = apply { current = current.copy(phone = phone) }

        public fun remarks(remarks: String): Builder = apply { current = current.copy(remarks = remarks) }

        public fun environment(environment: EnvironmentData?): Builder = apply { current = current.copy(environment = environment) }

        public fun sensorFov(sensorFov: SensorFovData?): Builder = apply { current = current.copy(sensorFov = sensorFov) }

        public fun marti(marti: List<String>): Builder = apply { current = current.copy(marti = marti) }

        public fun payload(payload: Payload): Builder = apply { current = current.copy(payload = payload) }

        public fun startEpochLo14(startEpochLo14: Int?): Builder = apply { current = current.copy(startEpochLo14 = startEpochLo14) }

        public fun detection(detection: DetectionData?): Builder = apply { current = current.copy(detection = detection) }

        /** Sets [TakPacketV2Data.cotTypeId] from a CoT type string, keeping the string itself only when it has no enum value (as the parser does). */
        public fun cotType(type: String): Builder =
            apply {
                val id = CotTypeMapper.typeToEnum(type)
                current = current.copy(cotTypeId = id, cotTypeStr = if (id == CotTypeMapper.COTTYPE_OTHER) type else null)
            }

        /** Sets [TakPacketV2Data.how] from a CoT `how` string such as `"m-g"` or `"h-e"`. */
        public fun how(how: String): Builder = apply { current = current.copy(how = CotTypeMapper.howToEnum(how)) }

        /** Latitude in degrees, stored as degrees × 1e7 like the parser does. */
        public fun latitudeDegrees(degrees: Double): Builder = apply { current = current.copy(latitudeI = scaleDegrees(degrees, MAX_LATITUDE)) }

        /** Longitude in degrees, stored as degrees × 1e7 like the parser does. */
        public fun longitudeDegrees(degrees: Double): Builder = apply { current = current.copy(longitudeI = scaleDegrees(degrees, MAX_LONGITUDE)) }

        /** The event's `start` time in epoch seconds; only its low 14 bits go on the wire. */
        public fun startEpochSeconds(epochSeconds: Long): Builder = apply { current = current.copy(startEpochLo14 = StartEpoch.truncate(epochSeconds)) }

        /** Adds one directed-routing recipient callsign. TAK Talk voice playback requires the receiver's callsign here. */
        public fun addMarti(callsign: String): Builder = apply { current = current.copy(marti = current.marti + callsign) }

        public fun build(): TakPacketV2Data = current
    }

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
     * Passive-sensor metadata from a CoT `<__detection>` detail element: what a sensor
     * reports about the object this event stands for, an uncrewed aircraft (UAS), its
     * operator or home point, a person or a vehicle. Maps to the `TAKDetection` proto
     * message at tag 47.
     *
     * The event itself still carries the object's position, CoT type and links.
     * Affiliation is not here: the CoT type carries it, and the producer chooses it.
     * The only identifier is [uasId], the UAS ID that Remote ID broadcasts.
     */
    @Suppress("MagicNumber") // the enums below carry the proto's wire values
    public data class DetectionData(
        /** What the reported object is. */
        val kind: Kind = Kind.Unspecified,
        /** Kinds of sensing behind the report: a bitwise OR of [Source.value] bits. 0 = not stated. */
        val sources: Int = 0,
        /** Producer's confidence in the report, in percent (0–100). 0 = unknown. */
        val confidence: Int = 0,
        /** Where the reported track is in its life cycle. */
        val state: State = State.Unspecified,
        /** Whether the object is closing on or opening from the reporting sensor. */
        val trend: Trend = Trend.Unspecified,
        /** Number of objects this event stands for. 0 = one. */
        val count: Int = 0,
        /** True when the event's point is the reporting sensor, because the object's position is unknown. */
        val sensorAnchored: Boolean = false,
        /** Horizontal position uncertainty in meters, also written as the CoT point's `ce`. 0 = unknown. */
        val cepMeters: Int = 0,
        /** Calibrated received power at the reporting sensor, in dBm. 0 = unknown. */
        val rssiDbm: Int = 0,
        /**
         * UAS ID from a Remote ID broadcast, at most 20 bytes: ASCII text for a serial number
         * or a CAA registration, without the broadcast's NUL fill, and the broadcast's bytes for
         * any other [uasIdType]. The parser, the builder and the serializer keep it only when
         * [kind] is [Kind.Uas], cut to 20 bytes. The array is held as given, not copied, like a
         * voice profile's data, so don't change it once it's in a detection.
         */
        val uasId: ByteArray = ByteArray(0),
        /** Type of [uasId]. */
        val uasIdType: UasIdType = UasIdType.Unspecified,
        /** Operational status from a Remote ID broadcast. */
        val uasStatus: UasStatus = UasStatus.Unspecified,
    ) {
        /** Whether [sources] has [source]'s bit set. Always false for [Source.Unspecified]. */
        public fun hasSource(source: Source): Boolean = (sources and source.value) != 0

        /** Starts a [Builder] from this value. */
        public fun toBuilder(): Builder = Builder(this)

        // uasId is a ByteArray, which the generated members would compare by reference.
        override fun equals(other: Any?): Boolean =
            other is DetectionData &&
                kind == other.kind &&
                sources == other.sources &&
                confidence == other.confidence &&
                state == other.state &&
                trend == other.trend &&
                count == other.count &&
                sensorAnchored == other.sensorAnchored &&
                cepMeters == other.cepMeters &&
                rssiDbm == other.rssiDbm &&
                uasId.contentEquals(other.uasId) &&
                uasIdType == other.uasIdType &&
                uasStatus == other.uasStatus

        override fun hashCode(): Int =
            listOf(kind, sources, confidence, state, trend, count, sensorAnchored, cepMeters, rssiDbm, uasIdType, uasStatus)
                .hashCode() * 31 + uasId.contentHashCode()

        override fun toString(): String =
            "DetectionData(kind=$kind, sources=$sources, confidence=$confidence, state=$state, trend=$trend, " +
                "count=$count, sensorAnchored=$sensorAnchored, cepMeters=$cepMeters, rssiDbm=$rssiDbm, " +
                "uasId=${uasId.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }}, " +
                "uasIdType=$uasIdType, uasStatus=$uasStatus)"

        /**
         * Fluent builder for [DetectionData], for Java callers that would otherwise have to
         * pass every constructor argument. Fields left unset keep [DetectionData]'s own defaults.
         */
        @Suppress("TooManyFunctions") // one setter per field
        public class Builder(
            private var current: DetectionData = DetectionData(),
        ) {
            public fun kind(kind: Kind): Builder = apply { current = current.copy(kind = kind) }

            public fun sources(sources: Int): Builder = apply { current = current.copy(sources = sources) }

            /** Adds [source]'s bit to [DetectionData.sources]. */
            public fun addSource(source: Source): Builder = apply { current = current.copy(sources = current.sources or source.value) }

            public fun confidence(confidence: Int): Builder = apply { current = current.copy(confidence = confidence) }

            public fun state(state: State): Builder = apply { current = current.copy(state = state) }

            public fun trend(trend: Trend): Builder = apply { current = current.copy(trend = trend) }

            public fun count(count: Int): Builder = apply { current = current.copy(count = count) }

            public fun sensorAnchored(sensorAnchored: Boolean): Builder = apply { current = current.copy(sensorAnchored = sensorAnchored) }

            public fun cepMeters(cepMeters: Int): Builder = apply { current = current.copy(cepMeters = cepMeters) }

            public fun rssiDbm(rssiDbm: Int): Builder = apply { current = current.copy(rssiDbm = rssiDbm) }

            public fun uasId(uasId: ByteArray): Builder = apply { current = current.copy(uasId = uasId) }

            /** Sets [DetectionData.uasId] to [uasId]'s UTF-8 bytes, for a text ID such as a serial number. */
            public fun uasId(uasId: String): Builder = uasId(uasId.encodeToByteArray())

            public fun uasIdType(uasIdType: UasIdType): Builder = apply { current = current.copy(uasIdType = uasIdType) }

            public fun uasStatus(uasStatus: UasStatus): Builder = apply { current = current.copy(uasStatus = uasStatus) }

            public fun build(): DetectionData = current
        }

        /**
         * What the reported object is.
         *
         * @property value the wire enum int carried in the proto `TAKDetection.Kind`.
         */
        public enum class Kind(
            public val value: Int,
        ) {
            /** Not stated. */
            Unspecified(0),

            /** Uncrewed aircraft. */
            Uas(1),

            /** Operator or control-station position. */
            UasOperator(2),

            /** Takeoff or home point. */
            UasHome(3),

            /** A person. */
            Person(4),

            /** A vehicle. */
            Vehicle(5),

            /** Any other object. */
            Other(6),
            ;

            public companion object {
                /** Map a wire enum int back to a [Kind], defaulting to [Unspecified] for any unknown value. */
                public fun fromValue(value: Int): Kind = entries.firstOrNull { it.value == value } ?: Unspecified
            }
        }

        /**
         * Kind of sensing behind a report.
         *
         * @property value the bit this source sets in [DetectionData.sources].
         */
        public enum class Source(
            public val value: Int,
        ) {
            /** No source stated. Sets no bit. */
            Unspecified(0),

            /** Remote ID broadcast (ASTM F3411). */
            RemoteId(1),

            /** Any other RF emission. */
            Rf(2),

            /** Sound. */
            Acoustic(4),

            /** Radar return. */
            Radar(8),

            /** Visible or infrared imaging. */
            Optical(16),

            /** PIR or thermopile. */
            Thermal(32),

            /** Ground vibration. */
            Seismic(64),

            /** Magnetic field change. */
            Magnetic(128),
        }

        /**
         * Where the reported track is in its life cycle.
         *
         * @property value the wire enum int carried in the proto `TAKDetection.State`.
         */
        public enum class State(
            public val value: Int,
        ) {
            /** Not stated. */
            Unspecified(0),

            /** First report of the object. */
            New(1),

            /** Updated from a fresh measurement. */
            Active(2),

            /** No fresh measurement; the position is predicted. */
            Coasting(3),

            /** Final update; receivers may remove the object. */
            Lost(4),
            ;

            public companion object {
                /** Map a wire enum int back to a [State], defaulting to [Unspecified] for any unknown value. */
                public fun fromValue(value: Int): State = entries.firstOrNull { it.value == value } ?: Unspecified
            }
        }

        /**
         * How the distance between the object and the reporting sensor is changing.
         *
         * @property value the wire enum int carried in the proto `TAKDetection.Trend`.
         */
        public enum class Trend(
            public val value: Int,
        ) {
            /** Not stated. */
            Unspecified(0),

            /** Distance shrinking. */
            Closing(1),

            /** Distance holding. */
            Steady(2),

            /** Distance growing. */
            Opening(3),
            ;

            public companion object {
                /** Map a wire enum int back to a [Trend], defaulting to [Unspecified] for any unknown value. */
                public fun fromValue(value: Int): Trend = entries.firstOrNull { it.value == value } ?: Unspecified
            }
        }

        /**
         * Type of [DetectionData.uasId]. Values match the ASTM F3411 UAS ID types.
         *
         * @property value the wire enum int carried in the proto `TAKDetection.UasIdType`.
         */
        public enum class UasIdType(
            public val value: Int,
        ) {
            /** No ID type. */
            Unspecified(0),

            /** ANSI/CTA-2063-A serial number. */
            SerialNumber(1),

            /** Civil aviation authority registration ID. */
            CaaRegistration(2),

            /** UTM-assigned UUID. */
            UtmAssigned(3),

            /** Specific session ID. */
            SpecificSession(4),
            ;

            public companion object {
                /** Map a wire enum int back to a [UasIdType], defaulting to [Unspecified] for any unknown value. */
                public fun fromValue(value: Int): UasIdType = entries.firstOrNull { it.value == value } ?: Unspecified
            }
        }

        /**
         * Operational status of the UAS. Values match the ASTM F3411 operational status.
         *
         * @property value the wire enum int carried in the proto `TAKDetection.UasStatus`.
         */
        public enum class UasStatus(
            public val value: Int,
        ) {
            /** Status undeclared. */
            Unspecified(0),

            /** On the ground. */
            Ground(1),

            /** In flight. */
            Airborne(2),

            /** Emergency declared. */
            Emergency(3),

            /** Remote ID system failure. */
            SystemFailure(4),
            ;

            public companion object {
                /** Map a wire enum int back to a [UasStatus], defaulting to [Unspecified] for any unknown value. */
                public fun fromValue(value: Int): UasStatus = entries.firstOrNull { it.value == value } ?: Unspecified
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
        ) : Payload() {
            /** Starts a [Builder] from this value. */
            public fun toBuilder(): Builder = Builder(this)

            /**
             * Fluent builder for [Chat], for Java callers that would otherwise have to pass
             * every constructor argument. Fields left unset keep [Chat]'s own defaults.
             */
            public class Builder(
                private var current: Chat = Chat(),
            ) {
                public fun message(message: String): Builder = apply { current = current.copy(message = message) }

                public fun to(to: String?): Builder = apply { current = current.copy(to = to) }

                public fun toCallsign(toCallsign: String?): Builder = apply { current = current.copy(toCallsign = toCallsign) }

                public fun receiptForUid(receiptForUid: String): Builder = apply { current = current.copy(receiptForUid = receiptForUid) }

                public fun receiptType(receiptType: Int): Builder = apply { current = current.copy(receiptType = receiptType) }

                public fun lang(lang: String): Builder = apply { current = current.copy(lang = lang) }

                public fun roomId(roomId: String): Builder = apply { current = current.copy(roomId = roomId) }

                /** Sets the voice profile ID and marks it present: the serializer drops the ID unless [hasVoiceProfile] is true. */
                public fun voiceProfileId(voiceProfileId: String): Builder = apply { current = current.copy(voiceProfileId = voiceProfileId, hasVoiceProfile = true) }

                public fun hasVoiceProfile(hasVoiceProfile: Boolean): Builder = apply { current = current.copy(hasVoiceProfile = hasVoiceProfile) }

                public fun build(): Chat = current
            }
        }

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
            /**
             * True when the message originated as speech-to-text. Packet-level only: it has no
             * CoT XML counterpart, so [CotXmlBuilder] doesn't render it and [CotXmlParser]
             * leaves it false. (`<voice>` carries [voiceProfileId], not this flag.)
             */
            val fromVoice: Boolean = false,
            /**
             * The [VoiceProfile] the receiver should speak this message with, matching
             * [VoiceProfile.voiceProfileId]. Empty = the receiver's default voice. In CoT XML it
             * is the body of `<voice>`, which [CotXmlBuilder] always writes for this payload.
             */
            val voiceProfileId: String = "",
        ) : Payload() {
            /** Starts a [Builder] from this value. */
            public fun toBuilder(): Builder = Builder(this)

            /**
             * Fluent builder for [TakTalk], for Java callers that would otherwise have to pass
             * every constructor argument. Fields left unset keep [TakTalk]'s own defaults.
             */
            public class Builder(
                private var current: TakTalk = TakTalk(),
            ) {
                public fun text(text: String): Builder = apply { current = current.copy(text = text) }

                public fun chatroomId(chatroomId: String): Builder = apply { current = current.copy(chatroomId = chatroomId) }

                public fun lang(lang: String): Builder = apply { current = current.copy(lang = lang) }

                public fun fromVoice(fromVoice: Boolean): Builder = apply { current = current.copy(fromVoice = fromVoice) }

                public fun voiceProfileId(voiceProfileId: String): Builder = apply { current = current.copy(voiceProfileId = voiceProfileId) }

                public fun build(): TakTalk = current
            }
        }

        /**
         * TAKTALK room/membership broadcast (CoT type y-). Not a chat message;
         * receivers cache these to resolve room UUIDs (used in
         * [TakTalk.chatroomId] and [Chat.roomId]) to a friendly name +
         * roster for display.
         *
         * Note: the sender's callsign is always equal to
         * [TakPacketV2Data.callsign] (the envelope-level field) — the
         * `<sender-callsign>` XML element is reconstituted from and parsed
         * into that envelope field, not carried as a separate wire byte here.
         * (Pre-v0.3.2 wire had a redundant `senderCallsign` field on this
         * class; it was removed once past its v0.4.x deprecation window.)
         */
        public data class TakTalkRoom(
            /** Room UUID being broadcast. */
            val roomId: String = "",
            /** Human-friendly room name for display. */
            val roomName: String = "",
            /** Current member callsigns in the room. */
            val participants: List<String> = emptyList(),
        ) : Payload() {
            /** Starts a [Builder] from this value. */
            public fun toBuilder(): Builder = Builder(this)

            /**
             * Fluent builder for [TakTalkRoom], for Java callers that would otherwise have to pass
             * every constructor argument. Fields left unset keep [TakTalkRoom]'s own defaults.
             */
            public class Builder(
                private var current: TakTalkRoom = TakTalkRoom(),
            ) {
                public fun roomId(roomId: String): Builder = apply { current = current.copy(roomId = roomId) }

                public fun roomName(roomName: String): Builder = apply { current = current.copy(roomName = roomName) }

                public fun participants(participants: List<String>): Builder = apply { current = current.copy(participants = participants) }

                /** Adds one member callsign. */
                public fun addParticipant(callsign: String): Builder = apply { current = current.copy(participants = current.participants + callsign) }

                public fun build(): TakTalkRoom = current
            }
        }

        /**
         * A sender's TAKTALK synthetic speech profile, so receivers can render that sender's
         * messages in their own voice. Maps to the `VoiceProfile` protobuf message
         * (payload_variant tag 44); it is the reply to a [VoiceProfileRequest].
         *
         * [profileData] is opaque model data, not audio, and usually bigger than one frame:
         * send it with `compressForMesh(..., fragmentIfOversize = true)` and a [TakFragmenter]
         * whose `maxSourceFragments` covers it (the default 8 carries about 1.7 KB at the 225 B
         * MTU; the ceiling is 32). It gains nothing from the TAK dictionary; the compressor's
         * skip-compress rule keeps it from growing past the raw protobuf plus one byte.
         *
         * In CoT XML it is a `y-` event with UID prefix `TAKTALK-RESPONSE-`, carrying
         * `<sender-callsign>`, `<voice-data>` (Base64), `<voice-id>` and `<voice-name>`.
         */
        public class VoiceProfile(
            /** Stable identifier, matching [Chat.voiceProfileId] and [VoiceProfileRequest.voiceProfileId]. */
            public val voiceProfileId: String = "",
            /** Friendly display name for the voice. */
            public val voiceProfileName: String = "",
            /** The profile itself, opaque to this SDK. */
            public val profileData: ByteArray = ByteArray(0),
        ) : Payload() {
            /** A copy with the given fields replaced, like a data class `copy`. */
            public fun copy(
                voiceProfileId: String = this.voiceProfileId,
                voiceProfileName: String = this.voiceProfileName,
                profileData: ByteArray = this.profileData,
            ): VoiceProfile = VoiceProfile(voiceProfileId, voiceProfileName, profileData)

            override fun equals(other: Any?): Boolean =
                other is VoiceProfile &&
                    voiceProfileId == other.voiceProfileId &&
                    voiceProfileName == other.voiceProfileName &&
                    profileData.contentEquals(other.profileData)

            override fun hashCode(): Int = (voiceProfileId.hashCode() * 31 + voiceProfileName.hashCode()) * 31 + profileData.contentHashCode()

            override fun toString(): String = "VoiceProfile(voiceProfileId=$voiceProfileId, voiceProfileName=$voiceProfileName, profileData=${profileData.size} bytes)"

            /** Starts a [Builder] from this value. */
            public fun toBuilder(): Builder = Builder(this)

            /**
             * Fluent builder for [VoiceProfile], for Java callers. Fields left unset keep
             * [VoiceProfile]'s own defaults.
             */
            public class Builder(
                private var current: VoiceProfile = VoiceProfile(),
            ) {
                public fun voiceProfileId(voiceProfileId: String): Builder = apply { current = current.copy(voiceProfileId = voiceProfileId) }

                public fun voiceProfileName(voiceProfileName: String): Builder = apply { current = current.copy(voiceProfileName = voiceProfileName) }

                public fun profileData(profileData: ByteArray): Builder = apply { current = current.copy(profileData = profileData) }

                public fun build(): VoiceProfile = current
            }
        }

        /**
         * TAKTALK request for a peer's voice profile (CoT type y-). Maps to the
         * `VoiceProfileRequest` protobuf message (payload_variant tag 45). Address it
         * to the peer with [TakPacketV2Data.marti]; the reply is that peer's voice profile.
         *
         * In CoT XML it is a `y-` event with UID prefix `TAKTALK-REQUEST-`, carrying only
         * `<sender-callsign>`: [voiceProfileId] has no XML counterpart and is dropped (and
         * logged) by [CotXmlBuilder], so it parses back empty.
         */
        public data class VoiceProfileRequest(
            /** Profile wanted, matching a chat's voice profile ID. Empty asks for whichever profile the peer is using. */
            val voiceProfileId: String = "",
        ) : Payload() {
            /** Starts a [Builder] from this value. */
            public fun toBuilder(): Builder = Builder(this)

            /**
             * Fluent builder for [VoiceProfileRequest], for Java callers. Fields left unset keep
             * [VoiceProfileRequest]'s own defaults.
             */
            public class Builder(
                private var current: VoiceProfileRequest = VoiceProfileRequest(),
            ) {
                public fun voiceProfileId(voiceProfileId: String): Builder = apply { current = current.copy(voiceProfileId = voiceProfileId) }

                public fun build(): VoiceProfileRequest = current
            }
        }

        /**
         * TAKTALK request for a room's name and roster (CoT type y-). Maps to the
         * `TakTalkRoomRequest` protobuf message (payload_variant tag 46). Address it
         * with [TakPacketV2Data.marti]; the reply is a [TakTalkRoom].
         *
         * In CoT XML it is a `y-` event with UID prefix `ROOM-REQUEST` (no trailing hyphen),
         * carrying `<sender-callsign>` and `<chatroom-id>`.
         */
        public data class TakTalkRoomRequest(
            /** Room UUID wanted, matching [TakTalkRoom.roomId]. */
            val roomId: String = "",
        ) : Payload() {
            /** Starts a [Builder] from this value. */
            public fun toBuilder(): Builder = Builder(this)

            /**
             * Fluent builder for [TakTalkRoomRequest], for Java callers. Fields left unset keep
             * [TakTalkRoomRequest]'s own defaults.
             */
            public class Builder(
                private var current: TakTalkRoomRequest = TakTalkRoomRequest(),
            ) {
                public fun roomId(roomId: String): Builder = apply { current = current.copy(roomId = roomId) }

                public fun build(): TakTalkRoomRequest = current
            }
        }

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
            /** Capped by the parser's `CotXmlParser.Limits` (MAX_VERTICES by default); [truncated] is set when exceeded. */
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
        ) : Payload() {
            /** Starts a [Builder] from this value. */
            public fun toBuilder(): Builder = Builder(this)

            /**
             * Fluent builder for [DrawnShape], for Java callers that would otherwise have to pass
             * every constructor argument. Fields left unset keep [DrawnShape]'s own defaults.
             */
            @Suppress("TooManyFunctions") // one setter per field
            public class Builder(
                private var current: DrawnShape = DrawnShape(),
            ) {
                public fun kind(kind: Int): Builder = apply { current = current.copy(kind = kind) }

                public fun style(style: Int): Builder = apply { current = current.copy(style = style) }

                public fun majorCm(majorCm: Int): Builder = apply { current = current.copy(majorCm = majorCm) }

                public fun minorCm(minorCm: Int): Builder = apply { current = current.copy(minorCm = minorCm) }

                public fun angleDeg(angleDeg: Int): Builder = apply { current = current.copy(angleDeg = angleDeg) }

                public fun strokeColor(strokeColor: Int): Builder = apply { current = current.copy(strokeColor = strokeColor) }

                public fun strokeArgb(strokeArgb: Int): Builder = apply { current = current.copy(strokeArgb = strokeArgb) }

                public fun strokeWeightX10(strokeWeightX10: Int): Builder = apply { current = current.copy(strokeWeightX10 = strokeWeightX10) }

                public fun fillColor(fillColor: Int): Builder = apply { current = current.copy(fillColor = fillColor) }

                public fun fillArgb(fillArgb: Int): Builder = apply { current = current.copy(fillArgb = fillArgb) }

                public fun labelsOn(labelsOn: Boolean): Builder = apply { current = current.copy(labelsOn = labelsOn) }

                public fun vertices(vertices: List<Vertex>): Builder = apply { current = current.copy(vertices = vertices) }

                public fun truncated(truncated: Boolean): Builder = apply { current = current.copy(truncated = truncated) }

                public fun bullseyeDistanceDm(bullseyeDistanceDm: Int): Builder = apply { current = current.copy(bullseyeDistanceDm = bullseyeDistanceDm) }

                public fun bullseyeBearingRef(bullseyeBearingRef: Int): Builder = apply { current = current.copy(bullseyeBearingRef = bullseyeBearingRef) }

                public fun bullseyeFlags(bullseyeFlags: Int): Builder = apply { current = current.copy(bullseyeFlags = bullseyeFlags) }

                public fun bullseyeUidRef(bullseyeUidRef: String): Builder = apply { current = current.copy(bullseyeUidRef = bullseyeUidRef) }

                /** Adds one vertex, in degrees × 1e7. */
                public fun addVertex(
                    latI: Int,
                    lonI: Int,
                ): Builder = apply { current = current.copy(vertices = current.vertices + Vertex(latI, lonI)) }

                public fun build(): DrawnShape = current
            }
        }

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
        ) : Payload() {
            /** Starts a [Builder] from this value. */
            public fun toBuilder(): Builder = Builder(this)

            /**
             * Fluent builder for [Marker], for Java callers that would otherwise have to pass
             * every constructor argument. Fields left unset keep [Marker]'s own defaults.
             */
            public class Builder(
                private var current: Marker = Marker(),
            ) {
                public fun kind(kind: Int): Builder = apply { current = current.copy(kind = kind) }

                public fun color(color: Int): Builder = apply { current = current.copy(color = color) }

                public fun colorArgb(colorArgb: Int): Builder = apply { current = current.copy(colorArgb = colorArgb) }

                public fun readiness(readiness: Boolean): Builder = apply { current = current.copy(readiness = readiness) }

                public fun parentUid(parentUid: String): Builder = apply { current = current.copy(parentUid = parentUid) }

                public fun parentType(parentType: String): Builder = apply { current = current.copy(parentType = parentType) }

                public fun parentCallsign(parentCallsign: String): Builder = apply { current = current.copy(parentCallsign = parentCallsign) }

                public fun iconset(iconset: String): Builder = apply { current = current.copy(iconset = iconset) }

                public fun build(): Marker = current
            }
        }

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
        ) : Payload() {
            /** Starts a [Builder] from this value. */
            public fun toBuilder(): Builder = Builder(this)

            /**
             * Fluent builder for [RangeAndBearing], for Java callers that would otherwise have to pass
             * every constructor argument. Fields left unset keep [RangeAndBearing]'s own defaults.
             */
            public class Builder(
                private var current: RangeAndBearing = RangeAndBearing(),
            ) {
                public fun anchorLatI(anchorLatI: Int): Builder = apply { current = current.copy(anchorLatI = anchorLatI) }

                public fun anchorLonI(anchorLonI: Int): Builder = apply { current = current.copy(anchorLonI = anchorLonI) }

                public fun anchorUid(anchorUid: String): Builder = apply { current = current.copy(anchorUid = anchorUid) }

                public fun rangeCm(rangeCm: Int): Builder = apply { current = current.copy(rangeCm = rangeCm) }

                public fun bearingCdeg(bearingCdeg: Int): Builder = apply { current = current.copy(bearingCdeg = bearingCdeg) }

                public fun strokeColor(strokeColor: Int): Builder = apply { current = current.copy(strokeColor = strokeColor) }

                public fun strokeArgb(strokeArgb: Int): Builder = apply { current = current.copy(strokeArgb = strokeArgb) }

                public fun strokeWeightX10(strokeWeightX10: Int): Builder = apply { current = current.copy(strokeWeightX10 = strokeWeightX10) }

                public fun build(): RangeAndBearing = current
            }
        }

        /**
         * Named route consisting of ordered waypoints and control points.
         * Maps to `Route` at payload_variant tag 37.
         *
         * Link count is capped by the parser's `CotXmlParser.Limits`
         * (MAX_ROUTE_LINKS, 16, by default); longer routes are truncated with
         * [truncated] set true.
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
            /** Ordered route waypoints / control points (capped by the parser's limits). */
            val links: List<Link> = emptyList(),
            /** True if links were dropped because the route exceeded the parser's limit. */
            val truncated: Boolean = false,
        ) : Payload() {
            /** Starts a [Builder] from this value. */
            public fun toBuilder(): Builder = Builder(this)

            /**
             * Fluent builder for [Route], for Java callers that would otherwise have to pass
             * every constructor argument. Fields left unset keep [Route]'s own defaults.
             */
            public class Builder(
                private var current: Route = Route(),
            ) {
                public fun method(method: Int): Builder = apply { current = current.copy(method = method) }

                public fun direction(direction: Int): Builder = apply { current = current.copy(direction = direction) }

                public fun prefix(prefix: String): Builder = apply { current = current.copy(prefix = prefix) }

                public fun strokeWeightX10(strokeWeightX10: Int): Builder = apply { current = current.copy(strokeWeightX10 = strokeWeightX10) }

                public fun links(links: List<Link>): Builder = apply { current = current.copy(links = links) }

                public fun truncated(truncated: Boolean): Builder = apply { current = current.copy(truncated = truncated) }

                /** Adds one waypoint or checkpoint. */
                public fun addLink(link: Link): Builder = apply { current = current.copy(links = current.links + link) }

                public fun build(): Route = current
            }

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
        ) : Payload() {
            /** Starts a [Builder] from this value. */
            public fun toBuilder(): Builder = Builder(this)

            /**
             * Fluent builder for [EmergencyAlert], for Java callers that would otherwise have to pass
             * every constructor argument. Fields left unset keep [EmergencyAlert]'s own defaults.
             */
            public class Builder(
                private var current: EmergencyAlert = EmergencyAlert(),
            ) {
                public fun type(type: Int): Builder = apply { current = current.copy(type = type) }

                public fun authoringUid(authoringUid: String): Builder = apply { current = current.copy(authoringUid = authoringUid) }

                public fun cancelReferenceUid(cancelReferenceUid: String): Builder = apply { current = current.copy(cancelReferenceUid = cancelReferenceUid) }

                public fun build(): EmergencyAlert = current
            }
        }

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
