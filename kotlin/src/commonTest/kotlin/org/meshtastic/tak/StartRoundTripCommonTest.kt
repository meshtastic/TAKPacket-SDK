package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * End-to-end coverage for the `start` timestamp, which the 47 fixtures cannot provide.
 *
 * Every fixture is stamped months in the past, so the parser correctly refuses to
 * encode its `start` (out of the 14-bit window) and the builder falls back to the
 * decode instant. That makes the whole fixture corpus pass whether this feature works
 * or not, and makes the goldens blind to a regression in it. So the timestamps here are
 * minted at run time rather than committed: a fixture written today would age out of
 * the window within the hour and start passing vacuously.
 */
class StartRoundTripCommonTest {
    private val parser = CotXmlParser()
    private val builder = CotXmlBuilder()
    private val compressor = TakCompressor()

    private fun cotWithStart(
        start: Instant,
        stale: Instant,
    ): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<event version="2.0" uid="ATOM-START-RT" type="a-f-G-U-C" how="m-g" """ +
            """time="$start" start="$start" stale="$stale">""" +
            """<point lat="38.8895" lon="-77.0353" hae="0.0" ce="9999999.0" le="9999999.0"/>""" +
            """<detail><contact callsign="ALPHA-1"/></detail></event>"""

    @Test
    fun `a recent start survives the full wire round trip`() {
        // 90 s old: comfortably inside the window, and far enough from now that a
        // fallback to the decode instant is unambiguous rather than a rounding tie.
        val start = Instant.fromEpochSeconds(Clock.System.now().epochSeconds - 90L)
        val packet = parser.parse(cotWithStart(start, start.plusSeconds(300)))
        assertNotNull(packet.startEpochLo14, "parser did not capture start")
        assertEquals(StartEpoch.truncate(start.epochSeconds), packet.startEpochLo14)

        val rt = compressor.decompress(compressor.compress(packet))
        assertEquals(packet.startEpochLo14, rt.startEpochLo14, "start did not survive the wire")

        // The rebuilt event must carry the ORIGINAL instant, not the decode instant.
        val rebuilt = builder.build(rt)
        assertTrue(rebuilt.contains("""start="$start""""), "rebuilt start is not the original: $rebuilt")
        assertTrue(rebuilt.contains("""time="$start""""), "rebuilt time is not the original: $rebuilt")
    }

    @Test
    fun `an absent start falls back to the decode instant`() {
        val before = Clock.System.now().epochSeconds
        val rebuilt = builder.build(TakPacketV2Data(uid = "ATOM-NO-START", cotTypeStr = "a-f-G-U-C"))
        val emitted = Regex("""start="([^"]+)"""").find(rebuilt)?.groupValues?.get(1)
        assertNotNull(emitted, "no start emitted")
        // Within a few seconds of now, i.e. the pre-existing behaviour is unchanged
        // when the sender supplied nothing.
        val delta = Instant.parse(emitted).epochSeconds - before
        assertTrue(delta in 0L..10L, "fallback start is not the decode instant: $emitted")
    }

    @Test
    fun `a start older than the window is not encoded`() {
        // The fixture case, asserted deliberately: refusing is correct, because an
        // out-of-window value would reconstruct as a plausible recent time.
        val tooOld = Instant.fromEpochSeconds(Clock.System.now().epochSeconds - StartEpoch.PAST_LIMIT_SECONDS - 60L)
        val packet = parser.parse(cotWithStart(tooOld, tooOld.plusSeconds(300)))
        assertNull(packet.startEpochLo14, "an out-of-window start should not be encoded")
    }

    @Test
    fun `stale is never emitted in the past for an old start`() {
        // start + staleSeconds can already have elapsed; the event must still be
        // visible on arrival rather than dead on delivery.
        val start = Instant.fromEpochSeconds(Clock.System.now().epochSeconds - 600L)
        val packet = parser.parse(cotWithStart(start, start.plusSeconds(60)))
        val rebuilt = builder.build(compressor.decompress(compressor.compress(packet)))
        val stale = Instant.parse(Regex("""stale="([^"]+)"""").find(rebuilt)!!.groupValues[1])
        assertTrue(stale > Clock.System.now(), "stale is already in the past: $stale")
    }

    private fun Instant.plusSeconds(s: Long) = Instant.fromEpochSeconds(epochSeconds + s)
}
