package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * `staleSeconds` is `stale.epochSeconds - time.epochSeconds`: each timestamp is rounded
 * down to whole epoch seconds before subtracting, and the result is capped at
 * [Int.MAX_VALUE]. Truncating or rounding the difference instead gives a different
 * answer whenever the two timestamps carry different fractions of a second.
 *
 * The C#, Python, Swift and TypeScript bindings check the same eleven cases
 * (`StaleSecondsTests`, `test_stale_seconds.py`, `staleSeconds.test.ts`), so all five
 * compute the same value.
 */
class StaleSecondsTest {
    private val parser = CotXmlParser()

    private fun event(
        time: String,
        stale: String,
    ): String =
        """<event version="2.0" uid="STALE-1" type="a-f-G-U-C" how="m-g" """ +
            """time="$time" start="$time" stale="$stale">""" +
            """<point lat="38.8895" lon="-77.0353" hae="0" ce="9999999" le="9999999"/>""" +
            """<detail></detail></event>"""

    private fun assertStaleSeconds(
        expected: Int,
        time: String,
        stale: String,
    ) = assertEquals(expected, parser.parse(event(time, stale)).staleSeconds, "time $time, stale $stale")

    @Test
    fun `subtracts whole epoch seconds`() {
        // 1.2 s and 0.5 s apart but 2 and 1 whole seconds: truncating the difference
        // gives 1 and 0.
        assertStaleSeconds(2, "2026-01-01T00:00:00.900Z", "2026-01-01T00:00:02.100Z")
        assertStaleSeconds(1, "2026-01-01T00:00:00.500Z", "2026-01-01T00:00:01.000Z")
        // 1.8 s and 0.999 s apart: rounding the difference gives 2 and 1.
        assertStaleSeconds(1, "2026-01-01T00:00:00.100Z", "2026-01-01T00:00:01.900Z")
        assertStaleSeconds(0, "2026-01-01T00:00:00.000Z", "2026-01-01T00:00:00.999Z")
        assertStaleSeconds(600, "2026-01-01T00:00:00Z", "2026-01-01T00:10:00Z")
        assertStaleSeconds(2, "2026-01-01T01:00:00.900+01:00", "2026-01-01T00:00:02.100Z")
    }

    @Test
    fun `is zero unless stale is after time`() {
        assertStaleSeconds(0, "2026-01-01T00:00:02.100Z", "2026-01-01T00:00:00.900Z")
        assertStaleSeconds(0, "2026-01-01T00:00:00.900Z", "2026-01-01T00:00:00.900Z")
    }

    @Test
    fun `rounds a pre-1970 timestamp down rather than toward zero`() {
        // -2 and -1 whole seconds.
        assertStaleSeconds(1, "1969-12-31T23:59:58.500Z", "1969-12-31T23:59:59.400Z")
    }

    @Test
    fun `caps a span over 68 years at Int MAX_VALUE`() {
        // toInt() alone gave -1959748096 for these 74 years, and wrapped the 174 years
        // around to 1195925504, about 38 years.
        assertStaleSeconds(Int.MAX_VALUE, "2026-01-01T00:00:00Z", "2100-01-01T00:00:00Z")
        assertStaleSeconds(Int.MAX_VALUE, "2026-01-01T00:00:00Z", "2200-01-01T00:00:00Z")
    }

    @Test
    fun `a capped span still rebuilds decades ahead`() {
        // What the cap is for. A negative staleSeconds went out as its unsigned value,
        // came back negative on a Kotlin receiver, and the builder gave the event its
        // 45-second minimum.
        val compressor = TakCompressor()
        val packet = parser.parse(event("2026-01-01T00:00:00Z", "2100-01-01T00:00:00Z"))
        val rebuilt = CotXmlBuilder().build(compressor.decompress(compressor.compress(packet)))
        val stale = assertNotNull(Regex("""stale="([^"]+)"""").find(rebuilt)).groupValues[1]
        assertTrue(Instant.parse(stale) - Clock.System.now() > (60 * 365).days, "stale $stale")
    }
}
