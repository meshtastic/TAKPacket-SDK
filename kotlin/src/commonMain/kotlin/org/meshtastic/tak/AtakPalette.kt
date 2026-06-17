package org.meshtastic.tak

/**
 * Bidirectional lookup between ATAK's 14-color palette and the Meshtastic
 * `Team` protobuf enum values. Used by the CoT parser/builder to encode
 * stroke/fill/marker colors as a 1–2 byte enum on the wire when the source
 * ARGB matches a palette entry, falling back to the exact `_argb` fixed32
 * field when it doesn't.
 *
 * ## Provenance
 *
 * The exact ARGB constants are taken from ATAK-CIV's
 * `com.atakmap.android.gui.ColorPalette` source file at
 * `atak/ATAK/app/src/main/java/com/atakmap/android/gui/ColorPalette.java`,
 * specifically the `COLOR1..COLOR14` class constants. ColorPalette's display
 * names don't always line up with Meshtastic's `Team` enum names — ATAK
 * labels position 6 "Brown" but the value `0xFF7F0000` is a dark red
 * (maroon); Meshtastic calls it `Maroon`, which is actually more accurate.
 * Position 14 is similarly misnamed: ATAK's `0xFF777777` is mid-gray and
 * Meshtastic's `Brown` label is incorrect, but the numeric positions match
 * and the enum tag is what rides on the wire, so the naming drift is
 * cosmetic.
 *
 * ## Wire-format contract
 *
 * - Every color field in the new typed payloads (DrawnShape.stroke_color,
 *   DrawnShape.fill_color, Marker.color, RangeAndBearing.stroke_color) is
 *   paired with a `_argb` fixed32 fallback.
 * - On **encode**: parser extracts the exact ARGB from the source XML,
 *   calls [argbToTeam] — if non-null it populates the palette field with
 *   that Team value; either way it stores the exact bits in the `_argb`
 *   field so readers can recover them byte-for-byte.
 * - On **decode**: builder reads the palette field first. If it is
 *   non-`Unspecifed_Color` (0), builder resolves the ARGB via [teamToArgb]
 *   and emits that as the `<strokeColor value="...">` int. If the palette
 *   is `Unspecifed_Color`, builder uses the stored `_argb` bits.
 *
 * This gives palette matches a 2-byte wire cost (tag + varint enum) and
 * custom user-picked colors a 5-byte cost (tag + fixed32), with the exact
 * bits always preserved for round-trip fidelity.
 *
 * ## Bit patterns
 *
 * The values below are the exact `int` bit patterns ATAK serializes in
 * `<strokeColor value="...">` / `<fillColor value="...">` / `<color argb="...">`.
 * `0xFFFFFFFF.toInt() == -1`, `0xFFFF0000.toInt() == -65536`, and so on.
 * ATAK emits them as signed decimal ints so the parser reads them as such.
 *
 * This object is immutable and safe to share across threads.
 */
public object AtakPalette {

    /**
     * Meshtastic `Team` enum value for "no palette match" / "use the exact
     * ARGB fallback". Mirrors the proto-generated `Team.Unspecifed_Color = 0`.
     * Exposed here so parser/builder code can use a named constant instead
     * of a magic zero.
     */
    public const val UNSPECIFIED: Int = 0

    /**
     * Team enum value → exact ARGB bit pattern.
     * Position numbers match the `Team` enum tag values in atak.proto.
     */
    private val TEAM_TO_ARGB: Map<Int, Int> = mapOf(
        1 to 0xFFFFFFFF.toInt(),  // White
        2 to 0xFFFFFF00.toInt(),  // Yellow
        3 to 0xFFFF7700.toInt(),  // Orange
        4 to 0xFFFF00FF.toInt(),  // Magenta
        5 to 0xFFFF0000.toInt(),  // Red
        6 to 0xFF7F0000.toInt(),  // Maroon (ATAK label "Brown")
        7 to 0xFF7F007F.toInt(),  // Purple
        8 to 0xFF00007F.toInt(),  // Dark_Blue (ATAK label "Navy")
        9 to 0xFF0000FF.toInt(),  // Blue
        10 to 0xFF00FFFF.toInt(), // Cyan
        11 to 0xFF007F7F.toInt(), // Teal (ATAK label "Turqoise")
        12 to 0xFF00FF00.toInt(), // Green
        13 to 0xFF007F00.toInt(), // Dark_Green (ATAK label "Forest")
        14 to 0xFF777777.toInt(), // Brown — misnamed in Meshtastic; ATAK value is mid-gray
    )

    /**
     * Exact ARGB → Team enum value. Built by inverting [TEAM_TO_ARGB] at
     * load time so [argbToTeam] is O(1).
     */
    private val ARGB_TO_TEAM: Map<Int, Int> =
        TEAM_TO_ARGB.entries.associate { (team, argb) -> argb to team }

    /**
     * Look up a Team enum value by its exact ARGB bit pattern.
     *
     * Returns [UNSPECIFIED] (0) if the ARGB doesn't match any of the 14
     * palette entries. Callers should still populate the `_argb` fallback
     * field in the proto message with the original bits so round-trip is
     * preserved for custom colors.
     */
    public fun argbToTeam(argb: Int): Int = ARGB_TO_TEAM[argb] ?: UNSPECIFIED

    /**
     * Look up an exact ARGB bit pattern by Team enum value.
     *
     * Returns `null` for [UNSPECIFIED] (meaning "no palette entry, use the
     * `_argb` field") and for any unknown Team value. The builder should
     * handle a `null` return by reading the `_argb` fallback field instead
     * of emitting a `<strokeColor>` element.
     */
    public fun teamToArgb(team: Int): Int? = TEAM_TO_ARGB[team]

    /**
     * True if the given Team value refers to one of the 14 named palette
     * colors (i.e. not [UNSPECIFIED] and within the valid enum range).
     */
    public fun isPaletteTeam(team: Int): Boolean = team in 1..14
}
