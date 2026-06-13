package org.meshtastic.tak

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.MethodSource

/**
 * STAGE 0 GATE (AC0, R9/R10) — JVM-only de-risking spike.
 *
 * Binary pass/fail: does the xpp3 -> xmlutil re-port [CotXmlParserXmlUtil]
 * produce BYTE-IDENTICAL protobuf output for all 47 fixtures versus the frozen
 * testdata/protobuf .pb goldens?
 *
 * For each fixture we parse with the ported parser, serialize via the unchanged
 * [TakPacketV2Serializer], and `assertArrayEquals` against the golden `.pb`.
 * On mismatch we ALSO parse the same XML with the canonical [CotXmlParser] and
 * diff the two [TakPacketV2Data] objects field-by-field, so a failure names the
 * exact offending field instead of just "N bytes differ". This localizes any
 * xmlutil-vs-xpp3 behavioral drift to a single field on a single fixture.
 */
class Stage0ParserParityTest {

    private val portedParser = CotXmlParserXmlUtil()
    private val originalParser = CotXmlParser()

    @ParameterizedTest(name = "{0}")
    @MethodSource("org.meshtastic.tak.TestFixtures#allFixtureNames")
    fun `ported parser produces byte-identical protobuf`(fixtureName: String) {
        val goldenPb = TestFixtures.loadProtobuf(fixtureName)
            ?: error("$fixtureName: golden .pb missing — cannot run parity gate")

        val xml = TestFixtures.loadFixture("$fixtureName.xml")
        val portedData = portedParser.parse(xml)
        val portedPb = TakPacketV2Serializer.serialize(portedData)

        if (!goldenPb.contentEquals(portedPb)) {
            // Localize the divergence: diff the ported data model against the
            // canonical parser's output for the same input.
            val originalData = originalParser.parse(xml)
            val report = buildString {
                appendLine("PARITY MISMATCH for fixture '$fixtureName'")
                appendLine("  ported .pb size=${portedPb.size}B  golden .pb size=${goldenPb.size}B")
                if (originalData == portedData) {
                    appendLine("  NOTE: ported TakPacketV2Data == original CotXmlParser output,")
                    appendLine("        yet serialized bytes differ from the golden. This means the")
                    appendLine("        golden was produced from a DIFFERENT data model than the")
                    appendLine("        current original parser yields (stale golden?), NOT a port bug.")
                } else {
                    appendLine("  ported TakPacketV2Data DIFFERS from original CotXmlParser output:")
                    diffPackets(originalData, portedData).forEach { appendLine("    - $it") }
                }
            }
            // Surface the diagnosis, then fail on the actual byte comparison.
            println(report)
            assertArrayEquals(
                goldenPb, portedPb,
                "$fixtureName: ported protobuf bytes differ from golden\n$report",
            )
        }
    }

    /** Field-by-field diff between two [TakPacketV2Data]; one line per difference. */
    private fun diffPackets(a: TakPacketV2Data, b: TakPacketV2Data): List<String> {
        val diffs = mutableListOf<String>()
        fun cmp(name: String, x: Any?, y: Any?) {
            if (x != y) diffs.add("$name: original=<$x> ported=<$y>")
        }
        cmp("cotTypeId", a.cotTypeId, b.cotTypeId)
        cmp("cotTypeStr", a.cotTypeStr, b.cotTypeStr)
        cmp("how", a.how, b.how)
        cmp("callsign", a.callsign, b.callsign)
        cmp("team", a.team, b.team)
        cmp("role", a.role, b.role)
        cmp("latitudeI", a.latitudeI, b.latitudeI)
        cmp("longitudeI", a.longitudeI, b.longitudeI)
        cmp("altitude", a.altitude, b.altitude)
        cmp("speed", a.speed, b.speed)
        cmp("course", a.course, b.course)
        cmp("battery", a.battery, b.battery)
        cmp("geoSrc", a.geoSrc, b.geoSrc)
        cmp("altSrc", a.altSrc, b.altSrc)
        cmp("uid", a.uid, b.uid)
        cmp("deviceCallsign", a.deviceCallsign, b.deviceCallsign)
        cmp("staleSeconds", a.staleSeconds, b.staleSeconds)
        cmp("takVersion", a.takVersion, b.takVersion)
        cmp("takDevice", a.takDevice, b.takDevice)
        cmp("takPlatform", a.takPlatform, b.takPlatform)
        cmp("takOs", a.takOs, b.takOs)
        cmp("endpoint", a.endpoint, b.endpoint)
        cmp("phone", a.phone, b.phone)
        cmp("remarks", a.remarks, b.remarks)
        cmp("environment", a.environment, b.environment)
        cmp("sensorFov", a.sensorFov, b.sensorFov)
        cmp("marti", a.marti, b.marti)
        // Payload variant type + structural contents (data classes => structural eq).
        cmp("payload.type", a.payload::class.simpleName, b.payload::class.simpleName)
        cmp("payload", a.payload, b.payload)
        return diffs
    }
}
