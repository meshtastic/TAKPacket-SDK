package org.meshtastic.tak

import org.meshtastic.tak.internal.zstd.PureZstdDecoder
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-platform spot check of the pure-Kotlin dictionary-aware zstd decoder
 * (`kotlin.test`, every target).
 *
 * The JVM [PureZstdDecoderGoldenTest] already validates all 47 compressed
 * goldens byte-for-byte against the `testdata/protobuf` `.pb` files; this is the lightweight
 * cross-platform counterpart that proves the SAME decoder runs correctly on
 * Native / JS / Wasm, where the byte-exact `.pb` oracle isn't on the filesystem.
 *
 * It decodes one non-aircraft (dict 0) and one aircraft (dict 1) golden frame
 * directly through [PureZstdDecoder] — exercising both shipped dictionaries —
 * and confirms the output deserializes back to the expected packet.
 */
class PureZstdDecoderCommonTest {

    // The 4-byte zstd frame magic that TakCompressor strips on encode; the
    // golden .bin frames have it stripped, so the decoder gets it re-prepended.
    private val zstdMagic = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    /** Re-frame a golden .bin: drop the 1-byte flags prefix, re-prepend the magic. */
    private fun reframe(golden: ByteArray): ByteArray {
        val body = golden.copyOfRange(1, golden.size)
        return zstdMagic + body
    }

    private fun decodeGolden(name: String, dictId: Int): ByteArray {
        val golden = InlinedFixtures.goldenWire.getValue(name)
        assertEquals(dictId, golden[0].toInt() and 0x3F, "$name should be dict $dictId")
        val dict = DictionaryProvider.getDictionary(dictId)
            ?: error("no dictionary for id $dictId")
        return PureZstdDecoder.decode(reframe(golden), dict, TakCompressor.MAX_DECOMPRESSED_SIZE)
    }

    @Test
    fun decodesNonAircraftDictFrame() {
        // pli_basic is a small, dict-0 (non-aircraft) compressed frame.
        val protobuf = decodeGolden("pli_basic", DictionaryProvider.DICT_ID_NON_AIRCRAFT)
        assertTrue(protobuf.isNotEmpty(), "decoded protobuf must be non-empty")
        val pkt = TakPacketV2Serializer.deserialize(protobuf)
        assertEquals("testnode", pkt.uid)
        assertEquals(CotTypeMapper.COTTYPE_A_F_G_U_C, pkt.cotTypeId)
    }

    @Test
    fun decodesAircraftDictFrame() {
        // aircraft_adsb is a dict-1 (aircraft) compressed frame.
        val protobuf = decodeGolden("aircraft_adsb", DictionaryProvider.DICT_ID_AIRCRAFT)
        assertTrue(protobuf.isNotEmpty(), "decoded protobuf must be non-empty")
        val pkt = TakPacketV2Serializer.deserialize(protobuf)
        assertEquals(CotTypeMapper.COTTYPE_A_N_A_C_F, pkt.cotTypeId)
        assertTrue(pkt.payload is TakPacketV2Data.Payload.Aircraft, "must decode to an Aircraft payload")
    }
}
