package org.meshtastic.tak

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File

/**
 * Compression tests: verify all fixtures compress under LoRa MTU
 * and generate the compression-report.md living document.
 */
class CompressionTest {

    companion object {
        const val LORA_MTU = 237
    }

    private val parser = CotXmlParser()
    private val compressor = TakCompressor()

    private val fixtures = listOf(
        "pli_basic.xml",
        "pli_full.xml",
        "pli_webtak.xml",
        "geochat_simple.xml",
        "aircraft_adsb.xml",
        "aircraft_hostile.xml",
        "delete_event.xml",
        "casevac.xml",
        "alert_tic.xml",
    )

    private fun loadFixture(name: String): String {
        val path = File("../testdata/cot_xml/$name")
        return path.readText()
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "pli_basic.xml",
        "pli_full.xml",
        "pli_webtak.xml",
        "geochat_simple.xml",
        "aircraft_adsb.xml",
        "aircraft_hostile.xml",
        "delete_event.xml",
        "casevac.xml",
        "alert_tic.xml",
    ])
    fun `compressed payload fits under LoRa MTU`(fixture: String) {
        val xml = loadFixture(fixture)
        val packet = parser.parse(xml)
        val result = compressor.compressWithStats(packet)

        assertTrue(result.compressedSize <= LORA_MTU,
            "$fixture: compressed size ${result.compressedSize}B exceeds LoRa MTU ${LORA_MTU}B " +
            "(XML: ${xml.length}B, proto: ${result.protobufSize}B)")
    }

    @Test
    fun `compression achieves meaningful ratio`() {
        var totalXml = 0
        var totalCompressed = 0

        for (fixture in fixtures) {
            val xml = loadFixture(fixture)
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
        report.appendLine("Generated: ${java.time.LocalDate.now()} | Dictionary: v1 (non-aircraft 8KB + aircraft 4KB)")
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

        for (fixture in fixtures) {
            val xml = loadFixture(fixture)
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

        // Also write golden files
        val goldenDir = File("../testdata/golden")
        goldenDir.mkdirs()
        for (fixture in fixtures) {
            val xml = loadFixture(fixture)
            val packet = parser.parse(xml)
            val wirePayload = compressor.compress(packet)
            val goldenFile = File(goldenDir, fixture.removeSuffix(".xml") + ".bin")
            goldenFile.writeBytes(wirePayload)
        }

        // Also write protobuf intermediate files
        val protoDir = File("../testdata/protobuf")
        protoDir.mkdirs()
        for (fixture in fixtures) {
            val xml = loadFixture(fixture)
            val packet = parser.parse(xml)
            val protobuf = TakPacketV2Serializer.serialize(packet)
            val pbFile = File(protoDir, fixture.removeSuffix(".xml") + ".pb")
            pbFile.writeBytes(protobuf)
        }

        assertTrue(reportFile.exists(), "Compression report should be generated")
        assertTrue(allUnderMtu, "All messages must fit under LoRa MTU")
    }
}
