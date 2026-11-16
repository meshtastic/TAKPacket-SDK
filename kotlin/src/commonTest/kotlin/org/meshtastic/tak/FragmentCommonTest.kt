package org.meshtastic.tak

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Fragmenting and reassembly (design doc §5–§7), on every target.
 *
 * Covers the CRC and GF(256) vectors, the codec's any-K property, the
 * fragmenter's single-frame / forced / capped rules, and the reassembly matrix:
 * order, duplicates, loss, CRC recovery, conflicts, malformed frames, caps,
 * deadlines and redelivery.
 */
class FragmentCommonTest {
    private val airtime = 2_200L

    private fun reassembler(): TakReassembler = TakReassembler(frameAirtimeMillis = airtime)

    /** Deterministic pseudo-random payload whose first byte looks like a real flags byte. */
    private fun payload(
        size: Int,
        seed: Int = size,
    ): ByteArray {
        val bytes = Random(seed).nextBytes(size)
        bytes[0] = 0x00
        return bytes
    }

    private fun fragmenter(
        maxSourceFragments: Int = TakFragment.MAX_SOURCE_FRAGMENTS,
        repair: (Int) -> Int = TakFragmenter::defaultRepairCount,
    ) = TakFragmenter(maxSourceFragments = maxSourceFragments, repairPolicy = repair)

    private fun deliver(
        r: TakReassembler,
        frames: List<ByteArray>,
        now: Long = 0L,
    ): List<ByteArray> = frames.mapNotNull { r.accept(it, now) }

    private fun kOf(frame: ByteArray): Int = (frame[HDR_SHAPE].toInt() and 0x1F) + 1

    // --- primitives ---------------------------------------------------------

    @Test
    fun crc32MatchesTheStandardCheckValue() {
        assertEquals(0xCBF43926.toInt(), Crc32.compute("123456789".encodeToByteArray()))
        assertEquals(0, Crc32.compute(ByteArray(0)))
    }

    @Test
    fun gf256TablesAreConsistent() {
        for (a in 1..255) {
            assertEquals(1, Gf256.mul(a, Gf256.inv(a)), "a * inv(a) for $a")
            assertEquals(a, Gf256.EXP[Gf256.LOG[a]])
        }
        assertEquals(0, Gf256.mul(0, 77))
        // Spot values that every binding must reproduce.
        assertEquals(0x1D, Gf256.mul(0x80, 0x02))
        assertEquals(0x8E, Gf256.inv(0x02))
    }

    @Test
    fun firstRepairRowIsPlainXor() {
        for (k in 1..TakFragment.MAX_SOURCE_FRAGMENTS) {
            for (j in 0 until k) assertEquals(1, TakFec.coef(k, j, k), "coef($k, $j, $k)")
        }
    }

    @Test
    fun everyKSubsetDecodesForSmallBlocks() {
        val s = 9
        for (k in 1..6) {
            val r = 4
            val source = List(k) { Random(k * 31 + it).nextBytes(s) }
            val all = (0 until k + r).map { e -> e to (if (e < k) source[e] else TakFec.encodeRepair(source, e)) }
            forEachSubset(k + r, k) { idx ->
                val decoded = assertNotNull(TakFec.decode(k, s, idx.map { all[it] }), "k=$k subset=$idx")
                decoded.forEachIndexed { i, sym -> assertContentEquals(source[i], sym, "k=$k subset=$idx symbol $i") }
            }
        }
    }

    @Test
    fun partitionIsBalanced() {
        assertEquals(listOf(1), TakFec.partition(0, 220, 128))
        assertEquals(listOf(16), TakFec.partition(16 * 220, 220, 32))
        // 440 symbols in blocks of at most 128 -> 4 blocks of 110.
        assertEquals(listOf(110, 110, 110, 110), TakFec.partition(440 * 220, 220, 128))
        assertEquals(listOf(65, 64), TakFec.partition(129 * 220, 220, 128))
    }

    // --- fragmenter ---------------------------------------------------------

    @Test
    fun aPayloadThatFitsIsReturnedUnchanged() {
        val inner = payload(TakFragment.DEFAULT_MAX_FRAME_BYTES)
        val frames = assertNotNull(fragmenter().split(inner, protobufSize = 300, msgId = 7))
        assertEquals(1, frames.size)
        assertContentEquals(inner, frames[0])
        assertTrue(!TakFragment.isFragment(frames[0]))
    }

    @Test
    fun oneByteOverAFrameFragmentsWithTwoSourceSlices() {
        val inner = payload(TakFragment.DEFAULT_MAX_FRAME_BYTES + 1)
        val frames = assertNotNull(fragmenter().split(inner, protobufSize = 400, msgId = 0x12345678))
        // K = ceil((226 + 5) / 220) = 2, R = 1.
        assertEquals(3, frames.size)
        val f0 = frames[0]
        assertEquals(0xFE, f0[0].toInt() and 0xFF)
        assertEquals(listOf(0x78, 0x56, 0x34, 0x12), (1..4).map { f0[it].toInt() and 0xFF }, "msg_id, little-endian")
        assertEquals(1, f0[5].toInt() and 0x1F, "K-1")
        assertEquals(0, (f0[5].toInt() and 0xFF) ushr 5, "code")
        assertEquals(listOf(0, 1, 2), frames.map { it[6].toInt() and 0xFF })
        assertTrue(frames.all { it.size <= TakFragment.DEFAULT_MAX_FRAME_BYTES })
        assertEquals(TakFragment.DEFAULT_MAX_FRAME_BYTES, frames[0].size)
        assertEquals(TakFragment.DEFAULT_MAX_FRAME_BYTES, frames[2].size, "repair symbols are a full slice")
    }

    @Test
    fun anOversizedProtobufForcesFragmentingEvenWhenThePayloadFits() {
        val inner = payload(150)
        val frames = assertNotNull(fragmenter().split(inner, protobufSize = TakCompressor.MAX_DECOMPRESSED_SIZE + 1, msgId = 3))
        assertEquals(2, frames.size, "K = 1 plus one repair fragment")
        assertEquals(1, kOf(frames[0]))
        assertContentEquals(inner, deliver(reassembler(), frames).single())
    }

    @Test
    fun aPayloadOverThePolicyCapIsRefused() {
        val inner = payload(4 * 220)
        assertNull(fragmenter(maxSourceFragments = 4).split(inner, protobufSize = 2000, msgId = 1))
        assertNotNull(fragmenter(maxSourceFragments = 5).split(inner, protobufSize = 2000, msgId = 1))
    }

    @Test
    fun theLargestSetFitsTheWireFormat() {
        val s = TakFragment.DEFAULT_MAX_FRAME_BYTES - TakFragment.HEADER_SIZE
        val inner = payload(TakFragment.MAX_SOURCE_FRAGMENTS * s - 5)
        val frames = assertNotNull(fragmenter().split(inner, protobufSize = 9000, msgId = 9))
        assertEquals(TakFragment.MAX_SOURCE_FRAGMENTS + 8, frames.size)
        assertEquals(32, kOf(frames[0]))
        assertNull(fragmenter().split(payload(inner.size + 1), protobufSize = 9000, msgId = 9), "33 slices is over the wire limit")
    }

    @Test
    fun repairIsClampedToTheBurstCap() {
        val inner = payload(20 * 220)
        val frames = assertNotNull(fragmenter(repair = { 100 }).split(inner, protobufSize = 9000, msgId = 2))
        assertEquals(TakFragment.MAX_BURST, frames.size)
    }

    @Test
    fun theMessageIdSourceIsUsedOnlyWhenFragmenting() {
        var calls = 0
        val f =
            TakFragmenter(messageIds = {
                calls++
                -0x21524111 // 0xDEADBEEF
            })
        f.split(payload(100), protobufSize = 100)
        assertEquals(0, calls)
        val frames = assertNotNull(f.split(payload(600), protobufSize = 900))
        assertEquals(1, calls)
        assertEquals(listOf(0xEF, 0xBE, 0xAD, 0xDE), (1..4).map { frames[0][it].toInt() and 0xFF })
    }

    // --- reassembly ---------------------------------------------------------

    @Test
    fun anyKFragmentsInAnyOrderWithDuplicatesReassemble() {
        for (size in listOf(300, 700, 1_200, 2_260, 3_400)) {
            val inner = payload(size)
            val frames = assertNotNull(fragmenter(repair = { 3 }).split(inner, protobufSize = size * 2, msgId = size))
            val k = kOf(frames[0])
            val rnd = Random(size)
            repeat(25) {
                val chosen = frames.shuffled(rnd).take(k)
                val withDupes = (chosen + chosen.take(2)).shuffled(rnd)
                val out = deliver(reassembler(), withDupes)
                assertEquals(1, out.size, "size=$size: exactly one completion")
                assertContentEquals(inner, out[0])
            }
        }
    }

    @Test
    fun aLostLastSliceIsRebuiltFromRepair() {
        val inner = payload(1_000)
        val frames = assertNotNull(fragmenter().split(inner, protobufSize = 2_000, msgId = 5))
        val k = kOf(frames[0])
        val withoutLast = frames.filterIndexed { i, _ -> i != k - 1 }
        assertContentEquals(inner, deliver(reassembler(), withoutLast).single())
    }

    @Test
    fun fewerThanKFragmentsWaitThenExpire() {
        val inner = payload(1_000)
        val frames = assertNotNull(fragmenter().split(inner, protobufSize = 2_000, msgId = 6))
        val k = kOf(frames[0])
        val r = reassembler()
        assertTrue(deliver(r, frames.take(k - 1)).isEmpty())
        assertEquals(1, r.pendingCount)
        r.evictExpired(10L * 60 * 60 * 1000)
        assertEquals(0, r.pendingCount)
    }

    @Test
    fun aCorruptedFragmentIsSkippedOnceAnotherArrives() {
        val inner = payload(900)
        val frames = assertNotNull(fragmenter(repair = { 2 }).split(inner, protobufSize = 2_000, msgId = 8)).toMutableList()
        val corrupted = frames[1].copyOf().also { it[10] = (it[10].toInt() xor 0x5A).toByte() }
        frames[1] = corrupted
        val r = reassembler()
        val k = kOf(frames[0])
        assertTrue(deliver(r, frames.take(k)).isEmpty(), "the CRC rejects the bad set")
        assertContentEquals(inner, deliver(r, frames.drop(k)).single(), "one more fragment lets it leave the bad one out")
    }

    @Test
    fun aDifferentKForTheSameIdRestartsThePartial() {
        val a = assertNotNull(fragmenter().split(payload(1_500, seed = 1), protobufSize = 3_000, msgId = 77))
        val bInner = payload(500, seed = 2)
        val b = assertNotNull(fragmenter().split(bInner, protobufSize = 1_000, msgId = 77))
        val r = reassembler()
        deliver(r, a.take(2))
        assertContentEquals(bInner, deliver(r, b).single())
    }

    @Test
    fun malformedFramesAreDroppedNotThrown() {
        val r = reassembler()
        val good = assertNotNull(fragmenter().split(payload(600), protobufSize = 900, msgId = 4))
        val unknownCode = good[0].copyOf().also { it[HDR_SHAPE] = ((1 shl 5) or (it[HDR_SHAPE].toInt() and 0x1F)).toByte() }
        val bad =
            listOf(
                byteArrayOf(0xFE.toByte()),
                byteArrayOf(0xFE.toByte(), 1, 2, 3, 4, 5, 6),
                unknownCode,
                good[0].copyOf(100), // non-last source slice shorter than the slice size
            )
        bad.forEach { assertNull(r.accept(it, 0L)) }
        val rnd = Random(99)
        repeat(500) {
            val junk = rnd.nextBytes(rnd.nextInt(1, 240)).also { it[0] = 0xFE.toByte() }
            r.accept(junk, 0L)
        }
    }

    @Test
    fun ordinaryFramesAreIgnored() {
        assertNull(reassembler().accept(payload(100), 0L))
    }

    @Test
    fun lateFragmentsOfACompletedMessageDoNotReopenIt() {
        val inner = payload(800)
        val frames = assertNotNull(fragmenter(repair = { 2 }).split(inner, protobufSize = 1_600, msgId = 12))
        val r = reassembler()
        val k = kOf(frames[0])
        assertEquals(1, deliver(r, frames.take(k)).size)
        assertTrue(deliver(r, frames.drop(k)).isEmpty())
        assertEquals(0, r.pendingCount)
    }

    @Test
    fun anExactRedeliveryUnderANewIdIsSuppressed() {
        val inner = payload(800)
        val r = reassembler()
        assertEquals(1, deliver(r, assertNotNull(fragmenter().split(inner, protobufSize = 1_600, msgId = 20))).size)
        assertTrue(deliver(r, assertNotNull(fragmenter().split(inner, protobufSize = 1_600, msgId = 21))).isEmpty())
        // Different bytes under a new ID are delivered.
        assertEquals(1, deliver(r, assertNotNull(fragmenter().split(payload(800, seed = 5), protobufSize = 1_600, msgId = 22))).size)
    }

    @Test
    fun thePendingCapEvictsTheOldestPartial() {
        val r = TakReassembler(frameAirtimeMillis = airtime, maxPending = 2)
        val sets = (0 until 3).map { id -> assertNotNull(fragmenter().split(payload(1_000, seed = id), protobufSize = 2_000, msgId = id)) }
        sets.forEachIndexed { id, frames -> r.accept(frames[0], id.toLong()) }
        assertEquals(2, r.pendingCount)
        // The oldest (ID 0) was evicted: its remaining fragments start over and fall short.
        assertTrue(deliver(r, sets[0].drop(1).take(kOf(sets[0][0]) - 1), now = 3L).isEmpty())
        // ID 2 is still held and completes.
        assertEquals(1, deliver(r, sets[2].drop(1), now = 3L).size)
    }

    @Test
    fun theIdleDeadlineMovesWithEachNewFragment() {
        // Small enough (K = 3) that the spread-out set still lands inside the absolute ceiling.
        val inner = payload(600)
        val frames = assertNotNull(fragmenter().split(inner, protobufSize = 1_200, msgId = 30))
        val k = kOf(frames[0])
        val r = reassembler()
        val idle = 5 * airtime * 3 + 30_000
        var now = 0L
        // Each fragment lands just inside the idle budget of the previous one.
        val out = frames.take(k).mapNotNull { f -> r.accept(f, now).also { now += idle - 1 } }
        assertContentEquals(inner, out.single())
    }

    @Test
    fun interleavedMessagesAreIndependent() {
        val aInner = payload(1_400, seed = 10)
        val bInner = payload(1_100, seed = 11)
        val a = assertNotNull(fragmenter(repair = { 0 }).split(aInner, protobufSize = 2_000, msgId = 0x0A0A0A0A))
        val b = assertNotNull(fragmenter(repair = { 0 }).split(bInner, protobufSize = 2_000, msgId = 0x0B0B0B0B))
        val r = reassembler()
        val out = mutableListOf<ByteArray>()
        // Interleave, and lose one of A's fragments: A never completes, B is untouched.
        for (i in 0 until maxOf(a.size, b.size)) {
            if (i < a.size && i != 2) r.accept(a[i], i.toLong())?.let(out::add)
            if (i < b.size) r.accept(b[i], i.toLong())?.let(out::add)
        }
        assertEquals(1, out.size)
        assertContentEquals(bInner, out[0])
    }

    private fun forEachSubset(
        n: Int,
        k: Int,
        block: (List<Int>) -> Unit,
    ) {
        val idx = IntArray(k) { it }
        while (true) {
            block(idx.toList())
            var i = k - 1
            while (i >= 0 && idx[i] == n - k + i) i--
            if (i < 0) return
            idx[i]++
            for (j in i + 1 until k) idx[j] = idx[j - 1] + 1
        }
    }
}
