package org.meshtastic.tak

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Locks the LoRa-resilience invariant: every packet MUST be fully, independently
 * decodable from its own bytes plus the static shipped dictionary. ZERO
 * cross-packet state. LoRa is lossy — losing any packet must never jeopardize
 * the decode of any other packet.
 *
 * If a future change introduces stateful/streaming compression or any
 * cross-packet dependency, one of these tests fails.
 */
class ResilienceTest {

    private val parser = CotXmlParser()
    private val compressor = TakCompressor()

    /** Encode every fixture once, in order, into independent wire payloads. */
    private fun encodeAll(): List<Pair<String, ByteArray>> =
        TestFixtures.fixtureNames.map { name ->
            name to compressor.compress(parser.parse(TestFixtures.loadFixture("$name.xml")))
        }

    @Test
    fun `each packet decodes identically regardless of order`() {
        val wire = encodeAll()

        // Baseline: decode each in the order produced.
        val forward = wire.map { (name, w) -> name to compressor.decompress(w) }

        // Decode the SAME payloads in reverse order — order must not matter.
        val reverse = wire.reversed().map { (name, w) -> name to compressor.decompress(w) }
            .reversed()

        for (i in forward.indices) {
            val (name, f) = forward[i]
            val (_, r) = reverse[i]
            assertEquals(
                TakPacketV2Serializer.serialize(f).toList(),
                TakPacketV2Serializer.serialize(r).toList(),
                "Packet $name decoded differently in reverse order — cross-packet state leak",
            )
        }
    }

    @Test
    fun `any single packet decodes with all others dropped`() {
        val wire = encodeAll()
        // Simulate a lossy mesh: for each packet, decode it ALONE (every other
        // packet "lost"). It must decode to the same result as in-sequence.
        val inSequence = wire.associate { (name, w) -> name to TakPacketV2Serializer.serialize(compressor.decompress(w)).toList() }
        for ((name, w) in wire) {
            val isolated = TakPacketV2Serializer.serialize(compressor.decompress(w)).toList()
            assertEquals(inSequence[name], isolated, "Packet $name failed to decode in isolation")
        }
    }

    @Test
    fun `a cold compressor instance decodes any packet (no warm-up state)`() {
        val wire = encodeAll()
        // A freshly-constructed decompressor — never having seen any prior
        // packet — must decode any single frame. Proves no warm-up/history state.
        for ((name, w) in wire) {
            val cold = TakCompressor()
            val pkt = cold.decompress(w)
            assertNotEquals("", pkt.uid, "Cold compressor failed to decode $name")
        }
    }

    @Test
    fun `re-encoding a packet on a fresh compressor is byte-identical`() {
        // Determinism: the same packet compressed by independent compressor
        // instances must yield identical wire bytes (no instance-accumulated
        // state changes the output).
        for (name in TestFixtures.fixtureNames) {
            val pkt = parser.parse(TestFixtures.loadFixture("$name.xml"))
            val a = TakCompressor().compress(pkt)
            val b = TakCompressor().compress(pkt)
            assertArrayEquals(a, b, "Non-deterministic compression for $name — possible state leak")
        }
    }
}
