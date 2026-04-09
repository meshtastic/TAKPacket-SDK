"""Maps CoT type strings to/from CotType enum values and classifies aircraft types."""

# CotType enum values from atak.proto
COTTYPE_OTHER = 0
COTTYPE_A_F_G_U_C = 1
COTTYPE_A_F_G_U_C_I = 2
COTTYPE_A_N_A_C_F = 3
COTTYPE_A_N_A_C_H = 4
COTTYPE_A_N_A_C = 5
COTTYPE_A_F_A_M_H = 6
COTTYPE_A_F_A_M = 7
COTTYPE_A_F_A_M_F_F = 8
COTTYPE_A_F_A_M_H_A = 9
COTTYPE_A_F_A_M_H_U_M = 10
COTTYPE_A_H_A_M_F_F = 11
COTTYPE_A_H_A_M_H_A = 12
COTTYPE_A_U_A_C = 13
COTTYPE_T_X_D_D = 14
COTTYPE_A_F_G_E_S_E = 15
COTTYPE_A_F_G_E_V_C = 16
COTTYPE_A_F_S = 17
COTTYPE_A_F_A_M_F = 18
COTTYPE_A_F_A_M_F_C_H = 19
COTTYPE_A_F_A_M_F_U_L = 20
COTTYPE_A_F_A_M_F_L = 21
COTTYPE_A_F_A_M_F_P = 22
COTTYPE_A_F_A_C_H = 23
COTTYPE_A_N_A_M_F_Q = 24
COTTYPE_B_T_F = 25
COTTYPE_B_R_F_H_C = 26
COTTYPE_B_A_O_PAN = 27
COTTYPE_B_A_O_OPN = 28
COTTYPE_B_A_O_CAN = 29
COTTYPE_B_A_O_TBL = 30
COTTYPE_B_A_G = 31
COTTYPE_A_F_G = 32
COTTYPE_A_F_G_U = 33
COTTYPE_A_H_G = 34
COTTYPE_A_U_G = 35
COTTYPE_A_N_G = 36
COTTYPE_B_M_R = 37
COTTYPE_B_M_P_W = 38
COTTYPE_B_M_P_S_P_I = 39
COTTYPE_U_D_F = 40
COTTYPE_U_D_R = 41
COTTYPE_U_D_C_C = 42
COTTYPE_U_RB_A = 43
COTTYPE_A_H_A = 44
COTTYPE_A_U_A = 45
COTTYPE_A_F_A_M_H_Q = 46
COTTYPE_A_F_A_C_F = 47
COTTYPE_A_F_A_C = 48
COTTYPE_A_F_A_C_L = 49
COTTYPE_A_F_A = 50
COTTYPE_A_F_A_M_H_C = 51
COTTYPE_A_N_A_M_F_F = 52
COTTYPE_A_U_A_C_F = 53
COTTYPE_A_F_G_U_C_F_T_A = 54
COTTYPE_A_F_G_U_C_V_S = 55
COTTYPE_A_F_G_U_C_R_X = 56
COTTYPE_A_F_G_U_C_I_Z = 57
COTTYPE_A_F_G_U_C_E_C_W = 58
COTTYPE_A_F_G_U_C_I_L = 59
COTTYPE_A_F_G_U_C_R_O = 60
COTTYPE_A_F_G_U_C_R_V = 61
COTTYPE_A_F_G_U_H = 62
COTTYPE_A_F_G_U_U_M_S_E = 63
COTTYPE_A_F_G_U_S_M_C = 64
COTTYPE_A_F_G_E_S = 65
COTTYPE_A_F_G_E = 66
COTTYPE_A_F_G_E_V_C_U = 67
COTTYPE_A_F_G_E_V_C_PS = 68
COTTYPE_A_U_G_E_V = 69
COTTYPE_A_F_S_N_N_R = 70
COTTYPE_A_F_F_B = 71
COTTYPE_B_M_P_S_P_LOC = 72
COTTYPE_B_I_V = 73
COTTYPE_B_F_T_R = 74
COTTYPE_B_F_T_A = 75

# --- Typed geometry CoT types (v2 protocol extension) ---
COTTYPE_U_D_F_M = 76         # Freehand telestration
COTTYPE_U_D_P = 77           # Closed polygon
COTTYPE_B_M_P_S_M = 78       # Spot map marker
COTTYPE_B_M_P_C = 79         # Checkpoint
COTTYPE_U_R_B_C_C = 80       # Ranging circle
COTTYPE_U_R_B_BULLSEYE = 81  # Bullseye with range rings

# --- Expanded coverage (values 82-124) ---
COTTYPE_A_F_G_E_V_A = 82
COTTYPE_A_N_A = 83
COTTYPE_A_U_G_U_C_F = 84
COTTYPE_A_N_G_U_C_F = 85
COTTYPE_A_H_G_U_C_F = 86
COTTYPE_A_F_G_U_C_F = 87
COTTYPE_A_U_G_I = 88
COTTYPE_A_N_G_I = 89
COTTYPE_A_H_G_I = 90
COTTYPE_A_F_G_I = 91
COTTYPE_A_U_G_E_X_M = 92
COTTYPE_A_N_G_E_X_M = 93
COTTYPE_A_H_G_E_X_M = 94
COTTYPE_A_F_G_E_X_M = 95
COTTYPE_A_U_S = 96
COTTYPE_A_N_S = 97
COTTYPE_A_H_S = 98
COTTYPE_A_U_G_U_C_I_D = 99
COTTYPE_A_N_G_U_C_I_D = 100
COTTYPE_A_H_G_U_C_I_D = 101
COTTYPE_A_F_G_U_C_I_D = 102
COTTYPE_A_U_G_E_V_A_T = 103
COTTYPE_A_N_G_E_V_A_T = 104
COTTYPE_A_H_G_E_V_A_T = 105
COTTYPE_A_F_G_E_V_A_T = 106
COTTYPE_A_U_G_U_C_I = 107
COTTYPE_A_N_G_U_C_I = 108
COTTYPE_A_H_G_U_C_I = 109
COTTYPE_A_N_G_E_V = 110
COTTYPE_A_H_G_E_V = 111
COTTYPE_A_F_G_E_V = 112
COTTYPE_B_M_P_W_GOTO = 113
COTTYPE_B_M_P_C_IP = 114
COTTYPE_B_M_P_C_CP = 115
COTTYPE_B_M_P_S_P_OP = 116
COTTYPE_U_D_V = 117
COTTYPE_U_D_V_M = 118
COTTYPE_U_D_C_E = 119
COTTYPE_B_I_X_I = 120
COTTYPE_B_T_F_D = 121
COTTYPE_B_T_F_R = 122
COTTYPE_B_A_O_C = 123
COTTYPE_T_S = 124

# CotHow enum values
COTHOW_UNSPECIFIED = 0
COTHOW_H_E = 1
COTHOW_M_G = 2
COTHOW_H_G_I_G_O = 3
COTHOW_M_R = 4
COTHOW_M_F = 5
COTHOW_M_P = 6
COTHOW_M_S = 7

_STRING_TO_TYPE = {
    "a-f-G-U-C": COTTYPE_A_F_G_U_C, "a-f-G-U-C-I": COTTYPE_A_F_G_U_C_I,
    "a-n-A-C-F": COTTYPE_A_N_A_C_F, "a-n-A-C-H": COTTYPE_A_N_A_C_H,
    "a-n-A-C": COTTYPE_A_N_A_C, "a-f-A-M-H": COTTYPE_A_F_A_M_H,
    "a-f-A-M": COTTYPE_A_F_A_M, "a-f-A-M-F-F": COTTYPE_A_F_A_M_F_F,
    "a-f-A-M-H-A": COTTYPE_A_F_A_M_H_A, "a-f-A-M-H-U-M": COTTYPE_A_F_A_M_H_U_M,
    "a-h-A-M-F-F": COTTYPE_A_H_A_M_F_F, "a-h-A-M-H-A": COTTYPE_A_H_A_M_H_A,
    "a-u-A-C": COTTYPE_A_U_A_C, "t-x-d-d": COTTYPE_T_X_D_D,
    "a-f-G-E-S-E": COTTYPE_A_F_G_E_S_E, "a-f-G-E-V-C": COTTYPE_A_F_G_E_V_C,
    "a-f-S": COTTYPE_A_F_S, "a-f-A-M-F": COTTYPE_A_F_A_M_F,
    "a-f-A-M-F-C-H": COTTYPE_A_F_A_M_F_C_H, "a-f-A-M-F-U-L": COTTYPE_A_F_A_M_F_U_L,
    "a-f-A-M-F-L": COTTYPE_A_F_A_M_F_L, "a-f-A-M-F-P": COTTYPE_A_F_A_M_F_P,
    "a-f-A-C-H": COTTYPE_A_F_A_C_H, "a-n-A-M-F-Q": COTTYPE_A_N_A_M_F_Q,
    "b-t-f": COTTYPE_B_T_F, "b-r-f-h-c": COTTYPE_B_R_F_H_C,
    "b-a-o-pan": COTTYPE_B_A_O_PAN, "b-a-o-opn": COTTYPE_B_A_O_OPN,
    "b-a-o-can": COTTYPE_B_A_O_CAN, "b-a-o-tbl": COTTYPE_B_A_O_TBL,
    "b-a-g": COTTYPE_B_A_G, "a-f-G": COTTYPE_A_F_G,
    "a-f-G-U": COTTYPE_A_F_G_U, "a-h-G": COTTYPE_A_H_G,
    "a-u-G": COTTYPE_A_U_G, "a-n-G": COTTYPE_A_N_G,
    "b-m-r": COTTYPE_B_M_R, "b-m-p-w": COTTYPE_B_M_P_W,
    "b-m-p-s-p-i": COTTYPE_B_M_P_S_P_I, "u-d-f": COTTYPE_U_D_F,
    "u-d-r": COTTYPE_U_D_R, "u-d-c-c": COTTYPE_U_D_C_C,
    "u-rb-a": COTTYPE_U_RB_A, "a-h-A": COTTYPE_A_H_A,
    "a-u-A": COTTYPE_A_U_A, "a-f-A-M-H-Q": COTTYPE_A_F_A_M_H_Q,
    "a-f-A-C-F": COTTYPE_A_F_A_C_F, "a-f-A-C": COTTYPE_A_F_A_C,
    "a-f-A-C-L": COTTYPE_A_F_A_C_L, "a-f-A": COTTYPE_A_F_A,
    "a-f-A-M-H-C": COTTYPE_A_F_A_M_H_C, "a-n-A-M-F-F": COTTYPE_A_N_A_M_F_F,
    "a-u-A-C-F": COTTYPE_A_U_A_C_F, "a-f-G-U-C-F-T-A": COTTYPE_A_F_G_U_C_F_T_A,
    "a-f-G-U-C-V-S": COTTYPE_A_F_G_U_C_V_S, "a-f-G-U-C-R-X": COTTYPE_A_F_G_U_C_R_X,
    "a-f-G-U-C-I-Z": COTTYPE_A_F_G_U_C_I_Z, "a-f-G-U-C-E-C-W": COTTYPE_A_F_G_U_C_E_C_W,
    "a-f-G-U-C-I-L": COTTYPE_A_F_G_U_C_I_L, "a-f-G-U-C-R-O": COTTYPE_A_F_G_U_C_R_O,
    "a-f-G-U-C-R-V": COTTYPE_A_F_G_U_C_R_V, "a-f-G-U-H": COTTYPE_A_F_G_U_H,
    "a-f-G-U-U-M-S-E": COTTYPE_A_F_G_U_U_M_S_E, "a-f-G-U-S-M-C": COTTYPE_A_F_G_U_S_M_C,
    "a-f-G-E-S": COTTYPE_A_F_G_E_S, "a-f-G-E": COTTYPE_A_F_G_E,
    "a-f-G-E-V-C-U": COTTYPE_A_F_G_E_V_C_U, "a-f-G-E-V-C-ps": COTTYPE_A_F_G_E_V_C_PS,
    "a-u-G-E-V": COTTYPE_A_U_G_E_V, "a-f-S-N-N-R": COTTYPE_A_F_S_N_N_R,
    "a-f-F-B": COTTYPE_A_F_F_B, "b-m-p-s-p-loc": COTTYPE_B_M_P_S_P_LOC,
    "b-i-v": COTTYPE_B_I_V, "b-f-t-r": COTTYPE_B_F_T_R, "b-f-t-a": COTTYPE_B_F_T_A,
    # Typed geometry additions (v2 protocol extension)
    "u-d-f-m": COTTYPE_U_D_F_M, "u-d-p": COTTYPE_U_D_P,
    "b-m-p-s-m": COTTYPE_B_M_P_S_M, "b-m-p-c": COTTYPE_B_M_P_C,
    "u-r-b-c-c": COTTYPE_U_R_B_C_C, "u-r-b-bullseye": COTTYPE_U_R_B_BULLSEYE,
    # Expanded coverage (values 82-124)
    "a-f-G-E-V-A": COTTYPE_A_F_G_E_V_A,
    "a-n-A": COTTYPE_A_N_A,
    "a-u-G-U-C-F": COTTYPE_A_U_G_U_C_F, "a-n-G-U-C-F": COTTYPE_A_N_G_U_C_F,
    "a-h-G-U-C-F": COTTYPE_A_H_G_U_C_F, "a-f-G-U-C-F": COTTYPE_A_F_G_U_C_F,
    "a-u-G-I": COTTYPE_A_U_G_I, "a-n-G-I": COTTYPE_A_N_G_I,
    "a-h-G-I": COTTYPE_A_H_G_I, "a-f-G-I": COTTYPE_A_F_G_I,
    "a-u-G-E-X-M": COTTYPE_A_U_G_E_X_M, "a-n-G-E-X-M": COTTYPE_A_N_G_E_X_M,
    "a-h-G-E-X-M": COTTYPE_A_H_G_E_X_M, "a-f-G-E-X-M": COTTYPE_A_F_G_E_X_M,
    "a-u-S": COTTYPE_A_U_S, "a-n-S": COTTYPE_A_N_S, "a-h-S": COTTYPE_A_H_S,
    "a-u-G-U-C-I-d": COTTYPE_A_U_G_U_C_I_D, "a-n-G-U-C-I-d": COTTYPE_A_N_G_U_C_I_D,
    "a-h-G-U-C-I-d": COTTYPE_A_H_G_U_C_I_D, "a-f-G-U-C-I-d": COTTYPE_A_F_G_U_C_I_D,
    "a-u-G-E-V-A-T": COTTYPE_A_U_G_E_V_A_T, "a-n-G-E-V-A-T": COTTYPE_A_N_G_E_V_A_T,
    "a-h-G-E-V-A-T": COTTYPE_A_H_G_E_V_A_T, "a-f-G-E-V-A-T": COTTYPE_A_F_G_E_V_A_T,
    "a-u-G-U-C-I": COTTYPE_A_U_G_U_C_I, "a-n-G-U-C-I": COTTYPE_A_N_G_U_C_I,
    "a-h-G-U-C-I": COTTYPE_A_H_G_U_C_I,
    "a-n-G-E-V": COTTYPE_A_N_G_E_V, "a-h-G-E-V": COTTYPE_A_H_G_E_V,
    "a-f-G-E-V": COTTYPE_A_F_G_E_V,
    "b-m-p-w-GOTO": COTTYPE_B_M_P_W_GOTO,
    "b-m-p-c-ip": COTTYPE_B_M_P_C_IP, "b-m-p-c-cp": COTTYPE_B_M_P_C_CP,
    "b-m-p-s-p-op": COTTYPE_B_M_P_S_P_OP,
    "u-d-v": COTTYPE_U_D_V, "u-d-v-m": COTTYPE_U_D_V_M,
    "u-d-c-e": COTTYPE_U_D_C_E, "b-i-x-i": COTTYPE_B_I_X_I,
    "b-t-f-d": COTTYPE_B_T_F_D, "b-t-f-r": COTTYPE_B_T_F_R,
    "b-a-o-c": COTTYPE_B_A_O_C, "t-s": COTTYPE_T_S,
}
_TYPE_TO_STRING = {v: k for k, v in _STRING_TO_TYPE.items()}

_STRING_TO_HOW = {
    "h-e": COTHOW_H_E, "m-g": COTHOW_M_G, "h-g-i-g-o": COTHOW_H_G_I_G_O,
    "m-r": COTHOW_M_R, "m-f": COTHOW_M_F, "m-p": COTHOW_M_P, "m-s": COTHOW_M_S,
}
_HOW_TO_STRING = {v: k for k, v in _STRING_TO_HOW.items()}


class CotTypeMapper:
    @staticmethod
    def type_to_enum(cot_type_string: str) -> int:
        return _STRING_TO_TYPE.get(cot_type_string, COTTYPE_OTHER)

    @staticmethod
    def type_to_string(cot_type_id: int) -> "str | None":
        return _TYPE_TO_STRING.get(cot_type_id)

    @staticmethod
    def how_to_enum(how_string: str) -> int:
        return _STRING_TO_HOW.get(how_string, COTHOW_UNSPECIFIED)

    @staticmethod
    def how_to_string(how_id: int) -> "str | None":
        return _HOW_TO_STRING.get(how_id)

    @staticmethod
    def is_aircraft(cot_type_id: int) -> bool:
        s = _TYPE_TO_STRING.get(cot_type_id)
        return CotTypeMapper.is_aircraft_string(s) if s else False

    @staticmethod
    def is_aircraft_string(cot_type_string: str) -> bool:
        atoms = cot_type_string.split("-")
        return len(atoms) >= 3 and atoms[2] == "A"
