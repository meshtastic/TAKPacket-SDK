/**
 * Bidirectional lookup between ATAK's 14-color palette and the Meshtastic
 * Team protobuf enum values.
 *
 * Used by the CoT parser/builder to encode stroke/fill/marker colors as a
 * 1-2 byte enum on the wire when the source ARGB matches a palette entry,
 * falling back to the exact `_argb` fixed32 field when it doesn't.
 *
 * ARGB values are stored as JavaScript numbers in the 0..0xFFFFFFFF range.
 * ATAK XML serializes them as signed Int32 decimals (e.g. `-1` for white);
 * parsers should normalize with `v >>> 0` before lookup, and builders should
 * emit `v | 0` to get the signed form.
 */

/** Sentinel "no palette match" value matching Team.Unspecifed_Color in atak.proto. */
export const UNSPECIFIED = 0;

/** Team enum value → exact ARGB bit pattern (unsigned 32-bit). */
const TEAM_TO_ARGB: Record<number, number> = {
  1: 0xffffffff, // White
  2: 0xffffff00, // Yellow
  3: 0xffff7700, // Orange
  4: 0xffff00ff, // Magenta
  5: 0xffff0000, // Red
  6: 0xff7f0000, // Maroon (ATAK label "Brown")
  7: 0xff7f007f, // Purple
  8: 0xff00007f, // Dark Blue (ATAK label "Navy")
  9: 0xff0000ff, // Blue
  10: 0xff00ffff, // Cyan
  11: 0xff007f7f, // Teal
  12: 0xff00ff00, // Green
  13: 0xff007f00, // Dark Green
  14: 0xff777777, // Brown (ATAK label "Gray" — misnamed in Meshtastic)
};

const ARGB_TO_TEAM: Record<number, number> = {};
for (const [team, argb] of Object.entries(TEAM_TO_ARGB)) {
  ARGB_TO_TEAM[argb as unknown as number] = parseInt(team, 10);
}

/**
 * Look up a `Team` enum value by its exact ARGB bit pattern. Returns
 * {@link UNSPECIFIED} (0) if the ARGB doesn't match one of the 14 palette
 * entries — callers should still populate the `_argb` fallback field in
 * that case so round-trip is preserved for custom colors.
 */
export function argbToTeam(argb: number): number {
  return ARGB_TO_TEAM[argb >>> 0] ?? UNSPECIFIED;
}

/**
 * Look up the exact ARGB bit pattern by `Team` enum value. Returns
 * `undefined` for {@link UNSPECIFIED} and for any unknown Team value;
 * the builder should emit the `_argb` fallback field in that case.
 */
export function teamToArgb(team: number): number | undefined {
  return TEAM_TO_ARGB[team];
}

/**
 * Resolve the ARGB int to emit in XML given a (palette, fallback) pair.
 * Uses the palette's canonical ARGB when set, otherwise the stored
 * fallback bits so custom user-picked colors round-trip byte-for-byte.
 */
export function resolveColor(paletteTeam: number, fallbackArgb: number): number {
  const fromPalette = teamToArgb(paletteTeam);
  return fromPalette !== undefined ? fromPalette : (fallbackArgb >>> 0);
}
