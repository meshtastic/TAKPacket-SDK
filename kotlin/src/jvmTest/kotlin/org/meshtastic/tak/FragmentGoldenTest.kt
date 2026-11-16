package org.meshtastic.tak

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import kotlin.random.Random

/**
 * Fragment goldens and the heavier codec checks (design doc §11).
 *
 * `testdata/fragment/` holds, for each case, `<name>.inner` (the wire payload that
 * was split) and `<name>.frames` (every frame, each prefixed by its length as a
 * little-endian u16), plus `manifest.json` with the parameters. Fragmenting is
 * deterministic given those parameters, so every binding must reproduce the frames
 * byte for byte, unlike compressed bytes, which may differ between zstd encoders.
 *
 * `committed fragment goldens match the fragmenter` checks the checked-in files on
 * every run, so a framing change fails here instead of silently rewriting them.
 * Regenerating is opt-in, for a deliberate wire change:
 * `REGENERATE_FRAGMENT_GOLDENS=1 ./gradlew jvmTest --tests "*FragmentGoldenTest*"`.
 */
class FragmentGoldenTest {
    private val parser = CotXmlParser()
    private val compressor = TakCompressor()
    private val fragmentDir = File("../testdata/fragment")

    private data class Case(
        val name: String,
        val inner: ByteArray,
        val protobufSize: Int,
        val msgId: Int,
        val repair: (Int) -> Int = TakFragmenter::defaultRepairCount,
    )

    private fun synthetic(
        size: Int,
        seed: Int,
    ): ByteArray = Random(seed).nextBytes(size).also { it[0] = 0x00 }

    /** A freehand drawing with [n] vertices on a deterministic random walk, compressed by the SDK. */
    private fun largeFreehand(n: Int): Pair<ByteArray, Int> {
        val base = parser.parse(TestFixtures.loadFixture("drawing_freeform.xml"))
        val shape = base.payload as TakPacketV2Data.Payload.DrawnShape
        val rnd = Random(n)
        var lat = base.latitudeI
        var lon = base.longitudeI
        val vertices =
            List(n) {
                lat += rnd.nextInt(-3_000, 3_000)
                lon += rnd.nextInt(-3_000, 3_000)
                TakPacketV2Data.Payload.Vertex(lat, lon)
            }
        val packet = base.copy(payload = shape.copy(vertices = vertices))
        return compressor.compress(packet) to TakPacketV2Serializer.serialize(packet).size
    }

    /** A spot marker carrying [words] words of deterministic remarks text, compressed by the SDK. */
    private fun longRemarks(words: Int): Pair<ByteArray, Int> {
        val vocabulary =
            (
                "enemy contact observed near the north ridge two vehicles moving east at speed request " +
                    "support hold position until relieved bridge crossing checkpoint clear report status"
            ).split(" ")
        val rnd = Random(words)
        val text = List(words) { vocabulary[rnd.nextInt(vocabulary.size)] }.joinToString(" ")
        val packet = parser.parse(TestFixtures.loadFixture("marker_spot.xml")).copy(remarks = text)
        return compressor.compress(packet) to TakPacketV2Serializer.serialize(packet).size
    }

    private fun cases(): List<Case> {
        val s = TakFragment.DEFAULT_MAX_FRAME_BYTES - TakFragment.HEADER_SIZE
        val (freehand, freehandProto) = largeFreehand(200)
        val (remarks, remarksProto) = longRemarks(500)
        return listOf(
            Case("freehand_200v", freehand, freehandProto, msgId = 0x01020304),
            Case("marker_remarks_500w", remarks, remarksProto, msgId = 0x02030405),
            Case("frame_plus_one", synthetic(TakFragment.DEFAULT_MAX_FRAME_BYTES + 1, 1), 400, msgId = 0x03040506),
            Case("forced_k1", synthetic(150, 2), TakCompressor.MAX_DECOMPRESSED_SIZE + 1, msgId = 0x04050607),
            Case("no_repair_k3", synthetic(600, 3), 1_200, msgId = 0x05060708, repair = { 0 }),
            Case("max_source_32", synthetic(TakFragment.MAX_SOURCE_FRAGMENTS * s - 5, 4), 9_000, msgId = -1),
        )
    }

    private fun fragmenter(case: Case) = TakFragmenter(maxSourceFragments = TakFragment.MAX_SOURCE_FRAGMENTS, repairPolicy = case.repair)

    @Test
    @EnabledIfEnvironmentVariable(named = "REGENERATE_FRAGMENT_GOLDENS", matches = "1")
    fun `generate fragment goldens`() {
        fragmentDir.mkdirs()
        val manifest = StringBuilder("{\n  \"maxFrameBytes\": ${TakFragment.DEFAULT_MAX_FRAME_BYTES},\n  \"cases\": [\n")
        val all = cases()
        all.forEachIndexed { i, case ->
            val frames = fragmenter(case).split(case.inner, case.protobufSize, case.msgId)!!
            assertTrue(frames.size > 1, "${case.name} should fragment")
            assertTrue(frames.all { it.size <= TakFragment.DEFAULT_MAX_FRAME_BYTES }, "${case.name}: every frame fits")
            val k = (frames[0][HDR_SHAPE].toInt() and 0x1F) + 1

            File(fragmentDir, "${case.name}.inner").writeBytes(case.inner)
            val out = ByteArrayOutputStream()
            for (f in frames) {
                out.write(f.size and 0xFF)
                out.write(f.size ushr 8)
                out.write(f)
            }
            File(fragmentDir, "${case.name}.frames").writeBytes(out.toByteArray())
            manifest.append(
                "    {\"name\": \"${case.name}\", \"msgId\": ${case.msgId.toUInt()}, \"protobufSize\": ${case.protobufSize}, " +
                    "\"innerSize\": ${case.inner.size}, \"k\": $k, \"repair\": ${frames.size - k}, \"frames\": ${frames.size}}" +
                    (if (i < all.size - 1) ",\n" else "\n"),
            )
        }
        manifest.append("  ]\n}\n")
        File(fragmentDir, "manifest.json").writeText(manifest.toString())
    }

    @Test
    fun `committed fragment goldens match the fragmenter`() {
        val manifest = File(fragmentDir, "manifest.json").readText()
        val maxFrame = Regex("\"maxFrameBytes\": (\\d+)").find(manifest)!!.groupValues[1].toInt()
        val entry =
            Regex(
                "\"name\": \"(\\w+)\", \"msgId\": (\\d+), \"protobufSize\": (\\d+), .*?\"repair\": (\\d+), \"frames\": (\\d+)",
            )
        val entries = entry.findAll(manifest).toList()
        assertEquals(6, entries.size, "manifest cases")
        for (m in entries) {
            val g = m.groupValues
            val name = g[1]
            val msgId = g[2]
            val protobufSize = g[3]
            val repair = g[4]
            val count = g[5]
            val inner = File(fragmentDir, "$name.inner").readBytes()
            val committed = readFrames(File(fragmentDir, "$name.frames").readBytes())
            val fragmenter =
                TakFragmenter(
                    maxFrameBytes = maxFrame,
                    maxSourceFragments = TakFragment.MAX_SOURCE_FRAGMENTS,
                    repairPolicy = { repair.toInt() },
                )
            val frames = fragmenter.split(inner, protobufSize.toInt(), msgId.toLong().toInt())!!
            assertEquals(count.toInt(), committed.size, "$name: committed frame count")
            assertEquals(committed.size, frames.size, "$name: frame count")
            frames.zip(committed).forEachIndexed { i, (got, want) -> assertArrayEquals(want, got, "$name frame $i") }
        }
    }

    /** Splits a `.frames` file: each frame is prefixed by its length as a little-endian u16. */
    private fun readFrames(bytes: ByteArray): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        var i = 0
        while (i < bytes.size) {
            val len = (bytes[i].toInt() and 0xFF) or ((bytes[i + 1].toInt() and 0xFF) shl 8)
            out += bytes.copyOfRange(i + 2, i + 2 + len)
            i += 2 + len
        }
        return out
    }

    @Test
    fun `golden fragment sets reassemble from any K frames`() {
        for (case in cases()) {
            val frames = fragmenter(case).split(case.inner, case.protobufSize, case.msgId)!!
            val k = (frames[0][HDR_SHAPE].toInt() and 0x1F) + 1
            val rnd = Random(case.msgId)
            repeat(20) {
                val r = TakReassembler(frameAirtimeMillis = 2_200)
                val got = frames.shuffled(rnd).take(k).mapNotNull { r.accept(it, 0L) }
                assertEquals(1, got.size, case.name)
                assertArrayEquals(case.inner, got[0], case.name)
            }
        }
    }

    @Test
    fun `reassembled real payloads decompress back to the same packet`() {
        for (case in cases().take(2)) {
            val frames = fragmenter(case).split(case.inner, case.protobufSize, case.msgId)!!
            val r = TakReassembler(frameAirtimeMillis = 2_200)
            val out = frames.mapNotNull { r.accept(it, 0L) }.single()
            val packet = compressor.decompress(out)
            assertEquals(case.protobufSize, TakPacketV2Serializer.serialize(packet).size, case.name)
        }
    }

    @Test
    fun `crc32 matches the JDK`() {
        val rnd = Random(1)
        repeat(200) {
            val data = rnd.nextBytes(rnd.nextInt(0, 3_000))
            val jdk = CRC32().apply { update(data) }.value.toInt()
            assertEquals(jdk, Crc32.compute(data))
        }
    }

    @Test
    fun `any K of K plus R decodes up to the wire limit`() {
        val s = 16
        val rnd = Random(5)
        for (k in 1..TakFragment.MAX_SOURCE_FRAGMENTS) {
            val r = minOf(16, TakFragment.MAX_BURST - k)
            val source = List(k) { rnd.nextBytes(s) }
            val all = (0 until k + r).map { e -> e to (if (e < k) source[e] else TakFec.encodeRepair(source, e)) }
            repeat(60) {
                val subset = all.shuffled(rnd).take(k)
                val decoded = TakFec.decode(k, s, subset)!!
                decoded.forEachIndexed { i, sym -> assertArrayEquals(source[i], sym, "k=$k") }
            }
        }
    }

    @Test
    fun `a 128-symbol file-transfer block decodes from any 128 of 256`() {
        val k = 128
        val s = 8
        val rnd = Random(6)
        val source = List(k) { rnd.nextBytes(s) }
        val all = (0 until TakFec.MAX_SYMBOLS).map { e -> e to (if (e < k) source[e] else TakFec.encodeRepair(source, e)) }
        repeat(3) {
            val decoded = TakFec.decode(k, s, all.shuffled(rnd).take(k))!!
            decoded.forEachIndexed { i, sym -> assertArrayEquals(source[i], sym) }
        }
        // The worst case: every source symbol lost, rebuilt from repair alone.
        val repairOnly = TakFec.decode(k, s, all.drop(k))!!
        repairOnly.forEachIndexed { i, sym -> assertArrayEquals(source[i], sym) }
    }

    @Test
    fun `the single-frame path is untouched for every shipped fixture`() {
        val f = TakFragmenter()
        for (name in TestFixtures.fixtureNames) {
            val packet = parser.parse(TestFixtures.loadFixture("$name.xml"))
            val wire = compressor.compress(packet)
            val frames = f.split(wire, TakPacketV2Serializer.serialize(packet).size, msgId = 1)
            assertNotNull(frames)
            assertEquals(1, frames!!.size, name)
            assertArrayEquals(wire, frames[0], name)
        }
        assertNull(TakFragmenter(maxSourceFragments = 1).split(synthetic(500, 9), 500, 1))
    }
}
