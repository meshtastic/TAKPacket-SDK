package org.meshtastic.tak

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource
import java.io.File

/**
 * Compression tests: verify all fixtures compress under LoRa MTU
 * and generate the compression-report.md living document.
 *
 * The `generate compression report` test ALSO writes the golden .pb and .bin
 * files to `testdata/protobuf/` and `testdata/golden/`. It is the canonical
 * fixture generator for the entire SDK — all other platforms (Swift, Python,
 * C#, TypeScript) load the bytes this test writes and validate against them.
 *
 * Fixture discovery is dynamic: [TestFixtures] enumerates the XML files under
 * `testdata/cot_xml/` so dropping a new fixture in that directory automatically
 * adds it to every test in the suite with zero code edits.
 */
class CompressionTest {

    companion object {
        const val LORA_MTU = 237
    }

    private val parser = CotXmlParser()
    private val compressor = TakCompressor()

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.meshtastic.tak.TestFixtures#allFixtureFilenames")
    fun `compressed payload fits under LoRa MTU`(fixture: String) {
        val xml = TestFixtures.loadFixture(fixture)
        val packet = parser.parse(xml)
        val result = compressor.compressWithStats(packet)

        assertTrue(result.compressedSize <= LORA_MTU,
            "$fixture: compressed size ${result.compressedSize}B exceeds LoRa MTU ${LORA_MTU}B " +
            "(XML: ${xml.length}B, proto: ${result.protobufSize}B)")
    }

    // -- compressWithRemarksFallback boundary & outcome tests ----------------
    //
    // These tests pin down the four outcomes of compressWithRemarksFallbackDetailed
    // (fit-as-is / fit-after-strip / dropped-with-remarks / dropped-no-remarks)
    // and the <=-inclusive MTU boundary.  They use synthetic packets built
    // directly from TakPacketV2Data rather than fixture XML, so the thresholds
    // are deterministic regardless of future fixture or dictionary changes.

    private fun makePliWithRemarks(remarks: String): TakPacketV2Data = TakPacketV2Data(
        cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
        how = CotTypeMapper.COTHOW_M_G,
        callsign = "TESTER",
        latitudeI = 340522000,
        longitudeI = -1182437000,
        altitude = 100,
        uid = "testnode",
        remarks = remarks,
        payload = TakPacketV2Data.Payload.Pli(true),
    )

    @Test
    fun `fallback returns full payload when under limit with no strip`() {
        val packet = makePliWithRemarks("")
        val result = compressor.compressWithRemarksFallbackDetailed(packet, maxWireBytes = 500)
        assertNotNull(result.wirePayload, "expected a wire payload under a generous limit")
        assertFalse(result.remarksStripped, "no remarks => no strip should happen")
        assertTrue(result.fits)
    }

    @Test
    fun `fallback strips remarks when oversized with remarks but fits without`() {
        val packet = makePliWithRemarks("x".repeat(500))
        val fullSize = compressor.compress(packet).size
        val strippedSize = compressor.compress(packet.copy(remarks = "")).size
        assertTrue(fullSize > strippedSize,
            "test setup: padded remarks must grow the wire payload (full=$fullSize stripped=$strippedSize)")

        // Limit sits between the two sizes — stripping must save the packet.
        val limit = strippedSize + (fullSize - strippedSize) / 2
        val result = compressor.compressWithRemarksFallbackDetailed(packet, limit)
        assertNotNull(result.wirePayload, "stripping should have produced a fit")
        assertTrue(result.remarksStripped, "remarksStripped flag should be true")
        assertTrue(result.wirePayload!!.size <= limit)
        // Backward-compat wrapper must agree with the detailed variant.
        val legacy = compressor.compressWithRemarksFallback(packet, limit)
        assertNotNull(legacy)
        assertTrue(legacy!!.contentEquals(result.wirePayload!!))
    }

    @Test
    fun `fallback drops when even stripped payload exceeds limit`() {
        val packet = makePliWithRemarks("x".repeat(500))
        val strippedSize = compressor.compress(packet.copy(remarks = "")).size

        // Limit is below even the stripped size — nothing can save this packet.
        val result = compressor.compressWithRemarksFallbackDetailed(packet, strippedSize - 1)
        assertNull(result.wirePayload)
        assertTrue(result.remarksStripped,
            "stripping was attempted even though it didn't help")
        assertFalse(result.fits)
        assertNull(compressor.compressWithRemarksFallback(packet, strippedSize - 1))
    }

    @Test
    fun `fallback drops when packet has no remarks to strip`() {
        val packet = makePliWithRemarks("")
        val fullSize = compressor.compress(packet).size

        // Limit is 1 byte below the natural compressed size. Packet has no
        // remarks, so stripping is a no-op and the caller must drop it.
        val result = compressor.compressWithRemarksFallbackDetailed(packet, fullSize - 1)
        assertNull(result.wirePayload)
        assertFalse(result.remarksStripped,
            "no remarks => stripping should not be attempted")
    }

    @Test
    fun `fallback maxWireBytes boundary is inclusive`() {
        // Pins the `<=` in compressWithRemarksFallbackDetailed. The boundary
        // case (limit == actual size) MUST succeed; limit-1 MUST drop (no
        // remarks to strip). This is the off-by-one guard for item #16 from
        // the audit — independent of the real 237B LoRa MTU.
        val packet = makePliWithRemarks("")
        val naturalSize = compressor.compress(packet).size

        val atLimit = compressor.compressWithRemarksFallbackDetailed(packet, maxWireBytes = naturalSize)
        assertNotNull(atLimit.wirePayload, "<= must accept the exact boundary")
        assertEquals(naturalSize, atLimit.wirePayload!!.size)
        assertFalse(atLimit.remarksStripped)

        val belowLimit = compressor.compressWithRemarksFallbackDetailed(packet, maxWireBytes = naturalSize - 1)
        assertNull(belowLimit.wirePayload, "1 byte under boundary must drop")
    }

    @Test
    fun `fallback accepts every fixture at the real LoRa MTU`() {
        // Sanity check: every real fixture survives the full fallback path at
        // the canonical 237B MTU. Worst-case fixture (drawing_telestration)
        // is 212B, so all of them should come back with remarksStripped=false.
        for (fixture in TestFixtures.filenames) {
            val xml = TestFixtures.loadFixture(fixture)
            val packet = parser.parse(xml)
            val result = compressor.compressWithRemarksFallbackDetailed(packet, maxWireBytes = LORA_MTU)
            assertNotNull(result.wirePayload, "$fixture: should fit under LoRa MTU")
            assertTrue(result.wirePayload!!.size <= LORA_MTU, "$fixture: wire size within MTU")
        }
    }

    // -- end fallback tests --------------------------------------------------

    @Test
    fun `compression achieves meaningful ratio`() {
        // At least 3x compression on average across all fixtures
        var totalXml = 0
        var totalCompressed = 0

        for (fixture in TestFixtures.filenames) {
            val xml = TestFixtures.loadFixture(fixture)
            val packet = parser.parse(xml)
            val result = compressor.compressWithStats(packet)
            totalXml += xml.length
            totalCompressed += result.compressedSize
        }

        val ratio = totalXml.toDouble() / totalCompressed
        assertTrue(ratio >= 3.0,
            "Average compression ratio ${String.format("%.1f", ratio)}x is below 3x minimum")
    }

    @Test
    fun `generate compression report`() {
        val report = StringBuilder()
        report.appendLine("# TAKPacket-SDK Compression Report")
        report.appendLine("Generated: ${java.time.LocalDate.now()} | Dictionary: v2 (non-aircraft 16KB + aircraft 4KB)")
        report.appendLine()

        data class Row(
            val fixture: String,
            val cotType: String,
            val xmlSize: Int,
            val protoSize: Int,
            val compressedSize: Int,
            val ratio: Double,
            val dictName: String,
        )

        val rows = mutableListOf<Row>()

        for (fixture in TestFixtures.filenames) {
            val xml = TestFixtures.loadFixture(fixture)
            val packet = parser.parse(xml)
            val result = compressor.compressWithStats(packet)
            val ratio = xml.length.toDouble() / result.compressedSize

            rows.add(Row(
                fixture = fixture.removeSuffix(".xml"),
                cotType = packet.cotTypeString(),
                xmlSize = xml.length,
                protoSize = result.protobufSize,
                compressedSize = result.compressedSize,
                ratio = ratio,
                dictName = result.dictName,
            ))
        }

        val allUnderMtu = rows.all { it.compressedSize <= LORA_MTU }
        val medianCompressed = rows.map { it.compressedSize }.sorted().let { it[it.size / 2] }
        val medianRatio = rows.map { it.ratio }.sorted().let { it[it.size / 2] }
        val worstCase = rows.maxByOrNull { it.compressedSize }!!

        report.appendLine("## Summary")
        report.appendLine("| Metric | Value |")
        report.appendLine("|--------|-------|")
        report.appendLine("| Total test messages | ${rows.size} |")
        report.appendLine("| 100% under ${LORA_MTU}B | ${if (allUnderMtu) "YES" else "NO"} |")
        report.appendLine("| Median compressed size | ${medianCompressed}B |")
        report.appendLine("| Median compression ratio | ${String.format("%.1f", medianRatio)}x |")
        report.appendLine("| Worst case | ${worstCase.compressedSize}B (${worstCase.compressedSize * 100 / LORA_MTU}% of LoRa MTU) |")
        report.appendLine()

        report.appendLine("## Per-Message Results")
        report.appendLine("| Fixture | CoT Type | XML Size | Proto Size | Compressed | Ratio | Dict |")
        report.appendLine("|---------|----------|----------|------------|------------|-------|------|")
        for (row in rows) {
            report.appendLine("| ${row.fixture} | ${row.cotType} | ${row.xmlSize}B | ${row.protoSize}B | ${row.compressedSize}B | ${String.format("%.1f", row.ratio)}x | ${row.dictName} |")
        }
        report.appendLine()

        report.appendLine("## Size Distribution")
        report.appendLine("```")
        for (row in rows.sortedBy { it.compressedSize }) {
            val bar = "#".repeat((row.compressedSize.toDouble() / LORA_MTU * 50).toInt().coerceAtLeast(1))
            report.appendLine("${row.fixture.padEnd(20)} ${row.compressedSize.toString().padStart(4)}B |$bar")
        }
        report.appendLine("${"LoRa MTU".padEnd(20)} ${LORA_MTU.toString().padStart(4)}B |${"#".repeat(50)}")
        report.appendLine("```")

        // Write report
        val reportFile = File("../testdata/compression-report.md")
        reportFile.writeText(report.toString())
        println("Compression report written to: ${reportFile.absolutePath}")

        // Also write golden files
        TestFixtures.goldenDir.mkdirs()
        for (fixture in TestFixtures.filenames) {
            val xml = TestFixtures.loadFixture(fixture)
            val packet = parser.parse(xml)
            val wirePayload = compressor.compress(packet)
            val goldenFile = File(TestFixtures.goldenDir, fixture.removeSuffix(".xml") + ".bin")
            goldenFile.writeBytes(wirePayload)
        }
        println("Golden files written to: ${TestFixtures.goldenDir.absolutePath}")

        // Also write protobuf intermediate files
        TestFixtures.protobufDir.mkdirs()
        for (fixture in TestFixtures.filenames) {
            val xml = TestFixtures.loadFixture(fixture)
            val packet = parser.parse(xml)
            val protobuf = TakPacketV2Serializer.serialize(packet)
            val pbFile = File(TestFixtures.protobufDir, fixture.removeSuffix(".xml") + ".pb")
            pbFile.writeBytes(protobuf)
        }
        println("Protobuf files written to: ${TestFixtures.protobufDir.absolutePath}")

        // Assert the report was generated
        assertTrue(reportFile.exists(), "Compression report should be generated")
        assertTrue(allUnderMtu, "All messages must fit under LoRa MTU")
    }
}
