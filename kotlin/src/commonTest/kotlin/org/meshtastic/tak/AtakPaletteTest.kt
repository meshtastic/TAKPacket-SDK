package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for [AtakPalette] — ARGB ↔ Team enum round-trip, edge cases,
 * and boundary checks.
 */
class AtakPaletteTest {

    @Test
    fun allPaletteColorsRoundTrip() {
        // Every palette team (1..14) must map to an ARGB and back
        for (team in 1..14) {
            val argb = AtakPalette.teamToArgb(team)
            requireNotNull(argb) { "Team $team should map to an ARGB value" }
            assertEquals(team, AtakPalette.argbToTeam(argb),
                "ARGB 0x${argb.toUInt().toString(16)} should map back to team $team")
        }
    }

    @Test
    fun unspecifiedTeamReturnsNullArgb() {
        assertNull(AtakPalette.teamToArgb(AtakPalette.UNSPECIFIED),
            "UNSPECIFIED (0) should return null ARGB")
    }

    @Test
    fun unknownTeamReturnsNullArgb() {
        assertNull(AtakPalette.teamToArgb(99),
            "Unknown team 99 should return null")
        assertNull(AtakPalette.teamToArgb(-1),
            "Negative team should return null")
        assertNull(AtakPalette.teamToArgb(15),
            "Team 15 (just above range) should return null")
    }

    @Test
    fun unknownArgbReturnsUnspecified() {
        assertEquals(AtakPalette.UNSPECIFIED, AtakPalette.argbToTeam(0x12345678),
            "Arbitrary ARGB should return UNSPECIFIED")
        assertEquals(AtakPalette.UNSPECIFIED, AtakPalette.argbToTeam(0),
            "Zero ARGB should return UNSPECIFIED")
    }

    @Test
    fun isPaletteTeamChecks() {
        assertFalse(AtakPalette.isPaletteTeam(0), "UNSPECIFIED is not a palette team")
        assertTrue(AtakPalette.isPaletteTeam(1), "1 (White) is a palette team")
        assertTrue(AtakPalette.isPaletteTeam(14), "14 is a palette team")
        assertFalse(AtakPalette.isPaletteTeam(15), "15 is out of range")
        assertFalse(AtakPalette.isPaletteTeam(-1), "Negative is not a palette team")
    }

    @Test
    fun knownColorValues() {
        // Spot-check a few well-known ATAK colors
        assertEquals(0xFFFF0000.toInt(), AtakPalette.teamToArgb(5),
            "Team 5 = Red = 0xFFFF0000")
        assertEquals(0xFF00FFFF.toInt(), AtakPalette.teamToArgb(10),
            "Team 10 = Cyan = 0xFF00FFFF")
        assertEquals(0xFFFFFFFF.toInt(), AtakPalette.teamToArgb(1),
            "Team 1 = White = 0xFFFFFFFF")
        assertEquals(0xFF00FF00.toInt(), AtakPalette.teamToArgb(12),
            "Team 12 = Green = 0xFF00FF00")
    }

    @Test
    fun redArgbMapsToTeam5() {
        assertEquals(5, AtakPalette.argbToTeam(0xFFFF0000.toInt()),
            "Red ARGB should map to team 5")
    }

    @Test
    fun spotMarkerRedArgbMapsCorrectly() {
        // -65536 is the signed representation of 0xFFFF0000 that ATAK uses
        // in <color argb="-65536"/>
        assertEquals(5, AtakPalette.argbToTeam(-65536),
            "-65536 (signed red) should map to team 5 (Red)")
    }
}
