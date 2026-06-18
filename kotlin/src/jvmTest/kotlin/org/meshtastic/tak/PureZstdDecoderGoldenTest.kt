package org.meshtastic.tak

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDictCompress
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

    /**
     * Guards the dictionary-entropy decode path against silently going dark. If
     * [TrainedDictVectors.trainedDict] were not a genuinely trained dict (magic
     * 0xEC30A437), the decoder would treat it as raw content (no Huffman/FSE
     * tables) and [`libzstd frames built with a zstd --train dictionary decode to the source bytes`]
     * would never exercise the treeless-literals / FSE-repeat branches — while
     * staying green. This fails loudly in that case.
     */
    @Test
    fun `trained-dict fixture is genuinely trained`() {
        val dict = TrainedDictVectors.trainedDict
        assertTrue(dict.size >= 8, "dict too small to carry a header")
        assertArrayEquals(
            TrainedDictVectors.TRAINED_DICT_MAGIC,
            dict.copyOfRange(0, 4),
            "trained-dict fixture must carry the trained magic (37 A4 30 EC); " +
                "otherwise the dict-entropy decode path is never exercised",
        )
    }

    /**
     * Regression guard for the Huffman weight-stream decoder. TAK's own dicts /
     * golden frames never reference a Huffman weight FSE table containing a 0-bit
     * (`nbBits == 0`) transition, so a false-positive "non-advancing weight
     * transition" guard in `decodeWeightStream` shipped undetected. A `zstd
     * --train` dictionary produces such a table; libzstd, compressing
     * training-distribution data WITH that dict, emits frames that reference it
     * (treeless literals / FSE-repeat). The pure decoder must read them — this is
     * the only direction that drives the dictionary-entropy decode branches
     * (TAK's own encoder emits Predefined-FSE + Raw-literals and never references
     * a dictionary's entropy tables).
     */
    @Test
    fun `libzstd frames built with a zstd --train dictionary decode to the source bytes`() {
        val dict = TrainedDictVectors.trainedDict
        val cdict = ZstdDictCompress(dict, 19)
        val ddict = ZstdDictDecompress(dict)

        for ((i, sample) in TrainedDictVectors.structured.withIndex()) {
            val frame = Zstd.compress(sample, cdict)

            // Sanity: zstd-jni itself round-trips the frame, proving it is a
            // well-formed, dict-referencing frame (not the variable under test).
            val jni = Zstd.decompress(frame, ddict, TakCompressor.MAX_DECOMPRESSED_SIZE)
            assertArrayEquals(sample, jni, "zstd-jni self round-trip failed for sample $i")

            // The guard: the pure-Kotlin decoder reads libzstd's dict-entropy frame.
            val pure = PureZstdDecoder.decode(frame, dict, TakCompressor.MAX_DECOMPRESSED_SIZE)
            assertArrayEquals(
                sample,
                pure,
                "PureZstdDecoder must decode libzstd's trained-dict frame (sample $i)",
            )
        }
    }
}
