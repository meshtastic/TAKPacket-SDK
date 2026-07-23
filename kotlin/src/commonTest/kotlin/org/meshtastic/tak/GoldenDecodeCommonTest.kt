package org.meshtastic.tak

import org.meshtastic.kzstd.Zstd
import org.meshtastic.kzstd.ZstdDictionary
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Cross-platform byte-exact oracle for TAK's golden wire frames decoding through
 * the linked zstd codec ([org.meshtastic.kzstd]), on EVERY target.
 *
 * Decode is the production decompress path on js / wasmJs / wasmWasi, so it must
 * be byte-exact on those targets — not just on the JVM. This validates the codec
 * against every compressed golden on every target, using the inlined `.pb`
 * protobuf goldens generated alongside the wire frames.
 *
 * This is an SDK *integration* check (TAK's shipped dictionaries + TAK's wire
 * frames), distinct from kzstd's own engine-level suite (malformed-frame
 * fuzzing, libzstd interop, trained-dict entropy vectors), which the SDK no
 * longer duplicates now that the engine lives in one place.
 *
 * For every fixture whose golden `.bin` is dictionary-compressed
 * (`flags & 0x3F` in {0, 1}; 0xFF / uncompressed fixtures are skipped — there is
 * no zstd to decode), we strip the 1-byte flags prefix, re-prepend the 4-byte
 * ZSTD magic that [TakCompressor] strips on encode, decompress with the matching
 * shipped dictionary, and assert the result equals the inlined `.pb` byte-for-byte.
 */
class GoldenDecodeCommonTest {
    // The 4-byte zstd frame magic that TakCompressor strips on encode; the golden
    // .bin frames have it stripped, so the decoder gets it re-prepended.
    private val zstdMagic = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    // Digest each shipped dictionary once and reuse it across the whole suite.
    private val dictById: Map<Int, ZstdDictionary> =
        mapOf(
            DictionaryProvider.DICT_ID_NON_AIRCRAFT to ZstdDictionary(DictionaryProvider.nonAircraftDict),
            DictionaryProvider.DICT_ID_AIRCRAFT to ZstdDictionary(DictionaryProvider.aircraftDict),
        )

    /** Re-frame a golden .bin: drop the 1-byte flags prefix, re-prepend the magic. */
    private fun reframe(golden: ByteArray): ByteArray = zstdMagic + golden.copyOfRange(1, golden.size)

    @Test
    fun decoderReproducesEveryCompressedGoldenByteForByte() {
        var compressed = 0
        var skipped = 0
        val failures = mutableListOf<String>()

        for (name in InlinedFixtures.names) {
            val golden = InlinedFixtures.goldenWire[name] ?: continue
            val expected = InlinedFixtures.protobuf[name] ?: continue

            val flags = golden[0].toInt() and 0xFF
            val dictId = flags and 0x3F
            // 0xFF (uncompressed) and unknown-dict fixtures carry no zstd frame.
            if (flags == DictionaryProvider.DICT_ID_UNCOMPRESSED ||
                (
                    dictId != DictionaryProvider.DICT_ID_NON_AIRCRAFT &&
                        dictId != DictionaryProvider.DICT_ID_AIRCRAFT
                )
            ) {
                skipped++
                continue
            }
            compressed++

            val dict = dictById[dictId] ?: error("no dictionary for id $dictId ($name)")
            val decoded =
                runCatching {
                    Zstd.decompress(reframe(golden), dict, TakCompressor.MAX_DECOMPRESSED_SIZE)
                }
            when {
                decoded.isFailure -> {
                    failures += "$name: decoder threw ${decoded.exceptionOrNull()?.message}"
                }

                !decoded.getOrNull().contentEquals(expected) -> {
                    failures += "$name: output mismatch (got ${decoded.getOrNull()?.size}B," +
                        " expected ${expected.size}B)"
                }
            }
        }

        assertTrue(failures.isEmpty(), "decode failed $failures")
        // Sanity: the oracle actually ran over the compressed fixtures (matches the
        // 45 compressed / 2 uncompressed split).
        assertTrue(compressed > 0, "no compressed fixtures were exercised")
        assertEquals(InlinedFixtures.names.size, compressed + skipped, "every fixture must be accounted for")
    }

    @Test
    fun pliBasicDecodesToItsProtobufGolden() {
        // A single explicit assertion so a failure points at one fixture and the
        // decoded packet is sanity-checked end to end.
        val golden = InlinedFixtures.goldenWire.getValue("pli_basic")
        val expected = InlinedFixtures.protobuf.getValue("pli_basic")
        val dictId = golden[0].toInt() and 0x3F
        val dict = dictById[dictId] ?: error("no dict for id $dictId")

        val actual = Zstd.decompress(reframe(golden), dict, TakCompressor.MAX_DECOMPRESSED_SIZE)
        assertContentEquals(expected, actual, "pli_basic decode must equal its .pb golden")

        val pkt = TakPacketV2Serializer.deserialize(actual)
        assertEquals("testnode", pkt.uid)
        assertEquals(CotTypeMapper.COTTYPE_A_F_G_U_C, pkt.cotTypeId)
    }

    @Test
    fun aircraftDictFrameDecodes() {
        // Exercise the aircraft (dict 1) path explicitly too.
        val golden = InlinedFixtures.goldenWire.getValue("aircraft_adsb")
        val expected = InlinedFixtures.protobuf.getValue("aircraft_adsb")
        val dictId = golden[0].toInt() and 0x3F
        assertEquals(DictionaryProvider.DICT_ID_AIRCRAFT, dictId, "aircraft_adsb should be a dict-1 frame")
        val dict = dictById[dictId] ?: error("no dict for id $dictId")

        val actual = Zstd.decompress(reframe(golden), dict, TakCompressor.MAX_DECOMPRESSED_SIZE)
        assertContentEquals(expected, actual, "aircraft_adsb decode must equal its .pb golden")

        val pkt = TakPacketV2Serializer.deserialize(actual)
        assertTrue(pkt.payload is TakPacketV2Data.Payload.Aircraft, "must decode to an Aircraft payload")
    }
}
