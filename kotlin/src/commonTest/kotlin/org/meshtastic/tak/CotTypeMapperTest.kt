package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CotTypeMapperTest {

    @Test
    fun knownTypeStringsMapToCorrectEnumValues() {
        assertEquals(CotTypeMapper.COTTYPE_A_F_G_U_C, CotTypeMapper.typeToEnum("a-f-G-U-C"))
        assertEquals(CotTypeMapper.COTTYPE_A_N_A_C_F, CotTypeMapper.typeToEnum("a-n-A-C-F"))
        assertEquals(CotTypeMapper.COTTYPE_T_X_D_D, CotTypeMapper.typeToEnum("t-x-d-d"))
        assertEquals(CotTypeMapper.COTTYPE_B_T_F, CotTypeMapper.typeToEnum("b-t-f"))
        assertEquals(CotTypeMapper.COTTYPE_B_R_F_H_C, CotTypeMapper.typeToEnum("b-r-f-h-c"))
        assertEquals(CotTypeMapper.COTTYPE_B_A_O_OPN, CotTypeMapper.typeToEnum("b-a-o-opn"))
        assertEquals(CotTypeMapper.COTTYPE_U_D_F, CotTypeMapper.typeToEnum("u-d-f"))
    }

    @Test
    fun unknownTypeStringsMapToOther() {
        assertEquals(CotTypeMapper.COTTYPE_OTHER, CotTypeMapper.typeToEnum("a-f-G-U-C-X-Y-Z"))
        assertEquals(CotTypeMapper.COTTYPE_OTHER, CotTypeMapper.typeToEnum("z-unknown"))
        assertEquals(CotTypeMapper.COTTYPE_OTHER, CotTypeMapper.typeToEnum(""))
    }

    @Test
    fun enumValuesRoundTripThroughString() {
        for (enumVal in 1..75) {
            val str = CotTypeMapper.typeToString(enumVal)
            if (str != null) {
                assertEquals(enumVal, CotTypeMapper.typeToEnum(str),
                    "Round-trip failed for enum $enumVal -> $str")
            }
        }
    }

    @Test
    fun otherReturnsNullString() {
        assertNull(CotTypeMapper.typeToString(CotTypeMapper.COTTYPE_OTHER))
    }

    @Test
    fun aircraftClassificationIsCorrect() {
        // Air domain types
        assertTrue(CotTypeMapper.isAircraft(CotTypeMapper.COTTYPE_A_N_A_C_F))
        assertTrue(CotTypeMapper.isAircraft(CotTypeMapper.COTTYPE_A_F_A_M_H))
        assertTrue(CotTypeMapper.isAircraft(CotTypeMapper.COTTYPE_A_H_A_M_F_F))
        assertTrue(CotTypeMapper.isAircraft(CotTypeMapper.COTTYPE_A_U_A_C))
        assertTrue(CotTypeMapper.isAircraft(CotTypeMapper.COTTYPE_A_H_A))
        assertTrue(CotTypeMapper.isAircraft(CotTypeMapper.COTTYPE_A_U_A))

        // Non-air domain types
        assertFalse(CotTypeMapper.isAircraft(CotTypeMapper.COTTYPE_A_F_G_U_C))
        assertFalse(CotTypeMapper.isAircraft(CotTypeMapper.COTTYPE_T_X_D_D))
        assertFalse(CotTypeMapper.isAircraft(CotTypeMapper.COTTYPE_B_T_F))
        assertFalse(CotTypeMapper.isAircraft(CotTypeMapper.COTTYPE_U_D_F))
        assertFalse(CotTypeMapper.isAircraft(CotTypeMapper.COTTYPE_A_F_G))
        assertFalse(CotTypeMapper.isAircraft(CotTypeMapper.COTTYPE_A_F_S))
    }

    @Test
    fun aircraftStringClassificationIsCorrect() {
        assertTrue(CotTypeMapper.isAircraftString("a-f-A-M-H"))
        assertTrue(CotTypeMapper.isAircraftString("a-n-A-C-F"))
        assertTrue(CotTypeMapper.isAircraftString("a-h-A-M-F-F"))

        assertFalse(CotTypeMapper.isAircraftString("a-f-G-U-C"))
        assertFalse(CotTypeMapper.isAircraftString("b-t-f"))
        assertFalse(CotTypeMapper.isAircraftString("t-x-d-d"))
        assertFalse(CotTypeMapper.isAircraftString("a-f-S"))
    }

    @Test
    fun howStringsMapCorrectly() {
        assertEquals(CotTypeMapper.COTHOW_H_E, CotTypeMapper.howToEnum("h-e"))
        assertEquals(CotTypeMapper.COTHOW_M_G, CotTypeMapper.howToEnum("m-g"))
        assertEquals(CotTypeMapper.COTHOW_H_G_I_G_O, CotTypeMapper.howToEnum("h-g-i-g-o"))
        assertEquals(CotTypeMapper.COTHOW_M_R, CotTypeMapper.howToEnum("m-r"))
        assertEquals(CotTypeMapper.COTHOW_UNSPECIFIED, CotTypeMapper.howToEnum("unknown-how"))
    }

    @Test
    fun howEnumRoundTripsThroughString() {
        for (enumVal in 1..7) {
            val str = CotTypeMapper.howToString(enumVal)
            if (str != null) {
                assertEquals(enumVal, CotTypeMapper.howToEnum(str),
                    "How round-trip failed for enum $enumVal -> $str")
            }
        }
    }
}
