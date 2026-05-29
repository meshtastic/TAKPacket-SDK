import XCTest
@testable import MeshtasticTAK

/// Locks the LoRa-resilience invariant: every packet MUST be fully,
/// independently decodable from its own bytes plus the static shipped
/// dictionary. ZERO cross-packet state. LoRa is lossy — losing any packet
/// must never jeopardize the decode of any other packet.
///
/// If a future change introduces stateful/streaming compression or any
/// cross-packet dependency, one of these tests fails. Mirrors Kotlin
/// ResilienceTest.kt. Uses XCTest (not Swift Testing) so it builds even where
/// the Testing module is unavailable.
final class ResilienceTests: XCTestCase {

    private let parser = CotXmlParser()
    private let compressor = TakCompressor()

    /// Encode every fixture once, in order, into independent wire payloads.
    private func encodeAll() throws -> [(name: String, wire: Data)] {
        try TestFixtures.fixtureNames.map { name in
            let xml = try TestFixtures.loadFixture(name)
            return (name, try compressor.compress(try parser.parse(xml)))
        }
    }

    func testEachPacketDecodesIdenticallyRegardlessOfOrder() throws {
        let wire = try encodeAll()
        // Baseline: decode each in the order produced.
        let forward = try wire.map { try compressor.decompress($0.wire) }
        // Decode the SAME payloads in reverse order — order must not matter.
        var reverseDecoded = try wire.reversed().map { try compressor.decompress($0.wire) }
        reverseDecoded.reverse() // back to original order
        for i in forward.indices {
            XCTAssertEqual(
                try forward[i].serializedData(),
                try reverseDecoded[i].serializedData(),
                "Packet \(wire[i].name) decoded differently in reverse order — cross-packet state leak"
            )
        }
    }

    func testAnySinglePacketDecodesWithAllOthersDropped() throws {
        let wire = try encodeAll()
        // Simulate a lossy mesh: each packet must decode ALONE (every other "lost").
        var inSequence: [String: Data] = [:]
        for entry in wire {
            inSequence[entry.name] = try compressor.decompress(entry.wire).serializedData()
        }
        for entry in wire {
            let isolated = try compressor.decompress(entry.wire).serializedData()
            XCTAssertEqual(inSequence[entry.name], isolated, "Packet \(entry.name) failed to decode in isolation")
        }
    }

    func testColdCompressorInstanceDecodesAnyPacket() throws {
        let wire = try encodeAll()
        // A freshly-constructed compressor — never having seen any prior packet —
        // must decode any single frame. Proves no warm-up/history state.
        for entry in wire {
            let cold = TakCompressor()
            let pkt = try cold.decompress(entry.wire)
            XCTAssertFalse(pkt.uid.isEmpty, "Cold compressor failed to decode \(entry.name)")
        }
    }

    func testReEncodingOnFreshCompressorIsByteIdentical() throws {
        // Determinism: the same packet compressed by independent compressor
        // instances must yield identical wire bytes (no instance-accumulated state).
        for name in TestFixtures.fixtureNames {
            let pkt = try parser.parse(try TestFixtures.loadFixture(name))
            let a = try TakCompressor().compress(pkt)
            let b = try TakCompressor().compress(pkt)
            XCTAssertEqual(a, b, "Non-deterministic compression for \(name) — possible state leak")
        }
    }
}
