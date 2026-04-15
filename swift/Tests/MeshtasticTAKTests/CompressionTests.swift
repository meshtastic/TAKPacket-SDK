import XCTest
import Testing
@testable import MeshtasticTAK

// MARK: - Parameterized MTU test (Swift Testing)
//
// Uses `@Test(arguments:)` so each fixture is reported as its own test case.
// See RoundTripTests.swift for the rationale — XCTest can't count fixtures
// individually inside a loop, which was producing a misleadingly low total.

@Suite("Compression")
struct CompressionSuite {

    static let LORA_MTU = 237

    let parser = CotXmlParser()
    let compressor = TakCompressor()

    @Test("compressed payload fits under LoRa MTU", arguments: TestFixtures.fixtureNames)
    func fitsUnderMtu(_ fixture: String) throws {
        let xml = try TestFixtures.loadFixture(fixture)
        let packet = parser.parse(xml)
        let result = try compressor.compressWithStats(packet)
        // Swift Testing's #expect comment must be a string literal, not a
        // concatenated expression — use a single interpolated literal.
        #expect(
            result.compressedSize <= Self.LORA_MTU,
            "\(fixture): \(result.compressedSize)B exceeds MTU \(Self.LORA_MTU)B (XML \(xml.count)B, proto \(result.protobufSize)B)"
        )
    }
}

// MARK: - Aggregate compression-ratio test (XCTest)

final class CompressionTests: XCTestCase {

    let parser = CotXmlParser()
    let compressor = TakCompressor()

    func testMeaningfulCompressionRatio() throws {
        var totalXml = 0
        var totalCompressed = 0
        for fixture in TestFixtures.fixtureNames {
            let xml = try TestFixtures.loadFixture(fixture)
            let packet = parser.parse(xml)
            let result = try compressor.compressWithStats(packet)
            totalXml += xml.count
            totalCompressed += result.compressedSize
        }
        let ratio = Double(totalXml) / Double(totalCompressed)
        XCTAssertGreaterThanOrEqual(
            ratio, 3.0,
            "Average compression ratio \(String(format: "%.1f", ratio))x below 3x minimum"
        )
    }
}
