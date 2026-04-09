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
