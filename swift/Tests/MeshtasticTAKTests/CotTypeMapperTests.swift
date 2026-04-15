import XCTest
@testable import MeshtasticTAK

final class CotTypeMapperTests: XCTestCase {

    func testKnownTypesMapCorrectly() {
        XCTAssertEqual(CotTypeMapper.typeToEnum("a-f-G-U-C"), .aFGUC)
        XCTAssertEqual(CotTypeMapper.typeToEnum("a-n-A-C-F"), .aNACF)
        XCTAssertEqual(CotTypeMapper.typeToEnum("t-x-d-d"), .tXDD)
        XCTAssertEqual(CotTypeMapper.typeToEnum("b-t-f"), .bTF)
        XCTAssertEqual(CotTypeMapper.typeToEnum("b-r-f-h-c"), .bRFHC)
        XCTAssertEqual(CotTypeMapper.typeToEnum("b-a-o-opn"), .bAOOpn)
        XCTAssertEqual(CotTypeMapper.typeToEnum("u-d-f"), .uDF)
    }

    func testUnknownTypeReturnsOther() {
        XCTAssertEqual(CotTypeMapper.typeToEnum("z-unknown-type"), .other)
        XCTAssertEqual(CotTypeMapper.typeToEnum(""), .other)
    }

    func testOtherReturnsNilString() {
        XCTAssertNil(CotTypeMapper.typeToString(.other))
    }

    func testTypeRoundTripExhaustive() {
        // Test all 46 known enum values round-trip through string
        for rawValue in 1...75 {
            guard let cotType = CotType(rawValue: rawValue) else { continue }
            if let str = CotTypeMapper.typeToString(cotType) {
                XCTAssertEqual(CotTypeMapper.typeToEnum(str), cotType,
                    "Round-trip failed for rawValue \(rawValue) -> \(str)")
            }
        }
    }

    func testAircraftClassification() {
        XCTAssertTrue(CotTypeMapper.isAircraft(.aNACF))
        XCTAssertTrue(CotTypeMapper.isAircraft(.aFAMH))
        XCTAssertTrue(CotTypeMapper.isAircraft(.aHAMFF))
        XCTAssertTrue(CotTypeMapper.isAircraft(.aHA))
        XCTAssertTrue(CotTypeMapper.isAircraft(.aUA))

        XCTAssertFalse(CotTypeMapper.isAircraft(.aFGUC))
        XCTAssertFalse(CotTypeMapper.isAircraft(.tXDD))
        XCTAssertFalse(CotTypeMapper.isAircraft(.bTF))
        XCTAssertFalse(CotTypeMapper.isAircraft(.aFS))
        XCTAssertFalse(CotTypeMapper.isAircraft(.aFG))
    }

    func testIsAircraftString() {
        XCTAssertTrue(CotTypeMapper.isAircraftString("a-f-A-M-H"))
        XCTAssertTrue(CotTypeMapper.isAircraftString("a-n-A-C-F"))
        XCTAssertTrue(CotTypeMapper.isAircraftString("a-h-A-M-F-F"))

        XCTAssertFalse(CotTypeMapper.isAircraftString("a-f-G-U-C"))
        XCTAssertFalse(CotTypeMapper.isAircraftString("b-t-f"))
        XCTAssertFalse(CotTypeMapper.isAircraftString("t-x-d-d"))
        XCTAssertFalse(CotTypeMapper.isAircraftString("a-f-S"))
    }

    func testHowMapping() {
        XCTAssertEqual(CotTypeMapper.howToEnum("h-e"), .hE)
        XCTAssertEqual(CotTypeMapper.howToEnum("m-g"), .mG)
        XCTAssertEqual(CotTypeMapper.howToEnum("h-g-i-g-o"), .hGIGO)
        XCTAssertEqual(CotTypeMapper.howToEnum("m-r"), .mR)
        XCTAssertEqual(CotTypeMapper.howToEnum("unknown"), .unspecified)
    }

    func testHowRoundTrip() {
        for rawValue in 1...7 {
            guard let how = CotHow(rawValue: rawValue) else { continue }
            if let str = CotTypeMapper.howToString(how) {
                XCTAssertEqual(CotTypeMapper.howToEnum(str), how,
                    "How round-trip failed for rawValue \(rawValue) -> \(str)")
            }
        }
    }
}
