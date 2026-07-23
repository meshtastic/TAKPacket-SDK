package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Cross-platform mirror of the JVM [ResilienceTest] (`kotlin.test`, every target).
 *
 * Locks the LoRa-resilience invariant on every platform's codec: each packet is
 * fully, independently decodable from its own bytes plus the static shipped
 * dictionary, with ZERO cross-packet state. LoRa is lossy — losing any packet
 * must never affect the decode of any other.
 *
 * The DECODE invariants use the inlined golden wire frames, and the COMPRESS
 * determinism invariant runs unconditionally: as of v0.6.0 the pure-Kotlin codec
 * compresses AND decompresses on EVERY target, so both halves run everywhere.
 */
class ResilienceCommonTest {
    private val parser = CotXmlParser()
    private val compressor = TakCompressor()

    /** name -> golden wire frame, every fixture (decode works on all targets). */
    private fun wireFrames(): List<Pair<String, ByteArray>> = InlinedFixtures.names.map { name -> name to InlinedFixtures.goldenWire.getValue(name) }

    private fun serialize(p: TakPacketV2Data): List<Byte> = TakPacketV2Serializer.serialize(p).toList()

    @Test
    fun eachPacketDecodesIdenticallyRegardlessOfOrder() {
        val wire = wireFrames()

        val forward = wire.map { (name, w) -> name to serialize(compressor.decompress(w)) }
        // Decode the same payloads in REVERSE order — order must not matter.
        val reverse =
            wire
                .reversed()
                .map { (name, w) -> name to serialize(compressor.decompress(w)) }
                .reversed()

        for (i in forward.indices) {
            val (name, f) = forward[i]
            val (_, r) = reverse[i]
            assertEquals(f, r, "Packet $name decoded differently in reverse order — cross-packet state leak")
        }
    }

    @Test
    fun anySinglePacketDecodesWithAllOthersDropped() {
        val wire = wireFrames()
        // Baseline: decode each in sequence.
        val inSequence = wire.associate { (name, w) -> name to serialize(compressor.decompress(w)) }
        // Simulate a lossy mesh: decode each ALONE on a brand-new compressor
        // (every other packet "lost"). Must match the in-sequence decode.
        for ((name, w) in wire) {
            val cold = TakCompressor()
            val isolated = serialize(cold.decompress(w))
            assertEquals(inSequence[name], isolated, "Packet $name failed to decode in isolation")
        }
    }

    @Test
    fun aColdCompressorDecodesAnyPacket() {
        // A freshly-constructed decompressor — never having seen any prior packet
        // — must decode any single frame. Proves no warm-up / history state.
        for ((name, w) in wireFrames()) {
            val cold = TakCompressor()
            val pkt = cold.decompress(w)
            assertNotEquals("", pkt.uid, "Cold compressor failed to decode $name")
        }
    }

    @Test
    fun releasingCodecResourcesIsSafeAndCachesRebuild() {
        // Decode a frame to warm the codec caches, drop them via the public
        // TakPacketSdk.releaseCodecResources(), then decode again: release must be
        // safe to call and the caches must transparently rebuild (the resources
        // are an optional optimization, not required state). On web/wasi release is
        // a no-op, but the decode-after-release invariant still holds.
        val (name, frame) = wireFrames().first()
        val before = serialize(compressor.decompress(frame))

        TakPacketSdk.releaseCodecResources()

        val afterRelease = serialize(TakCompressor().decompress(frame))
        assertEquals(before, afterRelease, "decode of $name changed after releaseCodecResources()")

        // Calling release with nothing cached must also be harmless.
        TakPacketSdk.releaseCodecResources()
        TakPacketSdk.releaseCodecResources()
    }

    @Test
    fun reEncodingOnAFreshCompressorIsByteIdentical() {
        // Determinism: the same packet compressed by independent instances must
        // yield identical wire bytes (no instance-accumulated state).
        for (name in InlinedFixtures.names) {
            val pkt = parser.parse(InlinedFixtures.xml.getValue(name))
            val a = TakCompressor().compress(pkt)
            val b = TakCompressor().compress(pkt)
            assertTrue(a.contentEquals(b), "Non-deterministic compression for $name — possible state leak")
        }
    }
}
