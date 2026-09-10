package org.meshtastic.tak

import okio.ByteString.Companion.toByteString
import org.meshtastic.proto.AircraftTrack
import org.meshtastic.proto.CasevacReport
import org.meshtastic.proto.CotGeoPoint
import org.meshtastic.proto.CotHow
import org.meshtastic.proto.CotType
import org.meshtastic.proto.DrawnShape
import org.meshtastic.proto.EmergencyAlert
import org.meshtastic.proto.GeoChat
import org.meshtastic.proto.GeoPointSource
import org.meshtastic.proto.Marker
import org.meshtastic.proto.Marti
import org.meshtastic.proto.MemberRole
import org.meshtastic.proto.RangeAndBearing
import org.meshtastic.proto.Route
import org.meshtastic.proto.SensorFov
import org.meshtastic.proto.TAKEnvironment
import org.meshtastic.proto.TAKPacketV2
import org.meshtastic.proto.TakTalkMessage
import org.meshtastic.proto.TakTalkRoomData
import org.meshtastic.proto.TaskRequest
import org.meshtastic.proto.Team
import org.meshtastic.proto.ZMistEntry
import kotlin.math.roundToInt

/**
 * Serializes/deserializes TakPacketV2Data to/from protobuf wire format
 * using Wire-generated Kotlin classes (com.squareup.wire).
 *
 * ## Forward-compatibility contract
 *
 * Any enum field (`CotType`, `CotHow`, `Team`, `MemberRole`, `GeoPointSource`)
 * that carries a value outside the enum's known range is mapped to the
 * "unspecified" sentinel for that enum: [CotType.CotType_Other],
 * [CotHow.CotHow_Unspecified], [Team.Unspecifed_Color],
 * [MemberRole.Unspecifed], [GeoPointSource.GeoPointSource_Unspecified].
 *
 * This is **by design** so that a v2 receiver can decode packets produced by
 * a v2.1 sender that added a new enum value — the receiver loses the new
 * semantic label but the rest of the packet still round-trips. For CotType
 * specifically, the full type string is preserved in `cot_type_str` (field
 * 23) whenever the enum can't represent it, so the reconstructed CoT XML
 * carries the correct `type="..."` attribute even when the enum downgrades.
 *
 * Callers that need to detect the downgrade — e.g. to surface a "newer peer
 * detected" warning — should check `cot_type_id == CotType_Other &&
 * cot_type_str.isNotEmpty()` on the deserialized packet.
 *
 * ## Wire vs protobuf-lite
 *
 * The SDK previously used protobuf-lite Java codegen with builder chains
 * (`TAKPacketV2.newBuilder().setX(...).build()`) and `.hasXxx()` / `.getXxxValue()`
 * accessors. Wire generates immutable Kotlin data classes with snake_case
 * field names and an `.ADAPTER` for encode/decode. Oneof fields are flattened
 * to nullable properties (`boxOneOfsMinSize = 5000` in build.gradle.kts), so
 * `proto.chat != null` replaces the old `proto.hasChat()` check. The public
 * API of this class — `serialize(data)` / `deserialize(bytes)` — is unchanged.
 */
public object TakPacketV2Serializer {
    /**
     * Encode a [TakPacketV2Data] to `TAKPacketV2` protobuf wire bytes.
     *
     * Maps the SDK's camelCase data model onto the Wire-generated proto types,
     * applying the unit conversions baked into the data model (lat/lon ×1e7,
     * speed cm/s, course degrees×100, altitude meters HAE, shape radii cm).
     * Exactly one [TakPacketV2Data.Payload] arm is emitted into the `oneof`;
     * [TakPacketV2Data.Payload.None] (an implicit PLI) emits no payload variant.
     *
     * @return the serialized protobuf bytes (these are the bytes fed to zstd —
     *         see [TakCompressor]).
     */
    public fun serialize(data: TakPacketV2Data): ByteArray {
        // Build oneof payload fields as nullable locals. Exactly one will be
        // set (or none for Payload.None) and passed to the TAKPacketV2 data
        // class constructor at the bottom. Unused fields stay null, matching
        // the semantics of the flattened oneof Wire generates when
        // boxOneOfsMinSize = 5000.
        var chatField: GeoChat? = null
        var aircraftField: AircraftTrack? = null
        var rawDetailField: okio.ByteString? = null
        var shapeField: DrawnShape? = null
        var markerField: Marker? = null
        var rabField: RangeAndBearing? = null
        var routeField: Route? = null
        var casevacField: CasevacReport? = null
        var emergencyField: EmergencyAlert? = null
        var taskField: TaskRequest? = null
        var takTalkField: TakTalkMessage? = null
        var takTalkRoomField: TakTalkRoomData? = null

        when (val payload = data.payload) {
            // PLI is the implicit payload — no oneof variant is set (saves ~3
            // bytes/beacon vs the former bool pli). Decoded back to Pli when no
            // variant is present. None also emits no variant (builder treats
            // None/Pli identically: envelope-only output).
            is TakPacketV2Data.Payload.Pli -> { /* no payload_variant on the wire */ }

            is TakPacketV2Data.Payload.Chat -> {
                // TAKTALK sidecars: voiceProfileId encodes the empty <voice_profile_id/>
                // marker as an empty string (with hasVoiceProfile true on the data class
                // side). On the wire we represent presence via the optional string field
                // being non-null; an empty marker becomes an explicit empty string ("").
                // Receiver parsers treat "present but empty" as the marker case.
                chatField =
                    GeoChat
                        .Builder()
                        .also { wb ->
                            wb.message = payload.message
                            wb.to = payload.to
                            wb.to_callsign = payload.toCallsign
                            wb.receipt_for_uid = payload.receiptForUid
                            wb.receipt_type = GeoChat.ReceiptType.fromValue(payload.receiptType)
                                ?: GeoChat.ReceiptType.ReceiptType_None
                            wb.lang = payload.lang.ifEmpty { null }
                            wb.room_id = payload.roomId.ifEmpty { null }
                            // Distinguish "present but empty" (marker) from "absent" — emit the
                            // empty string only when hasVoiceProfile was explicitly set.
                            wb.voice_profile_id = if (payload.hasVoiceProfile) payload.voiceProfileId else null
                        }.build()
            }

            is TakPacketV2Data.Payload.TakTalk -> {
                takTalkField =
                    TakTalkMessage
                        .Builder()
                        .also { wb ->
                            wb.text = payload.text
                            wb.chatroom_id = payload.chatroomId
                            wb.lang = payload.lang
                            wb.from_voice = payload.fromVoice
                        }.build()
            }

            is TakPacketV2Data.Payload.TakTalkRoom -> {
                // sender_callsign is deprecated in v0.3.2 — receivers
                // reconstitute <sender-callsign> from TAKPacketV2.callsign
                // on emit, so we stop writing the duplicate to save a few
                // wire bytes per y- packet. The proto field remains for one
                // release to keep v0.3.1-encoded packets decodable.
                takTalkRoomField =
                    TakTalkRoomData
                        .Builder()
                        .also { wb ->
                            wb.sender_callsign = ""
                            wb.room_id = payload.roomId
                            wb.room_name = payload.roomName
                            wb.participants = payload.participants
                        }.build()
            }

            is TakPacketV2Data.Payload.Aircraft -> {
                aircraftField =
                    AircraftTrack
                        .Builder()
                        .also { wb ->
                            wb.icao = payload.icao
                            wb.registration = payload.registration
                            wb.flight = payload.flight
                            wb.aircraft_type = payload.aircraftType
                            wb.squawk = payload.squawk
                            wb.category = payload.category
                            wb.rssi_x10 = payload.rssiX10
                            wb.gps = payload.gps
                            wb.cot_host_id = payload.cotHostId
                        }.build()
            }

            is TakPacketV2Data.Payload.RawDetail -> {
                rawDetailField = payload.bytes.toByteString()
            }

            is TakPacketV2Data.Payload.DrawnShape -> {
                shapeField =
                    DrawnShape
                        .Builder()
                        .also { wb ->
                            wb.kind = DrawnShape.Kind.fromValue(payload.kind)
                                ?: DrawnShape.Kind.Kind_Unspecified
                            wb.style = DrawnShape.StyleMode.fromValue(payload.style)
                                ?: DrawnShape.StyleMode.StyleMode_Unspecified
                            wb.major_cm = payload.majorCm
                            wb.minor_cm = payload.minorCm
                            wb.angle_deg = payload.angleDeg
                            wb.stroke_color = Team.fromValue(payload.strokeColor)
                                ?: Team.Unspecifed_Color
                            wb.stroke_argb = payload.strokeArgb
                            wb.stroke_weight_x10 = payload.strokeWeightX10
                            wb.fill_color = Team.fromValue(payload.fillColor)
                                ?: Team.Unspecifed_Color
                            wb.fill_argb = payload.fillArgb
                            wb.labels_on = payload.labelsOn
                            // Vertices are delta-encoded from the event anchor (see
                            // atak.proto) into two PACKED parallel sint32 columns. The
                            // parser stores absolutes in the payload, so we subtract the
                            // anchor here to get the wire-form deltas. Packing pays the
                            // field framing once per column instead of once per vertex.
                            wb.vertex_lat_deltas = payload.vertices.map { it.latI - data.latitudeI }
                            wb.vertex_lon_deltas = payload.vertices.map { it.lonI - data.longitudeI }
                            wb.truncated = payload.truncated
                            wb.bullseye_distance_dm = payload.bullseyeDistanceDm
                            wb.bullseye_bearing_ref = payload.bullseyeBearingRef
                            wb.bullseye_flags = payload.bullseyeFlags
                            wb.bullseye_uid_ref = payload.bullseyeUidRef
                        }.build()
            }

            is TakPacketV2Data.Payload.Marker -> {
                markerField =
                    Marker
                        .Builder()
                        .also { wb ->
                            wb.kind = Marker.Kind.fromValue(payload.kind)
                                ?: Marker.Kind.Kind_Unspecified
                            wb.color = Team.fromValue(payload.color) ?: Team.Unspecifed_Color
                            wb.color_argb = payload.colorArgb
                            wb.readiness = payload.readiness
                            wb.parent_uid = payload.parentUid
                            wb.parent_type = payload.parentType
                            wb.parent_callsign = payload.parentCallsign
                            wb.iconset = payload.iconset
                        }.build()
            }

            is TakPacketV2Data.Payload.RangeAndBearing -> {
                rabField =
                    RangeAndBearing
                        .Builder()
                        .also { wb ->
                            wb.anchor =
                                CotGeoPoint
                                    .Builder()
                                    .also { wb ->
                                        wb.lat_delta_i = payload.anchorLatI - data.latitudeI
                                        wb.lon_delta_i = payload.anchorLonI - data.longitudeI
                                    }.build()
                            wb.anchor_uid = payload.anchorUid
                            wb.range_cm = payload.rangeCm
                            wb.bearing_cdeg = payload.bearingCdeg
                            wb.stroke_color = Team.fromValue(payload.strokeColor)
                                ?: Team.Unspecifed_Color
                            wb.stroke_argb = payload.strokeArgb
                            wb.stroke_weight_x10 = payload.strokeWeightX10
                        }.build()
            }

            is TakPacketV2Data.Payload.Route -> {
                routeField =
                    Route
                        .Builder()
                        .also { wb ->
                            wb.method = Route.Method.fromValue(payload.method)
                                ?: Route.Method.Method_Unspecified
                            wb.direction = Route.Direction.fromValue(payload.direction)
                                ?: Route.Direction.Direction_Unspecified
                            wb.prefix = payload.prefix
                            wb.stroke_weight_x10 = payload.strokeWeightX10
                            wb.links =
                                payload.links.map { link ->
                                    Route.Link
                                        .Builder()
                                        .also { wb ->
                                            wb.point =
                                                CotGeoPoint
                                                    .Builder()
                                                    .also { wb ->
                                                        wb.lat_delta_i = link.latI - data.latitudeI
                                                        wb.lon_delta_i = link.lonI - data.longitudeI
                                                    }.build()
                                            wb.uid = link.uid
                                            wb.callsign = link.callsign
                                            wb.link_type = link.linkType
                                        }.build()
                                }
                            wb.truncated = payload.truncated
                        }.build()
            }

            is TakPacketV2Data.Payload.CasevacReport -> {
                casevacField =
                    CasevacReport
                        .Builder()
                        .also { wb ->
                            wb.precedence = CasevacReport.Precedence.fromValue(payload.precedence)
                                ?: CasevacReport.Precedence.Precedence_Unspecified
                            wb.equipment_flags = payload.equipmentFlags
                            wb.litter_patients = payload.litterPatients
                            wb.ambulatory_patients = payload.ambulatoryPatients
                            wb.security = CasevacReport.Security.fromValue(payload.security)
                                ?: CasevacReport.Security.Security_Unspecified
                            wb.hlz_marking = CasevacReport.HlzMarking.fromValue(payload.hlzMarking)
                                ?: CasevacReport.HlzMarking.HlzMarking_Unspecified
                            wb.zone_marker = payload.zoneMarker
                            wb.us_military = payload.usMilitary
                            wb.us_civilian = payload.usCivilian
                            wb.non_us_military = payload.nonUsMilitary
                            wb.non_us_civilian = payload.nonUsCivilian
                            wb.epw = payload.epw
                            wb.child = payload.child
                            wb.terrain_flags = payload.terrainFlags
                            wb.frequency = payload.frequency
                            // v2.x medline extensions
                            wb.title = payload.title
                            wb.medline_remarks = payload.medlineRemarks
                            wb.urgent_count = payload.urgentCount
                            wb.urgent_surgical_count = payload.urgentSurgicalCount
                            wb.priority_count = payload.priorityCount
                            wb.routine_count = payload.routineCount
                            wb.convenience_count = payload.convenienceCount
                            wb.equipment_detail = payload.equipmentDetail
                            wb.zone_protected_coord = payload.zoneProtectedCoord
                            wb.terrain_slope_dir = payload.terrainSlopeDir
                            wb.terrain_other_detail = payload.terrainOtherDetail
                            wb.marked_by = payload.markedBy
                            wb.obstacles = payload.obstacles
                            wb.winds_are_from = payload.windsAreFrom
                            wb.friendlies = payload.friendlies
                            wb.enemy = payload.enemy
                            wb.hlz_remarks = payload.hlzRemarks
                            wb.zmist =
                                payload.zmist.map { entry ->
                                    ZMistEntry
                                        .Builder()
                                        .also { wb ->
                                            wb.title = entry.title
                                            wb.z = entry.z
                                            wb.m = entry.m
                                            wb.i = entry.i
                                            wb.s = entry.s
                                            wb.t = entry.t
                                        }.build()
                                }
                        }.build()
            }

            is TakPacketV2Data.Payload.EmergencyAlert -> {
                emergencyField =
                    EmergencyAlert
                        .Builder()
                        .also { wb ->
                            wb.type = EmergencyAlert.Type.fromValue(payload.type)
                                ?: EmergencyAlert.Type.Type_Unspecified
                            wb.authoring_uid = payload.authoringUid
                            wb.cancel_reference_uid = payload.cancelReferenceUid
                        }.build()
            }

            is TakPacketV2Data.Payload.TaskRequest -> {
                taskField =
                    TaskRequest
                        .Builder()
                        .also { wb ->
                            wb.task_type = payload.taskType
                            wb.target_uid = payload.targetUid
                            wb.assignee_uid = payload.assigneeUid
                            wb.priority = TaskRequest.Priority.fromValue(payload.priority)
                                ?: TaskRequest.Priority.Priority_Unspecified
                            wb.status = TaskRequest.Status.fromValue(payload.status)
                                ?: TaskRequest.Status.Status_Unspecified
                            wb.note = payload.note
                        }.build()
            }

            is TakPacketV2Data.Payload.None -> { /* all oneof fields stay null */ }
        }

        // Payload-agnostic annotations — TAKEnvironment and SensorFov ride
        // alongside whatever payload_variant the packet carries (or even on
        // an empty Payload.None). See their bridge helpers below for the
        // unit conventions.
        //
        // Wire type is `TAKEnvironment` (not `Environment`) to avoid
        // colliding with SwiftUI's `@Environment` in iOS consumers.
        val environmentField: TAKEnvironment? = data.environment?.toWire()
        val sensorFovField: SensorFov? = data.sensorFov?.toWire()
        // Directed-routing recipient list. Encode as a present-but-empty
        // Marti message only when there is at least one recipient — an
        // empty marti is the same as no marti (broadcast), so don't pay
        // the 2-byte wrapper cost on broadcast packets.
        val martiField: Marti? =
            data.marti
                .takeIf { it.isNotEmpty() }
                ?.let { Marti.Builder().also { wb -> wb.dest_callsign = it }.build() }

        // Enum .fromValue() returns null for out-of-range values — fall back
        // to the "unspecified" sentinel for each enum. See the class KDoc.
        val packet =
            TAKPacketV2
                .Builder()
                .also { wb ->
                    wb.cot_type_id = CotType.fromValue(data.cotTypeId) ?: CotType.CotType_Other
                    wb.how = CotHow.fromValue(data.how) ?: CotHow.CotHow_Unspecified
                    wb.callsign = data.callsign
                    wb.team = Team.fromValue(data.team) ?: Team.Unspecifed_Color
                    wb.role = MemberRole.fromValue(data.role) ?: MemberRole.Unspecifed
                    wb.latitude_i = data.latitudeI
                    wb.longitude_i = data.longitudeI
                    wb.altitude = data.altitude
                    wb.speed = data.speed
                    wb.course = data.course
                    wb.battery = data.battery
                    wb.geo_src = GeoPointSource.fromValue(data.geoSrc)
                        ?: GeoPointSource.GeoPointSource_Unspecified
                    wb.alt_src = GeoPointSource.fromValue(data.altSrc)
                        ?: GeoPointSource.GeoPointSource_Unspecified
                    wb.uid = data.uid
                    wb.device_callsign = data.deviceCallsign
                    wb.stale_seconds = data.staleSeconds
                    wb.tak_version = data.takVersion
                    wb.tak_device = data.takDevice
                    wb.tak_platform = data.takPlatform
                    wb.tak_os = data.takOs
                    wb.endpoint = data.endpoint
                    wb.phone = data.phone
                    wb.cot_type_str = data.cotTypeStr ?: ""
                    wb.remarks = data.remarks
                    // Payload-agnostic annotations (optional proto3 fields; null when
                    // the source packet had no <environment> / <sensor> element).
                    wb.environment = environmentField
                    wb.sensor_fov = sensorFovField
                    // Directed-routing recipients (<marti><dest callsign='X'/>…</marti>).
                    // null = broadcast; non-null Marti carries 1+ dest callsigns.
                    wb.marti = martiField
                    // Oneof payload_variant — at most one non-null. PLI sets NONE of
                    // them (implicit position payload); decoded back to Pli below.
                    wb.chat = chatField
                    wb.aircraft = aircraftField
                    wb.raw_detail = rawDetailField
                    wb.shape = shapeField
                    wb.marker = markerField
                    wb.rab = rabField
                    wb.route = routeField
                    wb.casevac = casevacField
                    wb.emergency = emergencyField
                    wb.task = taskField
                    wb.taktalk = takTalkField
                    wb.taktalk_room = takTalkRoomField
                }.build()

        return TAKPacketV2.ADAPTER.encode(packet)
    }

    /**
     * Decode `TAKPacketV2` protobuf wire bytes into a [TakPacketV2Data].
     *
     * The inverse of [serialize]: reads the flattened nullable `oneof` arms,
     * reverses the unit conversions, and reconstructs the [TakPacketV2Data.Payload]
     * variant (a packet with no payload arm becomes [TakPacketV2Data.Payload.None],
     * i.e. an implicit PLI). Enum values outside the known range downgrade to
     * their "unspecified" sentinel per the class-level forward-compatibility
     * contract.
     *
     * @param bytes the decompressed protobuf bytes (as produced by [TakCompressor.decompress]).
     */
    public fun deserialize(bytes: ByteArray): TakPacketV2Data {
        val proto = TAKPacketV2.ADAPTER.decode(bytes)

        // Oneof payload_variant is flattened by boxOneOfsMinSize = 5000, so each
        // case is a nullable top-level property. Exactly one is non-null (or
        // all null for an empty packet). Preserve the same dispatch order as
        // the old protobuf-lite code.
        val payload =
            when {
                // TAKTALK variants checked before chat so a y- broadcast with a
                // chatroom-id can't get mis-deserialized as Chat if both fields
                // somehow end up populated on an out-of-spec packet.
                proto.taktalk_room != null -> {
                    val r = proto.taktalk_room!!
                    TakPacketV2Data.Payload.TakTalkRoom(
                        roomId = r.room_id,
                        roomName = r.room_name,
                        participants = r.participants.toList(),
                    )
                }

                proto.taktalk != null -> {
                    val t = proto.taktalk!!
                    TakPacketV2Data.Payload.TakTalk(
                        text = t.text,
                        chatroomId = t.chatroom_id,
                        lang = t.lang,
                        fromVoice = t.from_voice,
                    )
                }

                proto.chat != null -> {
                    val chat = proto.chat!!
                    TakPacketV2Data.Payload.Chat(
                        message = chat.message,
                        to = chat.to,
                        toCallsign = chat.to_callsign,
                        receiptForUid = chat.receipt_for_uid,
                        receiptType = chat.receipt_type.value,
                        // TAKTALK sidecars; empty strings when the chat is regular ATAK GeoChat.
                        lang = chat.lang ?: "",
                        roomId = chat.room_id ?: "",
                        voiceProfileId = chat.voice_profile_id ?: "",
                        // Track marker-vs-absent — chat.voice_profile_id != null means the
                        // sender included the field, even if it's an empty string (the
                        // <voice_profile_id/> empty-marker case).
                        hasVoiceProfile = chat.voice_profile_id != null,
                    )
                }

                proto.aircraft != null -> {
                    val a = proto.aircraft!!
                    TakPacketV2Data.Payload.Aircraft(
                        icao = a.icao,
                        registration = a.registration,
                        flight = a.flight,
                        aircraftType = a.aircraft_type,
                        squawk = a.squawk,
                        category = a.category,
                        rssiX10 = a.rssi_x10,
                        gps = a.gps,
                        cotHostId = a.cot_host_id,
                    )
                }

                proto.route != null -> {
                    val r = proto.route!!
                    TakPacketV2Data.Payload.Route(
                        method = r.method.value,
                        direction = r.direction.value,
                        prefix = r.prefix,
                        strokeWeightX10 = r.stroke_weight_x10,
                        // CotGeoPoint is delta-encoded from the event anchor — re-add
                        // the top-level latitude_i/longitude_i to recover absolutes.
                        links =
                            r.links.map { link ->
                                TakPacketV2Data.Payload.Route.Link(
                                    latI = proto.latitude_i + (link.point?.lat_delta_i ?: 0),
                                    lonI = proto.longitude_i + (link.point?.lon_delta_i ?: 0),
                                    uid = link.uid,
                                    callsign = link.callsign,
                                    linkType = link.link_type,
                                )
                            },
                        truncated = r.truncated,
                    )
                }

                proto.rab != null -> {
                    val rb = proto.rab!!
                    TakPacketV2Data.Payload.RangeAndBearing(
                        anchorLatI = proto.latitude_i + (rb.anchor?.lat_delta_i ?: 0),
                        anchorLonI = proto.longitude_i + (rb.anchor?.lon_delta_i ?: 0),
                        anchorUid = rb.anchor_uid,
                        rangeCm = rb.range_cm,
                        bearingCdeg = rb.bearing_cdeg,
                        strokeColor = rb.stroke_color.value,
                        strokeArgb = rb.stroke_argb,
                        strokeWeightX10 = rb.stroke_weight_x10,
                    )
                }

                proto.shape != null -> {
                    val s = proto.shape!!
                    TakPacketV2Data.Payload.DrawnShape(
                        kind = s.kind.value,
                        style = s.style.value,
                        majorCm = s.major_cm,
                        minorCm = s.minor_cm,
                        angleDeg = s.angle_deg,
                        strokeColor = s.stroke_color.value,
                        strokeArgb = s.stroke_argb,
                        strokeWeightX10 = s.stroke_weight_x10,
                        fillColor = s.fill_color.value,
                        fillArgb = s.fill_argb,
                        labelsOn = s.labels_on,
                        // Zip the two packed delta columns back into absolute
                        // vertex pairs. The columns are the same length by
                        // construction; zip() truncates to the shorter if a
                        // malformed packet sends mismatched lengths (defensive).
                        vertices =
                            s.vertex_lat_deltas.zip(s.vertex_lon_deltas) { latD, lonD ->
                                TakPacketV2Data.Payload.Vertex(
                                    latI = proto.latitude_i + latD,
                                    lonI = proto.longitude_i + lonD,
                                )
                            },
                        truncated = s.truncated,
                        bullseyeDistanceDm = s.bullseye_distance_dm,
                        bullseyeBearingRef = s.bullseye_bearing_ref,
                        bullseyeFlags = s.bullseye_flags,
                        bullseyeUidRef = s.bullseye_uid_ref,
                    )
                }

                proto.marker != null -> {
                    val m = proto.marker!!
                    TakPacketV2Data.Payload.Marker(
                        kind = m.kind.value,
                        color = m.color.value,
                        colorArgb = m.color_argb,
                        readiness = m.readiness,
                        parentUid = m.parent_uid,
                        parentType = m.parent_type,
                        parentCallsign = m.parent_callsign,
                        iconset = m.iconset,
                    )
                }

                proto.casevac != null -> {
                    val c = proto.casevac!!
                    TakPacketV2Data.Payload.CasevacReport(
                        precedence = c.precedence.value,
                        equipmentFlags = c.equipment_flags,
                        litterPatients = c.litter_patients,
                        ambulatoryPatients = c.ambulatory_patients,
                        security = c.security.value,
                        hlzMarking = c.hlz_marking.value,
                        zoneMarker = c.zone_marker,
                        usMilitary = c.us_military,
                        usCivilian = c.us_civilian,
                        nonUsMilitary = c.non_us_military,
                        nonUsCivilian = c.non_us_civilian,
                        epw = c.epw,
                        child = c.child,
                        terrainFlags = c.terrain_flags,
                        frequency = c.frequency,
                        // v2.x medline extensions
                        title = c.title,
                        medlineRemarks = c.medline_remarks,
                        urgentCount = c.urgent_count,
                        urgentSurgicalCount = c.urgent_surgical_count,
                        priorityCount = c.priority_count,
                        routineCount = c.routine_count,
                        convenienceCount = c.convenience_count,
                        equipmentDetail = c.equipment_detail,
                        zoneProtectedCoord = c.zone_protected_coord,
                        terrainSlopeDir = c.terrain_slope_dir,
                        terrainOtherDetail = c.terrain_other_detail,
                        markedBy = c.marked_by,
                        obstacles = c.obstacles,
                        windsAreFrom = c.winds_are_from,
                        friendlies = c.friendlies,
                        enemy = c.enemy,
                        hlzRemarks = c.hlz_remarks,
                        zmist =
                            c.zmist.map { z ->
                                TakPacketV2Data.Payload.CasevacReport.ZMistEntry(
                                    title = z.title,
                                    z = z.z,
                                    m = z.m,
                                    i = z.i,
                                    s = z.s,
                                    t = z.t,
                                )
                            },
                    )
                }

                proto.emergency != null -> {
                    val e = proto.emergency!!
                    TakPacketV2Data.Payload.EmergencyAlert(
                        type = e.type.value,
                        authoringUid = e.authoring_uid,
                        cancelReferenceUid = e.cancel_reference_uid,
                    )
                }

                proto.task != null -> {
                    val t = proto.task!!
                    TakPacketV2Data.Payload.TaskRequest(
                        taskType = t.task_type,
                        targetUid = t.target_uid,
                        assigneeUid = t.assignee_uid,
                        priority = t.priority.value,
                        status = t.status.value,
                        note = t.note,
                    )
                }

                proto.raw_detail != null -> {
                    TakPacketV2Data.Payload.RawDetail(
                        proto.raw_detail!!.toByteArray(),
                    )
                }

                // No payload_variant set → implicit PLI (position report). The
                // former `bool pli` field was dropped; a position beacon now carries
                // zero payload bytes. None is never produced on decode (the parser
                // never emits it, and the builder renders None/Pli identically), so
                // collapsing no-variant to Pli is lossless for real traffic.
                else -> {
                    TakPacketV2Data.Payload.Pli(true)
                }
            }

        return TakPacketV2Data(
            cotTypeId = proto.cot_type_id.value,
            cotTypeStr = proto.cot_type_str.ifEmpty { null },
            how = proto.how.value,
            callsign = proto.callsign,
            team = proto.team.value,
            role = proto.role.value,
            latitudeI = proto.latitude_i,
            longitudeI = proto.longitude_i,
            altitude = proto.altitude,
            speed = proto.speed,
            course = proto.course,
            battery = proto.battery,
            geoSrc = proto.geo_src.value,
            altSrc = proto.alt_src.value,
            uid = proto.uid,
            deviceCallsign = proto.device_callsign,
            staleSeconds = proto.stale_seconds,
            takVersion = proto.tak_version,
            takDevice = proto.tak_device,
            takPlatform = proto.tak_platform,
            takOs = proto.tak_os,
            endpoint = proto.endpoint,
            phone = proto.phone,
            remarks = proto.remarks,
            environment = proto.environment?.toData(),
            sensorFov = proto.sensor_fov?.toData(),
            // Directed-routing recipients; empty list when proto.marti is null
            // (broadcast packet) or when it has no dest_callsign entries.
            marti = proto.marti?.dest_callsign?.toList() ?: emptyList(),
            payload = payload,
        )
    }

    // -- TAKEnvironment <-> wire bridge ---------------------------------------
    //
    // The SDK's EnvironmentData exposes natural units (°C, whole degrees, m/s)
    // with nullable fields to distinguish "not set" from "zero". The wire form
    // packs temperature into deci-degrees Celsius (×10 sint32) and wind speed
    // into cm/s (×100 uint32) to match TAKPacketV2.speed's unit convention.
    // Absent nullable scalars encode as the proto3 default (0) and decode back
    // to null via the sentinel checks in toData().
    //
    // The wire type is `TAKEnvironment` (prefix added to avoid colliding with
    // SwiftUI's `@Environment` in iOS consumers); the SDK's data class is
    // still named `EnvironmentData` to match the source `<environment>` CoT
    // XML element name — only the proto/wire type name changed.

    private fun TakPacketV2Data.EnvironmentData.toWire(): TAKEnvironment =
        TAKEnvironment
            .Builder()
            .also { wb ->
                wb.temperature_c_x10 = temperatureCelsius?.let { (it * 10).roundToInt() } ?: 0
                wb.wind_direction_deg = windDirectionDeg ?: 0
                wb.wind_speed_cm_s = windSpeedMetersPerSec?.let { (it * 100).roundToInt() } ?: 0
            }.build()

    private fun TAKEnvironment.toData(): TakPacketV2Data.EnvironmentData {
        // Wire scalars are always present (proto3 defaults to 0). Treat an
        // all-zeros field as "not set" on decode — a genuine 0° / 0 m/s wind
        // round-trips as null, which is acceptable for this annotation's
        // semantics (the element is optional and a reading of exactly zero
        // is indistinguishable from "absent" in CoT anyway).
        //
        // Temperature is the one exception: 0°C is a meaningful reading. The
        // distinction between "0°C" and "not set" is preserved by the source
        // XML carrying the attribute; once it's in the proto form we can't
        // tell them apart cheaply without a synthetic oneof. We choose to
        // preserve 0 as "freezing" here since cold-weather ops are the
        // primary use case for temperature data.
        return TakPacketV2Data.EnvironmentData(
            temperatureCelsius = temperature_c_x10 / 10.0,
            windDirectionDeg = if (wind_direction_deg == 0) null else wind_direction_deg,
            windSpeedMetersPerSec = if (wind_speed_cm_s == 0) null else wind_speed_cm_s / 100.0,
        )
    }

    // -- SensorFov <-> wire bridge --------------------------------------------

    private fun TakPacketV2Data.SensorFovData.toWire(): SensorFov =
        SensorFov
            .Builder()
            .also { wb ->
                wb.type = SensorFov.SensorType.fromValue(type.value)
                    ?: SensorFov.SensorType.SensorType_Unspecified
                wb.azimuth_deg = azimuthDeg
                wb.range_m = rangeMeters
                wb.fov_horizontal_deg = fovHorizontalDeg
                wb.fov_vertical_deg = fovVerticalDeg ?: 0
                wb.elevation_deg = elevationDeg
                wb.roll_deg = rollDeg ?: 0
                wb.model = model ?: ""
            }.build()

    private fun SensorFov.toData(): TakPacketV2Data.SensorFovData =
        TakPacketV2Data.SensorFovData(
            type = TakPacketV2Data.SensorFovData.SensorType.fromValue(type.value),
            azimuthDeg = azimuth_deg,
            rangeMeters = range_m,
            fovHorizontalDeg = fov_horizontal_deg,
            // A 0 vertical FOV is meaningless (a degenerate cone), so treat
            // it as "not set" and let the receiver fall back to horizontal.
            fovVerticalDeg = if (fov_vertical_deg == 0) null else fov_vertical_deg,
            elevationDeg = elevation_deg,
            // Roll genuinely can be 0 (a level camera) — however the proto
            // default collapses "not set" into 0 anyway, so we can't tell
            // them apart post-encode. We preserve 0 as null to match the
            // common ATAK-CIV default behavior where roll is typically
            // absent rather than explicitly zero.
            rollDeg = if (roll_deg == 0) null else roll_deg,
            model = model.ifEmpty { null },
        )
}
