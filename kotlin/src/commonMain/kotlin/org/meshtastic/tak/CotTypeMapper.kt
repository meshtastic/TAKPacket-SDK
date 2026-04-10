package org.meshtastic.tak

/**
 * Maps CoT type strings (e.g. "a-f-G-U-C") to integer enum values matching
 * the CotType enum in atak.proto, and back.
 *
 * When a CoT type string is not in the known mapping, COTTYPE_OTHER (0) is returned
 * and the caller should populate cot_type_str with the original string.
 */
object CotTypeMapper {

    // CotType enum values from atak.proto
    const val COTTYPE_OTHER = 0
    const val COTTYPE_A_F_G_U_C = 1
    const val COTTYPE_A_F_G_U_C_I = 2
    const val COTTYPE_A_N_A_C_F = 3
    const val COTTYPE_A_N_A_C_H = 4
    const val COTTYPE_A_N_A_C = 5
    const val COTTYPE_A_F_A_M_H = 6
    const val COTTYPE_A_F_A_M = 7
    const val COTTYPE_A_F_A_M_F_F = 8
    const val COTTYPE_A_F_A_M_H_A = 9
    const val COTTYPE_A_F_A_M_H_U_M = 10
    const val COTTYPE_A_H_A_M_F_F = 11
    const val COTTYPE_A_H_A_M_H_A = 12
    const val COTTYPE_A_U_A_C = 13
    const val COTTYPE_T_X_D_D = 14
    const val COTTYPE_A_F_G_E_S_E = 15
    const val COTTYPE_A_F_G_E_V_C = 16
    const val COTTYPE_A_F_S = 17
    const val COTTYPE_A_F_A_M_F = 18
    const val COTTYPE_A_F_A_M_F_C_H = 19
    const val COTTYPE_A_F_A_M_F_U_L = 20
    const val COTTYPE_A_F_A_M_F_L = 21
    const val COTTYPE_A_F_A_M_F_P = 22
    const val COTTYPE_A_F_A_C_H = 23
    const val COTTYPE_A_N_A_M_F_Q = 24
    const val COTTYPE_B_T_F = 25
    const val COTTYPE_B_R_F_H_C = 26
    const val COTTYPE_B_A_O_PAN = 27
    const val COTTYPE_B_A_O_OPN = 28
    const val COTTYPE_B_A_O_CAN = 29
    const val COTTYPE_B_A_O_TBL = 30
    const val COTTYPE_B_A_G = 31
    const val COTTYPE_A_F_G = 32
    const val COTTYPE_A_F_G_U = 33
    const val COTTYPE_A_H_G = 34
    const val COTTYPE_A_U_G = 35
    const val COTTYPE_A_N_G = 36
    const val COTTYPE_B_M_R = 37
    const val COTTYPE_B_M_P_W = 38
    const val COTTYPE_B_M_P_S_P_I = 39
    const val COTTYPE_U_D_F = 40
    const val COTTYPE_U_D_R = 41
    const val COTTYPE_U_D_C_C = 42
    const val COTTYPE_U_RB_A = 43
    const val COTTYPE_A_H_A = 44
    const val COTTYPE_A_U_A = 45
    const val COTTYPE_A_F_A_M_H_Q = 46
    const val COTTYPE_A_F_A_C_F = 47
    const val COTTYPE_A_F_A_C = 48
    const val COTTYPE_A_F_A_C_L = 49
    const val COTTYPE_A_F_A = 50
    const val COTTYPE_A_F_A_M_H_C = 51
    const val COTTYPE_A_N_A_M_F_F = 52
    const val COTTYPE_A_U_A_C_F = 53
    const val COTTYPE_A_F_G_U_C_F_T_A = 54
    const val COTTYPE_A_F_G_U_C_V_S = 55
    const val COTTYPE_A_F_G_U_C_R_X = 56
    const val COTTYPE_A_F_G_U_C_I_Z = 57
    const val COTTYPE_A_F_G_U_C_E_C_W = 58
    const val COTTYPE_A_F_G_U_C_I_L = 59
    const val COTTYPE_A_F_G_U_C_R_O = 60
    const val COTTYPE_A_F_G_U_C_R_V = 61
    const val COTTYPE_A_F_G_U_H = 62
    const val COTTYPE_A_F_G_U_U_M_S_E = 63
    const val COTTYPE_A_F_G_U_S_M_C = 64
    const val COTTYPE_A_F_G_E_S = 65
    const val COTTYPE_A_F_G_E = 66
    const val COTTYPE_A_F_G_E_V_C_U = 67
    const val COTTYPE_A_F_G_E_V_C_PS = 68
    const val COTTYPE_A_U_G_E_V = 69
    const val COTTYPE_A_F_S_N_N_R = 70
    const val COTTYPE_A_F_F_B = 71
    const val COTTYPE_B_M_P_S_P_LOC = 72
    const val COTTYPE_B_I_V = 73
    const val COTTYPE_B_F_T_R = 74
    const val COTTYPE_B_F_T_A = 75

    // --- Typed geometry CoT types (v2 protocol extension) ---
    // These 6 entries accompany the new DrawnShape/Marker/RangeAndBearing
    // payload variants added to TAKPacketV2 at tags 34-37. See atak.proto.
    const val COTTYPE_U_D_F_M = 76         // Freehand telestration
    const val COTTYPE_U_D_P = 77           // Closed polygon
    const val COTTYPE_B_M_P_S_M = 78       // Spot map marker
    const val COTTYPE_B_M_P_C = 79         // Checkpoint
    const val COTTYPE_U_R_B_C_C = 80       // Ranging circle
    const val COTTYPE_U_R_B_BULLSEYE = 81  // Bullseye with range rings

    // --- Expanded coverage (values 82-124) --------------------------------
    // Covers ATAK-CIV quick-drop pallet types, mission-specific points,
    // chat receipts, emergency beacons, and tasking. No new payload variants
    // beyond what atak.proto already defines (CasevacReport, EmergencyAlert,
    // TaskRequest at tags 38/39/40 and GeoChat receipt extension on tag 31).

    // PLI self-reporting (1)
    const val COTTYPE_A_F_G_E_V_A = 82     // Friendly armored vehicle self-PLI
    // 2525 quick-drop: basic affiliation gaps (1)
    const val COTTYPE_A_N_A = 83           // Neutral aircraft
    // 2525 artillery (4)
    const val COTTYPE_A_U_G_U_C_F = 84
    const val COTTYPE_A_N_G_U_C_F = 85
    const val COTTYPE_A_H_G_U_C_F = 86
    const val COTTYPE_A_F_G_U_C_F = 87
    // 2525 building (4)
    const val COTTYPE_A_U_G_I = 88
    const val COTTYPE_A_N_G_I = 89
    const val COTTYPE_A_H_G_I = 90
    const val COTTYPE_A_F_G_I = 91
    // 2525 mine (4)
    const val COTTYPE_A_U_G_E_X_M = 92
    const val COTTYPE_A_N_G_E_X_M = 93
    const val COTTYPE_A_H_G_E_X_M = 94
    const val COTTYPE_A_F_G_E_X_M = 95
    // 2525 ship (3; a-f-S already at 17)
    const val COTTYPE_A_U_S = 96
    const val COTTYPE_A_N_S = 97
    const val COTTYPE_A_H_S = 98
    // 2525 sniper (4) — lowercase `d` suffix distinguishes from troops
    const val COTTYPE_A_U_G_U_C_I_D = 99
    const val COTTYPE_A_N_G_U_C_I_D = 100
    const val COTTYPE_A_H_G_U_C_I_D = 101
    const val COTTYPE_A_F_G_U_C_I_D = 102
    // 2525 tank (4)
    const val COTTYPE_A_U_G_E_V_A_T = 103
    const val COTTYPE_A_N_G_E_V_A_T = 104
    const val COTTYPE_A_H_G_E_V_A_T = 105
    const val COTTYPE_A_F_G_E_V_A_T = 106
    // 2525 troops (3; a-f-G-U-C-I already at 2)
    const val COTTYPE_A_U_G_U_C_I = 107
    const val COTTYPE_A_N_G_U_C_I = 108
    const val COTTYPE_A_H_G_U_C_I = 109
    // 2525 generic vehicle (3; a-u-G-E-V already at 69)
    const val COTTYPE_A_N_G_E_V = 110
    const val COTTYPE_A_H_G_E_V = 111
    const val COTTYPE_A_F_G_E_V = 112
    // Mission-specific points (4)
    const val COTTYPE_B_M_P_W_GOTO = 113   // Go To / bloodhound
    const val COTTYPE_B_M_P_C_IP = 114     // Initial point
    const val COTTYPE_B_M_P_C_CP = 115     // Contact point
    const val COTTYPE_B_M_P_S_P_OP = 116   // Observation post
    // Vehicle drawings (2)
    const val COTTYPE_U_D_V = 117          // 2D vehicle outline
    const val COTTYPE_U_D_V_M = 118        // 3D vehicle model
    // Drawing shapes (1)
    const val COTTYPE_U_D_C_E = 119        // Ellipse
    // Image / media marker (1)
    const val COTTYPE_B_I_X_I = 120        // Quick Pic image marker
    // GeoChat receipts (2) — note COTTYPE_B_F_T_R at 74 is a DIFFERENT type
    const val COTTYPE_B_T_F_D = 121        // Chat delivered receipt
    const val COTTYPE_B_T_F_R = 122        // Chat read receipt
    // Custom emergency (1)
    const val COTTYPE_B_A_O_C = 123        // Custom emergency beacon
    // Tasking (1)
    const val COTTYPE_T_S = 124            // Task / engage request

    // CotHow enum values from atak.proto
    const val COTHOW_UNSPECIFIED = 0
    const val COTHOW_H_E = 1
    const val COTHOW_M_G = 2
    const val COTHOW_H_G_I_G_O = 3
    const val COTHOW_M_R = 4
    const val COTHOW_M_F = 5
    const val COTHOW_M_P = 6
    const val COTHOW_M_S = 7

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
    fun typeToEnum(cotTypeString: String): Int =
        stringToType[cotTypeString] ?: COTTYPE_OTHER

    /** Convert a CotType enum int to its canonical string. Returns null for COTTYPE_OTHER. */
    fun typeToString(cotTypeId: Int): String? = typeToString[cotTypeId]

    /** Convert a CoT how string to its enum int value. */
    fun howToEnum(howString: String): Int =
        stringToHow[howString] ?: COTHOW_UNSPECIFIED

    /** Convert a CotHow enum int to its canonical string. */
    fun howToString(howId: Int): String? = howToStr[howId]

    /**
     * Returns true if the CoT type is in the Air domain (3rd atom = 'A').
     * Used to select the aircraft vs non-aircraft compression dictionary.
     */
    fun isAircraft(cotTypeId: Int): Boolean {
        val typeStr = typeToString(cotTypeId) ?: return false
        return isAircraftString(typeStr)
    }

    /** Returns true if the CoT type string is in the Air domain. */
    fun isAircraftString(cotTypeString: String): Boolean {
        val atoms = cotTypeString.split("-")
        return atoms.size >= 3 && atoms[2] == "A"
    }
}
