import XCTest
@testable import MeshtasticTAK

final class MalformedInputTests: XCTestCase {

    let compressor = TakCompressor()

    func loadMalformed(_ name: String) -> Data {
        (try? TestFixtures.loadMalformed(name)) ?? Data()
    }

    func testRejectsEmptyPayload() {
        XCTAssertThrowsError(try compressor.decompress(Data()))
    }

    func testRejectsSingleByte() {
        XCTAssertThrowsError(try compressor.decompress(Data([0x00])))
    }

    func testRejectsInvalidDictId() {
        XCTAssertThrowsError(try compressor.decompress(loadMalformed("invalid_dict_id.bin")))
    }

    func testRejectsTruncatedZstd() {
        XCTAssertThrowsError(try compressor.decompress(loadMalformed("truncated_zstd.bin")))
    }

    func testRejectsCorruptedZstd() {
        XCTAssertThrowsError(try compressor.decompress(loadMalformed("corrupted_zstd.bin")))
    }

    func testHandlesInvalidProtobufWithoutCrash() {
        // 0xFF + garbage bytes — no crash is the key assertion
        do {
            _ = try compressor.decompress(loadMalformed("invalid_protobuf.bin"))
        } catch {
            // Expected — either outcome is acceptable
        }
    }

    func testIgnoresReservedBitsInFlagsByte() throws {
        // 0xC0 has reserved bits set but dict ID = 0 (0xC0 & 0x3F = 0)
        let packet = try compressor.decompress(loadMalformed("reserved_bits_set.bin"))
        XCTAssertFalse(packet.uid.isEmpty, "Should decompress despite reserved bits")
    }

    // Security attack tests

    func testRejectsXmlWithDoctype() throws {
        let xml = try TestFixtures.loadMalformedXml("xml_doctype.xml")
        let parser = CotXmlParser()
        let result = parser.parse(xml)
        // Parser should return empty packet (rejected DOCTYPE)
        XCTAssertTrue(result.uid.isEmpty, "DOCTYPE XML should produce empty/rejected result")
    }

    func testRejectsXmlWithEntityExpansion() throws {
        let xml = try TestFixtures.loadMalformedXml("xml_entity_expansion.xml")
        let parser = CotXmlParser()
        let result = parser.parse(xml)
        XCTAssertTrue(result.uid.isEmpty, "Entity expansion XML should produce empty/rejected result")
    }

    func testRejectsOversizedFields() {
        XCTAssertThrowsError(try compressor.decompress(loadMalformed("oversized_callsign.bin")))
    }

    func testRejectsDecompressionBomb() {
        XCTAssertThrowsError(try compressor.decompress(loadMalformed("decompression_bomb.bin")))
    }
}
