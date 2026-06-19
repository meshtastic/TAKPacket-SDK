/**
 * Loads the canonical zstd dictionaries from the package's `resources/`
 * directory and selects the right dictionary for a given CoT type.
 *
 * Two dictionaries ship with the SDK, both pre-trained on serialized
 * `TAKPacketV2` protobuf bytes (not raw XML): a ~512 KB non-aircraft dictionary
 * and a ~4 KB aircraft dictionary. The dictionary ID also doubles as the wire
 * `flags` byte value (bits 0–5), so the same constants name both the dictionary
 * and the on-wire flag. All nodes on a mesh must ship the same dictionary pair
 * to interoperate; the dictionaries are static and never adapted from runtime
 * traffic, which keeps every packet independently decodable.
 *
 * @remarks
 * Dictionary buffers are read from disk lazily and cached for the process
 * lifetime — the first call to {@link nonAircraftDict} / {@link aircraftDict}
 * pays the file read, subsequent calls are free.
 */
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { isAircraft, isAircraftString, COTTYPE_OTHER } from "./CotTypeMapper.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RESOURCES_DIR = path.resolve(__dirname, "../resources");

/**
 * Dictionary ID / flags-byte value for the non-aircraft (~512 KB) dictionary.
 * Covers PLI, GeoChat, ground units, shapes, markers, routes, ranging, alerts,
 * casevac, emergency, task, TAKTALK, and delete events.
 */
export const DICT_ID_NON_AIRCRAFT = 0;

/**
 * Dictionary ID / flags-byte value for the aircraft (~4 KB) dictionary.
 * Covers ADS-B tracks, military air, and helicopters.
 */
export const DICT_ID_AIRCRAFT = 1;

/**
 * Sentinel flags-byte value (`0xFF`) marking an uncompressed payload: the body
 * is raw `TAKPacketV2` protobuf with no zstd compression. Emitted by the
 * encoder's skip-compress path when compression wouldn't shrink the payload,
 * and by firmware devices that cannot compress.
 */
export const DICT_ID_UNCOMPRESSED = 0xff;

let _nonAircraftDict: Buffer | null = null;
let _aircraftDict: Buffer | null = null;

/**
 * Load (and cache) the non-aircraft zstd dictionary from `resources/`.
 *
 * @returns The raw dictionary bytes.
 * @throws If `resources/dict_non_aircraft.zstd` cannot be read.
 */
export function nonAircraftDict(): Buffer {
  if (!_nonAircraftDict) {
    _nonAircraftDict = fs.readFileSync(path.join(RESOURCES_DIR, "dict_non_aircraft.zstd"));
  }
  return _nonAircraftDict;
}

/**
 * Load (and cache) the aircraft zstd dictionary from `resources/`.
 *
 * @returns The raw dictionary bytes.
 * @throws If `resources/dict_aircraft.zstd` cannot be read.
 */
export function aircraftDict(): Buffer {
  if (!_aircraftDict) {
    _aircraftDict = fs.readFileSync(path.join(RESOURCES_DIR, "dict_aircraft.zstd"));
  }
  return _aircraftDict;
}

/**
 * Resolve a dictionary ID (from a received flags byte, masked with `& 0x3F`)
 * to its dictionary bytes.
 *
 * @param dictId - {@link DICT_ID_NON_AIRCRAFT} or {@link DICT_ID_AIRCRAFT}.
 * @returns The matching dictionary buffer, or `null` for any unknown ID
 *          (including {@link DICT_ID_UNCOMPRESSED}, which has no dictionary).
 */
export function getDictionary(dictId: number): Buffer | null {
  if (dictId === DICT_ID_NON_AIRCRAFT) return nonAircraftDict();
  if (dictId === DICT_ID_AIRCRAFT) return aircraftDict();
  return null;
}

/**
 * Choose the dictionary ID to compress a packet with, based on its CoT type.
 *
 * Aircraft types use {@link DICT_ID_AIRCRAFT}; everything else uses
 * {@link DICT_ID_NON_AIRCRAFT}. When the type is known
 * ({@link COTTYPE_OTHER} not returned by the enum) the decision is made from
 * the enum via {@link isAircraft}; otherwise it falls back to classifying the
 * raw `cotTypeStr` with {@link isAircraftString}.
 *
 * @param cotTypeId  - The packet's `CotType` enum value.
 * @param cotTypeStr - The raw CoT type string, used only when `cotTypeId` is
 *                     {@link COTTYPE_OTHER}.
 * @returns {@link DICT_ID_AIRCRAFT} or {@link DICT_ID_NON_AIRCRAFT}.
 */
export function selectDictId(cotTypeId: number, cotTypeStr?: string): number {
  if (cotTypeId !== COTTYPE_OTHER) {
    return isAircraft(cotTypeId) ? DICT_ID_AIRCRAFT : DICT_ID_NON_AIRCRAFT;
  }
  if (cotTypeStr && isAircraftString(cotTypeStr)) return DICT_ID_AIRCRAFT;
  return DICT_ID_NON_AIRCRAFT;
}
