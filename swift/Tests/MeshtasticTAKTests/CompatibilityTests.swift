import Testing
@testable import MeshtasticTAK

// MARK: - Parameterized cross-platform compatibility (Swift Testing)
//
// Uses `@Test(arguments:)` so each fixture gets its own individually-counted
// test case and the Swift total reported by `./build.sh test-swift` scales
// with the fixture count, matching what Kotlin/Python/C#/TypeScript report.
//
// Goldens are byte arrays written by Kotlin's `CompressionTest` — the
// canonical cross-platform fixture generator. Swift decompresses them here
// to prove interoperability.

@Suite("Compatibility")
struct CompatibilitySuite {

    let parser = CotXmlParser()
    let compressor = TakCompressor()

    /// For every XML fixture with a matching golden file, decompress the
    /// golden and verify a non-empty UID is recovered. Fixtures without a
    /// golden file are skipped silently (they haven't been regenerated yet).
    @Test("golden file decompresses to valid packet", arguments: TestFixtures.fixtureNames)
    func goldenDecompressesToValidPacket(_ name: String) throws {
        guard let golden = TestFixtures.loadGolden(name) else { return }
        let packet = try compressor.decompress(golden)
        #expect(!packet.uid.isEmpty, "\(name): decompressed packet should have a UID")
    }

    /// For every XML fixture with a matching golden, parse the XML, compress
    /// it here, and check our output size is within 2× of the golden. Wide
    /// tolerance is intentional: protobuf serializers across platforms may
    /// emit fields in different orders, so exact byte match is not guaranteed
    /// — the key invariant is that ANY platform's golden can be decompressed
    /// by any other.
    @Test("compressed size similar to golden", arguments: TestFixtures.fixtureNames)
    func compressedSizeSimilarToGolden(_ name: String) throws {
        guard let golden = TestFixtures.loadGolden(name) else { return }
        let xml = try TestFixtures.loadFixture(name)
        let packet = parser.parse(xml)
        let wirePayload = try compressor.compress(packet)
        let ratio = Double(wirePayload.count) / Double(golden.count)
        #expect(
            ratio > 0.5 && ratio < 2.0,
            "\(name): compressed size \(wirePayload.count)B differs significantly from golden \(golden.count)B"
        )
    }
}
