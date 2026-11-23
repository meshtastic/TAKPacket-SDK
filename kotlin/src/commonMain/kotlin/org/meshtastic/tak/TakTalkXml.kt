package org.meshtastic.tak

import kotlin.io.encoding.Base64
import kotlin.random.Random

/**
 * How the ATAK TAKTALK plugin writes its voice profile and request events, shared by
 * [CotXmlBuilder] and [CotXmlParser].
 *
 * All three are CoT type `y-`, like the room broadcast, and the plugin tells them apart
 * by UID prefix, not by type. Each carries `<sender-callsign>`; the room request adds
 * `<chatroom-id>`, and the voice profile adds `<voice-data>`, `<voice-id>` and
 * `<voice-name>`.
 */
internal object TakTalkXml {
    /** UID prefix of a [TakPacketV2Data.Payload.VoiceProfile] reply (tag 44). */
    const val VOICE_PROFILE_UID_PREFIX: String = "TAKTALK-RESPONSE-"

    /** UID prefix of a [TakPacketV2Data.Payload.VoiceProfileRequest] (tag 45). */
    const val VOICE_PROFILE_REQUEST_UID_PREFIX: String = "TAKTALK-REQUEST-"

    /**
     * UID prefix of a [TakPacketV2Data.Payload.TakTalkRoomRequest] (tag 46). It has no
     * trailing hyphen, the UUID follows directly; that is what the plugin sends and
     * matches on, so it is copied exactly.
     */
    const val ROOM_REQUEST_UID_PREFIX: String = "ROOM-REQUEST"

    /** Line length of Android's `Base64.DEFAULT`, which the plugin encodes `voice-data` with. */
    private const val BASE64_LINE: Int = 76

    private const val UUID_BYTES = 16
    private const val UUID_VERSION_BYTE = 6
    private const val UUID_VARIANT_BYTE = 8
    private const val LOW_NIBBLE = 0x0F
    private const val VERSION_4 = 0x40
    private const val LOW_SIX_BITS = 0x3F
    private const val VARIANT_RFC4122 = 0x80
    private const val HEX_RADIX = 16
    private const val UUID_GROUPS = "8-4-4-4-12"

    /** The UID prefix the plugin routes [payload] by, or null for any other payload. */
    fun uidPrefixFor(payload: TakPacketV2Data.Payload): String? =
        when (payload) {
            is TakPacketV2Data.Payload.VoiceProfile -> VOICE_PROFILE_UID_PREFIX
            is TakPacketV2Data.Payload.VoiceProfileRequest -> VOICE_PROFILE_REQUEST_UID_PREFIX
            is TakPacketV2Data.Payload.TakTalkRoomRequest -> ROOM_REQUEST_UID_PREFIX
            else -> null
        }

    /**
     * [uid] with the routing prefix for [payload] in front, so the plugin can dispatch the
     * event; unchanged when it already has it or [payload] needs none. An empty UID gets a
     * random UUID after the prefix.
     */
    fun routedUid(
        uid: String,
        payload: TakPacketV2Data.Payload,
    ): String {
        val prefix = uidPrefixFor(payload)
        return if (prefix == null || uid.startsWith(prefix)) {
            uid
        } else {
            val routed = prefix + uid.ifEmpty { randomUuid() }
            trace { "CotXmlBuilder: uid \"$uid\" lacks the TAKTALK routing prefix, sent as \"$routed\"" }
            routed
        }
    }

    /**
     * `<sender-callsign>`, which every TAKTALK `y-` event carries instead of `<contact>`.
     * Written even when [callsign] is empty (`<sender-callsign></sender-callsign>`): the plugin's receiver
     * reads the element without checking that it exists, so leaving it out crashes the
     * receiver rather than leaving the sender blank.
     */
    fun appendSenderCallsign(
        sb: StringBuilder,
        callsign: String,
        esc: (String) -> String,
    ) {
        sb.append("    <sender-callsign>").append(esc(callsign)).append("</sender-callsign>\n")
    }

    /**
     * The `<detail>` children of a voice profile, voice profile request or room request,
     * as the plugin writes them; [esc] escapes XML text. Every one starts with
     * `<sender-callsign>`. The request carries nothing else, so a
     * [TakPacketV2Data.Payload.VoiceProfileRequest.voiceProfileId] can't be sent and is
     * logged as dropped.
     */
    fun appendControlDetail(
        sb: StringBuilder,
        packet: TakPacketV2Data,
        esc: (String) -> String,
    ) {
        appendSenderCallsign(sb, packet.callsign, esc)
        when (val payload = packet.payload) {
            is TakPacketV2Data.Payload.VoiceProfile -> {
                sb.append("    <voice-data>").append(encodeVoiceData(payload.profileData)).append("</voice-data>\n")
                sb.append("    <voice-id>").append(esc(payload.voiceProfileId)).append("</voice-id>\n")
                sb.append("    <voice-name>").append(esc(payload.voiceProfileName)).append("</voice-name>\n")
            }

            is TakPacketV2Data.Payload.VoiceProfileRequest -> {
                if (payload.voiceProfileId.isNotEmpty()) {
                    trace {
                        "CotXmlBuilder: VoiceProfileRequest.voiceProfileId \"${payload.voiceProfileId}\" dropped: " +
                            "the plugin's request XML carries no profile ID"
                    }
                }
            }

            is TakPacketV2Data.Payload.TakTalkRoomRequest -> {
                sb.append("    <chatroom-id>").append(esc(payload.roomId)).append("</chatroom-id>\n")
            }

            else -> {}
        }
    }

    /**
     * `voice-data` text as the plugin writes it: Base64 with Android's `Base64.DEFAULT`
     * layout, 76-character lines separated by `\n` and a trailing newline.
     */
    fun encodeVoiceData(bytes: ByteArray): String {
        if (bytes.isEmpty()) return ""
        return Base64.Default
            .encode(bytes)
            .chunked(BASE64_LINE)
            .joinToString(separator = "\n", postfix = "\n")
    }

    /**
     * Profile bytes from `voice-data` text. [text] has already had its character references
     * resolved by the XML reader, so a `&#10;` from ATAK's serializer arrives as a newline;
     * the whitespace is stripped and the rest decoded strictly. A leftover `&`, `#` or `;`,
     * or any other character outside the Base64 alphabet, is an error rather than bytes:
     * a lenient decoder would turn the digits of an unresolved `&#10;` into
     * plausible-looking garbage.
     *
     * @throws IllegalArgumentException if [text] is not valid Base64.
     */
    fun decodeVoiceData(text: String): ByteArray {
        val compact = text.filterNot { it == '\n' || it == '\r' || it == ' ' || it == '\t' }
        if (compact.isEmpty()) return ByteArray(0)
        return try {
            Base64.Default.decode(compact)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("TAKTALK <voice-data> is not valid Base64: ${e.message}", e)
        }
    }

    /** A random (version 4, RFC 4122 variant) UUID in its canonical lowercase form. */
    private fun randomUuid(): String {
        val bytes = Random.nextBytes(UUID_BYTES)
        bytes[UUID_VERSION_BYTE] = ((bytes[UUID_VERSION_BYTE].toInt() and LOW_NIBBLE) or VERSION_4).toByte()
        bytes[UUID_VARIANT_BYTE] = ((bytes[UUID_VARIANT_BYTE].toInt() and LOW_SIX_BITS) or VARIANT_RFC4122).toByte()
        var hex = bytes.joinToString("") { (it.toInt() and BYTE_MASK).toString(HEX_RADIX).padStart(2, '0') }
        return UUID_GROUPS.split("-").joinToString("-") { group ->
            hex.take(group.toInt()).also { hex = hex.drop(group.toInt()) }
        }
    }
}
