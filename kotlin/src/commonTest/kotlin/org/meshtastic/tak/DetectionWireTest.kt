package org.meshtastic.tak

import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import org.meshtastic.tak.TakPacketV2Data.DetectionData
import org.meshtastic.tak.TakPacketV2Data.DetectionData.Kind
import org.meshtastic.tak.TakPacketV2Data.DetectionData.Source
import org.meshtastic.tak.TakPacketV2Data.DetectionData.State
import org.meshtastic.tak.TakPacketV2Data.DetectionData.Trend
import org.meshtastic.tak.TakPacketV2Data.DetectionData.UasIdType
import org.meshtastic.tak.TakPacketV2Data.DetectionData.UasStatus
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [DetectionWire], which writes and reads `TAKPacketV2.detection` (tag 47) without a generated
 * type. The expected bytes are protoc's for the same `TAKDetection`.
 */
class DetectionWireTest {
    private val full =
        DetectionData(
            kind = Kind.Uas,
            sources = Source.RemoteId.value or Source.Rf.value,
            confidence = 87,
            state = State.Active,
            trend = Trend.Closing,
            count = 2,
            sensorAnchored = true,
            cepMeters = 12,
            rssiDbm = -71,
            uasId = "ZZZZ8TEST0001".encodeToByteArray(),
            uasIdType = UasIdType.SerialNumber,
            uasStatus = UasStatus.Airborne,
        )

    // protoc's bytes for `full`: all 12 fields in tag order, rssi as a zigzag sint32.
    private val fullHex = "0801100318572002280130023801400c488d01520d5a5a5a5a38544553543030303158016002"

    private fun packet(detection: DetectionData?): TakPacketV2Data =
        TakPacketV2Data
            .Builder()
            .cotType("a-u-A-M-H-Q")
            .uid("2f6d3b8e-5a41-4c1f-9e07-6b2d8c4a1f35")
            .callsign("UAS-1")
            .detection(detection)
            .build()

    private fun serialize(packet: TakPacketV2Data): ByteArray = TakPacketV2Serializer.serialize(packet)

    private fun deserialize(bytes: ByteArray): TakPacketV2Data = TakPacketV2Serializer.deserialize(bytes)

    // Tag 47 as a length-delimited field around [messageHex]: key (47 << 3) | 2 = 378, varint fa 02.
    private fun field47(messageHex: String): ByteArray {
        val length = messageHex.length / 2
        check(length < 0x80) { "one-byte length only" }
        return ("fa02" + length.toString(16).padStart(2, '0') + messageHex).decodeHex().toByteArray()
    }

    @Test
    fun aDetectionIsWrittenAsProtocWritesIt() {
        assertEquals(fullHex, DetectionWire.encode(full).hex())
    }

    @Test
    fun itGoesAfterEveryOtherFieldWhereTagOrderPutsIt() {
        val bytes = serialize(packet(full))
        assertTrue(bytes.toByteString().endsWith(field47(fullHex).toByteString()))
        assertEquals(serialize(packet(null)).size, bytes.size - field47(fullHex).size)
    }

    @Test
    fun aDetectionIsReadWhereverItSitsInThePacket() {
        val first = field47(fullHex) + serialize(packet(null))
        assertEquals(deserialize(serialize(packet(full))), deserialize(first))
        assertEquals(full, deserialize(first).detection)
    }

    @Test
    fun anAllDefaultDetectionIsAnEmptyMessageAndStillADetection() {
        assertEquals("", DetectionWire.encode(DetectionData()).hex())
        val bytes = serialize(packet(DetectionData()))
        assertTrue(bytes.toByteString().endsWith(field47("").toByteString()))
        assertEquals(DetectionData(), deserialize(bytes).detection)
        assertNull(deserialize(serialize(packet(null))).detection)
    }

    @Test
    fun unknownFieldsAndEnumValuesInTheMessageAreSkipped() {
        // State 99, then a varint tag 13 and a length-delimited tag 14 this model doesn't have.
        val odd = fullHex.replaceFirst("18572002", "18572063") + "6805" + "720161"
        assertEquals(full.copy(state = State.Unspecified), deserialize(field47(odd) + serialize(packet(null))).detection)
    }

    @Test
    fun repeatedTag47MessagesMerge() {
        // The first gives the kind and the second the confidence; protobuf merges them, so both stay.
        val merged = field47("0801") + field47("1857") + serialize(packet(null))
        assertEquals(DetectionData(kind = Kind.Uas, confidence = 87), deserialize(merged).detection)
        // An empty second message takes nothing away.
        assertEquals(full, deserialize(field47(fullHex) + field47("") + serialize(packet(null))).detection)
    }

    @Test
    fun aFieldInAnotherWireTypeIsSkipped() {
        // confidence (tag 3) as length-delimited bytes, then a valid state: only the state counts.
        assertEquals(DetectionData(state = State.Active), deserialize(field47("1a01572002") + serialize(packet(null))).detection)
        // Tag 47 as a varint, key (47 << 3) | 0 = 376 (f8 02), is no TAKDetection; protoc skips it too.
        assertNull(deserialize("f80205".decodeHex().toByteArray() + serialize(packet(null))).detection)
    }

    @Test
    fun aUasIdGoesOnlyWithAUasAndAtMost20Bytes() {
        // protoc's bytes for `full` as a vehicle without its ID.
        val vehicle = full.copy(kind = Kind.Vehicle)
        assertEquals("0805100318572002280130023801400c488d0158016002", DetectionWire.encode(vehicle).hex())
        assertEquals(vehicle.copy(uasId = ByteArray(0)), deserialize(serialize(packet(vehicle))).detection)

        val long = ByteArray(25) { 0x41 }
        assertContentEquals(long.copyOf(20), deserialize(serialize(packet(full.copy(uasId = long)))).detection?.uasId)

        // What a peer sends is read the same way: a vehicle's ID is dropped, a long one cut.
        assertEquals(DetectionData(kind = Kind.Vehicle), deserialize(field47("08055203414243") + serialize(packet(null))).detection)
        val peerLong = "0801" + "5219" + "41".repeat(25)
        assertContentEquals(long.copyOf(20), deserialize(field47(peerLong) + serialize(packet(null))).detection?.uasId)
    }
}
