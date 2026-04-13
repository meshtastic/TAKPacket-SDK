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

/**
 * Converts between [TakPacketV2Data] and protobuf wire format using
 * Wire KMP generated classes.
 *
 * This object is the only place in the SDK that touches Wire-generated types
 * (`org.meshtastic.proto.*`). Wire types are **not** exposed through the
 * public API — `wire-runtime` is an `implementation` dependency, not `api`.
 *
 * ## Thread safety
 *
 * Both [serialize] and [deserialize] are stateless and safe to call from any
 * thread or coroutine context.
 */
public object TakPacketV2Serializer {

    /**
     * Serialize a [TakPacketV2Data] to protobuf wire bytes.
     *
     * @param data the SDK data class to serialize
     * @return the encoded protobuf bytes (uncompressed)
     */
    @Throws(IllegalStateException::class)
    public fun serialize(data: TakPacketV2Data): ByteArray {
        var pliPayload: Boolean? = null
        var chatPayload: GeoChat? = null
        var aircraftPayload: AircraftTrack? = null
        var rawDetailPayload: okio.ByteString? = null
        var shapePayload: DrawnShape? = null
        var markerPayload: Marker? = null
        var rabPayload: RangeAndBearing? = null
        var routePayload: Route? = null
        var casevacPayload: CasevacReport? = null
        var emergencyPayload: EmergencyAlert? = null
        var taskPayload: TaskRequest? = null

        when (val payload = data.payload) {
            is TakPacketV2Data.Payload.Pli -> {
                pliPayload = payload.value
            }
            is TakPacketV2Data.Payload.Chat -> {
                chatPayload = GeoChat(
                    message = payload.message,
                    to = payload.to ?: "",
                    to_callsign = payload.toCallsign ?: "",
                    receipt_for_uid = payload.receiptForUid,
                    receipt_type = GeoChat.ReceiptType.fromValue(payload.receiptType)
                        ?: GeoChat.ReceiptType.ReceiptType_None,
                )
            }
            is TakPacketV2Data.Payload.Aircraft -> {
                aircraftPayload = AircraftTrack(
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
                rawDetailPayload = payload.bytes.toByteString()
            }
            is TakPacketV2Data.Payload.DrawnShape -> {
                shapePayload = DrawnShape(
                    kind = DrawnShape.Kind.fromValue(payload.kind) ?: DrawnShape.Kind.Kind_Unspecified,
                    style = DrawnShape.StyleMode.fromValue(payload.style) ?: DrawnShape.StyleMode.StyleMode_Unspecified,
                    major_cm = payload.majorCm,
                    minor_cm = payload.minorCm,
                    angle_deg = payload.angleDeg,
                    stroke_color = Team.fromValue(payload.strokeColor) ?: Team.Unspecifed_Color,
                    stroke_argb = payload.strokeArgb,
                    stroke_weight_x10 = payload.strokeWeightX10,
                    fill_color = Team.fromValue(payload.fillColor) ?: Team.Unspecifed_Color,
                    fill_argb = payload.fillArgb,
                    labels_on = payload.labelsOn,
                    truncated = payload.truncated,
                    vertices = payload.vertices.map { v ->
                        CotGeoPoint(
                            lat_delta_i = v.latI - data.latitudeI,
                            lon_delta_i = v.lonI - data.longitudeI,
                        )
                    },
                    bullseye_distance_dm = payload.bullseyeDistanceDm,
                    bullseye_bearing_ref = payload.bullseyeBearingRef,
                    bullseye_flags = payload.bullseyeFlags,
                    bullseye_uid_ref = payload.bullseyeUidRef,
                )
            }
            is TakPacketV2Data.Payload.Marker -> {
                markerPayload = Marker(
                    kind = Marker.Kind.fromValue(payload.kind) ?: Marker.Kind.Kind_Unspecified,
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
                rabPayload = RangeAndBearing(
                    anchor = CotGeoPoint(
                        lat_delta_i = payload.anchorLatI - data.latitudeI,
                        lon_delta_i = payload.anchorLonI - data.longitudeI,
                    ),
                    anchor_uid = payload.anchorUid,
                    range_cm = payload.rangeCm,
                    bearing_cdeg = payload.bearingCdeg,
                    stroke_color = Team.fromValue(payload.strokeColor) ?: Team.Unspecifed_Color,
                    stroke_argb = payload.strokeArgb,
                    stroke_weight_x10 = payload.strokeWeightX10,
                )
            }
            is TakPacketV2Data.Payload.Route -> {
                routePayload = Route(
                    method = Route.Method.fromValue(payload.method) ?: Route.Method.Method_Unspecified,
                    direction = Route.Direction.fromValue(payload.direction) ?: Route.Direction.Direction_Unspecified,
                    prefix = payload.prefix,
                    stroke_weight_x10 = payload.strokeWeightX10,
                    truncated = payload.truncated,
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
                )
            }
            is TakPacketV2Data.Payload.CasevacReport -> {
                casevacPayload = CasevacReport(
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
                )
            }
            is TakPacketV2Data.Payload.EmergencyAlert -> {
                emergencyPayload = EmergencyAlert(
                    type = EmergencyAlert.Type.fromValue(payload.type)
                        ?: EmergencyAlert.Type.Type_Unspecified,
                    authoring_uid = payload.authoringUid,
                    cancel_reference_uid = payload.cancelReferenceUid,
                )
            }
            is TakPacketV2Data.Payload.TaskRequest -> {
                taskPayload = TaskRequest(
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
            is TakPacketV2Data.Payload.None -> { /* all payloads remain null */ }
        }

        val proto = TAKPacketV2(
            cot_type_id = CotType.fromValue(data.cotTypeId) ?: CotType.CotType_Other,
            cot_type_str = data.cotTypeStr ?: "",
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
            geo_src = GeoPointSource.fromValue(data.geoSrc) ?: GeoPointSource.GeoPointSource_Unspecified,
            alt_src = GeoPointSource.fromValue(data.altSrc) ?: GeoPointSource.GeoPointSource_Unspecified,
            uid = data.uid,
            device_callsign = data.deviceCallsign,
            stale_seconds = data.staleSeconds,
            tak_version = data.takVersion,
            tak_device = data.takDevice,
            tak_platform = data.takPlatform,
            tak_os = data.takOs,
            endpoint = data.endpoint,
            phone = data.phone,
            remarks = data.remarks,
            pli = pliPayload,
            chat = chatPayload,
            aircraft = aircraftPayload,
            raw_detail = rawDetailPayload,
            shape = shapePayload,
            marker = markerPayload,
            rab = rabPayload,
            route = routePayload,
            casevac = casevacPayload,
            emergency = emergencyPayload,
            task = taskPayload,
        )

        return proto.encode()
    }

    /**
     * Deserialize protobuf wire bytes back into a [TakPacketV2Data].
     *
     * @param bytes the encoded protobuf bytes (as produced by [serialize])
     * @return the deserialized SDK data class
     * @throws com.squareup.wire.ProtoAdapter.EnumConstantNotFoundException
     *         if an unknown enum value is encountered (shouldn't happen in
     *         practice since Wire falls back to default values)
     */
    @Throws(IllegalStateException::class)
    public fun deserialize(bytes: ByteArray): TakPacketV2Data {
        val proto = TAKPacketV2.ADAPTER.decode(bytes)

        val payload = when {
            proto.chat != null -> {
                val chat = proto.chat
                TakPacketV2Data.Payload.Chat(
                    message = chat.message,
                    to = chat.to?.ifEmpty { null },
                    toCallsign = chat.to_callsign?.ifEmpty { null },
                    receiptForUid = chat.receipt_for_uid,
                    receiptType = chat.receipt_type.value,
                )
            }
            proto.aircraft != null -> {
                val aircraft = proto.aircraft
                TakPacketV2Data.Payload.Aircraft(
                    icao = aircraft.icao,
                    registration = aircraft.registration,
                    flight = aircraft.flight,
                    aircraftType = aircraft.aircraft_type,
                    squawk = aircraft.squawk,
                    category = aircraft.category,
                    rssiX10 = aircraft.rssi_x10,
                    gps = aircraft.gps,
                    cotHostId = aircraft.cot_host_id,
                )
            }
            proto.route != null -> {
                val route = proto.route
                TakPacketV2Data.Payload.Route(
                    method = route.method.value,
                    direction = route.direction.value,
                    prefix = route.prefix,
                    strokeWeightX10 = route.stroke_weight_x10,
                    truncated = route.truncated,
                    links = route.links.map { link ->
                        TakPacketV2Data.Payload.Route.Link(
                            latI = proto.latitude_i + (link.point?.lat_delta_i ?: 0),
                            lonI = proto.longitude_i + (link.point?.lon_delta_i ?: 0),
                            uid = link.uid,
                            callsign = link.callsign,
                            linkType = link.link_type,
                        )
                    },
                )
            }
            proto.rab != null -> {
                val rab = proto.rab
                TakPacketV2Data.Payload.RangeAndBearing(
                    anchorLatI = proto.latitude_i + (rab.anchor?.lat_delta_i ?: 0),
                    anchorLonI = proto.longitude_i + (rab.anchor?.lon_delta_i ?: 0),
                    anchorUid = rab.anchor_uid,
                    rangeCm = rab.range_cm,
                    bearingCdeg = rab.bearing_cdeg,
                    strokeColor = rab.stroke_color.value,
                    strokeArgb = rab.stroke_argb,
                    strokeWeightX10 = rab.stroke_weight_x10,
                )
            }
            proto.shape != null -> {
                val shape = proto.shape
                TakPacketV2Data.Payload.DrawnShape(
                    kind = shape.kind.value,
                    style = shape.style.value,
                    majorCm = shape.major_cm,
                    minorCm = shape.minor_cm,
                    angleDeg = shape.angle_deg,
                    strokeColor = shape.stroke_color.value,
                    strokeArgb = shape.stroke_argb,
                    strokeWeightX10 = shape.stroke_weight_x10,
                    fillColor = shape.fill_color.value,
                    fillArgb = shape.fill_argb,
                    labelsOn = shape.labels_on,
                    truncated = shape.truncated,
                    vertices = shape.vertices.map { v ->
                        TakPacketV2Data.Payload.Vertex(
                            latI = proto.latitude_i + v.lat_delta_i,
                            lonI = proto.longitude_i + v.lon_delta_i,
                        )
                    },
                    bullseyeDistanceDm = shape.bullseye_distance_dm,
                    bullseyeBearingRef = shape.bullseye_bearing_ref,
                    bullseyeFlags = shape.bullseye_flags,
                    bullseyeUidRef = shape.bullseye_uid_ref,
                )
            }
            proto.marker != null -> {
                val marker = proto.marker
                TakPacketV2Data.Payload.Marker(
                    kind = marker.kind.value,
                    color = marker.color.value,
                    colorArgb = marker.color_argb,
                    readiness = marker.readiness,
                    parentUid = marker.parent_uid,
                    parentType = marker.parent_type,
                    parentCallsign = marker.parent_callsign,
                    iconset = marker.iconset,
                )
            }
            proto.casevac != null -> {
                val casevac = proto.casevac
                TakPacketV2Data.Payload.CasevacReport(
                    precedence = casevac.precedence.value,
                    equipmentFlags = casevac.equipment_flags,
                    litterPatients = casevac.litter_patients,
                    ambulatoryPatients = casevac.ambulatory_patients,
                    security = casevac.security.value,
                    hlzMarking = casevac.hlz_marking.value,
                    zoneMarker = casevac.zone_marker,
                    usMilitary = casevac.us_military,
                    usCivilian = casevac.us_civilian,
                    nonUsMilitary = casevac.non_us_military,
                    nonUsCivilian = casevac.non_us_civilian,
                    epw = casevac.epw,
                    child = casevac.child,
                    terrainFlags = casevac.terrain_flags,
                    frequency = casevac.frequency,
                )
            }
            proto.emergency != null -> {
                val emergency = proto.emergency
                TakPacketV2Data.Payload.EmergencyAlert(
                    type = emergency.type.value,
                    authoringUid = emergency.authoring_uid,
                    cancelReferenceUid = emergency.cancel_reference_uid,
                )
            }
            proto.task != null -> {
                val task = proto.task
                TakPacketV2Data.Payload.TaskRequest(
                    taskType = task.task_type,
                    targetUid = task.target_uid,
                    assigneeUid = task.assignee_uid,
                    priority = task.priority.value,
                    status = task.status.value,
                    note = task.note,
                )
            }
            proto.raw_detail != null -> TakPacketV2Data.Payload.RawDetail(proto.raw_detail.toByteArray())
            proto.pli != null -> TakPacketV2Data.Payload.Pli(proto.pli)
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
