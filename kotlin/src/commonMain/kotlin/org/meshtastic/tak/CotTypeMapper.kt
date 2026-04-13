package org.meshtastic.tak

/**
 * Bidirectional mapping between CoT type/how strings and their compact
 * integer enum values from `atak.proto`.
 *
 * The SDK encodes CoT types as small integers on the wire to save bandwidth.
 * When a type string has a known mapping (e.g. `"a-f-G-U-C"` -> 1), only the
 * integer rides on the wire. Unknown types fall through to [COTTYPE_OTHER] (0)
 * and the full string is carried in the `cot_type_str` field instead.
 *
 * This object also provides [isAircraft] / [isAircraftString] helpers used by
 * [DictionaryProvider] to select the aircraft-specific zstd dictionary.
 *
 * All functions are pure and thread-safe.
 */
public object CotTypeMapper {

    // CotType enum values from atak.proto
    public const val COTTYPE_OTHER: Int = 0
    public const val COTTYPE_A_F_G_U_C: Int = 1
    public const val COTTYPE_A_F_G_U_C_I: Int = 2
    public const val COTTYPE_A_N_A_C_F: Int = 3
    public const val COTTYPE_A_N_A_C_H: Int = 4
    public const val COTTYPE_A_N_A_C: Int = 5
    public const val COTTYPE_A_F_A_M_H: Int = 6
    public const val COTTYPE_A_F_A_M: Int = 7
    public const val COTTYPE_A_F_A_M_F_F: Int = 8
    public const val COTTYPE_A_F_A_M_H_A: Int = 9
    public const val COTTYPE_A_F_A_M_H_U_M: Int = 10
    public const val COTTYPE_A_H_A_M_F_F: Int = 11
    public const val COTTYPE_A_H_A_M_H_A: Int = 12
    public const val COTTYPE_A_U_A_C: Int = 13
    public const val COTTYPE_T_X_D_D: Int = 14
    public const val COTTYPE_A_F_G_E_S_E: Int = 15
    public const val COTTYPE_A_F_G_E_V_C: Int = 16
    public const val COTTYPE_A_F_S: Int = 17
    public const val COTTYPE_A_F_A_M_F: Int = 18
    public const val COTTYPE_A_F_A_M_F_C_H: Int = 19
    public const val COTTYPE_A_F_A_M_F_U_L: Int = 20
    public const val COTTYPE_A_F_A_M_F_L: Int = 21
    public const val COTTYPE_A_F_A_M_F_P: Int = 22
    public const val COTTYPE_A_F_A_C_H: Int = 23
    public const val COTTYPE_A_N_A_M_F_Q: Int = 24
    public const val COTTYPE_B_T_F: Int = 25
    public const val COTTYPE_B_R_F_H_C: Int = 26
    public const val COTTYPE_B_A_O_PAN: Int = 27
    public const val COTTYPE_B_A_O_OPN: Int = 28
    public const val COTTYPE_B_A_O_CAN: Int = 29
    public const val COTTYPE_B_A_O_TBL: Int = 30
    public const val COTTYPE_B_A_G: Int = 31
    public const val COTTYPE_A_F_G: Int = 32
    public const val COTTYPE_A_F_G_U: Int = 33
    public const val COTTYPE_A_H_G: Int = 34
    public const val COTTYPE_A_U_G: Int = 35
    public const val COTTYPE_A_N_G: Int = 36
    public const val COTTYPE_B_M_R: Int = 37
    public const val COTTYPE_B_M_P_W: Int = 38
    public const val COTTYPE_B_M_P_S_P_I: Int = 39
    public const val COTTYPE_U_D_F: Int = 40
    public const val COTTYPE_U_D_R: Int = 41
    public const val COTTYPE_U_D_C_C: Int = 42
    public const val COTTYPE_U_RB_A: Int = 43
    public const val COTTYPE_A_H_A: Int = 44
    public const val COTTYPE_A_U_A: Int = 45
    public const val COTTYPE_A_F_A_M_H_Q: Int = 46
    public const val COTTYPE_A_F_A_C_F: Int = 47
    public const val COTTYPE_A_F_A_C: Int = 48
    public const val COTTYPE_A_F_A_C_L: Int = 49
    public const val COTTYPE_A_F_A: Int = 50
    public const val COTTYPE_A_F_A_M_H_C: Int = 51
    public const val COTTYPE_A_N_A_M_F_F: Int = 52
    public const val COTTYPE_A_U_A_C_F: Int = 53
    public const val COTTYPE_A_F_G_U_C_F_T_A: Int = 54
    public const val COTTYPE_A_F_G_U_C_V_S: Int = 55
    public const val COTTYPE_A_F_G_U_C_R_X: Int = 56
    public const val COTTYPE_A_F_G_U_C_I_Z: Int = 57
    public const val COTTYPE_A_F_G_U_C_E_C_W: Int = 58
    public const val COTTYPE_A_F_G_U_C_I_L: Int = 59
    public const val COTTYPE_A_F_G_U_C_R_O: Int = 60
    public const val COTTYPE_A_F_G_U_C_R_V: Int = 61
    public const val COTTYPE_A_F_G_U_H: Int = 62
    public const val COTTYPE_A_F_G_U_U_M_S_E: Int = 63
    public const val COTTYPE_A_F_G_U_S_M_C: Int = 64
    public const val COTTYPE_A_F_G_E_S: Int = 65
    public const val COTTYPE_A_F_G_E: Int = 66
    public const val COTTYPE_A_F_G_E_V_C_U: Int = 67
    public const val COTTYPE_A_F_G_E_V_C_PS: Int = 68
    public const val COTTYPE_A_U_G_E_V: Int = 69
    public const val COTTYPE_A_F_S_N_N_R: Int = 70
    public const val COTTYPE_A_F_F_B: Int = 71
    public const val COTTYPE_B_M_P_S_P_LOC: Int = 72
    public const val COTTYPE_B_I_V: Int = 73
    public const val COTTYPE_B_F_T_R: Int = 74
    public const val COTTYPE_B_F_T_A: Int = 75

    // --- Typed geometry CoT types (v2 protocol extension) ---
    // These 6 entries accompany the new DrawnShape/Marker/RangeAndBearing
    // payload variants added to TAKPacketV2 at tags 34-37. See atak.proto.
    public const val COTTYPE_U_D_F_M: Int = 76         // Freehand telestration
    public const val COTTYPE_U_D_P: Int = 77           // Closed polygon
    public const val COTTYPE_B_M_P_S_M: Int = 78       // Spot map marker
    public const val COTTYPE_B_M_P_C: Int = 79         // Checkpoint
    public const val COTTYPE_U_R_B_C_C: Int = 80       // Ranging circle
    public const val COTTYPE_U_R_B_BULLSEYE: Int = 81  // Bullseye with range rings

    // --- Expanded coverage (values 82-124) --------------------------------
    // Covers ATAK-CIV quick-drop pallet types, mission-specific points,
    // chat receipts, emergency beacons, and tasking. No new payload variants
    // beyond what atak.proto already defines (CasevacReport, EmergencyAlert,
    // TaskRequest at tags 38/39/40 and GeoChat receipt extension on tag 31).

    // PLI self-reporting (1)
    public const val COTTYPE_A_F_G_E_V_A: Int = 82     // Friendly armored vehicle self-PLI
    // 2525 quick-drop: basic affiliation gaps (1)
    public const val COTTYPE_A_N_A: Int = 83           // Neutral aircraft
    // 2525 artillery (4)
    public const val COTTYPE_A_U_G_U_C_F: Int = 84
    public const val COTTYPE_A_N_G_U_C_F: Int = 85
    public const val COTTYPE_A_H_G_U_C_F: Int = 86
    public const val COTTYPE_A_F_G_U_C_F: Int = 87
    // 2525 building (4)
    public const val COTTYPE_A_U_G_I: Int = 88
    public const val COTTYPE_A_N_G_I: Int = 89
    public const val COTTYPE_A_H_G_I: Int = 90
    public const val COTTYPE_A_F_G_I: Int = 91
    // 2525 mine (4)
    public const val COTTYPE_A_U_G_E_X_M: Int = 92
    public const val COTTYPE_A_N_G_E_X_M: Int = 93
    public const val COTTYPE_A_H_G_E_X_M: Int = 94
    public const val COTTYPE_A_F_G_E_X_M: Int = 95
    // 2525 ship (3; a-f-S already at 17)
    public const val COTTYPE_A_U_S: Int = 96
    public const val COTTYPE_A_N_S: Int = 97
    public const val COTTYPE_A_H_S: Int = 98
    // 2525 sniper (4) — lowercase `d` suffix distinguishes from troops
    public const val COTTYPE_A_U_G_U_C_I_D: Int = 99
    public const val COTTYPE_A_N_G_U_C_I_D: Int = 100
    public const val COTTYPE_A_H_G_U_C_I_D: Int = 101
    public const val COTTYPE_A_F_G_U_C_I_D: Int = 102
    // 2525 tank (4)
    public const val COTTYPE_A_U_G_E_V_A_T: Int = 103
    public const val COTTYPE_A_N_G_E_V_A_T: Int = 104
    public const val COTTYPE_A_H_G_E_V_A_T: Int = 105
    public const val COTTYPE_A_F_G_E_V_A_T: Int = 106
    // 2525 troops (3; a-f-G-U-C-I already at 2)
    public const val COTTYPE_A_U_G_U_C_I: Int = 107
    public const val COTTYPE_A_N_G_U_C_I: Int = 108
    public const val COTTYPE_A_H_G_U_C_I: Int = 109
    // 2525 generic vehicle (3; a-u-G-E-V already at 69)
    public const val COTTYPE_A_N_G_E_V: Int = 110
    public const val COTTYPE_A_H_G_E_V: Int = 111
    public const val COTTYPE_A_F_G_E_V: Int = 112
    // Mission-specific points (4)
    public const val COTTYPE_B_M_P_W_GOTO: Int = 113   // Go To / bloodhound
    public const val COTTYPE_B_M_P_C_IP: Int = 114     // Initial point
    public const val COTTYPE_B_M_P_C_CP: Int = 115     // Contact point
    public const val COTTYPE_B_M_P_S_P_OP: Int = 116   // Observation post
    // Vehicle drawings (2)
    public const val COTTYPE_U_D_V: Int = 117          // 2D vehicle outline
    public const val COTTYPE_U_D_V_M: Int = 118        // 3D vehicle model
    // Drawing shapes (1)
    public const val COTTYPE_U_D_C_E: Int = 119        // Ellipse
    // Image / media marker (1)
    public const val COTTYPE_B_I_X_I: Int = 120        // Quick Pic image marker
    // GeoChat receipts (2) — note COTTYPE_B_F_T_R at 74 is a DIFFERENT type
    public const val COTTYPE_B_T_F_D: Int = 121        // Chat delivered receipt
    public const val COTTYPE_B_T_F_R: Int = 122        // Chat read receipt
    // Custom emergency (1)
    public const val COTTYPE_B_A_O_C: Int = 123        // Custom emergency beacon
    // Tasking (1)
    public const val COTTYPE_T_S: Int = 124            // Task / engage request

    // CotHow enum values from atak.proto
    public const val COTHOW_UNSPECIFIED: Int = 0
    public const val COTHOW_H_E: Int = 1
    public const val COTHOW_M_G: Int = 2
    public const val COTHOW_H_G_I_G_O: Int = 3
    public const val COTHOW_M_R: Int = 4
    public const val COTHOW_M_F: Int = 5
    public const val COTHOW_M_P: Int = 6
    public const val COTHOW_M_S: Int = 7

    private val stringToType = mapOf(
        "a-f-G-U-C" to COTTYPE_A_F_G_U_C,
        "a-f-G-U-C-I" to COTTYPE_A_F_G_U_C_I,
        "a-n-A-C-F" to COTTYPE_A_N_A_C_F,
        "a-n-A-C-H" to COTTYPE_A_N_A_C_H,
        "a-n-A-C" to COTTYPE_A_N_A_C,
        "a-f-A-M-H" to COTTYPE_A_F_A_M_H,
        "a-f-A-M" to COTTYPE_A_F_A_M,
        "a-f-A-M-F-F" to COTTYPE_A_F_A_M_F_F,
        "a-f-A-M-H-A" to COTTYPE_A_F_A_M_H_A,
        "a-f-A-M-H-U-M" to COTTYPE_A_F_A_M_H_U_M,
        "a-h-A-M-F-F" to COTTYPE_A_H_A_M_F_F,
        "a-h-A-M-H-A" to COTTYPE_A_H_A_M_H_A,
        "a-u-A-C" to COTTYPE_A_U_A_C,
        "t-x-d-d" to COTTYPE_T_X_D_D,
        "a-f-G-E-S-E" to COTTYPE_A_F_G_E_S_E,
        "a-f-G-E-V-C" to COTTYPE_A_F_G_E_V_C,
        "a-f-S" to COTTYPE_A_F_S,
        "a-f-A-M-F" to COTTYPE_A_F_A_M_F,
        "a-f-A-M-F-C-H" to COTTYPE_A_F_A_M_F_C_H,
        "a-f-A-M-F-U-L" to COTTYPE_A_F_A_M_F_U_L,
        "a-f-A-M-F-L" to COTTYPE_A_F_A_M_F_L,
        "a-f-A-M-F-P" to COTTYPE_A_F_A_M_F_P,
        "a-f-A-C-H" to COTTYPE_A_F_A_C_H,
        "a-n-A-M-F-Q" to COTTYPE_A_N_A_M_F_Q,
        "b-t-f" to COTTYPE_B_T_F,
        "b-r-f-h-c" to COTTYPE_B_R_F_H_C,
        "b-a-o-pan" to COTTYPE_B_A_O_PAN,
        "b-a-o-opn" to COTTYPE_B_A_O_OPN,
        "b-a-o-can" to COTTYPE_B_A_O_CAN,
        "b-a-o-tbl" to COTTYPE_B_A_O_TBL,
        "b-a-g" to COTTYPE_B_A_G,
        "a-f-G" to COTTYPE_A_F_G,
        "a-f-G-U" to COTTYPE_A_F_G_U,
        "a-h-G" to COTTYPE_A_H_G,
        "a-u-G" to COTTYPE_A_U_G,
        "a-n-G" to COTTYPE_A_N_G,
        "b-m-r" to COTTYPE_B_M_R,
        "b-m-p-w" to COTTYPE_B_M_P_W,
        "b-m-p-s-p-i" to COTTYPE_B_M_P_S_P_I,
        "u-d-f" to COTTYPE_U_D_F,
        "u-d-r" to COTTYPE_U_D_R,
        "u-d-c-c" to COTTYPE_U_D_C_C,
        "u-rb-a" to COTTYPE_U_RB_A,
        "a-h-A" to COTTYPE_A_H_A,
        "a-u-A" to COTTYPE_A_U_A,
        "a-f-A-M-H-Q" to COTTYPE_A_F_A_M_H_Q,
        "a-f-A-C-F" to COTTYPE_A_F_A_C_F,
        "a-f-A-C" to COTTYPE_A_F_A_C,
        "a-f-A-C-L" to COTTYPE_A_F_A_C_L,
        "a-f-A" to COTTYPE_A_F_A,
        "a-f-A-M-H-C" to COTTYPE_A_F_A_M_H_C,
        "a-n-A-M-F-F" to COTTYPE_A_N_A_M_F_F,
        "a-u-A-C-F" to COTTYPE_A_U_A_C_F,
        "a-f-G-U-C-F-T-A" to COTTYPE_A_F_G_U_C_F_T_A,
        "a-f-G-U-C-V-S" to COTTYPE_A_F_G_U_C_V_S,
        "a-f-G-U-C-R-X" to COTTYPE_A_F_G_U_C_R_X,
        "a-f-G-U-C-I-Z" to COTTYPE_A_F_G_U_C_I_Z,
        "a-f-G-U-C-E-C-W" to COTTYPE_A_F_G_U_C_E_C_W,
        "a-f-G-U-C-I-L" to COTTYPE_A_F_G_U_C_I_L,
        "a-f-G-U-C-R-O" to COTTYPE_A_F_G_U_C_R_O,
        "a-f-G-U-C-R-V" to COTTYPE_A_F_G_U_C_R_V,
        "a-f-G-U-H" to COTTYPE_A_F_G_U_H,
        "a-f-G-U-U-M-S-E" to COTTYPE_A_F_G_U_U_M_S_E,
        "a-f-G-U-S-M-C" to COTTYPE_A_F_G_U_S_M_C,
        "a-f-G-E-S" to COTTYPE_A_F_G_E_S,
        "a-f-G-E" to COTTYPE_A_F_G_E,
        "a-f-G-E-V-C-U" to COTTYPE_A_F_G_E_V_C_U,
        "a-f-G-E-V-C-ps" to COTTYPE_A_F_G_E_V_C_PS,
        "a-u-G-E-V" to COTTYPE_A_U_G_E_V,
        "a-f-S-N-N-R" to COTTYPE_A_F_S_N_N_R,
        "a-f-F-B" to COTTYPE_A_F_F_B,
        "b-m-p-s-p-loc" to COTTYPE_B_M_P_S_P_LOC,
        "b-i-v" to COTTYPE_B_I_V,
        "b-f-t-r" to COTTYPE_B_F_T_R,
        "b-f-t-a" to COTTYPE_B_F_T_A,
        // Typed geometry additions (v2 protocol extension)
        "u-d-f-m" to COTTYPE_U_D_F_M,
        "u-d-p" to COTTYPE_U_D_P,
        "b-m-p-s-m" to COTTYPE_B_M_P_S_M,
        "b-m-p-c" to COTTYPE_B_M_P_C,
        "u-r-b-c-c" to COTTYPE_U_R_B_C_C,
        "u-r-b-bullseye" to COTTYPE_U_R_B_BULLSEYE,
        // Expanded coverage (values 82-124) — ATAK-CIV quick-drop pallet,
        // mission points, receipts, emergency, tasking.
        "a-f-G-E-V-A" to COTTYPE_A_F_G_E_V_A,
        "a-n-A" to COTTYPE_A_N_A,
        "a-u-G-U-C-F" to COTTYPE_A_U_G_U_C_F,
        "a-n-G-U-C-F" to COTTYPE_A_N_G_U_C_F,
        "a-h-G-U-C-F" to COTTYPE_A_H_G_U_C_F,
        "a-f-G-U-C-F" to COTTYPE_A_F_G_U_C_F,
        "a-u-G-I" to COTTYPE_A_U_G_I,
        "a-n-G-I" to COTTYPE_A_N_G_I,
        "a-h-G-I" to COTTYPE_A_H_G_I,
        "a-f-G-I" to COTTYPE_A_F_G_I,
        "a-u-G-E-X-M" to COTTYPE_A_U_G_E_X_M,
        "a-n-G-E-X-M" to COTTYPE_A_N_G_E_X_M,
        "a-h-G-E-X-M" to COTTYPE_A_H_G_E_X_M,
        "a-f-G-E-X-M" to COTTYPE_A_F_G_E_X_M,
        "a-u-S" to COTTYPE_A_U_S,
        "a-n-S" to COTTYPE_A_N_S,
        "a-h-S" to COTTYPE_A_H_S,
        "a-u-G-U-C-I-d" to COTTYPE_A_U_G_U_C_I_D,
        "a-n-G-U-C-I-d" to COTTYPE_A_N_G_U_C_I_D,
        "a-h-G-U-C-I-d" to COTTYPE_A_H_G_U_C_I_D,
        "a-f-G-U-C-I-d" to COTTYPE_A_F_G_U_C_I_D,
        "a-u-G-E-V-A-T" to COTTYPE_A_U_G_E_V_A_T,
        "a-n-G-E-V-A-T" to COTTYPE_A_N_G_E_V_A_T,
        "a-h-G-E-V-A-T" to COTTYPE_A_H_G_E_V_A_T,
        "a-f-G-E-V-A-T" to COTTYPE_A_F_G_E_V_A_T,
        "a-u-G-U-C-I" to COTTYPE_A_U_G_U_C_I,
        "a-n-G-U-C-I" to COTTYPE_A_N_G_U_C_I,
        "a-h-G-U-C-I" to COTTYPE_A_H_G_U_C_I,
        "a-n-G-E-V" to COTTYPE_A_N_G_E_V,
        "a-h-G-E-V" to COTTYPE_A_H_G_E_V,
        "a-f-G-E-V" to COTTYPE_A_F_G_E_V,
        "b-m-p-w-GOTO" to COTTYPE_B_M_P_W_GOTO,
        "b-m-p-c-ip" to COTTYPE_B_M_P_C_IP,
        "b-m-p-c-cp" to COTTYPE_B_M_P_C_CP,
        "b-m-p-s-p-op" to COTTYPE_B_M_P_S_P_OP,
        "u-d-v" to COTTYPE_U_D_V,
        "u-d-v-m" to COTTYPE_U_D_V_M,
        "u-d-c-e" to COTTYPE_U_D_C_E,
        "b-i-x-i" to COTTYPE_B_I_X_I,
        "b-t-f-d" to COTTYPE_B_T_F_D,
        "b-t-f-r" to COTTYPE_B_T_F_R,
        "b-a-o-c" to COTTYPE_B_A_O_C,
        "t-s" to COTTYPE_T_S,
    )

    private val typeToString = stringToType.entries.associate { (k, v) -> v to k }

    private val stringToHow = mapOf(
        "h-e" to COTHOW_H_E,
        "m-g" to COTHOW_M_G,
        "h-g-i-g-o" to COTHOW_H_G_I_G_O,
        "m-r" to COTHOW_M_R,
        "m-f" to COTHOW_M_F,
        "m-p" to COTHOW_M_P,
        "m-s" to COTHOW_M_S,
    )

    private val howToStr = stringToHow.entries.associate { (k, v) -> v to k }

    /** Convert a CoT type string to its enum int value. Returns COTTYPE_OTHER if unknown. */
    public fun typeToEnum(cotTypeString: String): Int =
        stringToType[cotTypeString] ?: COTTYPE_OTHER

    /** Convert a CotType enum int to its canonical string. Returns null for COTTYPE_OTHER. */
    public fun typeToString(cotTypeId: Int): String? = typeToString[cotTypeId]

    /** Convert a CoT how string to its enum int value. */
    public fun howToEnum(howString: String): Int =
        stringToHow[howString] ?: COTHOW_UNSPECIFIED

    /** Convert a CotHow enum int to its canonical string. */
    public fun howToString(howId: Int): String? = howToStr[howId]

    /**
     * Returns true if the CoT type is in the Air domain (3rd atom = 'A').
     * Used to select the aircraft vs non-aircraft compression dictionary.
     */
    public fun isAircraft(cotTypeId: Int): Boolean {
        val typeStr = typeToString(cotTypeId) ?: return false
        return isAircraftString(typeStr)
    }

    /** Returns true if the CoT type string is in the Air domain. */
    public fun isAircraftString(cotTypeString: String): Boolean {
        val atoms = cotTypeString.split("-")
        return atoms.size >= 3 && atoms[2] == "A"
    }
}
