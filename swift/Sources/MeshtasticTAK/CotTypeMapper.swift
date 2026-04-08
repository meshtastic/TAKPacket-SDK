import Foundation

/// Maps CoT type strings to/from CotType enum values and classifies aircraft types.
public enum CotTypeMapper {

    private static let stringToType: [String: CotType] = [
        "a-f-G-U-C": .aFGUC,
        "a-f-G-U-C-I": .aFGUCI,
        "a-n-A-C-F": .aNACF,
        "a-n-A-C-H": .aNACH,
        "a-n-A-C": .aNAC,
        "a-f-A-M-H": .aFAMH,
        "a-f-A-M": .aFAM,
        "a-f-A-M-F-F": .aFAMFF,
        "a-f-A-M-H-A": .aFAMHA,
        "a-f-A-M-H-U-M": .aFAMHUM,
        "a-h-A-M-F-F": .aHAMFF,
        "a-h-A-M-H-A": .aHAMHA,
        "a-u-A-C": .aUAC,
        "t-x-d-d": .tXDD,
        "a-f-G-E-S-E": .aFGESE,
        "a-f-G-E-V-C": .aFGEVC,
        "a-f-S": .aFS,
        "a-f-A-M-F": .aFAMF,
        "a-f-A-M-F-C-H": .aFAMFCH,
        "a-f-A-M-F-U-L": .aFAMFUL,
        "a-f-A-M-F-L": .aFAMFL,
        "a-f-A-M-F-P": .aFAMFP,
        "a-f-A-C-H": .aFACH,
        "a-n-A-M-F-Q": .aNAMFQ,
        "b-t-f": .bTF,
        "b-r-f-h-c": .bRFHC,
        "b-a-o-pan": .bAOPan,
        "b-a-o-opn": .bAOOpn,
        "b-a-o-can": .bAOCan,
        "b-a-o-tbl": .bAOTbl,
        "b-a-g": .bAG,
        "a-f-G": .aFG,
        "a-f-G-U": .aFGU,
        "a-h-G": .aHG,
        "a-u-G": .aUG,
        "a-n-G": .aNG,
        "b-m-r": .bMR,
        "b-m-p-w": .bMPW,
        "b-m-p-s-p-i": .bMPSPI,
        "u-d-f": .uDF,
        "u-d-r": .uDR,
        "u-d-c-c": .uDCC,
        "u-rb-a": .uRbA,
        "a-h-A": .aHA,
        "a-u-A": .aUA,
        "a-f-A-M-H-Q": .aFAMHQ,
        "a-f-A-C-F": .aFACF, "a-f-A-C": .aFAC, "a-f-A-C-L": .aFACL, "a-f-A": .aFA,
        "a-f-A-M-H-C": .aFAMHC, "a-n-A-M-F-F": .aNAMFF, "a-u-A-C-F": .aUACF,
        "a-f-G-U-C-F-T-A": .aFGUCFTA, "a-f-G-U-C-V-S": .aFGUCVS,
        "a-f-G-U-C-R-X": .aFGUCRX, "a-f-G-U-C-I-Z": .aFGUCIZ,
        "a-f-G-U-C-E-C-W": .aFGUCECW, "a-f-G-U-C-I-L": .aFGUCIL,
        "a-f-G-U-C-R-O": .aFGUCRO, "a-f-G-U-C-R-V": .aFGUCRV,
        "a-f-G-U-H": .aFGUH, "a-f-G-U-U-M-S-E": .aFGUUMSE,
        "a-f-G-U-S-M-C": .aFGUSMC, "a-f-G-E-S": .aFGES, "a-f-G-E": .aFGE,
        "a-f-G-E-V-C-U": .aFGEVCU, "a-f-G-E-V-C-ps": .aFGEVCPs,
        "a-u-G-E-V": .aUGEV, "a-f-S-N-N-R": .aFSNNR, "a-f-F-B": .aFFB,
        "b-m-p-s-p-loc": .bMPSPLoc, "b-i-v": .bIV, "b-f-t-r": .bFTR, "b-f-t-a": .bFTA,
    ]

    private static let typeToStr: [CotType: String] = {
        var result: [CotType: String] = [:]
        for (str, type) in stringToType { result[type] = str }
        return result
    }()

    private static let stringToHow: [String: CotHow] = [
        "h-e": .hE,
        "m-g": .mG,
        "h-g-i-g-o": .hGIGO,
        "m-r": .mR,
        "m-f": .mF,
        "m-p": .mP,
        "m-s": .mS,
    ]

    private static let howToStr: [CotHow: String] = {
        var result: [CotHow: String] = [:]
        for (str, how) in stringToHow { result[how] = str }
        return result
    }()

    public static func typeToEnum(_ str: String) -> CotType {
        stringToType[str] ?? .other
    }

    public static func typeToString(_ type: CotType) -> String? {
        typeToStr[type]
    }

    public static func howToEnum(_ str: String) -> CotHow {
        stringToHow[str] ?? .unspecified
    }

    public static func howToString(_ how: CotHow) -> String? {
        howToStr[how]
    }

    public static func isAircraft(_ type: CotType) -> Bool {
        guard let str = typeToString(type) else { return false }
        return isAircraftString(str)
    }

    public static func isAircraftString(_ str: String) -> Bool {
        let atoms = str.split(separator: "-")
        return atoms.count >= 3 && atoms[2] == "A"
    }
}
