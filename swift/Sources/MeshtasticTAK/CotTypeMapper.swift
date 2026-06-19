import Foundation

/// Maps CoT type strings to/from `CotType` enum values and classifies aircraft types.
///
/// ## Forward-compatibility contract
///
/// When a CoT type string is not in the known mapping — either because it's
/// new (a v2.1 peer added a type the v2 receiver doesn't know) or because
/// it's legitimately niche — `typeToEnum` returns ``CotType/other`` (0) and
/// the caller populates `cot_type_str` (field 23) with the full original
/// string. On the wire, the combination `cot_type_id = 0` + `cot_type_str = "…"`
/// is the canonical way to carry unknown types without losing information:
/// the reconstructed CoT XML uses `cot_type_str` directly, so
/// `<event type="…">` comes back byte-identical regardless of whether the
/// receiver's enum knows the value.
///
/// Receivers that want to detect the downgrade should check
/// `cot_type_id == .other && !cot_type_str.isEmpty`.
///
/// ## Topics
/// ### CoT type mapping
/// - ``typeToEnum(_:)``
/// - ``typeToString(_:)``
/// ### CoT `how` mapping
/// - ``howToEnum(_:)``
/// - ``howToString(_:)``
/// ### Aircraft classification
/// - ``isAircraft(_:)``
/// - ``isAircraftString(_:)``
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
        // Typed geometry additions (v2 protocol extension)
        "u-d-f-m": .uDFM,
        "u-d-p": .uDP,
        "b-m-p-s-m": .bMPSM,
        "b-m-p-c": .bMPC,
        "u-r-b-c-c": .uRBCC,
        "u-r-b-bullseye": .uRBBullseye,
        // Expanded coverage (values 82-124)
        "a-f-G-E-V-A": .aFGEVA,
        "a-n-A": .aNA,
        "a-u-G-U-C-F": .aUGUCF,
        "a-n-G-U-C-F": .aNGUCF,
        "a-h-G-U-C-F": .aHGUCF,
        "a-f-G-U-C-F": .aFGUCF,
        "a-u-G-I": .aUGI,
        "a-n-G-I": .aNGI,
        "a-h-G-I": .aHGI,
        "a-f-G-I": .aFGI,
        "a-u-G-E-X-M": .aUGEXM,
        "a-n-G-E-X-M": .aNGEXM,
        "a-h-G-E-X-M": .aHGEXM,
        "a-f-G-E-X-M": .aFGEXM,
        "a-u-S": .aUS,
        "a-n-S": .aNS,
        "a-h-S": .aHS,
        "a-u-G-U-C-I-d": .aUGUCID,
        "a-n-G-U-C-I-d": .aNGUCID,
        "a-h-G-U-C-I-d": .aHGUCID,
        "a-f-G-U-C-I-d": .aFGUCID,
        "a-u-G-E-V-A-T": .aUGEVAT,
        "a-n-G-E-V-A-T": .aNGEVAT,
        "a-h-G-E-V-A-T": .aHGEVAT,
        "a-f-G-E-V-A-T": .aFGEVAT,
        "a-u-G-U-C-I": .aUGUCI,
        "a-n-G-U-C-I": .aNGUCI,
        "a-h-G-U-C-I": .aHGUCI,
        "a-n-G-E-V": .aNGEV,
        "a-h-G-E-V": .aHGEV,
        "a-f-G-E-V": .aFGEV,
        "b-m-p-w-GOTO": .bMPWGoto,
        "b-m-p-c-ip": .bMPCIp,
        "b-m-p-c-cp": .bMPCCp,
        "b-m-p-s-p-op": .bMPSPOp,
        "u-d-v": .uDV,
        "u-d-v-m": .uDVM,
        "u-d-c-e": .uDCE,
        "b-i-x-i": .bIXI,
        "b-t-f-d": .bTFD,
        "b-t-f-r": .bTFR,
        "b-a-o-c": .bAOC,
        "t-s": .tS,
        // TAKTALK plugin shapes. The "y-" string literally has a trailing
        // dash and no second atom — that's the wire format ATAK + TAKTALK
        // emit for room broadcasts. Not a typo.
        "m-t-t": .mTT,
        "y-": .y,
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

    /// Map a CoT type string to its well-known ``CotType`` enum value.
    ///
    /// - Parameter str: A CoT type string such as `"a-f-G-U-C"`.
    /// - Returns: The matching ``CotType``, or ``CotType/other`` (0) when the
    ///   string is not in the known mapping. On ``CotType/other`` the caller
    ///   should preserve the original string in `cot_type_str` (see the
    ///   type-level forward-compatibility contract).
    public static func typeToEnum(_ str: String) -> CotType {
        stringToType[str] ?? .other
    }

    /// Map a well-known ``CotType`` enum value back to its CoT type string.
    ///
    /// - Parameter type: A well-known CoT type enum value.
    /// - Returns: The canonical CoT type string, or `nil` for ``CotType/other``
    ///   and any value with no string mapping (in which case the caller should
    ///   fall back to the carried `cot_type_str`).
    public static func typeToString(_ type: CotType) -> String? {
        typeToStr[type]
    }

    /// Map a CoT `how` string (coordinate-generation method) to its ``CotHow``
    /// enum value.
    ///
    /// - Parameter str: A CoT `how` string such as `"m-g"` or `"h-e"`.
    /// - Returns: The matching ``CotHow``, or ``CotHow/unspecified`` when
    ///   unknown.
    public static func howToEnum(_ str: String) -> CotHow {
        stringToHow[str] ?? .unspecified
    }

    /// Map a ``CotHow`` enum value back to its CoT `how` string.
    ///
    /// - Parameter how: A `how` enum value.
    /// - Returns: The canonical `how` string, or `nil` for
    ///   ``CotHow/unspecified`` and any value with no string mapping.
    public static func howToString(_ how: CotHow) -> String? {
        howToStr[how]
    }

    /// Whether a well-known CoT type classifies as an aircraft track, which
    /// selects the aircraft compression dictionary (ID `1`).
    ///
    /// Resolves the type to its string form and applies ``isAircraftString(_:)``.
    ///
    /// - Parameter type: A well-known CoT type enum value.
    /// - Returns: `true` if the type's third hierarchical atom is `A`;
    ///   `false` for unknown or non-aircraft types.
    public static func isAircraft(_ type: CotType) -> Bool {
        guard let str = typeToString(type) else { return false }
        return isAircraftString(str)
    }

    /// Whether a raw CoT type string classifies as an aircraft track.
    ///
    /// Aircraft types carry the Air battle-dimension indicator `A` as the third
    /// hierarchical atom of the dash-separated type string (e.g. `a-f-A-M-H`,
    /// `a-n-A-C-F`).
    ///
    /// - Parameter str: A CoT type string.
    /// - Returns: `true` if the string has at least three atoms and the third
    ///   (index 2) is exactly `"A"`.
    public static func isAircraftString(_ str: String) -> Bool {
        let atoms = str.split(separator: "-")
        return atoms.count >= 3 && atoms[2] == "A"
    }
}
