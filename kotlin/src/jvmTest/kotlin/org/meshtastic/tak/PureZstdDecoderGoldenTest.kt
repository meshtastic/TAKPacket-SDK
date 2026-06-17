package org.meshtastic.tak

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDictDecompress
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.meshtastic.tak.internal.zstd.PureZstdDecoder

/**
 * STAGE 5 (R14a) oracle gate: the pure-Kotlin, dictionary-aware zstd decoder in
 * `commonMain` must decompress the SDK's golden wire frames to byte-identical
 * protobuf output, using the shipped trained dictionaries.
 *
 * For every fixture whose golden `.bin` is dictionary-compressed
 * (`flags & 0x3F` in {0, 1}; 0xFF/uncompressed fixtures are skipped — there is
 * no zstd to decode), we:
 *   1. read the `.bin`, strip the 1-byte flags prefix,
 *   2. re-prepend the 4-byte ZSTD magic that [TakCompressor] strips on encode,
 *   3. load the matching dictionary from the JVM resources,
 *   4. run [PureZstdDecoder.decode], and
 *   5. assert the result equals `testdata/protobuf/<name>.pb` byte-for-byte.
 *
 * The test NEVER writes to `testdata/`. It also cross-checks each frame through
 * zstd-jni so a divergence between the two decoders is reported explicitly.
 */
class PureZstdDecoderGoldenTest {

    private val zstdMagic = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    private fun loadDict(name: String): ByteArray =
        PureZstdDecoderGoldenTest::class.java.classLoader
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
    fun `pure-Kotlin decoder reproduces every compressed golden byte-for-byte`() {
        val passing = mutableListOf<String>()
        val failing = mutableListOf<Pair<String, String>>()
        var compressedCount = 0
        var uncompressedSkipped = 0

        for (name in TestFixtures.fixtureNames) {
            val golden = TestFixtures.loadGolden(name) ?: continue
            val expected = TestFixtures.loadProtobuf(name) ?: continue

            val flags = golden[0].toInt() and 0xFF
            val dictId = flags and 0x3F
            if (flags == DictionaryProvider.DICT_ID_UNCOMPRESSED) {
                uncompressedSkipped++
                continue
            }
            if (dictId != DictionaryProvider.DICT_ID_NON_AIRCRAFT &&
                dictId != DictionaryProvider.DICT_ID_AIRCRAFT
            ) {
                uncompressedSkipped++
                continue
            }
            compressedCount++

            // Re-frame: strip flags byte, prepend the stripped 4-byte magic.
            val body = golden.copyOfRange(1, golden.size)
            val framed = ByteArray(zstdMagic.size + body.size)
            zstdMagic.copyInto(framed, 0)
            body.copyInto(framed, zstdMagic.size)

            val dict = dictFor(dictId)

            // Cross-check: zstd-jni MUST be able to decode this frame (sanity
            // that the re-framing is correct and the fixture is well-formed).
            val jniResult = runCatching {
                Zstd.decompress(framed, ZstdDictDecompress(dict), TakCompressor.MAX_DECOMPRESSED_SIZE)
            }

            val pureResult = runCatching {
                PureZstdDecoder.decode(framed, dict, TakCompressor.MAX_DECOMPRESSED_SIZE)
            }

            when {
                pureResult.isFailure -> {
                    val jniNote = if (jniResult.isSuccess) {
                        "(zstd-jni decoded OK -> ${jniResult.getOrNull()!!.size}B, so the frame is valid)"
                    } else {
                        "(zstd-jni ALSO failed: ${jniResult.exceptionOrNull()?.message})"
                    }
                    failing += name to "decoder threw: ${pureResult.exceptionOrNull()?.message} $jniNote"
                }
                !pureResult.getOrNull().contentEquals(expected) -> {
                    val got = pureResult.getOrNull()!!
                    val firstDiff = (0 until minOf(got.size, expected.size))
                        .firstOrNull { got[it] != expected[it] } ?: minOf(got.size, expected.size)
                    failing += name to
                        "output mismatch: got ${got.size}B, expected ${expected.size}B, " +
                        "first diff at byte $firstDiff; matches zstd-jni=${
                            jniResult.getOrNull()?.contentEquals(got)
                        }"
                }
                else -> passing += name
            }
        }

        val report = buildString {
            appendLine()
            appendLine("=== PureZstdDecoder golden oracle ===")
            appendLine("compressed fixtures attempted: $compressedCount")
            appendLine("uncompressed (0xFF/unknown) skipped: $uncompressedSkipped")
            appendLine("PASS: ${passing.size}/$compressedCount")
            if (failing.isNotEmpty()) {
                appendLine("FAIL: ${failing.size}")
                failing.sortedBy { it.first }.forEach { (n, why) -> appendLine("  - $n: $why") }
            }
            appendLine("passing fixtures: ${passing.sorted()}")
        }
        println(report)

        assertTrue(
            failing.isEmpty(),
            "PureZstdDecoder failed ${failing.size}/$compressedCount compressed goldens:$report",
        )
    }

    /**
     * Spot-check a single small fixture with an explicit assertArrayEquals so a
     * failure points at one fixture rather than the aggregate, and to exercise
     * the assertion path directly.
     */
    @Test
    fun `pli_basic decodes to its protobuf golden`() {
        val golden = TestFixtures.loadGolden("pli_basic") ?: error("missing pli_basic.bin")
        val expected = TestFixtures.loadProtobuf("pli_basic") ?: error("missing pli_basic.pb")
        val flags = golden[0].toInt() and 0xFF
        val dictId = flags and 0x3F

        val body = golden.copyOfRange(1, golden.size)
        val framed = ByteArray(zstdMagic.size + body.size)
        zstdMagic.copyInto(framed, 0)
        body.copyInto(framed, zstdMagic.size)

        val actual = PureZstdDecoder.decode(framed, dictFor(dictId), TakCompressor.MAX_DECOMPRESSED_SIZE)
        assertArrayEquals(expected, actual)
    }
}
