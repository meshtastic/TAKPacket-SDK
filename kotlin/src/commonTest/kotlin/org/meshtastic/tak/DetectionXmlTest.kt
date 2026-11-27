package org.meshtastic.tak

import org.meshtastic.tak.TakPacketV2Data.DetectionData
import org.meshtastic.tak.TakPacketV2Data.DetectionData.Kind
import org.meshtastic.tak.TakPacketV2Data.DetectionData.Source
import org.meshtastic.tak.TakPacketV2Data.DetectionData.State
import org.meshtastic.tak.TakPacketV2Data.DetectionData.Trend
import org.meshtastic.tak.TakPacketV2Data.DetectionData.UasIdType
import org.meshtastic.tak.TakPacketV2Data.DetectionData.UasStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The passive-sensor detection annotation (TAKPacketV2 tag 47) as `<__detection>` and on the wire. */
class DetectionXmlTest {
    private val parser = CotXmlParser()
    private val builder = CotXmlBuilder()
    private val compressor = TakCompressor()

    // A synthetic CTA-2063-A serial number: the placeholder manufacturer code ZZZZ,
    // length code 8, then the eight-character serial.
    private val uasId = "ZZZZ8TEST0001"

    private val fullElement =
        """<__detection kind="uas" sources="remote_id rf" confidence="87" state="active" trend="closing" count="2" """ +
            """sensorAnchored="true" cep="12" rssi="-71" uasId="$uasId" uasIdType="serial_number" uasStatus="airborne"/>"""

    private fun event(
        detail: String,
        type: String = "a-u-A-M-H-Q",
        ce: String = "9999999",
    ): String =
        """<?xml version="1.0" encoding="UTF-8"?>
<event version="2.0" uid="2f6d3b8e-5a41-4c1f-9e07-6b2d8c4a1f35" type="$type" how="m-r" time="2026-11-20T15:00:00Z" start="2026-11-20T15:00:00Z" stale="2026-11-20T15:01:00Z">
  <point lat="33.1286" lon="-107.2470" hae="1452.0" ce="$ce" le="9999999"/>
  <detail><contact callsign="UAS-1"/>$detail</detail>
</event>"""

    private fun detection(
        element: String,
        ce: String = "9999999",
    ): DetectionData? = parser.parse(event(element, ce = ce)).detection

    private fun packet(detection: DetectionData): TakPacketV2Data =
        TakPacketV2Data
            .Builder()
            .cotType("a-u-A-M-H-Q")
            .uid("2f6d3b8e-5a41-4c1f-9e07-6b2d8c4a1f35")
            .callsign("UAS-1")
            .detection(detection)
            .build()

    private fun throughTheWire(packet: TakPacketV2Data): TakPacketV2Data = compressor.decompress(compressor.compress(packet))

    // --- Tokens ---------------------------------------------------------------

    // Spelled out rather than derived, so a renamed enum value can't quietly change the XML.
    private val kinds =
        mapOf(
            Kind.Unspecified to "unspecified",
            Kind.Uas to "uas",
            Kind.UasOperator to "uas_operator",
            Kind.UasHome to "uas_home",
            Kind.Person to "person",
            Kind.Vehicle to "vehicle",
            Kind.Other to "other",
        )
    private val states =
        mapOf(
            State.Unspecified to "unspecified",
            State.New to "new",
            State.Active to "active",
            State.Coasting to "coasting",
            State.Lost to "lost",
        )
    private val trends =
        mapOf(
            Trend.Unspecified to "unspecified",
            Trend.Closing to "closing",
            Trend.Steady to "steady",
            Trend.Opening to "opening",
        )
    private val idTypes =
        mapOf(
            UasIdType.Unspecified to "unspecified",
            UasIdType.SerialNumber to "serial_number",
            UasIdType.CaaRegistration to "caa_registration",
            UasIdType.UtmAssigned to "utm_assigned",
            UasIdType.SpecificSession to "specific_session",
        )
    private val statuses =
        mapOf(
            UasStatus.Unspecified to "unspecified",
            UasStatus.Ground to "ground",
            UasStatus.Airborne to "airborne",
            UasStatus.Emergency to "emergency",
            UasStatus.SystemFailure to "system_failure",
        )
    private val sources =
        mapOf(
            Source.Unspecified to "unspecified",
            Source.RemoteId to "remote_id",
            Source.Rf to "rf",
            Source.Acoustic to "acoustic",
            Source.Radar to "radar",
            Source.Optical to "optical",
            Source.Thermal to "thermal",
            Source.Seismic to "seismic",
            Source.Magnetic to "magnetic",
        )

    @Test
    fun everyEnumValueHasATokenThatReadsBack() {
        // Every value is in its table, so a value added later without a token fails here.
        assertEquals(Kind.entries.toSet(), kinds.keys)
        assertEquals(State.entries.toSet(), states.keys)
        assertEquals(Trend.entries.toSet(), trends.keys)
        assertEquals(UasIdType.entries.toSet(), idTypes.keys)
        assertEquals(UasStatus.entries.toSet(), statuses.keys)
        assertEquals(Source.entries.toSet(), sources.keys)

        for ((value, token) in kinds) {
            assertEquals(token, DetectionXml.token(value))
            assertEquals(value, detection("""<__detection kind="$token"/>""")?.kind)
            assertBuildsAttribute(DetectionData(kind = value), "kind", token, value == Kind.Unspecified)
        }
        for ((value, token) in states) {
            assertEquals(token, DetectionXml.token(value))
            assertEquals(value, detection("""<__detection state="$token"/>""")?.state)
            assertBuildsAttribute(DetectionData(state = value), "state", token, value == State.Unspecified)
        }
        for ((value, token) in trends) {
            assertEquals(token, DetectionXml.token(value))
            assertEquals(value, detection("""<__detection trend="$token"/>""")?.trend)
            assertBuildsAttribute(DetectionData(trend = value), "trend", token, value == Trend.Unspecified)
        }
        for ((value, token) in idTypes) {
            assertEquals(token, DetectionXml.token(value))
            assertEquals(value, detection("""<__detection uasIdType="$token"/>""")?.uasIdType)
            assertBuildsAttribute(DetectionData(uasIdType = value), "uasIdType", token, value == UasIdType.Unspecified)
        }
        for ((value, token) in statuses) {
            assertEquals(token, DetectionXml.token(value))
            assertEquals(value, detection("""<__detection uasStatus="$token"/>""")?.uasStatus)
            assertBuildsAttribute(DetectionData(uasStatus = value), "uasStatus", token, value == UasStatus.Unspecified)
        }
        for ((value, token) in sources) {
            assertEquals(token, DetectionXml.token(value))
            assertEquals(value.value, detection("""<__detection sources="$token"/>""")?.sources)
            assertBuildsAttribute(DetectionData(sources = value.value), "sources", token, value == Source.Unspecified)
        }
    }

    private fun assertBuildsAttribute(
        detection: DetectionData,
        name: String,
        token: String,
        omitted: Boolean,
    ) {
        val xml = builder.build(packet(detection))
        if (omitted) {
            assertTrue(xml.contains("    <__detection/>\n"), xml)
        } else {
            assertTrue(xml.contains("""<__detection $name="$token"/>"""), xml)
        }
    }

    @Test
    fun sourcesAreWrittenInBitOrderAndReadAsABitmask() {
        val all = Source.entries.fold(0) { bits, s -> bits or s.value }
        val xml = builder.build(packet(DetectionData(sources = all)))
        assertTrue(xml.contains("""sources="remote_id rf acoustic radar optical thermal seismic magnetic""""), xml)
        assertEquals(all, parser.parse(xml).detection?.sources)
    }

    // --- The element ----------------------------------------------------------

    @Test
    fun theFullElementSurvivesTheMesh() {
        val expected =
            DetectionData(
                kind = Kind.Uas,
                sources = Source.RemoteId.value or Source.Rf.value,
                confidence = 87,
                state = State.Active,
                trend = Trend.Closing,
                count = 2,
                sensorAnchored = true,
                cepMeters = 12,
                rssiDbm = -71,
                uasId = uasId,
                uasIdType = UasIdType.SerialNumber,
                uasStatus = UasStatus.Airborne,
            )
        val parsed = parser.parse(event(fullElement))
        assertEquals(expected, parsed.detection)

        val received = throughTheWire(parsed)
        assertEquals(parsed, received)

        val rebuilt = builder.build(received)
        assertTrue(rebuilt.contains("    $fullElement\n"), rebuilt)
        assertTrue(rebuilt.contains("""ce="12" le="9999999""""), rebuilt)
        assertEquals(expected, parser.parse(rebuilt).detection)
    }

    @Test
    fun aBareElementIsPresentWithEveryDefault() {
        assertEquals(DetectionData(), detection("<__detection/>"))
        val xml = builder.build(packet(DetectionData()))
        assertTrue(xml.contains("    <__detection/>\n"), xml)
        assertTrue(xml.contains("""ce="9999999""""), xml)
    }

    @Test
    fun anEventWithoutTheElementHasNoDetectionAndKeepsTheUnknownCe() {
        val parsed = parser.parse(event("", ce = "12.0"))
        assertNull(parsed.detection)
        assertNull(throughTheWire(parsed).detection)
        val xml = builder.build(parsed)
        assertFalse(xml.contains("__detection"), xml)
        assertTrue(xml.contains("""ce="9999999""""), xml)
    }

    @Test
    fun unknownTokensReadAsUnspecifiedAndCaseIsIgnored() {
        val d = detection("""<__detection kind="balloon" sources=" RF bogus  ACOUSTIC " state="LOST" trend="sideways" uasIdType="mystery" uasStatus="Ground" sensorAnchored="TRUE"/>""")
        assertEquals(Kind.Unspecified, d?.kind)
        assertEquals(Source.Rf.value or Source.Acoustic.value, d?.sources)
        assertEquals(State.Lost, d?.state)
        assertEquals(Trend.Unspecified, d?.trend)
        assertEquals(UasIdType.Unspecified, d?.uasIdType)
        assertEquals(UasStatus.Ground, d?.uasStatus)
        assertEquals(true, d?.sensorAnchored)
        assertEquals(false, detection("""<__detection sensorAnchored="yes"/>""")?.sensorAnchored)
    }

    @Test
    fun numbersAreRoundedHalfUpAndClamped() {
        assertEquals(100, detection("""<__detection confidence="250"/>""")?.confidence)
        assertEquals(0, detection("""<__detection confidence="-5"/>""")?.confidence)
        assertEquals(88, detection("""<__detection confidence="87.5"/>""")?.confidence)
        assertEquals(0, detection("""<__detection count="-3"/>""")?.count)
        assertEquals(0, detection("""<__detection cep="-1"/>""")?.cepMeters)
        assertEquals(12, detection("""<__detection cep=" 12 "/>""")?.cepMeters)
        assertEquals(-70, detection("""<__detection rssi="-70.5"/>""")?.rssiDbm)
        assertEquals(-71, detection("""<__detection rssi="-70.6"/>""")?.rssiDbm)
        assertEquals(1500, detection("""<__detection count="1.5e3"/>""")?.count)
        // Not plain decimals, or too large to be finite: read as absent.
        assertEquals(
            DetectionData(),
            detection("""<__detection confidence="NaN" count="Infinity" cep="0x10" rssi="1e400"/>"""),
        )
        assertEquals(0, detection("""<__detection count="2f"/>""")?.count)
        // The builder clamps a value that arrived out of range on the wire.
        val xml = builder.build(packet(DetectionData(confidence = 150, count = -2, cepMeters = -4)))
        assertTrue(xml.contains("""<__detection confidence="100"/>"""), xml)
        assertTrue(xml.contains("""ce="9999999""""), xml)
    }

    @Test
    fun theUasIdIsCutTo20CharactersAndKeptOnlyForAUas() {
        val long = "ZZZZFTEST000000000001EXTRA" // 26 characters
        assertEquals(long.take(20), detection("""<__detection kind="uas" uasId="$long"/>""")?.uasId)
        // A character outside the BMP counts once and is never split.
        val astral = "A".repeat(19) + "𝐀" + "B"
        assertEquals("A".repeat(19) + "𝐀", detection("""<__detection kind="uas" uasId="$astral"/>""")?.uasId)

        for (kind in listOf("uas_operator", "uas_home", "person", "vehicle", "other", "unspecified")) {
            assertEquals("", detection("""<__detection kind="$kind" uasId="$uasId"/>""")?.uasId, kind)
        }
        assertEquals("", detection("""<__detection uasId="$uasId"/>""")?.uasId)

        // The builder holds a packet built in code to the same rule.
        val xml = builder.build(packet(DetectionData(kind = Kind.Person, uasId = uasId)))
        assertFalse(xml.contains(uasId), xml)
        val cut = builder.build(packet(DetectionData(kind = Kind.Uas, uasId = long)))
        assertTrue(cut.contains("""uasId="${long.take(20)}""""), cut)
    }

    @Test
    fun cepFallsBackToThePointCe() {
        assertEquals(15, detection("<__detection/>", ce = "15.0")?.cepMeters)
        assertEquals(12, detection("""<__detection cep="12"/>""", ce = "15.0")?.cepMeters)
        assertEquals(15, detection("""<__detection cep="unknown"/>""", ce = "15.0")?.cepMeters)
        assertEquals(0, detection("<__detection/>", ce = "9999999")?.cepMeters)
        assertEquals(0, detection("<__detection/>", ce = "9999999.0")?.cepMeters)
        assertEquals(0, detection("<__detection/>", ce = "-3")?.cepMeters)
    }

    @Test
    fun theAnnotationLeavesThePayloadVariantAlone() {
        val parentLink = """<link uid="ANDROID-0000000000000012" type="a-f-G-E-S" parent_callsign="SENSOR-1" relation="p-p"/>"""
        val marker = parser.parse(event(parentLink + fullElement)).payload
        assertIs<TakPacketV2Data.Payload.Marker>(marker)
        assertEquals("SENSOR-1", marker.parentCallsign)
        assertIs<TakPacketV2Data.Payload.Pli>(parser.parse(event(fullElement, type = "a-f-G-U-C")).payload)
        assertEquals(
            parser.parse(event(parentLink)).payload,
            parser.parse(event(parentLink + fullElement)).payload,
        )
    }

    @Test
    fun sourceBitsThisModelDoesNotNameSurviveTheWire() {
        val unnamed = 1 shl 9
        val d = DetectionData(kind = Kind.Uas, sources = Source.Radar.value or unnamed)
        assertEquals(d, throughTheWire(packet(d)).detection)
        // CoT XML can only name the bits it knows.
        val xml = builder.build(packet(d))
        assertTrue(xml.contains("""<__detection kind="uas" sources="radar"/>"""), xml)
    }

    @Test
    fun theBuilderMatchesTheConstructor() {
        val built =
            DetectionData
                .Builder()
                .kind(Kind.Uas)
                .addSource(Source.RemoteId)
                .addSource(Source.Rf)
                .confidence(87)
                .state(State.Active)
                .trend(Trend.Closing)
                .count(2)
                .sensorAnchored(true)
                .cepMeters(12)
                .rssiDbm(-71)
                .uasId(uasId)
                .uasIdType(UasIdType.SerialNumber)
                .uasStatus(UasStatus.Airborne)
                .build()
        assertEquals(parser.parse(event(fullElement)).detection, built)
        assertEquals(built, built.toBuilder().build())
        assertTrue(built.hasSource(Source.Rf))
        assertFalse(built.hasSource(Source.Radar))
        assertFalse(built.hasSource(Source.Unspecified))
    }
}
