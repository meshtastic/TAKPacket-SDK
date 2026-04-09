package org.meshtastic.tak

import org.meshtastic.proto.AircraftTrack
import org.meshtastic.proto.CotHow
import org.meshtastic.proto.CotType
import org.meshtastic.proto.GeoChat
import org.meshtastic.proto.GeoPointSource
import org.meshtastic.proto.MemberRole
import org.meshtastic.proto.TAKPacketV2
import org.meshtastic.proto.Team
import okio.ByteString.Companion.toByteString

/**
 * Serializes/deserializes [TakPacketV2Data] to/from protobuf wire format
 * using Wire KMP generated classes.
 */
object TakPacketV2Serializer {

    fun serialize(data: TakPacketV2Data): ByteArray {
        val chatPayload: GeoChat?
        val aircraftPayload: AircraftTrack?
        val pliPayload: Boolean?
        val rawDetailPayload: okio.ByteString?

        when (val payload = data.payload) {
            is TakPacketV2Data.Payload.Pli -> {
                pliPayload = true
                chatPayload = null
                aircraftPayload = null
                rawDetailPayload = null
            }
            is TakPacketV2Data.Payload.Chat -> {
                pliPayload = null
                chatPayload = GeoChat(
                    message = payload.message,
                    to = payload.to ?: "",
                    to_callsign = payload.toCallsign ?: "",
                )
                aircraftPayload = null
                rawDetailPayload = null
            }
            is TakPacketV2Data.Payload.Aircraft -> {
                pliPayload = null
                chatPayload = null
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
                rawDetailPayload = null
            }
            is TakPacketV2Data.Payload.RawDetail -> {
                pliPayload = null
                chatPayload = null
                aircraftPayload = null
                rawDetailPayload = payload.bytes.toByteString()
            }
            is TakPacketV2Data.Payload.None -> {
                pliPayload = null
                chatPayload = null
                aircraftPayload = null
                rawDetailPayload = null
            }
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
            pli = pliPayload,
            chat = chatPayload,
            aircraft = aircraftPayload,
            raw_detail = rawDetailPayload,
        )

        return proto.encode()
    }

    fun deserialize(bytes: ByteArray): TakPacketV2Data {
        val proto = TAKPacketV2.ADAPTER.decode(bytes)

        val payload = when {
            proto.pli != null -> TakPacketV2Data.Payload.Pli(proto.pli)
            proto.chat != null -> TakPacketV2Data.Payload.Chat(
                message = proto.chat.message,
                to = proto.chat.to?.ifEmpty { null },
                toCallsign = proto.chat.to_callsign?.ifEmpty { null },
            )
            proto.aircraft != null -> TakPacketV2Data.Payload.Aircraft(
                icao = proto.aircraft.icao,
                registration = proto.aircraft.registration,
                flight = proto.aircraft.flight,
                aircraftType = proto.aircraft.aircraft_type,
                squawk = proto.aircraft.squawk,
                category = proto.aircraft.category,
                rssiX10 = proto.aircraft.rssi_x10,
                gps = proto.aircraft.gps,
                cotHostId = proto.aircraft.cot_host_id,
            )
            proto.raw_detail != null -> TakPacketV2Data.Payload.RawDetail(proto.raw_detail.toByteArray())
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
            payload = payload,
        )
    }
}
