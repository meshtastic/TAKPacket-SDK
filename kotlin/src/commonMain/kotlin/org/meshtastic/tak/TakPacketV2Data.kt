package org.meshtastic.tak

/**
 * Platform-agnostic data class representing a TAKPacketV2.
 * This is the SDK's internal representation, decoupled from any specific
 * protobuf library. Each platform serializes/deserializes this to protobuf
 * wire format using its native protobuf library.
 */
data class TakPacketV2Data(
    val cotTypeId: Int = CotTypeMapper.COTTYPE_OTHER,
    val cotTypeStr: String? = null,
    val how: Int = CotTypeMapper.COTHOW_UNSPECIFIED,
    val callsign: String = "",
    val team: Int = 0,    // Team enum value
    val role: Int = 0,    // MemberRole enum value
    val latitudeI: Int = 0,   // degrees * 1e7
    val longitudeI: Int = 0,  // degrees * 1e7
    val altitude: Int = 0,    // meters HAE
    val speed: Int = 0,       // cm/s
    val course: Int = 0,      // degrees * 100
    val battery: Int = 0,     // 0-100
    val geoSrc: Int = 0,      // GeoPointSource enum
    val altSrc: Int = 0,      // GeoPointSource enum
    val uid: String = "",
    val deviceCallsign: String = "",
    val staleSeconds: Int = 0,
    val takVersion: String = "",
    val takDevice: String = "",
    val takPlatform: String = "",
    val takOs: String = "",
    val endpoint: String = "",
    val phone: String = "",
    val payload: Payload = Payload.None,
) {
    sealed class Payload {
        object None : Payload()
        data class Pli(val value: Boolean = true) : Payload()
        data class Chat(
            val message: String = "",
            val to: String? = null,
            val toCallsign: String? = null,
        ) : Payload()
        data class Aircraft(
            val icao: String = "",
            val registration: String = "",
            val flight: String = "",
            val aircraftType: String = "",
            val squawk: Int = 0,
            val category: String = "",
            val rssiX10: Int = 0,
            val gps: Boolean = false,
            val cotHostId: String = "",
        ) : Payload()
        data class RawDetail(val bytes: ByteArray) : Payload() {
            override fun equals(other: Any?): Boolean =
                other is RawDetail && bytes.contentEquals(other.bytes)
            override fun hashCode(): Int = bytes.contentHashCode()
        }
    }

    /** Convenience: get the CoT type as a string, resolving enum or fallback. */
    fun cotTypeString(): String =
        CotTypeMapper.typeToString(cotTypeId) ?: cotTypeStr ?: ""

    /** Convenience: get the how as a string. */
    fun howString(): String =
        CotTypeMapper.howToString(how) ?: ""
}
