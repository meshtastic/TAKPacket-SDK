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
            is TakPacketV2Data.Payload.None -> { /* no payload */ }
        }

        return builder.build().toByteArray()
    }

    fun deserialize(bytes: ByteArray): TakPacketV2Data {
        val proto = TAKPacketV2.parseFrom(bytes)

        val payload = when {
            proto.hasPli() -> TakPacketV2Data.Payload.Pli(proto.pli)
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
            proto.hasRawDetail() -> TakPacketV2Data.Payload.RawDetail(proto.rawDetail.toByteArray())
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
