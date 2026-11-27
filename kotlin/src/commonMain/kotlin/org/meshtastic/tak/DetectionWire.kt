package org.meshtastic.tak

import com.squareup.wire.FieldEncoding
import com.squareup.wire.ProtoAdapter
import com.squareup.wire.ProtoReader
import com.squareup.wire.ProtoWriter
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.meshtastic.tak.TakPacketV2Data.DetectionData

/**
 * `TAKPacketV2.detection` (tag 47), a `TAKDetection` message, written and read without a
 * generated type. The published `org.meshtastic:protobufs` this binding builds on predates the
 * message, so [TakPacketV2Serializer] adds the field to the packet as an unknown field and reads
 * it back from the packet's bytes. The message's bytes are the ones generated code writes:
 * fields in tag order, each left out at its default.
 *
 * Reading the packet's bytes rather than the decoded packet's unknown fields keeps working when
 * a consumer's protobufs knows tag 47 and decodes it into a field of its own.
 *
 * Once a protobufs release with the message is this binding's floor, the serializer can use the
 * generated type and this object can go.
 */
internal object DetectionWire {
    /** The tag of `TAKPacketV2.detection`. */
    const val TAG: Int = 47

    private const val KIND = 1
    private const val SOURCES = 2
    private const val CONFIDENCE = 3
    private const val STATE = 4
    private const val TREND = 5
    private const val COUNT = 6
    private const val SENSOR_ANCHORED = 7
    private const val CEP_M = 8
    private const val RSSI_DBM = 9
    private const val UAS_ID = 10
    private const val UAS_ID_TYPE = 11
    private const val UAS_STATUS = 12

    /** [detection] as a `TAKDetection` message. */
    fun encode(detection: DetectionData): ByteString {
        val buffer = Buffer()
        val writer = ProtoWriter(buffer)
        with(detection) {
            writer.uint32(KIND, kind.value)
            writer.uint32(SOURCES, sources)
            writer.uint32(CONFIDENCE, confidence)
            writer.uint32(STATE, state.value)
            writer.uint32(TREND, trend.value)
            writer.uint32(COUNT, count)
            if (sensorAnchored) ProtoAdapter.BOOL.encodeWithTag(writer, SENSOR_ANCHORED, true)
            writer.uint32(CEP_M, cepMeters)
            if (rssiDbm != 0) ProtoAdapter.SINT32.encodeWithTag(writer, RSSI_DBM, rssiDbm)
            if (uasId.isNotEmpty()) ProtoAdapter.BYTES.encodeWithTag(writer, UAS_ID, uasId.toByteString())
            writer.uint32(UAS_ID_TYPE, uasIdType.value)
            writer.uint32(UAS_STATUS, uasStatus.value)
        }
        return buffer.readByteString()
    }

    /**
     * The detection in a `TAKPacketV2`'s [packet] bytes, or null when it has none. Like generated
     * code, it takes the last tag 47 when there are several.
     */
    fun read(packet: ByteArray): DetectionData? {
        var message: ByteString? = null
        val reader = ProtoReader(Buffer().write(packet))
        reader.forEachField { tag ->
            if (tag == TAG && reader.peekFieldEncoding() == FieldEncoding.LENGTH_DELIMITED) {
                message = reader.readBytes()
            } else {
                reader.skip()
            }
        }
        return message?.let(::decode)
    }

    // Each field is read with the adapter generated code uses for its type, and an enum value
    // this model lacks falls back to Unspecified, as everywhere in the serializer.
    private fun decode(message: ByteString): DetectionData {
        val values = IntArray(UAS_STATUS + 1)
        var sensorAnchored = false
        var uasId = ByteString.EMPTY
        val reader = ProtoReader(Buffer().write(message))
        reader.forEachField { tag ->
            when (tag) {
                SENSOR_ANCHORED -> sensorAnchored = ProtoAdapter.BOOL.decode(reader)
                RSSI_DBM -> values[tag] = ProtoAdapter.SINT32.decode(reader)
                UAS_ID -> uasId = ProtoAdapter.BYTES.decode(reader)
                in KIND..UAS_STATUS -> values[tag] = ProtoAdapter.UINT32.decode(reader)
                else -> reader.skip()
            }
        }
        return DetectionData(
            kind = DetectionData.Kind.fromValue(values[KIND]),
            sources = values[SOURCES],
            confidence = values[CONFIDENCE],
            state = DetectionData.State.fromValue(values[STATE]),
            trend = DetectionData.Trend.fromValue(values[TREND]),
            count = values[COUNT],
            sensorAnchored = sensorAnchored,
            cepMeters = values[CEP_M],
            rssiDbm = values[RSSI_DBM],
            uasId = uasId.toByteArray(),
            uasIdType = DetectionData.UasIdType.fromValue(values[UAS_ID_TYPE]),
            uasStatus = DetectionData.UasStatus.fromValue(values[UAS_STATUS]),
        )
    }

    // A uint32 or enum field, left out at zero as proto3 leaves out a default.
    private fun ProtoWriter.uint32(
        tag: Int,
        value: Int,
    ) {
        if (value != 0) ProtoAdapter.UINT32.encodeWithTag(this, tag, value)
    }

    /** Calls [onField] with each field's tag in turn, from one message; it must read or skip the field. */
    private inline fun ProtoReader.forEachField(onField: (Int) -> Unit) {
        val token = beginMessage()
        while (true) {
            val tag = nextTag()
            if (tag == -1) break
            onField(tag)
        }
        endMessageAndGetUnknownFields(token)
    }
}
