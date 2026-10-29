package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
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

    // Split so the Kotlin raw string does not have to nest a quote next to its terminator.
    private val startedAt = "start=\""
    private val quote = "\""

    private fun cotWithStart(
        start: Instant,
        stale: Instant,
    ): String = cotWithRawStart(start.toString(), stale.toString())

    /** As [cotWithStart], but the caller controls the textual form of the instants. */
    private fun cotWithRawStart(
        start: String,
        stale: String,
    ): String =
        """<?xml version="1.0" encoding="UTF-8"?>""" +
            """<event version="2.0" uid="ATOM-START-RT" type="a-f-G-U-C" how="m-g" """ +
            """time="$start" start="$start" stale="$stale">""" +
            """<point lat="38.8895" lon="-77.0353" hae="0.0" ce="9999999.0" le="9999999.0"/>""" +
            """<detail><contact callsign="ALPHA-1"/></detail></event>"""

    @Test
    fun `the rebuilt start and time are UTC`() {
        // The whole field is epoch seconds, so every instant on this path is zone-free
        // by construction and the only place a zone could be asserted is the text we
        // emit. ATAK's own CoT formatter is pinned to TimeZone UTC with a literal Z, and
        // its parser reads fixed character positions as GMT, so UTC is the ecosystem
        // invariant rather than a choice this library gets to make. Pinned here so a
        // future change to a local-zone formatter fails loudly instead of shifting every
        // reconstructed start by the formatting device's offset.
        val start = Instant.fromEpochSeconds(Clock.System.now().epochSeconds - 90L)
        val packet = parser.parse(cotWithStart(start, start.plusSeconds(300)))
        val rebuilt = builder.build(compressor.decompress(compressor.compress(packet)))
        for (attr in listOf("time", "start", "stale")) {
            val v = Regex("""$attr="([^"]+)"""").find(rebuilt)?.groupValues?.get(1)
            assertNotNull(v, "no $attr emitted")
            assertTrue(v.endsWith("Z"), "$attr is not UTC: $v")
        }
    }

    @Test
    fun `an offset-bearing start is converted rather than read positionally`() {
        // Same instant, written with a +04:00 offset instead of Z. We must encode the
        // same 14 bits for both, which holds because the parser resolves the instant
        // instead of reading fixed character positions the way ATAK's fast path does.
        // Worth pinning precisely because we are STRICTER than ATAK here: that strictness
        // is what turns a malformed timestamp into a safe null rather than a wrong time.
        val start = Instant.fromEpochSeconds(Clock.System.now().epochSeconds - 90L)
        val asOffset = (start + 4.hours).toString().removeSuffix("Z") + "+04:00"
        val stale = start.plusSeconds(300)

        val fromZ = parser.parse(cotWithStart(start, stale))
        val fromOffset = parser.parse(cotWithRawStart(asOffset, stale.toString()))
        assertNotNull(fromOffset.startEpochLo14, "offset form was not encoded")
        assertEquals(fromZ.startEpochLo14, fromOffset.startEpochLo14, "offset form encoded a different instant")
    }

    @Test
    fun `a start with no zone designator is not guessed at`() {
        // ATAK's fixed-position parser would read this as GMT. We refuse it instead and
        // fall back, because a producer that omits the designator has told us nothing
        // trustworthy, and inventing a zone is how a local-time stamp becomes a silent
        // multi-hour error. Null is the honest answer and arbitration fails open on it.
        val start = Instant.fromEpochSeconds(Clock.System.now().epochSeconds - 90L)
        val noZone = start.toString().removeSuffix("Z")
        val packet = parser.parse(cotWithRawStart(noZone, start.plusSeconds(300).toString()))
        assertNull(packet.startEpochLo14, "a zone-less start should not be encoded")
    }


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
    fun `a lo14 of zero survives the wire as present rather than absent`() {
        // `start` mod 16384 is 0 for one second in every 16384, i.e. once every 4.55 h,
        // and that second is the only one where a genuine value collides with proto3's
        // default. Explicit presence is what separates them, which is why the field is
        // `optional` even though the v0.4.0 altitude experiment found `optional`
        // net-negative: a genuine 0 m altitude is common, this is one second in 16384.
        //
        // Drop the `optional` and nothing fails loudly. For one second every 4.55 h a
        // real start reads as absent, the receiver falls back to its decode time and
        // arbitration fails open for that one report. The fixture corpus cannot catch
        // it: every fixture is months old and takes the not-encoded path regardless.
        //
        // The arithmetic for this value is covered in StartEpochTest, whose boundary
        // constant IS a window boundary and so truncates to exactly 0. Only presence
        // across the serializer is at stake here.
        val atBoundary = TakPacketV2Data(uid = "ATOM-LO14-ZERO", cotTypeStr = "a-f-G-U-C", startEpochLo14 = 0)
        val rt = compressor.decompress(compressor.compress(atBoundary))
        assertNotNull(rt.startEpochLo14, "a lo14 of 0 came back as absent: presence was lost")
        assertEquals(0, rt.startEpochLo14)

        // The other direction, so this pins presence rather than just non-nullness:
        // a sender that supplied nothing must not arrive looking like a boundary start.
        val absent = TakPacketV2Data(uid = "ATOM-LO14-ABSENT", cotTypeStr = "a-f-G-U-C")
        assertNull(
            compressor.decompress(compressor.compress(absent)).startEpochLo14,
            "an absent start came back as 0: presence was lost the other way",
        )
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
    fun `a present but out-of-range start falls back rather than being used or dropped`() {
        // The receiver-side counterpart to the sender gate above, and the one path the
        // unit tests on reconstruct cannot show: a field that IS present, that the
        // receiver refuses, travelling all the way through the builder.
        //
        // Two ways to get here, and the receiver cannot tell them apart: clock
        // disagreement between the two devices, since the hour is spent by both, or a
        // transit longer than an hour, which a mesh does not deliver. Either way the
        // answer is to fall back to decode time and deliver. NOT to use the value, which
        // would be wrong by an unknown amount, and NOT to drop the CoT: uid, type and
        // position round-trip faithfully and are not impeached by a bad clock, so
        // dropping would hide a sound position behind an unsound timestamp and make a
        // node with a wrong clock permanently invisible.
        //
        // 7200 s out is inside the 8192 s half-window, so this exercises the BOUND and
        // not the wrap: reconstruct resolves the instant exactly and then refuses it.
        val nowSec = Clock.System.now().epochSeconds
        val outOfRange =
            TakPacketV2Data(
                uid = "ATOM-OUT-OF-RANGE",
                cotTypeStr = "a-f-G-U-C",
                startEpochLo14 = StartEpoch.truncate(nowSec - 7200L),
            )
        assertNotNull(outOfRange.startEpochLo14, "fixture should carry a present field")

        val rebuilt = builder.build(outOfRange)
        val emitted = Regex("""start="([^"]+)"""").find(rebuilt)?.groupValues?.get(1)
        assertNotNull(emitted, "no start emitted, the event must still be built")
        val delta = Instant.parse(emitted).epochSeconds - nowSec
        assertTrue(delta in 0L..10L, "out-of-range start was used, not the decode instant: $emitted")
    }

    @Test
    fun `buildWithOrigin reports a recovered start and agrees with the xml`() {
        val start = Instant.fromEpochSeconds(Clock.System.now().epochSeconds - 90L)
        val packet = parser.parse(cotWithStart(start, start.plusSeconds(300)))
        val built = builder.buildWithOrigin(compressor.decompress(compressor.compress(packet)))

        assertEquals(StartRecovery.RECOVERED, built.recovery)
        assertEquals(start, built.originStart)
        // The reported instant and the emitted attribute must be the same value: one
        // computation, so a caller cannot act on an origin time the map never saw.
        assertTrue(built.xml.contains(startedAt + start + quote), "xml disagrees: ${built.xml}")
        // The convenience overload must agree on the fields the recovery governs. Not on
        // `stale`, which is `now + window` and so differs by however long the two calls are
        // apart; only `time` and `start` come from the recovered value.
        val viaBuild = builder.build(compressor.decompress(compressor.compress(packet)))
        for (attr in listOf("time", "start")) {
            val a = Regex("""$attr="([^"]+)"""").find(built.xml)?.groupValues?.get(1)
            val b = Regex("""$attr="([^"]+)"""").find(viaBuild)?.groupValues?.get(1)
            assertEquals(a, b, "build and buildWithOrigin disagree on $attr")
        }
        // Given a fixed reference instant the output is byte-identical, which the `now`
        // parameter is what makes testable at all.
        val fixed = Instant.fromEpochSeconds(start.epochSeconds + 30L)
        assertEquals(
            builder.buildWithOrigin(packet, fixed).xml,
            builder.buildWithOrigin(packet, fixed).xml,
        )
    }

    @Test
    fun `buildWithOrigin separates an absent start from a refused one`() {
        val nowSec = Clock.System.now().epochSeconds

        // Nothing sent: no accusation, and the xml still falls back to the decode instant.
        val absent = builder.buildWithOrigin(TakPacketV2Data(uid = "A", cotTypeStr = "a-f-G-U-C"))
        assertEquals(StartRecovery.ABSENT, absent.recovery)
        assertNull(absent.originStart)

        // Sent and refused. 7200 s is inside the 8192 s half-window, so this is the bound
        // rather than the wrap. This is the case a consumer may discard the report over,
        // and the only reason the two are separate values.
        val refused = builder.buildWithOrigin(
            TakPacketV2Data(
                uid = "B",
                cotTypeStr = "a-f-G-U-C",
                startEpochLo14 = StartEpoch.truncate(nowSec - 7200L),
            ),
        )
        assertEquals(StartRecovery.REFUSED, refused.recovery)
        assertNull(refused.originStart)
        // Still built. The codec does not get to decide; uid, type and position are fine.
        assertTrue(refused.xml.contains("uid=\"B\""), "the event must still be produced")
    }

    @Test
    fun `an explicit reference instant lets a stored capture decode`() {
        // Why buildWithOrigin takes `now`. Replaying a recording an hour later would refuse
        // every carried start against the current clock, so the original decode instant has
        // to be passable. Without this, a capture is undecodable by construction.
        val recordedAt = Instant.fromEpochSeconds(Clock.System.now().epochSeconds - 86_400L)
        val start = recordedAt.plusSeconds(-30)
        val packet = TakPacketV2Data(
            uid = "ATOM-REPLAY",
            cotTypeStr = "a-f-G-U-C",
            startEpochLo14 = StartEpoch.truncate(start.epochSeconds),
        )

        assertEquals(StartRecovery.REFUSED, builder.buildWithOrigin(packet).recovery)
        val asRecorded = builder.buildWithOrigin(packet, recordedAt)
        assertEquals(StartRecovery.RECOVERED, asRecorded.recovery)
        assertEquals(start, asRecorded.originStart)
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
