package org.meshtastic.tak

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The mesh send and receive paths (design doc §6, §8): parser limits, the
 * decompression cap, `compressForMesh` fallbacks and outcomes, the airtime
 * check, and `TakMeshReceiver` routing.
 */
class MeshCommonTest {
    private val compressor = TakCompressor()
    private val parser = CotXmlParser()
    private val airtime = 2_200L

    private fun receiver() = TakMeshReceiver(TakReassembler(frameAirtimeMillis = airtime), compressor)

    /** Points on a deterministic random walk near the fixture area (Truth or Consequences, NM). */
    private fun walk(
        n: Int,
        seed: Int,
    ): List<String> {
        val rnd = Random(seed)
        var lat = 33.12740
        var lon = -107.24880
        return List(n) {
            lat += (rnd.nextInt(-40, 41)) / 100_000.0
            lon += (rnd.nextInt(-40, 41)) / 100_000.0
            "${(lat * 100_000).toLong() / 100_000.0},${(lon * 100_000).toLong() / 100_000.0}"
        }
    }

    private fun shapeXml(
        vertices: Int,
        remarks: String = "",
    ): String {
        val links = walk(vertices, seed = vertices).joinToString("") { "<link point=\"$it\"/>" }
        return "<event version=\"2.0\" uid=\"shape-$vertices\" type=\"u-d-f-m\" " +
            "time=\"2026-03-15T14:22:10Z\" start=\"2026-03-15T14:22:10Z\" stale=\"2026-03-16T14:22:10Z\" how=\"h-e\">" +
            "<point lat=\"33.12740\" lon=\"-107.24880\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>" +
            "<detail>$links<contact callsign=\"Freehand $vertices\"/><remarks>$remarks</remarks></detail></event>"
    }

    private fun routeXml(links: Int): String {
        val points =
            walk(links, seed = 1000 + links)
                .mapIndexed { i, p ->
                    "<link uid=\"wp-$i\" type=\"b-m-p-w\" callsign=\"CP${i + 1}\" point=\"$p\"/>"
                }.joinToString("")
        return "<event version=\"2.0\" uid=\"route-$links\" type=\"b-m-r\" " +
            "time=\"2026-03-15T14:22:10Z\" start=\"2026-03-15T14:22:10Z\" stale=\"2026-03-16T14:22:10Z\" how=\"h-e\">" +
            "<point lat=\"33.12040\" lon=\"-107.24480\" hae=\"9999999.0\" ce=\"9999999.0\" le=\"9999999.0\"/>" +
            "<detail><__routeinfo/><link_attr method=\"Driving\" direction=\"Infil\" prefix=\"CP\" stroke=\"3\"/>" +
            "$points<contact callsign=\"Route $links\"/></detail></event>"
    }

    private val pliXml =
        "<event version=\"2.0\" uid=\"ANDROID-0000000000000001\" type=\"a-f-G-U-C\" " +
            "time=\"2026-03-15T14:22:10Z\" start=\"2026-03-15T14:22:10Z\" stale=\"2026-03-15T14:28:10Z\" how=\"m-g\">" +
            "<point lat=\"33.1274\" lon=\"-107.2488\" hae=\"1294.0\" ce=\"9.9\" le=\"9999999.0\"/>" +
            "<detail><contact callsign=\"ALPHA-1\"/><__group name=\"Cyan\" role=\"Team Member\"/></detail></event>"

    private fun shape(p: TakPacketV2Data) = p.payload as TakPacketV2Data.Payload.DrawnShape

    private fun route(p: TakPacketV2Data) = p.payload as TakPacketV2Data.Payload.Route

    private fun chat(message: String) =
        TakPacketV2Data(
            cotTypeId = CotTypeMapper.typeToEnum("b-t-f"),
            cotTypeStr = if (CotTypeMapper.typeToEnum("b-t-f") == CotTypeMapper.COTTYPE_OTHER) "b-t-f" else null,
            callsign = "ALPHA-1",
            uid = "GeoChat.ANDROID-0000000000000001.All Chat Rooms.0001",
            payload = TakPacketV2Data.Payload.Chat(message = message),
        )

    /** Text that the dictionary can't shrink much, so it needs several frames. */
    private fun noisyText(
        length: Int,
        seed: Int = length,
    ): String {
        val rnd = Random(seed)
        val alphabet = "abcdefghijklmnopqrstuvwxyz0123456789 "
        return String(CharArray(length) { alphabet[rnd.nextInt(alphabet.length)] })
    }

    private fun deliverAll(
        rx: TakMeshReceiver,
        frames: List<ByteArray>,
    ): List<TakPacketV2Data> = frames.mapNotNull { rx.onPayload(7L, it, 0L) }

    // --- parser limits ------------------------------------------------------

    @Test
    fun parseDefaultsToSingleFrameLimits() {
        val xml = shapeXml(100)
        assertEquals(parser.parse(xml), parser.parse(xml, CotXmlParser.Limits.SINGLE_FRAME))
        assertEquals(CotXmlParser.MAX_VERTICES, shape(parser.parse(xml)).vertices.size)
        assertTrue(shape(parser.parse(xml)).truncated)
    }

    @Test
    fun largeLimitsKeepMoreGeometry() {
        val big = shape(parser.parse(shapeXml(100), CotXmlParser.Limits.LARGE))
        assertEquals(100, big.vertices.size)
        assertFalse(big.truncated)

        val huge = shape(parser.parse(shapeXml(600), CotXmlParser.Limits.LARGE))
        assertEquals(CotXmlParser.MAX_VERTICES_LARGE, huge.vertices.size)
        assertTrue(huge.truncated)

        val longRoute = route(parser.parse(routeXml(40), CotXmlParser.Limits.LARGE))
        assertEquals(40, longRoute.links.size)
        assertFalse(longRoute.truncated)
    }

    @Test
    fun truncatingAParsedPacketMatchesReparsingWithSingleFrameLimits() {
        for (xml in listOf(shapeXml(100), routeXml(40), shapeXml(10), pliXml)) {
            val large = parser.parse(xml, CotXmlParser.Limits.LARGE)
            val expected = parser.parse(xml, CotXmlParser.Limits.SINGLE_FRAME)
            val actual = if (MeshEncoder.exceedsSingleFrameLimits(large)) MeshEncoder.truncateToSingleFrame(large) else large
            assertEquals(expected, actual)
        }
    }

    // --- decompression cap --------------------------------------------------

    @Test
    fun decompressCapIsAParameter() {
        val packet = chat(noisyText(6000))
        val inner = compressor.compress(packet)
        assertFailsWith<ZstdException> { compressor.decompress(inner) }
        assertEquals(packet, compressor.decompress(inner, TakFragment.MAX_REASSEMBLED_DECOMPRESSED_SIZE))
        assertFailsWith<IllegalArgumentException> { compressor.decompress(inner, 0) }
    }

    @Test
    fun theUncompressedPathHonoursTheCap() {
        val raw = ByteArray(5000) { 0 }.also { it[0] = 0xFF.toByte() }
        assertFailsWith<IllegalArgumentException> { compressor.decompress(raw) }
        // Past the size check it fails on the protobuf itself, not on the cap.
        val e = assertFailsWith<RuntimeException> { compressor.decompress(raw, 8192) }
        assertFalse(e is IllegalArgumentException)
    }

    // --- compressForMesh ----------------------------------------------------

    @Test
    fun aSmallCotIsOneFrameInTodaysFormat() {
        val fragmenter = TakFragmenter()
        for (opt in listOf(false, true)) {
            val r = compressor.compressForMesh(pliXml, fragmenter, fragmentIfOversize = opt)
            assertEquals(MeshEncodeResult.Outcome.SINGLE_FRAME, r.outcome)
            assertContentEquals(compressor.compress(parser.parse(pliXml)), r.frames!!.single())
            assertFalse(r.remarksStripped)
            assertFalse(r.truncated)
        }
    }

    @Test
    fun withoutOptInTheResultMatchesTheRemarksFallback() {
        val fragmenter = TakFragmenter()
        for (xml in listOf(shapeXml(100), shapeXml(100, remarks = noisyText(300)), routeXml(40), pliXml)) {
            val r = compressor.compressForMesh(xml, fragmenter)
            val today = compressor.compressWithRemarksFallbackDetailed(parser.parse(xml), fragmenter.maxFrameBytes)
            if (today.wirePayload == null) {
                assertEquals(MeshEncodeResult.Outcome.TOO_LARGE, r.outcome)
            } else {
                assertEquals(MeshEncodeResult.Outcome.SINGLE_FRAME, r.outcome)
                assertContentEquals(today.wirePayload, r.frames!!.single())
            }
            assertEquals(today.remarksStripped, r.remarksStripped)
            assertFalse(r.truncated)
            r.frames?.forEach { assertFalse(TakFragment.isFragment(it)) }
        }
    }

    @Test
    fun withOptInALargeShapeIsFragmentedAndArrivesWhole() {
        val xml = shapeXml(200)
        val r = compressor.compressForMesh(xml, TakFragmenter(), fragmentIfOversize = true)
        assertEquals(MeshEncodeResult.Outcome.FRAGMENTED, r.outcome)
        val frames = r.frames!!
        assertTrue(frames.size > 2)
        assertTrue(frames.all { TakFragment.isFragment(it) && it.size <= TakFragment.DEFAULT_MAX_FRAME_BYTES })

        val received = deliverAll(receiver(), frames)
        assertEquals(1, received.size)
        assertEquals(parser.parse(xml, CotXmlParser.Limits.LARGE), received.single())
        assertEquals(200, shape(received.single()).vertices.size)
    }

    @Test
    fun withOptInALongRouteIsFragmented() {
        val r = compressor.compressForMesh(routeXml(64), TakFragmenter(), fragmentIfOversize = true)
        assertEquals(MeshEncodeResult.Outcome.FRAGMENTED, r.outcome)
        assertEquals(64, route(deliverAll(receiver(), r.frames!!).single()).links.size)
    }

    @Test
    fun aLongChatNeedsOptInToBeSent() {
        val packet = chat(noisyText(1500))
        assertEquals(MeshEncodeResult.Outcome.TOO_LARGE, compressor.compressForMesh(packet, TakFragmenter()).outcome)

        val r = compressor.compressForMesh(packet, TakFragmenter(), fragmentIfOversize = true)
        assertEquals(MeshEncodeResult.Outcome.FRAGMENTED, r.outcome)
        assertEquals(packet, deliverAll(receiver(), r.frames!!).single())
    }

    @Test
    fun fallbacksRunInOrderWhenTheFragmentCapIsTooSmall() {
        // Too big for one source fragment even without remarks, so both fallbacks run.
        val xml = shapeXml(200, remarks = noisyText(400))
        val r = compressor.compressForMesh(xml, TakFragmenter(maxSourceFragments = 1), fragmentIfOversize = true)
        assertTrue(r.remarksStripped)
        assertTrue(r.truncated)
        assertTrue(r.outcome == MeshEncodeResult.Outcome.SINGLE_FRAME || r.outcome == MeshEncodeResult.Outcome.FRAGMENTED)
        val got = deliverAll(receiver(), r.frames!!).single()
        assertEquals("", got.remarks)
        assertEquals(CotXmlParser.MAX_VERTICES, shape(got).vertices.size)
        assertTrue(shape(got).truncated)
    }

    @Test
    fun remarksAreDroppedBeforeGeometryIsCut() {
        // Fits as a fragment set only once the remarks are gone.
        val xml = shapeXml(60, remarks = noisyText(3000))
        val fragmenter = TakFragmenter(maxSourceFragments = 4)
        val r = compressor.compressForMesh(xml, fragmenter, fragmentIfOversize = true)
        assertEquals(MeshEncodeResult.Outcome.FRAGMENTED, r.outcome)
        assertTrue(r.remarksStripped)
        assertFalse(r.truncated)
        assertEquals(60, shape(deliverAll(receiver(), r.frames!!).single()).vertices.size)
    }

    @Test
    fun anOversizedProtobufThatCompressesIntoOneFrameIsFragmentedOrStripped() {
        // Highly compressible remarks: one frame on the wire, but over the 4,096 B protobuf cap.
        val packet = parser.parse(pliXml).copy(remarks = "A".repeat(5000))
        val inner = compressor.compress(packet)
        assertTrue(inner.size <= TakFragment.DEFAULT_MAX_FRAME_BYTES, "precondition: compresses into one frame")

        val opted = compressor.compressForMesh(packet, TakFragmenter(), fragmentIfOversize = true)
        assertEquals(MeshEncodeResult.Outcome.FRAGMENTED, opted.outcome)
        assertFalse(opted.remarksStripped)
        assertEquals(packet, deliverAll(receiver(), opted.frames!!).single())

        val plain = compressor.compressForMesh(packet, TakFragmenter())
        assertEquals(MeshEncodeResult.Outcome.SINGLE_FRAME, plain.outcome)
        assertTrue(plain.remarksStripped)
    }

    @Test
    fun anUncompressedFrameOverThePoolsIsNeverSentAlone() {
        // 33 vertices with small random deltas: zstd can't beat the raw protobuf,
        // so it would go out as one uncompressed frame the firmware can't decode.
        val rnd = Random(33)
        var lat = 331_274_000
        var lon = -1_072_488_000
        val vertices =
            List(33) {
                lat += rnd.nextInt(-50, 50)
                lon += rnd.nextInt(-50, 50)
                TakPacketV2Data.Payload.Vertex(lat, lon)
            }
        val packet =
            TakPacketV2Data(
                cotTypeId = CotTypeMapper.typeToEnum("u-d-f-m"),
                latitudeI = 331_274_000,
                longitudeI = -1_072_488_000,
                payload = TakPacketV2Data.Payload.DrawnShape(vertices = vertices),
            )
        val inner = compressor.compress(packet)
        assertEquals(DictionaryProvider.DICT_ID_UNCOMPRESSED, inner[0].toInt() and 0xFF, "precondition: uncompressed")
        assertTrue(inner.size <= TakFragment.DEFAULT_MAX_FRAME_BYTES, "precondition: fits one frame")
        assertTrue(MeshEncoder.exceedsSingleFrameLimits(packet))

        // Opted in: a one-source-fragment set, which firmware never tries to decode.
        val opted = compressor.compressForMesh(packet, TakFragmenter(), fragmentIfOversize = true)
        assertEquals(MeshEncodeResult.Outcome.FRAGMENTED, opted.outcome)
        assertEquals(0, opted.frames!![0][3].toInt() and 0x1F, "K = 1")
        assertFalse(opted.truncated)
        assertEquals(packet, deliverAll(receiver(), opted.frames).single())

        // Not opted in: cut to the pools and sent as one frame.
        val plain = compressor.compressForMesh(packet, TakFragmenter())
        assertEquals(MeshEncodeResult.Outcome.SINGLE_FRAME, plain.outcome)
        assertTrue(plain.truncated)
        assertEquals(CotXmlParser.MAX_VERTICES, shape(compressor.decompress(plain.frames!!.single())).vertices.size)
    }

    @Test
    fun aProtobufOverTheReassembledCapIsNotFragmented() {
        val packet = chat("B".repeat(40_000))
        val r = compressor.compressForMesh(packet, TakFragmenter(), fragmentIfOversize = true)
        assertEquals(MeshEncodeResult.Outcome.TOO_LARGE, r.outcome)
        assertNull(TakFragmenter().split(byteArrayOf(0, 1, 2), TakFragment.MAX_REASSEMBLED_DECOMPRESSED_SIZE + 1, 1))
    }

    // --- airtime ------------------------------------------------------------

    @Test
    fun aBurstThatDoesNotFitTheBudgetIsNotStarted() {
        val packet = chat(noisyText(1500))
        val frames = compressor.compressForMesh(packet, TakFragmenter(), fragmentIfOversize = true).frames!!.size

        val tight = AirtimeBudget(frameAirtimeMillis = airtime, remainingMillis = airtime * (frames - 1))
        val refused = compressor.compressForMesh(packet, TakFragmenter(), fragmentIfOversize = true, airtime = tight)
        assertEquals(MeshEncodeResult.Outcome.INSUFFICIENT_AIRTIME, refused.outcome)
        assertNull(refused.frames)

        val enough = AirtimeBudget(frameAirtimeMillis = airtime, remainingMillis = airtime * frames)
        assertEquals(
            MeshEncodeResult.Outcome.FRAGMENTED,
            compressor.compressForMesh(packet, TakFragmenter(), fragmentIfOversize = true, airtime = enough).outcome,
        )

        // Single frames are not held back by the budget.
        val none = AirtimeBudget(frameAirtimeMillis = airtime, remainingMillis = 0)
        assertEquals(
            MeshEncodeResult.Outcome.SINGLE_FRAME,
            compressor.compressForMesh(pliXml, TakFragmenter(), fragmentIfOversize = true, airtime = none).outcome,
        )
    }

    @Test
    fun budgetFromDeviceMetrics() {
        // 10% of an hour is 360 s; 4% already used leaves 216 s; 3 queued frames of 2.2 s leave 209.4 s.
        val b = AirtimeBudget.fromDeviceMetrics(10.0, 4.0, queuedFrames = 3, frameAirtimeMillis = airtime)
        assertEquals(209_400L, b.remainingMillis)
        assertTrue(b.allows(95))
        assertFalse(b.allows(96))
        assertEquals(0L, AirtimeBudget.fromDeviceMetrics(10.0, 12.0, 0, airtime).remainingMillis)
    }

    // --- receiver -----------------------------------------------------------

    @Test
    fun theReceiverPassesSingleFramesThroughAndIgnoresRepairRequests() {
        val rx = receiver()
        val packet = parser.parse(pliXml)
        assertEquals(packet, rx.onPayload(1L, compressor.compress(packet), 0L))
        assertNull(rx.onPayload(1L, byteArrayOf(0xFD.toByte(), 1, 2, 3, 4, 5), 0L))
        assertFailsWith<IllegalArgumentException> { rx.onPayload(1L, byteArrayOf(0x3E, 0, 0), 0L) }
        assertFailsWith<IllegalArgumentException> { rx.onPayload(1L, ByteArray(0), 0L) }
    }

    @Test
    fun theReceiverWaitsForEnoughFragmentsThenExpires() {
        val frames = compressor.compressForMesh(chat(noisyText(1500)), TakFragmenter(), fragmentIfOversize = true).frames!!
        val k = (frames[0][3].toInt() and 0x1F) + 1
        val rx = receiver()
        // Drop the first fragment: any K of the rest still decode.
        val got = frames.drop(1).mapNotNull { rx.onPayload(3L, it, 0L) }
        assertEquals(1, got.size)

        val rx2 = receiver()
        frames.take(k - 1).forEach { assertNull(rx2.onPayload(3L, it, 0L)) }
        assertEquals(1, rx2.reassembler.pendingCount)
        rx2.evictExpired(Long.MAX_VALUE / 2)
        assertEquals(0, rx2.reassembler.pendingCount)
    }
}
