import XCTest
@testable import MeshtasticTAK

final class CompatibilityTests: XCTestCase {

    let parser = CotXmlParser()
    let compressor = TakCompressor()

    func loadFixtureXml(_ name: String) throws -> String {
        let url = Bundle.module.url(forResource: "Fixtures/cot_xml/\(name)", withExtension: "xml")!
        return try String(contentsOf: url, encoding: .utf8)
    }

    func loadGolden(_ name: String) -> Data? {
        guard let url = Bundle.module.url(forResource: "Fixtures/golden/\(name)", withExtension: "bin") else { return nil }
        return try? Data(contentsOf: url)
    }

    // MARK: - Golden file decompression tests

    func assertGoldenDecompresses(_ name: String, file: StaticString = #file, line: UInt = #line) throws {
        guard let golden = loadGolden(name) else { return }
        let packet = try compressor.decompress(golden)
        XCTAssertFalse(packet.uid.isEmpty, "\(name): decompressed packet should have a UID", file: file, line: line)
    }

    func testGolden_pliBasic() throws { try assertGoldenDecompresses("pli_basic") }
    func testGolden_pliFull() throws { try assertGoldenDecompresses("pli_full") }
    func testGolden_pliWebtak() throws { try assertGoldenDecompresses("pli_webtak") }
    func testGolden_geochatSimple() throws { try assertGoldenDecompresses("geochat_simple") }
    func testGolden_aircraftAdsb() throws { try assertGoldenDecompresses("aircraft_adsb") }
    func testGolden_aircraftHostile() throws { try assertGoldenDecompresses("aircraft_hostile") }
    func testGolden_deleteEvent() throws { try assertGoldenDecompresses("delete_event") }
    func testGolden_casevac() throws { try assertGoldenDecompresses("casevac") }
    func testGolden_alertTic() throws { try assertGoldenDecompresses("alert_tic") }

    // MARK: - Size similarity tests

    func assertSizeSimilar(_ name: String, file: StaticString = #file, line: UInt = #line) throws {
        guard let golden = loadGolden(name) else { return }
        let xml = try loadFixtureXml(name)
        let packet = parser.parse(xml)
        let wirePayload = try compressor.compress(packet)
        let ratio = Double(wirePayload.count) / Double(golden.count)
        XCTAssertTrue(ratio > 0.5 && ratio < 2.0,
            "\(name): compressed size \(wirePayload.count)B differs significantly from golden \(golden.count)B",
            file: file, line: line)
    }

    func testSizeSimilar_pliBasic() throws { try assertSizeSimilar("pli_basic") }
    func testSizeSimilar_pliFull() throws { try assertSizeSimilar("pli_full") }
    func testSizeSimilar_pliWebtak() throws { try assertSizeSimilar("pli_webtak") }
    func testSizeSimilar_geochatSimple() throws { try assertSizeSimilar("geochat_simple") }
    func testSizeSimilar_aircraftAdsb() throws { try assertSizeSimilar("aircraft_adsb") }
    func testSizeSimilar_aircraftHostile() throws { try assertSizeSimilar("aircraft_hostile") }
    func testSizeSimilar_deleteEvent() throws { try assertSizeSimilar("delete_event") }
    func testSizeSimilar_casevac() throws { try assertSizeSimilar("casevac") }
    func testSizeSimilar_alertTic() throws { try assertSizeSimilar("alert_tic") }
}
