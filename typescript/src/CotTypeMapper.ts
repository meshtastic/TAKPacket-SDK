/**
 * Maps CoT type strings to/from CotType enum values and classifies aircraft types.
 *
 * ## Forward-compatibility contract
 *
 * When a CoT type string is not in the known mapping — either because it's
 * new (a v2.1 peer added a type the v2 receiver doesn't know) or because
 * it's legitimately niche — `typeToEnum` returns {@link COTTYPE_OTHER} (0)
 * and the caller populates `cot_type_str` (field 23) with the full original
 * string. On the wire, the combination `cot_type_id = 0` + `cot_type_str = "…"`
 * is the canonical way to carry unknown types without losing information:
 * the reconstructed CoT XML uses `cot_type_str` directly, so
 * `<event type="…">` comes back byte-identical regardless of whether the
 * receiver's enum knows the value.
 *
 * Receivers that want to detect the downgrade should check
 * `cotTypeId === COTTYPE_OTHER && cotTypeStr`.
 */

export const COTTYPE_OTHER = 0;
export const COTHOW_UNSPECIFIED = 0;

const STRING_TO_TYPE: Record<string, number> = {
  "a-f-G-U-C": 1, "a-f-G-U-C-I": 2, "a-n-A-C-F": 3, "a-n-A-C-H": 4,
  "a-n-A-C": 5, "a-f-A-M-H": 6, "a-f-A-M": 7, "a-f-A-M-F-F": 8,
  "a-f-A-M-H-A": 9, "a-f-A-M-H-U-M": 10, "a-h-A-M-F-F": 11, "a-h-A-M-H-A": 12,
  "a-u-A-C": 13, "t-x-d-d": 14, "a-f-G-E-S-E": 15, "a-f-G-E-V-C": 16,
  "a-f-S": 17, "a-f-A-M-F": 18, "a-f-A-M-F-C-H": 19, "a-f-A-M-F-U-L": 20,
  "a-f-A-M-F-L": 21, "a-f-A-M-F-P": 22, "a-f-A-C-H": 23, "a-n-A-M-F-Q": 24,
  "b-t-f": 25, "b-r-f-h-c": 26, "b-a-o-pan": 27, "b-a-o-opn": 28,
  "b-a-o-can": 29, "b-a-o-tbl": 30, "b-a-g": 31, "a-f-G": 32,
  "a-f-G-U": 33, "a-h-G": 34, "a-u-G": 35, "a-n-G": 36,
  "b-m-r": 37, "b-m-p-w": 38, "b-m-p-s-p-i": 39, "u-d-f": 40,
  "u-d-r": 41, "u-d-c-c": 42, "u-rb-a": 43, "a-h-A": 44,
  "a-u-A": 45, "a-f-A-M-H-Q": 46,
  "a-f-A-C-F": 47, "a-f-A-C": 48, "a-f-A-C-L": 49, "a-f-A": 50,
  "a-f-A-M-H-C": 51, "a-n-A-M-F-F": 52, "a-u-A-C-F": 53,
  "a-f-G-U-C-F-T-A": 54, "a-f-G-U-C-V-S": 55, "a-f-G-U-C-R-X": 56,
  "a-f-G-U-C-I-Z": 57, "a-f-G-U-C-E-C-W": 58, "a-f-G-U-C-I-L": 59,
  "a-f-G-U-C-R-O": 60, "a-f-G-U-C-R-V": 61, "a-f-G-U-H": 62,
  "a-f-G-U-U-M-S-E": 63, "a-f-G-U-S-M-C": 64, "a-f-G-E-S": 65,
  "a-f-G-E": 66, "a-f-G-E-V-C-U": 67, "a-f-G-E-V-C-ps": 68,
  "a-u-G-E-V": 69, "a-f-S-N-N-R": 70, "a-f-F-B": 71,
  "b-m-p-s-p-loc": 72, "b-i-v": 73, "b-f-t-r": 74, "b-f-t-a": 75,
  // Typed geometry additions (v2 protocol extension)
  "u-d-f-m": 76, "u-d-p": 77, "b-m-p-s-m": 78, "b-m-p-c": 79,
  "u-r-b-c-c": 80, "u-r-b-bullseye": 81,
  // Expanded coverage (values 82-124)
  "a-f-G-E-V-A": 82, "a-n-A": 83,
  "a-u-G-U-C-F": 84, "a-n-G-U-C-F": 85, "a-h-G-U-C-F": 86, "a-f-G-U-C-F": 87,
  "a-u-G-I": 88, "a-n-G-I": 89, "a-h-G-I": 90, "a-f-G-I": 91,
  "a-u-G-E-X-M": 92, "a-n-G-E-X-M": 93, "a-h-G-E-X-M": 94, "a-f-G-E-X-M": 95,
  "a-u-S": 96, "a-n-S": 97, "a-h-S": 98,
  "a-u-G-U-C-I-d": 99, "a-n-G-U-C-I-d": 100, "a-h-G-U-C-I-d": 101, "a-f-G-U-C-I-d": 102,
  "a-u-G-E-V-A-T": 103, "a-n-G-E-V-A-T": 104, "a-h-G-E-V-A-T": 105, "a-f-G-E-V-A-T": 106,
  "a-u-G-U-C-I": 107, "a-n-G-U-C-I": 108, "a-h-G-U-C-I": 109,
  "a-n-G-E-V": 110, "a-h-G-E-V": 111, "a-f-G-E-V": 112,
  "b-m-p-w-GOTO": 113, "b-m-p-c-ip": 114, "b-m-p-c-cp": 115, "b-m-p-s-p-op": 116,
  "u-d-v": 117, "u-d-v-m": 118, "u-d-c-e": 119,
  "b-i-x-i": 120, "b-t-f-d": 121, "b-t-f-r": 122, "b-a-o-c": 123, "t-s": 124,
};

const TYPE_TO_STRING: Record<number, string> = {};
for (const [k, v] of Object.entries(STRING_TO_TYPE)) TYPE_TO_STRING[v] = k;

const STRING_TO_HOW: Record<string, number> = {
  "h-e": 1, "m-g": 2, "h-g-i-g-o": 3, "m-r": 4, "m-f": 5, "m-p": 6, "m-s": 7,
};

const HOW_TO_STRING: Record<number, string> = {};
for (const [k, v] of Object.entries(STRING_TO_HOW)) HOW_TO_STRING[v] = k;

export function typeToEnum(s: string): number {
  return STRING_TO_TYPE[s] ?? COTTYPE_OTHER;
}

export function typeToString(id: number): string | undefined {
  return TYPE_TO_STRING[id];
}

export function howToEnum(s: string): number {
  return STRING_TO_HOW[s] ?? COTHOW_UNSPECIFIED;
}

export function howToString(id: number): string | undefined {
  return HOW_TO_STRING[id];
}

export function isAircraft(id: number): boolean {
  const s = typeToString(id);
  return s ? isAircraftString(s) : false;
}

export function isAircraftString(s: string): boolean {
  const atoms = s.split("-");
  return atoms.length >= 3 && atoms[2] === "A";
}
