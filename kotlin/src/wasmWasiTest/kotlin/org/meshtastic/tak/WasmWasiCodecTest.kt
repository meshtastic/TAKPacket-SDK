package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * wasmWasi-specific codec contract (`kotlin.test`).
 *
 * wasmWasi is the one target with no compress path (no JS host, no cinterop, no
 * pure-Kotlin encoder), but it IS decode-capable via the pure-Kotlin decoder.
 * This pins both halves of that contract:
 *
 *  - [TakCompressor.compress] surfaces the documented [ZstdException] (wrapped by
 *    the compressor as a RuntimeException is NOT the case here — compress calls
 *    the codec directly and the codec throws ZstdException up front before any
 *    framing). We assert at the codec level AND that the parse/serialize/decode
 *    pipeline still works end-to-end.
 *  - Decompress of a real golden frame produces the expected packet.
 *
 * The shared common suites (RoundTrip / Resilience / Decode) already run on
 * wasmWasi too — this just locks the wasmWasi-only throw-on-compress behavior.
 */
class WasmWasiCodecTest {

    @Test
    fun compressThrowsZstdException() {
        // The codec's compress actual throws unconditionally on wasmWasi.
        assertFailsWith<ZstdException> {
            ZstdCodec.compressWithDict(byteArrayOf(1, 2, 3), DictionaryProvider.DICT_ID_NON_AIRCRAFT, 19)
        }
    }

    @Test
    fun takCompressorCompressThrowsButParseAndDecodeWork() {
        val parser = CotXmlParser()
        val compressor = TakCompressor()

        // Parse works on wasmWasi (pure-Kotlin xmlutil parser).
        val packet = parser.parse(InlinedFixtures.xml.getValue("pli_basic"))
        assertEquals("testnode", packet.uid)

        // Compress propagates the codec's ZstdException (no encoder on wasmWasi).
        assertFailsWith<ZstdException> { compressor.compress(packet) }

        // But decompress of the golden frame works — wasmWasi is decode-capable.
        val golden = InlinedFixtures.goldenWire.getValue("pli_basic")
        val decoded = compressor.decompress(golden)
        assertEquals(packet.cotTypeId, decoded.cotTypeId)
        assertEquals(packet.uid, decoded.uid)
        assertEquals(packet.callsign, decoded.callsign)
    }

    @Test
    fun decompressionGuardsTheMaxSize() {
        // A frame that decompresses fine stays under the cap; this just confirms
        // the decode path is wired (a fuller bomb test lives in the JVM suite).
        val golden = InlinedFixtures.goldenWire.getValue("pli_basic")
        val decoded = TakCompressor().decompress(golden)
        assertTrue(TakPacketV2Serializer.serialize(decoded).size <= TakCompressor.MAX_DECOMPRESSED_SIZE)
    }
}
