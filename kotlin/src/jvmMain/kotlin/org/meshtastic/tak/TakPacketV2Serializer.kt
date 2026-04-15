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
import org.meshtastic.proto.MemberRole
import org.meshtastic.proto.RangeAndBearing
import org.meshtastic.proto.Route
import org.meshtastic.proto.TAKPacketV2
import org.meshtastic.proto.TaskRequest
import org.meshtastic.proto.Team
import org.meshtastic.proto.ZMistEntry

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
object TakPacketV2Serializer {

    fun serialize(data: TakPacketV2Data): ByteArray {
        // Build oneof payload fields as nullable locals. Exactly one will be
        // set (or none for Payload.None) and passed to the TAKPacketV2 data
        // class constructor at the bottom. Unused fields stay null, matching
        // the semantics of the flattened oneof Wire generates when
        // boxOneOfsMinSize = 5000.
        var pliField: Boolean? = null
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

        when (val payload = data.payload) {
            is TakPacketV2Data.Payload.Pli -> pliField = true
            is TakPacketV2Data.Payload.Chat -> {
                chatField = GeoChat(
                    message = payload.message,
                    to = payload.to,
                    to_callsign = payload.toCallsign,
                    receipt_for_uid = payload.receiptForUid,
                    receipt_type = GeoChat.ReceiptType.fromValue(payload.receiptType)
                        ?: GeoChat.ReceiptType.ReceiptType_None,
                )
            }
            is TakPacketV2Data.Payload.Aircraft -> {
                aircraftField = AircraftTrack(
                    icao = payload.icao,
                    registration = payload.registration,
                    flight = payload.flight,
                    aircraft_type = payload.aircraftType,
                    squawk = payload.squawk,
                    category = payload.category,
                    rssi_x10 = payload.rssiX10,
                    gps = payload.gps,
                    cot_host_id = payload.cotHostId,
                )
            }
            is TakPacketV2Data.Payload.RawDetail -> {
                rawDetailField = payload.bytes.toByteString()
            }
            is TakPacketV2Data.Payload.DrawnShape -> {
                shapeField = DrawnShape(
                    kind = DrawnShape.Kind.fromValue(payload.kind)
                        ?: DrawnShape.Kind.Kind_Unspecified,
                    style = DrawnShape.StyleMode.fromValue(payload.style)
                        ?: DrawnShape.StyleMode.StyleMode_Unspecified,
                    major_cm = payload.majorCm,
                    minor_cm = payload.minorCm,
                    angle_deg = payload.angleDeg,
                    stroke_color = Team.fromValue(payload.strokeColor)
                        ?: Team.Unspecifed_Color,
                    stroke_argb = payload.strokeArgb,
                    stroke_weight_x10 = payload.strokeWeightX10,
                    fill_color = Team.fromValue(payload.fillColor)
                        ?: Team.Unspecifed_Color,
                    fill_argb = payload.fillArgb,
                    labels_on = payload.labelsOn,
                    // CotGeoPoint is delta-encoded from the event anchor — see
                    // atak.proto. The parser stores absolutes in the payload, so
                    // we subtract here to get the wire-form deltas.
                    vertices = payload.vertices.map { v ->
                        CotGeoPoint(
                            lat_delta_i = v.latI - data.latitudeI,
                            lon_delta_i = v.lonI - data.longitudeI,
                        )
                    },
                    truncated = payload.truncated,
                    bullseye_distance_dm = payload.bullseyeDistanceDm,
                    bullseye_bearing_ref = payload.bullseyeBearingRef,
                    bullseye_flags = payload.bullseyeFlags,
                    bullseye_uid_ref = payload.bullseyeUidRef,
                )
            }
            is TakPacketV2Data.Payload.Marker -> {
                markerField = Marker(
                    kind = Marker.Kind.fromValue(payload.kind)
                        ?: Marker.Kind.Kind_Unspecified,
                    color = Team.fromValue(payload.color) ?: Team.Unspecifed_Color,
                    color_argb = payload.colorArgb,
                    readiness = payload.readiness,
                    parent_uid = payload.parentUid,
                    parent_type = payload.parentType,
                    parent_callsign = payload.parentCallsign,
                    iconset = payload.iconset,
                )
            }
            is TakPacketV2Data.Payload.RangeAndBearing -> {
                rabField = RangeAndBearing(
                    anchor = CotGeoPoint(
                        lat_delta_i = payload.anchorLatI - data.latitudeI,
                        lon_delta_i = payload.anchorLonI - data.longitudeI,
                    ),
                    anchor_uid = payload.anchorUid,
                    range_cm = payload.rangeCm,
                    bearing_cdeg = payload.bearingCdeg,
                    stroke_color = Team.fromValue(payload.strokeColor)
                        ?: Team.Unspecifed_Color,
                    stroke_argb = payload.strokeArgb,
                    stroke_weight_x10 = payload.strokeWeightX10,
                )
            }
            is TakPacketV2Data.Payload.Route -> {
                routeField = Route(
                    method = Route.Method.fromValue(payload.method)
                        ?: Route.Method.Method_Unspecified,
                    direction = Route.Direction.fromValue(payload.direction)
                        ?: Route.Direction.Direction_Unspecified,
                    prefix = payload.prefix,
                    stroke_weight_x10 = payload.strokeWeightX10,
                    links = payload.links.map { link ->
                        Route.Link(
                            point = CotGeoPoint(
                                lat_delta_i = link.latI - data.latitudeI,
                                lon_delta_i = link.lonI - data.longitudeI,
                            ),
                            uid = link.uid,
                            callsign = link.callsign,
                            link_type = link.linkType,
                        )
                    },
                    truncated = payload.truncated,
                )
            }
            is TakPacketV2Data.Payload.CasevacReport -> {
                casevacField = CasevacReport(
                    precedence = CasevacReport.Precedence.fromValue(payload.precedence)
                        ?: CasevacReport.Precedence.Precedence_Unspecified,
                    equipment_flags = payload.equipmentFlags,
                    litter_patients = payload.litterPatients,
                    ambulatory_patients = payload.ambulatoryPatients,
                    security = CasevacReport.Security.fromValue(payload.security)
                        ?: CasevacReport.Security.Security_Unspecified,
                    hlz_marking = CasevacReport.HlzMarking.fromValue(payload.hlzMarking)
                        ?: CasevacReport.HlzMarking.HlzMarking_Unspecified,
                    zone_marker = payload.zoneMarker,
                    us_military = payload.usMilitary,
                    us_civilian = payload.usCivilian,
                    non_us_military = payload.nonUsMilitary,
                    non_us_civilian = payload.nonUsCivilian,
                    epw = payload.epw,
                    child = payload.child,
                    terrain_flags = payload.terrainFlags,
                    frequency = payload.frequency,
                    // v2.x medline extensions
                    title = payload.title,
                    medline_remarks = payload.medlineRemarks,
                    urgent_count = payload.urgentCount,
                    urgent_surgical_count = payload.urgentSurgicalCount,
                    priority_count = payload.priorityCount,
                    routine_count = payload.routineCount,
                    convenience_count = payload.convenienceCount,
                    equipment_detail = payload.equipmentDetail,
                    zone_protected_coord = payload.zoneProtectedCoord,
                    terrain_slope_dir = payload.terrainSlopeDir,
                    terrain_other_detail = payload.terrainOtherDetail,
                    marked_by = payload.markedBy,
                    obstacles = payload.obstacles,
                    winds_are_from = payload.windsAreFrom,
                    friendlies = payload.friendlies,
                    enemy = payload.enemy,
                    hlz_remarks = payload.hlzRemarks,
                    zmist = payload.zmist.map { entry ->
                        ZMistEntry(
                            title = entry.title,
                            z = entry.z,
                            m = entry.m,
                            i = entry.i,
                            s = entry.s,
                            t = entry.t,
                        )
                    },
                )
            }
            is TakPacketV2Data.Payload.EmergencyAlert -> {
                emergencyField = EmergencyAlert(
                    type = EmergencyAlert.Type.fromValue(payload.type)
                        ?: EmergencyAlert.Type.Type_Unspecified,
                    authoring_uid = payload.authoringUid,
                    cancel_reference_uid = payload.cancelReferenceUid,
                )
            }
            is TakPacketV2Data.Payload.TaskRequest -> {
                taskField = TaskRequest(
                    task_type = payload.taskType,
                    target_uid = payload.targetUid,
                    assignee_uid = payload.assigneeUid,
                    priority = TaskRequest.Priority.fromValue(payload.priority)
                        ?: TaskRequest.Priority.Priority_Unspecified,
                    status = TaskRequest.Status.fromValue(payload.status)
                        ?: TaskRequest.Status.Status_Unspecified,
                    note = payload.note,
                )
            }
            is TakPacketV2Data.Payload.None -> { /* all oneof fields stay null */ }
        }

        // Enum .fromValue() returns null for out-of-range values — fall back
        // to the "unspecified" sentinel for each enum. See the class KDoc.
        val packet = TAKPacketV2(
            cot_type_id = CotType.fromValue(data.cotTypeId) ?: CotType.CotType_Other,
            how = CotHow.fromValue(data.how) ?: CotHow.CotHow_Unspecified,
            callsign = data.callsign,
            team = Team.fromValue(data.team) ?: Team.Unspecifed_Color,
            role = MemberRole.fromValue(data.role) ?: MemberRole.Unspecifed,
            latitude_i = data.latitudeI,
            longitude_i = data.longitudeI,
            altitude = data.altitude,
            speed = data.speed,
            course = data.course,
            battery = data.battery,
            geo_src = GeoPointSource.fromValue(data.geoSrc)
                ?: GeoPointSource.GeoPointSource_Unspecified,
            alt_src = GeoPointSource.fromValue(data.altSrc)
                ?: GeoPointSource.GeoPointSource_Unspecified,
            uid = data.uid,
            device_callsign = data.deviceCallsign,
            stale_seconds = data.staleSeconds,
            tak_version = data.takVersion,
            tak_device = data.takDevice,
            tak_platform = data.takPlatform,
            tak_os = data.takOs,
            endpoint = data.endpoint,
            phone = data.phone,
            cot_type_str = data.cotTypeStr ?: "",
            remarks = data.remarks,
            // Oneof payload_variant — exactly one non-null (or all null for None)
            pli = pliField,
            chat = chatField,
            aircraft = aircraftField,
            raw_detail = rawDetailField,
            shape = shapeField,
            marker = markerField,
            rab = rabField,
            route = routeField,
            casevac = casevacField,
            emergency = emergencyField,
            task = taskField,
        )

        return TAKPacketV2.ADAPTER.encode(packet)
    }

    fun deserialize(bytes: ByteArray): TakPacketV2Data {
        val proto = TAKPacketV2.ADAPTER.decode(bytes)

        // Oneof payload_variant is flattened by boxOneOfsMinSize = 5000, so each
        // case is a nullable top-level property. Exactly one is non-null (or
        // all null for an empty packet). Preserve the same dispatch order as
        // the old protobuf-lite code.
        val payload = when {
            proto.chat != null -> {
                val chat = proto.chat!!
                TakPacketV2Data.Payload.Chat(
                    message = chat.message,
                    to = chat.to,
                    toCallsign = chat.to_callsign,
                    receiptForUid = chat.receipt_for_uid,
                    receiptType = chat.receipt_type.value,
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
                    links = r.links.map { link ->
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
                    vertices = s.vertices.map { v ->
                        TakPacketV2Data.Payload.Vertex(
                            latI = proto.latitude_i + v.lat_delta_i,
                            lonI = proto.longitude_i + v.lon_delta_i,
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
                    zmist = c.zmist.map { z ->
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
            proto.raw_detail != null -> TakPacketV2Data.Payload.RawDetail(
                proto.raw_detail!!.toByteArray(),
            )
            proto.pli != null -> TakPacketV2Data.Payload.Pli(proto.pli!!)
            else -> TakPacketV2Data.Payload.None
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
            payload = payload,
        )
    }
}
