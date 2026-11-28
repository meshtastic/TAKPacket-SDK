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
 * it back from the packet's bytes. Both directions follow the protoc runtimes the other bindings
 * use: fields go out in tag order, each left out at its default, and reading merges repeated
 * tag 47 messages and skips a field whose wire type is not its own.
 *
 * Reading the packet's bytes rather than the decoded packet's unknown fields keeps working when
 * a consumer's protobufs knows tag 47 and decodes it into a field of its own.
 *
 * Both directions keep `uas_id` only for [DetectionData.Kind.Uas], cut to 20 bytes, as the
 * schema says and as the XML does.
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

    // The schema's cap on uas_id, the one UasIdXml applies to the XML.
    private const val UAS_ID_MAX_BYTES = 20

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
            val id = uasIdFor(kind, uasId)
            if (id.isNotEmpty()) ProtoAdapter.BYTES.encodeWithTag(writer, UAS_ID, id.toByteString())
            writer.uint32(UAS_ID_TYPE, uasIdType.value)
            writer.uint32(UAS_STATUS, uasStatus.value)
        }
        return buffer.readByteString()
    }

    /**
     * The detection in a `TAKPacketV2`'s [packet] bytes, or null when it has none. Several tag 47
     * messages merge, as protobuf merges a message field that appears more than once: a later
     * one's fields replace an earlier one's, and the rest stay.
     */
    fun read(packet: ByteArray): DetectionData? {
        var found = false
        // Merging messages is parsing their concatenation.
        val messages = Buffer()
        val reader = ProtoReader(Buffer().write(packet))
        reader.forEachField { tag ->
            if (tag == TAG && reader.peekFieldEncoding() == FieldEncoding.LENGTH_DELIMITED) {
                messages.write(reader.readBytes())
                found = true
            } else {
                reader.skip()
            }
        }
        return if (found) decode(messages.readByteString()) else null
    }

    // Each field is read with the adapter generated code uses for its type, and only in its own
    // wire type: one in another is skipped, as the protoc runtimes skip it. An enum value this
    // model lacks falls back to Unspecified, as everywhere in the serializer.
    private fun decode(message: ByteString): DetectionData {
        val values = IntArray(UAS_STATUS + 1)
        var sensorAnchored = false
        var uasId = ByteString.EMPTY
        val reader = ProtoReader(Buffer().write(message))
        reader.forEachField { tag ->
            val encoding = if (tag == UAS_ID) FieldEncoding.LENGTH_DELIMITED else FieldEncoding.VARINT
            when {
                tag !in KIND..UAS_STATUS || reader.peekFieldEncoding() != encoding -> reader.skip()
                tag == SENSOR_ANCHORED -> sensorAnchored = ProtoAdapter.BOOL.decode(reader)
                tag == RSSI_DBM -> values[tag] = ProtoAdapter.SINT32.decode(reader)
                tag == UAS_ID -> uasId = ProtoAdapter.BYTES.decode(reader)
                else -> values[tag] = ProtoAdapter.UINT32.decode(reader)
            }
        }
        val kind = DetectionData.Kind.fromValue(values[KIND])
        return DetectionData(
            kind = kind,
            sources = values[SOURCES],
            confidence = values[CONFIDENCE],
            state = DetectionData.State.fromValue(values[STATE]),
            trend = DetectionData.Trend.fromValue(values[TREND]),
            count = values[COUNT],
            sensorAnchored = sensorAnchored,
            cepMeters = values[CEP_M],
            rssiDbm = values[RSSI_DBM],
            uasId = uasIdFor(kind, uasId.toByteArray()),
            uasIdType = DetectionData.UasIdType.fromValue(values[UAS_ID_TYPE]),
            uasStatus = DetectionData.UasStatus.fromValue(values[UAS_STATUS]),
        )
    }

    // uas_id as the schema allows it: only for a UAS, and at most 20 bytes.
    private fun uasIdFor(
        kind: DetectionData.Kind,
        id: ByteArray,
    ): ByteArray =
        when {
            kind != DetectionData.Kind.Uas -> ByteArray(0)
            id.size > UAS_ID_MAX_BYTES -> id.copyOf(UAS_ID_MAX_BYTES)
            else -> id
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
