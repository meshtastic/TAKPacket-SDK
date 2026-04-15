import Foundation

/// Bidirectional lookup between ATAK's 14-color palette and the Meshtastic
/// `Team` protobuf enum values. Used by the CoT parser/builder to encode
/// stroke/fill/marker colors as a 1–2 byte enum on the wire when the source
/// ARGB matches a palette entry, falling back to the exact `_argb` fixed32
/// field when it doesn't.
///
/// The exact ARGB constants are taken from ATAK-CIV's
/// `com.atakmap.android.gui.ColorPalette` source file. Position numbers match
/// the `Team` enum tag values in atak.proto. ColorPalette's display names
/// don't always line up with Meshtastic's `Team` enum names — the numeric
/// positions match and the enum tag is what rides on the wire, so the
/// naming drift is cosmetic.
public enum AtakPalette {

    /// Team enum value → exact ARGB bit pattern. Stored as `UInt32` to
    /// match the proto-generated field type for `fixed32 _argb = N` fields.
    /// ATAK's XML uses signed decimal (e.g. `-65536` for red) — parse as
    /// Int32 and convert via `UInt32(bitPattern:)`.
    private static let teamToArgbTable: [Team: UInt32] = [
        .white:     0xFFFFFFFF,
        .yellow:    0xFFFFFF00,
        .orange:    0xFFFF7700,
        .magenta:   0xFFFF00FF,
        .red:       0xFFFF0000,
        .maroon:    0xFF7F0000,
        .purple:    0xFF7F007F,
        .darkBlue:  0xFF00007F,
        .blue:      0xFF0000FF,
        .cyan:      0xFF00FFFF,
        .teal:      0xFF007F7F,
        .green:     0xFF00FF00,
        .darkGreen: 0xFF007F00,
        .brown:     0xFF777777,
    ]

    private static let argbToTeamTable: [UInt32: Team] = {
        var result: [UInt32: Team] = [:]
        for (team, argb) in teamToArgbTable { result[argb] = team }
        return result
    }()

    /// Look up a `Team` enum value by its exact ARGB bit pattern. Returns
    /// `.unspecifedColor` when the ARGB doesn't match one of the 14 palette
    /// entries — callers should still populate the `_argb` fallback field
    /// in that case so round-trip is preserved for custom colors.
    public static func argbToTeam(_ argb: UInt32) -> Team {
        argbToTeamTable[argb] ?? .unspecifedColor
    }

    /// Look up the exact ARGB bit pattern by `Team` enum value. Returns
    /// `nil` for `.unspecifedColor` and for any unknown Team value; the
    /// builder should emit the `_argb` fallback field in that case.
    public static func teamToArgb(_ team: Team) -> UInt32? {
        teamToArgbTable[team]
    }

    /// Resolve the ARGB int to emit in XML given a (palette, fallback) pair.
    /// Uses the palette's canonical ARGB when set, otherwise the stored
    /// fallback bits so custom colors round-trip byte-for-byte.
    public static func resolveColor(palette: Team, fallback: UInt32) -> UInt32 {
        teamToArgb(palette) ?? fallback
    }
}
