from meshtastic_tak.cot_type_mapper import *


def test_known_types_map_correctly():
    assert CotTypeMapper.type_to_enum("a-f-G-U-C") == COTTYPE_A_F_G_U_C
    assert CotTypeMapper.type_to_enum("a-n-A-C-F") == COTTYPE_A_N_A_C_F
    assert CotTypeMapper.type_to_enum("t-x-d-d") == COTTYPE_T_X_D_D
    assert CotTypeMapper.type_to_enum("b-t-f") == COTTYPE_B_T_F
    assert CotTypeMapper.type_to_enum("b-r-f-h-c") == COTTYPE_B_R_F_H_C


def test_unknown_type_returns_other():
    assert CotTypeMapper.type_to_enum("z-unknown") == COTTYPE_OTHER
    assert CotTypeMapper.type_to_enum("") == COTTYPE_OTHER


def test_round_trip():
    for enum_val in range(1, 76):
        s = CotTypeMapper.type_to_string(enum_val)
        if s is not None:
            assert CotTypeMapper.type_to_enum(s) == enum_val, f"Round-trip failed for {enum_val} -> {s}"


def test_aircraft_classification():
    assert CotTypeMapper.is_aircraft(COTTYPE_A_N_A_C_F)
    assert CotTypeMapper.is_aircraft(COTTYPE_A_F_A_M_H)
    assert CotTypeMapper.is_aircraft(COTTYPE_A_H_A_M_F_F)
    assert CotTypeMapper.is_aircraft(COTTYPE_A_H_A)
    assert CotTypeMapper.is_aircraft(COTTYPE_A_U_A)
    assert CotTypeMapper.is_aircraft(COTTYPE_A_U_A_C)

    assert not CotTypeMapper.is_aircraft(COTTYPE_A_F_G_U_C)
    assert not CotTypeMapper.is_aircraft(COTTYPE_T_X_D_D)
    assert not CotTypeMapper.is_aircraft(COTTYPE_B_T_F)
    assert not CotTypeMapper.is_aircraft(COTTYPE_A_F_S)


def test_how_mapping():
    assert CotTypeMapper.how_to_enum("h-e") == COTHOW_H_E
    assert CotTypeMapper.how_to_enum("m-g") == COTHOW_M_G
    assert CotTypeMapper.how_to_enum("m-r") == COTHOW_M_R
    assert CotTypeMapper.how_to_enum("unknown") == COTHOW_UNSPECIFIED


def test_how_round_trip():
    for enum_val in range(1, 8):
        s = CotTypeMapper.how_to_string(enum_val)
        if s is not None:
            assert CotTypeMapper.how_to_enum(s) == enum_val


def test_other_returns_none():
    assert CotTypeMapper.type_to_string(COTTYPE_OTHER) is None


def test_is_aircraft_string():
    assert CotTypeMapper.is_aircraft_string("a-f-A-M-H")
    assert CotTypeMapper.is_aircraft_string("a-n-A-C-F")
    assert CotTypeMapper.is_aircraft_string("a-h-A-M-F-F")
    assert not CotTypeMapper.is_aircraft_string("a-f-G-U-C")
    assert not CotTypeMapper.is_aircraft_string("b-t-f")
    assert not CotTypeMapper.is_aircraft_string("a-f-S")
