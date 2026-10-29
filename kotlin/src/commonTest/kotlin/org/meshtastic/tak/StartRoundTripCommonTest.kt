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
    fun `a start past the sender gate is not encoded`() {
        // 3660 s is well INSIDE the 8192 s window, so this exercises the sender gate,
        // PAST_LIMIT_SECONDS, and not the window. Worth pinning in its own right: that
        // gate is the only thing keeping a receiver out of the band where reconstruction
        // is wrongly accepted. The fixture corpus takes this path too, being months old.
        val tooOld = Instant.fromEpochSeconds(Clock.System.now().epochSeconds - StartEpoch.PAST_LIMIT_SECONDS - 60L)
        val packet = parser.parse(cotWithStart(tooOld, tooOld.plusSeconds(300)))
        assertNull(packet.startEpochLo14, "a start past the sender gate should not be encoded")
    }

    @Test
    fun `stale is measured from arrival rather than the recovered start`() {
        // An honest `start` must not shorten the event's life. This one spent ten
        // minutes in transit against a 60 s window, so deriving `stale` from `start`
        // would arrive long expired and never display. ATAK pads static types before
        // sending but not PLI or chat, which are exactly the types whose transit can
        // exceed their own window, so the full window has to run from arrival.
        val window = 60L
        val start = Instant.fromEpochSeconds(Clock.System.now().epochSeconds - 600L)
        val packet = parser.parse(cotWithStart(start, start.plusSeconds(window)))
        val before = Clock.System.now().epochSeconds
        val rebuilt = builder.build(compressor.decompress(compressor.compress(packet)))
        val stale = Instant.parse(Regex("""stale="([^"]+)"""").find(rebuilt)!!.groupValues[1])

        // Roughly now + window, NOT start + window (which is already 540 s in the past).
        val fromArrival = stale.epochSeconds - before
        assertTrue(fromArrival in (window - 5)..(window + 10), "stale is not arrival-measured: $fromArrival s")
        assertTrue(stale > Clock.System.now(), "stale is already in the past: $stale")
    }

    private fun Instant.plusSeconds(s: Long) = Instant.fromEpochSeconds(epochSeconds + s)
}
