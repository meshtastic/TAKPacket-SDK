package org.meshtastic.tak

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDictDecompress
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.meshtastic.tak.internal.zstd.PureZstdDecoder
import org.meshtastic.tak.internal.zstd.PureZstdEncoder

/**
 * R14b oracle gate: the pure-Kotlin, dictionary-aware zstd ENCODER must produce
 * frames that are (1) decodable by real libzstd (zstd-jni), (2) decodable by the
 * pure-Kotlin decoder, (3) within the cross-binding size tolerance vs the golden
 * `.bin`, and (4) under the 237-byte LoRa MTU once framed the SDK way.
 *
 * For every fixture whose golden `.bin` is dictionary-compressed (`flags & 0x3F`
 * in {0,1}; the 0xFF/uncompressed fixtures have no zstd frame to encode), we:
 *   1. encode the golden `.pb` with the matching dictionary,
 *   2. assert zstd-jni decodes the frame back to the exact `.pb` (gate 1),
 *   3. assert PureZstdDecoder decodes it back to the exact `.pb` (gate 2),
 *   4. strip the 4-byte magic, frame it SDK-style ([flags][body]), and assert the
 *      ratio vs the golden `.bin` is in 0.5..2.0 (gate 3, CompatibilityTest's
 *      bound) and the full wire payload is <= 237 bytes (gate 4).
 *
 * The test NEVER writes to `testdata/`.
 */
class PureZstdEncoderGoldenTest {

    private val zstdMagic = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
    private val mtu = 237

    private fun loadDict(name: String): ByteArray =
        PureZstdEncoderGoldenTest::class.java.classLoader
            ?.getResourceAsStream(name)
            ?.use { it.readBytes() }
            ?: error("dictionary resource not found: $name")

    private val nonAircraftDict by lazy { loadDict("dict_non_aircraft.zstd") }
    private val aircraftDict by lazy { loadDict("dict_aircraft.zstd") }

    private fun dictFor(dictId: Int): ByteArray = when (dictId) {
        DictionaryProvider.DICT_ID_NON_AIRCRAFT -> nonAircraftDict
        DictionaryProvider.DICT_ID_AIRCRAFT -> aircraftDict
        else -> error("no dict for id $dictId")
    }

    @Test
    fun `pure-Kotlin encoder round-trips every compressed fixture within tolerance and MTU`() {
        data class Row(
            val name: String,
            val pb: Int,
            val encodedBody: Int,
            val goldenBody: Int,
            val wire: Int,
            val ratio: Double,
            val mtuOk: Boolean,
            val jniOk: Boolean,
            val pureOk: Boolean,
            val tolOk: Boolean,
        )

        val rows = mutableListOf<Row>()
        val failures = mutableListOf<String>()
        var attempted = 0

        for (name in TestFixtures.fixtureNames) {
            val golden = TestFixtures.loadGolden(name) ?: continue
            val pb = TestFixtures.loadProtobuf(name) ?: continue

            val flags = golden[0].toInt() and 0xFF
            val dictId = flags and 0x3F
            if (flags == DictionaryProvider.DICT_ID_UNCOMPRESSED) continue
            if (dictId != DictionaryProvider.DICT_ID_NON_AIRCRAFT &&
                dictId != DictionaryProvider.DICT_ID_AIRCRAFT
            ) {
                continue
            }
            attempted++

            val dict = dictFor(dictId)

            // Encode → full standard frame (with magic).
            val frame = runCatching { PureZstdEncoder.encode(pb, dict, 19) }
            if (frame.isFailure) {
                failures += "$name: encode threw ${frame.exceptionOrNull()?.message}"
                continue
            }
            val f = frame.getOrNull()!!

            // Gate 1: zstd-jni decodes to the exact pb.
            val jni = runCatching {
                Zstd.decompress(f, ZstdDictDecompress(dict), TakCompressor.MAX_DECOMPRESSED_SIZE)
            }
            val jniOk = jni.isSuccess && jni.getOrNull()!!.contentEquals(pb)

            // Gate 2: pure-Kotlin decoder decodes to the exact pb.
            val pure = runCatching {
                PureZstdDecoder.decode(f, dict, TakCompressor.MAX_DECOMPRESSED_SIZE)
            }
            val pureOk = pure.isSuccess && pure.getOrNull()!!.contentEquals(pb)

            // SDK framing: strip the 4-byte magic, prepend the 1-byte flags.
            val body = f.copyOfRange(zstdMagic.size, f.size)
            val wireSize = 1 + body.size
            val goldenBody = golden.size - 1
            val ratio = body.size.toDouble() / goldenBody.toDouble()
            val tolOk = ratio in 0.5..2.0
            val mtuOk = wireSize <= mtu

            rows += Row(name, pb.size, body.size, goldenBody, wireSize, ratio, mtuOk, jniOk, pureOk, tolOk)

            if (!jniOk) {
                failures += "$name: zstd-jni round-trip FAILED " +
                    if (jni.isFailure) "(threw ${jni.exceptionOrNull()?.message})"
                    else "(size ${jni.getOrNull()?.size} != ${pb.size})"
            }
            if (!pureOk) {
                failures += "$name: PureZstdDecoder round-trip FAILED " +
                    if (pure.isFailure) "(threw ${pure.exceptionOrNull()?.message})"
                    else "(size ${pure.getOrNull()?.size} != ${pb.size})"
            }
            if (!tolOk) failures += "$name: size ratio ${"%.3f".format(ratio)} outside 0.5..2.0 " +
                "(encoded ${body.size}B vs golden $goldenBody B)"
            if (!mtuOk) failures += "$name: wire $wireSize B exceeds MTU $mtu"
        }

        val report = buildString {
            appendLine()
            appendLine("=== PureZstdEncoder golden oracle (gates 1-4) ===")
            appendLine("compressed fixtures attempted: $attempted")
            val pass4 = rows.count { it.jniOk && it.pureOk && it.tolOk && it.mtuOk }
            appendLine("PASS all 4 gates: $pass4/$attempted")
            appendLine()
            appendLine("%-34s %5s %6s %6s %5s %6s %4s %4s %4s %4s".format(
                "fixture", "pb", "enc", "gold", "wire", "ratio", "MTU", "jni", "pure", "tol",
            ))
            rows.sortedBy { it.name }.forEach {
                appendLine("%-34s %5d %6d %6d %5d %6.3f %4s %4s %4s %4s".format(
                    it.name, it.pb, it.encodedBody, it.goldenBody, it.wire, it.ratio,
                    if (it.mtuOk) "ok" else "BAD",
                    if (it.jniOk) "ok" else "BAD",
                    if (it.pureOk) "ok" else "BAD",
                    if (it.tolOk) "ok" else "BAD",
                ))
            }
            if (failures.isNotEmpty()) {
                appendLine()
                appendLine("FAILURES (${failures.size}):")
                failures.forEach { appendLine("  - $it") }
            }
        }
        println(report)

        assertTrue(failures.isEmpty(), "PureZstdEncoder gate failures:$report")
    }

    /**
     * Trained-dict interop, encoder direction: the pure encoder must produce
     * frames that BOTH libzstd and the pure decoder accept, even against a
     * foreign `zstd --train` dictionary (not one of the SDK's two shipped dicts).
     * Companion to [PureZstdDecoderGoldenTest]'s trained-dict decode guard — see
     * [TrainedDictVectors] for fixture provenance.
     */
    @Test
    fun `pure encoder frames built with a zstd --train dictionary round-trip through libzstd and the pure decoder`() {
        val dict = TrainedDictVectors.trainedDict
        val ddict = ZstdDictDecompress(dict)

        for ((i, sample) in TrainedDictVectors.structured.withIndex()) {
            val frame = PureZstdEncoder.encode(sample, dict, 19)

            val jni = Zstd.decompress(frame, ddict, TakCompressor.MAX_DECOMPRESSED_SIZE)
            assertArrayEquals(sample, jni, "zstd-jni must decode our trained-dict frame (sample $i)")

            val pure = PureZstdDecoder.decode(frame, dict, TakCompressor.MAX_DECOMPRESSED_SIZE)
            assertArrayEquals(sample, pure, "pure decoder must decode our trained-dict frame (sample $i)")
        }
    }
}
