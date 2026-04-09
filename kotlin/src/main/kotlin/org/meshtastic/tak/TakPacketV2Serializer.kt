package org.meshtastic.tak

import com.google.protobuf.ByteString
import org.meshtastic.proto.ATAKProtos.*

/**
 * Serializes/deserializes TakPacketV2Data to/from protobuf wire format
 * using the generated protobuf-lite classes.
 */
object TakPacketV2Serializer {

    fun serialize(data: TakPacketV2Data): ByteArray {
        val builder = TAKPacketV2.newBuilder()
            .setCotTypeId(CotType.forNumber(data.cotTypeId) ?: CotType.CotType_Other)
            .setHow(CotHow.forNumber(data.how) ?: CotHow.CotHow_Unspecified)
            .setCallsign(data.callsign)
            .setTeam(Team.forNumber(data.team) ?: Team.Unspecifed_Color)
            .setRole(MemberRole.forNumber(data.role) ?: MemberRole.Unspecifed)
            .setLatitudeI(data.latitudeI)
            .setLongitudeI(data.longitudeI)
            .setAltitude(data.altitude)
            .setSpeed(data.speed)
            .setCourse(data.course)
            .setBattery(data.battery)
            .setGeoSrc(GeoPointSource.forNumber(data.geoSrc) ?: GeoPointSource.GeoPointSource_Unspecified)
            .setAltSrc(GeoPointSource.forNumber(data.altSrc) ?: GeoPointSource.GeoPointSource_Unspecified)
            .setUid(data.uid)
            .setDeviceCallsign(data.deviceCallsign)
            .setStaleSeconds(data.staleSeconds)
            .setTakVersion(data.takVersion)
            .setTakDevice(data.takDevice)
            .setTakPlatform(data.takPlatform)
            .setTakOs(data.takOs)
            .setEndpoint(data.endpoint)
            .setPhone(data.phone)

        if (data.cotTypeStr != null) {
            builder.setCotTypeStr(data.cotTypeStr)
        }

        when (val payload = data.payload) {
            is TakPacketV2Data.Payload.Pli -> builder.setPli(true)
            is TakPacketV2Data.Payload.Chat -> {
                val chatBuilder = GeoChat.newBuilder().setMessage(payload.message)
                payload.to?.let { chatBuilder.setTo(it) }
                payload.toCallsign?.let { chatBuilder.setToCallsign(it) }
                builder.setChat(chatBuilder)
            }
            is TakPacketV2Data.Payload.Aircraft -> {
                builder.setAircraft(
                    AircraftTrack.newBuilder()
                        .setIcao(payload.icao)
                        .setRegistration(payload.registration)
                        .setFlight(payload.flight)
                        .setAircraftType(payload.aircraftType)
                        .setSquawk(payload.squawk)
                        .setCategory(payload.category)
                        .setRssiX10(payload.rssiX10)
                        .setGps(payload.gps)
                        .setCotHostId(payload.cotHostId)
                )
            }
            is TakPacketV2Data.Payload.RawDetail -> {
                builder.setRawDetail(ByteString.copyFrom(payload.bytes))
            }
            is TakPacketV2Data.Payload.DrawnShape -> {
                val shapeBuilder = DrawnShape.newBuilder()
                    .setKindValue(payload.kind)
                    .setStyleValue(payload.style)
                    .setMajorCm(payload.majorCm)
                    .setMinorCm(payload.minorCm)
                    .setAngleDeg(payload.angleDeg)
                    .setStrokeColor(Team.forNumber(payload.strokeColor) ?: Team.Unspecifed_Color)
                    .setStrokeArgb(payload.strokeArgb)
                    .setStrokeWeightX10(payload.strokeWeightX10)
                    .setFillColor(Team.forNumber(payload.fillColor) ?: Team.Unspecifed_Color)
                    .setFillArgb(payload.fillArgb)
                    .setLabelsOn(payload.labelsOn)
                    .setTruncated(payload.truncated)
                    .setBullseyeDistanceDm(payload.bullseyeDistanceDm)
                    .setBullseyeBearingRef(payload.bullseyeBearingRef)
                    .setBullseyeFlags(payload.bullseyeFlags)
                    .setBullseyeUidRef(payload.bullseyeUidRef)
                // GeoPoint is delta-encoded from the event anchor — see atak.proto.
                payload.vertices.forEach { v ->
                    shapeBuilder.addVertices(
                        GeoPoint.newBuilder()
                            .setLatDeltaI(v.latI - data.latitudeI)
                            .setLonDeltaI(v.lonI - data.longitudeI)
                    )
                }
                builder.setShape(shapeBuilder)
            }
            is TakPacketV2Data.Payload.Marker -> {
                builder.setMarker(
                    Marker.newBuilder()
                        .setKindValue(payload.kind)
                        .setColor(Team.forNumber(payload.color) ?: Team.Unspecifed_Color)
                        .setColorArgb(payload.colorArgb)
                        .setReadiness(payload.readiness)
                        .setParentUid(payload.parentUid)
                        .setParentType(payload.parentType)
                        .setParentCallsign(payload.parentCallsign)
                        .setIconset(payload.iconset)
                )
            }
            is TakPacketV2Data.Payload.RangeAndBearing -> {
                builder.setRab(
                    RangeAndBearing.newBuilder()
                        .setAnchor(
                            GeoPoint.newBuilder()
                                .setLatDeltaI(payload.anchorLatI - data.latitudeI)
                                .setLonDeltaI(payload.anchorLonI - data.longitudeI)
                        )
                        .setAnchorUid(payload.anchorUid)
                        .setRangeCm(payload.rangeCm)
                        .setBearingCdeg(payload.bearingCdeg)
                        .setStrokeColor(Team.forNumber(payload.strokeColor) ?: Team.Unspecifed_Color)
                        .setStrokeArgb(payload.strokeArgb)
                        .setStrokeWeightX10(payload.strokeWeightX10)
                )
            }
            is TakPacketV2Data.Payload.Route -> {
                val routeBuilder = Route.newBuilder()
                    .setMethodValue(payload.method)
                    .setDirectionValue(payload.direction)
                    .setPrefix(payload.prefix)
                    .setStrokeWeightX10(payload.strokeWeightX10)
                    .setTruncated(payload.truncated)
                payload.links.forEach { link ->
                    routeBuilder.addLinks(
                        Route.Link.newBuilder()
                            .setPoint(
                                GeoPoint.newBuilder()
                                    .setLatDeltaI(link.latI - data.latitudeI)
                                    .setLonDeltaI(link.lonI - data.longitudeI)
                            )
                            .setUid(link.uid)
                            .setCallsign(link.callsign)
                            .setLinkType(link.linkType)
                    )
                }
                builder.setRoute(routeBuilder)
            }
            is TakPacketV2Data.Payload.None -> { /* no payload */ }
        }

        return builder.build().toByteArray()
    }

    fun deserialize(bytes: ByteArray): TakPacketV2Data {
        val proto = TAKPacketV2.parseFrom(bytes)

        val payload = when {
            proto.hasChat() -> TakPacketV2Data.Payload.Chat(
                message = proto.chat.message,
                to = if (proto.chat.hasTo()) proto.chat.to else null,
                toCallsign = if (proto.chat.hasToCallsign()) proto.chat.toCallsign else null,
            )
            proto.hasAircraft() -> TakPacketV2Data.Payload.Aircraft(
                icao = proto.aircraft.icao,
                registration = proto.aircraft.registration,
                flight = proto.aircraft.flight,
                aircraftType = proto.aircraft.aircraftType,
                squawk = proto.aircraft.squawk,
                category = proto.aircraft.category,
                rssiX10 = proto.aircraft.rssiX10,
                gps = proto.aircraft.gps,
                cotHostId = proto.aircraft.cotHostId,
            )
            proto.hasRoute() -> TakPacketV2Data.Payload.Route(
                method = proto.route.methodValue,
                direction = proto.route.directionValue,
                prefix = proto.route.prefix,
                strokeWeightX10 = proto.route.strokeWeightX10,
                // GeoPoint is delta-encoded from the event anchor — re-add
                // the top-level latitude_i/longitude_i to recover absolutes.
                links = proto.route.linksList.map { link ->
                    TakPacketV2Data.Payload.Route.Link(
                        latI = proto.latitudeI + link.point.latDeltaI,
                        lonI = proto.longitudeI + link.point.lonDeltaI,
                        uid = link.uid,
                        callsign = link.callsign,
                        linkType = link.linkType,
                    )
                },
                truncated = proto.route.truncated,
            )
            proto.hasRab() -> TakPacketV2Data.Payload.RangeAndBearing(
                anchorLatI = proto.latitudeI + proto.rab.anchor.latDeltaI,
                anchorLonI = proto.longitudeI + proto.rab.anchor.lonDeltaI,
                anchorUid = proto.rab.anchorUid,
                rangeCm = proto.rab.rangeCm,
                bearingCdeg = proto.rab.bearingCdeg,
                strokeColor = proto.rab.strokeColorValue,
                strokeArgb = proto.rab.strokeArgb,
                strokeWeightX10 = proto.rab.strokeWeightX10,
            )
            proto.hasShape() -> TakPacketV2Data.Payload.DrawnShape(
                kind = proto.shape.kindValue,
                style = proto.shape.styleValue,
                majorCm = proto.shape.majorCm,
                minorCm = proto.shape.minorCm,
                angleDeg = proto.shape.angleDeg,
                strokeColor = proto.shape.strokeColorValue,
                strokeArgb = proto.shape.strokeArgb,
                strokeWeightX10 = proto.shape.strokeWeightX10,
                fillColor = proto.shape.fillColorValue,
                fillArgb = proto.shape.fillArgb,
                labelsOn = proto.shape.labelsOn,
                vertices = proto.shape.verticesList.map { v ->
                    TakPacketV2Data.Payload.Vertex(
                        latI = proto.latitudeI + v.latDeltaI,
                        lonI = proto.longitudeI + v.lonDeltaI,
                    )
                },
                truncated = proto.shape.truncated,
                bullseyeDistanceDm = proto.shape.bullseyeDistanceDm,
                bullseyeBearingRef = proto.shape.bullseyeBearingRef,
                bullseyeFlags = proto.shape.bullseyeFlags,
                bullseyeUidRef = proto.shape.bullseyeUidRef,
            )
            proto.hasMarker() -> TakPacketV2Data.Payload.Marker(
                kind = proto.marker.kindValue,
                color = proto.marker.colorValue,
                colorArgb = proto.marker.colorArgb,
                readiness = proto.marker.readiness,
                parentUid = proto.marker.parentUid,
                parentType = proto.marker.parentType,
                parentCallsign = proto.marker.parentCallsign,
                iconset = proto.marker.iconset,
            )
            proto.hasRawDetail() -> TakPacketV2Data.Payload.RawDetail(proto.rawDetail.toByteArray())
            proto.hasPli() -> TakPacketV2Data.Payload.Pli(proto.pli)
            else -> TakPacketV2Data.Payload.None
        }

        return TakPacketV2Data(
            cotTypeId = proto.cotTypeIdValue,
            cotTypeStr = proto.cotTypeStr.ifEmpty { null },
            how = proto.howValue,
            callsign = proto.callsign,
            team = proto.teamValue,
            role = proto.roleValue,
            latitudeI = proto.latitudeI,
            longitudeI = proto.longitudeI,
            altitude = proto.altitude,
            speed = proto.speed,
            course = proto.course,
            battery = proto.battery,
            geoSrc = proto.geoSrcValue,
            altSrc = proto.altSrcValue,
            uid = proto.uid,
            deviceCallsign = proto.deviceCallsign,
            staleSeconds = proto.staleSeconds,
            takVersion = proto.takVersion,
            takDevice = proto.takDevice,
            takPlatform = proto.takPlatform,
            takOs = proto.takOs,
            endpoint = proto.endpoint,
            phone = proto.phone,
            payload = payload,
        )
    }
}
