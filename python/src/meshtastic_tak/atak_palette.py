"""
Bidirectional lookup between ATAK's 14-color palette and the Meshtastic
Team protobuf enum values.

Used by the CoT parser/builder to encode stroke/fill/marker colors as a
1-2 byte enum on the wire when the source ARGB matches a palette entry,
falling back to the exact `_argb` fixed32 field when it doesn't.

The exact ARGB constants come from ATAK-CIV's
`com.atakmap.android.gui.ColorPalette` source. Position numbers match the
`Team` enum tag values in atak.proto. Meshtastic's enum display names
don't always line up with ColorPalette's (position 6 is "Maroon" here
versus ATAK's "Brown" label), but the numeric positions match and the
enum tag is what rides on the wire.

Values are stored as Python ints in the 0..0xFFFFFFFF range to match the
proto-generated `fixed32 _argb` field type (unsigned 32-bit).
"""

#: Sentinel "no palette match" value matching Team.Unspecifed_Color in atak.proto.
UNSPECIFIED = 0

#: Team enum value → exact ARGB bit pattern (unsigned 32-bit).
_TEAM_TO_ARGB = {
    1: 0xFFFFFFFF,   # White
    2: 0xFFFFFF00,   # Yellow
    3: 0xFFFF7700,   # Orange
    4: 0xFFFF00FF,   # Magenta
    5: 0xFFFF0000,   # Red
    6: 0xFF7F0000,   # Maroon (ATAK label "Brown")
    7: 0xFF7F007F,   # Purple
    8: 0xFF00007F,   # Dark Blue (ATAK label "Navy")
    9: 0xFF0000FF,   # Blue
    10: 0xFF00FFFF,  # Cyan
    11: 0xFF007F7F,  # Teal
    12: 0xFF00FF00,  # Green
    13: 0xFF007F00,  # Dark Green
    14: 0xFF777777,  # Brown (ATAK label "Gray" — misnamed in Meshtastic)
}

_ARGB_TO_TEAM = {v: k for k, v in _TEAM_TO_ARGB.items()}


def argb_to_team(argb: int) -> int:
    """Look up a Team enum value by its exact ARGB bit pattern.

    Returns :data:`UNSPECIFIED` (0) if the ARGB doesn't match one of the
    14 palette entries. Callers should still populate the `_argb` fallback
    field with the original bits so round-trip is preserved for custom colors.
    """
    # Normalize to unsigned 32-bit so the lookup matches regardless of whether
    # the caller passed a signed int (e.g. from parsing `<strokeColor value="-1"/>`).
    return _ARGB_TO_TEAM.get(argb & 0xFFFFFFFF, UNSPECIFIED)


def team_to_argb(team: int):
    """Look up the exact ARGB bit pattern by Team enum value.

    Returns ``None`` for :data:`UNSPECIFIED` and for any unknown Team value;
    the caller should fall back to the stored `_argb` field instead.
    """
    return _TEAM_TO_ARGB.get(team)


def resolve_color(palette_team: int, fallback_argb: int) -> int:
    """Return the ARGB int to emit in XML.

    Uses the palette's canonical ARGB when the Team tag is set, otherwise
    the stored fallback bits so custom user-picked colors round-trip
    byte-for-byte.
    """
    resolved = team_to_argb(palette_team)
    return resolved if resolved is not None else (fallback_argb & 0xFFFFFFFF)


def is_palette_team(team: int) -> bool:
    """True when the value refers to one of the 14 named palette colors."""
    return 1 <= team <= 14
