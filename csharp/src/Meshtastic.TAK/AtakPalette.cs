using Meshtastic.Protobufs;

namespace Meshtastic.TAK;

/// <summary>
/// Bidirectional lookup between ATAK's 14-color palette and the Meshtastic
/// <c>Team</c> protobuf enum values. Used by the CoT parser/builder to encode
/// stroke/fill/marker colors as a 1-2 byte enum on the wire when the source
/// ARGB matches a palette entry, falling back to the exact <c>_argb</c>
/// fixed32 field when it doesn't.
///
/// ARGB values are stored as <c>uint</c> to match the proto-generated
/// <c>fixed32 _argb</c> field type. ATAK's XML writes them as signed Int32
/// decimals (e.g. <c>-1</c> for white), so the parser converts via
/// <c>unchecked((uint)signedValue)</c> and the builder via
/// <c>unchecked((int)unsignedValue)</c>.
/// </summary>
public static class AtakPalette
{
    /// <summary>Sentinel "no palette match" value matching Team.UnspecifedColor.</summary>
    public const int Unspecified = 0;

    private static readonly Dictionary<Team, uint> TeamToArgbTable = new()
    {
        { Team.White,     0xFFFFFFFFu },
        { Team.Yellow,    0xFFFFFF00u },
        { Team.Orange,    0xFFFF7700u },
        { Team.Magenta,   0xFFFF00FFu },
        { Team.Red,       0xFFFF0000u },
        { Team.Maroon,    0xFF7F0000u }, // ATAK label "Brown"
        { Team.Purple,    0xFF7F007Fu },
        { Team.DarkBlue,  0xFF00007Fu }, // ATAK label "Navy"
        { Team.Blue,      0xFF0000FFu },
        { Team.Cyan,      0xFF00FFFFu },
        { Team.Teal,      0xFF007F7Fu },
        { Team.Green,     0xFF00FF00u },
        { Team.DarkGreen, 0xFF007F00u },
        { Team.Brown,     0xFF777777u }, // ATAK "Gray" — misnamed in Meshtastic
    };

    private static readonly Dictionary<uint, Team> ArgbToTeamTable =
        TeamToArgbTable.ToDictionary(kv => kv.Value, kv => kv.Key);

    /// <summary>
    /// Look up a <see cref="Team"/> enum value by its exact ARGB bit pattern.
    /// Returns <see cref="Team.UnspecifedColor"/> if the ARGB doesn't match
    /// one of the 14 palette entries; callers should still populate the
    /// <c>_argb</c> fallback field in that case so round-trip is preserved
    /// for custom colors.
    /// </summary>
    public static Team ArgbToTeam(uint argb)
    {
        return ArgbToTeamTable.TryGetValue(argb, out var team) ? team : Team.UnspecifedColor;
    }

    /// <summary>
    /// Look up the exact ARGB bit pattern by <see cref="Team"/> enum value.
    /// Returns <c>null</c> for <see cref="Team.UnspecifedColor"/> and any
    /// unknown Team value; the builder should emit the <c>_argb</c> fallback
    /// field in that case.
    /// </summary>
    public static uint? TeamToArgb(Team team)
    {
        return TeamToArgbTable.TryGetValue(team, out var argb) ? argb : null;
    }

    /// <summary>
    /// Resolve the ARGB int to emit in XML given a (palette, fallback) pair.
    /// Uses the palette's canonical ARGB when set, otherwise the stored
    /// fallback bits so custom user-picked colors round-trip byte-for-byte.
    /// </summary>
    public static uint ResolveColor(Team palette, uint fallback)
    {
        return TeamToArgb(palette) ?? fallback;
    }
}
