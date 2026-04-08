package org.meshtastic.tak

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class CotTypeMapperTest {

    @Test
    fun `known type strings map to correct enum values`() {
        assertEquals(CotTypeMapper.COTTYPE_A_F_G_U_C, CotTypeMapper.typeToEnum("a-f-G-U-C"))
        assertEquals(CotTypeMapper.COTTYPE_A_N_A_C_F, CotTypeMapper.typeToEnum("a-n-A-C-F"))
        assertEquals(CotTypeMapper.COTTYPE_T_X_D_D, CotTypeMapper.typeToEnum("t-x-d-d"))
        assertEquals(CotTypeMapper.COTTYPE_B_T_F, CotTypeMapper.typeToEnum("b-t-f"))
        assertEquals(CotTypeMapper.COTTYPE_B_R_F_H_C, CotTypeMapper.typeToEnum("b-r-f-h-c"))
        assertEquals(CotTypeMapper.COTTYPE_B_A_O_OPN, CotTypeMapper.typeToEnum("b-a-o-opn"))
        assertEquals(CotTypeMapper.COTTYPE_U_D_F, CotTypeMapper.typeToEnum("u-d-f"))
    }

    @Test
    fun `unknown type strings map to OTHER`() {
        assertEquals(CotTypeMapper.COTTYPE_OTHER, CotTypeMapper.typeToEnum("a-f-G-U-C-X-Y-Z"))
        assertEquals(CotTypeMapper.COTTYPE_OTHER, CotTypeMapper.typeToEnum("z-unknown"))
        assertEquals(CotTypeMapper.COTTYPE_OTHER, CotTypeMapper.typeToEnum(""))
    }

    @Test
    fun `enum values round-trip through string`() {
        for (enumVal in 1..75) {
            val str = CotTypeMapper.typeToString(enumVal)
            if (str != null) {
                assertEquals(enumVal, CotTypeMapper.typeToEnum(str),
                    "Round-trip failed for enum $enumVal -> $str")
            }
        }
    }

    @Test
    fun `OTHER returns null string`() {
        assertNull(CotTypeMapper.typeToString(CotTypeMapper.COTTYPE_OTHER))
    }

    @Test
    fun `aircraft classification is correct`() {
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
    fun `aircraft string classification is correct`() {
        assertTrue(CotTypeMapper.isAircraftString("a-f-A-M-H"))
        assertTrue(CotTypeMapper.isAircraftString("a-n-A-C-F"))
        assertTrue(CotTypeMapper.isAircraftString("a-h-A-M-F-F"))

        assertFalse(CotTypeMapper.isAircraftString("a-f-G-U-C"))
        assertFalse(CotTypeMapper.isAircraftString("b-t-f"))
        assertFalse(CotTypeMapper.isAircraftString("t-x-d-d"))
        assertFalse(CotTypeMapper.isAircraftString("a-f-S"))
    }

    @Test
    fun `how strings map correctly`() {
        assertEquals(CotTypeMapper.COTHOW_H_E, CotTypeMapper.howToEnum("h-e"))
        assertEquals(CotTypeMapper.COTHOW_M_G, CotTypeMapper.howToEnum("m-g"))
        assertEquals(CotTypeMapper.COTHOW_H_G_I_G_O, CotTypeMapper.howToEnum("h-g-i-g-o"))
        assertEquals(CotTypeMapper.COTHOW_M_R, CotTypeMapper.howToEnum("m-r"))
        assertEquals(CotTypeMapper.COTHOW_UNSPECIFIED, CotTypeMapper.howToEnum("unknown-how"))
    }

    @Test
    fun `how enum round-trips through string`() {
        for (enumVal in 1..7) {
            val str = CotTypeMapper.howToString(enumVal)
            if (str != null) {
                assertEquals(enumVal, CotTypeMapper.howToEnum(str),
                    "How round-trip failed for enum $enumVal -> $str")
            }
        }
    }
}
