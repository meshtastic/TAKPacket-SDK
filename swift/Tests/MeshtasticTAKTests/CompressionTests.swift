import XCTest
@testable import MeshtasticTAK

final class CompressionTests: XCTestCase {

    static let LORA_MTU = 237

    let parser = CotXmlParser()
    let compressor = TakCompressor()

    func loadFixture(_ name: String) throws -> String {
        let url = Bundle.module.url(forResource: "Fixtures/cot_xml/\(name)", withExtension: "xml")!
        return try String(contentsOf: url, encoding: .utf8)
    }

    func assertFitsUnderMtu(_ fixture: String, file: StaticString = #file, line: UInt = #line) throws {
        let xml = try loadFixture(fixture)
        let packet = parser.parse(xml)
        let result = try compressor.compressWithStats(packet)
        XCTAssertLessThanOrEqual(result.compressedSize, Self.LORA_MTU,
            "\(fixture): \(result.compressedSize)B exceeds LoRa MTU \(Self.LORA_MTU)B", file: file, line: line)
    }

    // Per-fixture MTU tests
    func testMtu_pliBasic() throws { try assertFitsUnderMtu("pli_basic") }
    func testMtu_pliFull() throws { try assertFitsUnderMtu("pli_full") }
    func testMtu_pliWebtak() throws { try assertFitsUnderMtu("pli_webtak") }
    func testMtu_geochatSimple() throws { try assertFitsUnderMtu("geochat_simple") }
    func testMtu_aircraftAdsb() throws { try assertFitsUnderMtu("aircraft_adsb") }
    func testMtu_aircraftHostile() throws { try assertFitsUnderMtu("aircraft_hostile") }
    func testMtu_deleteEvent() throws { try assertFitsUnderMtu("delete_event") }
    func testMtu_casevac() throws { try assertFitsUnderMtu("casevac") }
    func testMtu_alertTic() throws { try assertFitsUnderMtu("alert_tic") }

    func testMeaningfulCompressionRatio() throws {
        let fixtures = ["pli_basic", "pli_full", "pli_webtak", "geochat_simple",
                        "aircraft_adsb", "aircraft_hostile", "delete_event", "casevac", "alert_tic"]
        var totalXml = 0
        var totalCompressed = 0
        for fixture in fixtures {
            let xml = try loadFixture(fixture)
            let packet = parser.parse(xml)
            let result = try compressor.compressWithStats(packet)
            totalXml += xml.count
            totalCompressed += result.compressedSize
        }
        let ratio = Double(totalXml) / Double(totalCompressed)
        XCTAssertGreaterThanOrEqual(ratio, 3.0,
            "Average compression ratio \(String(format: "%.1f", ratio))x below 3x minimum")
    }
}
