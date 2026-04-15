import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { isAircraft, isAircraftString, COTTYPE_OTHER } from "./CotTypeMapper.js";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const RESOURCES_DIR = path.resolve(__dirname, "../resources");

export const DICT_ID_NON_AIRCRAFT = 0;
export const DICT_ID_AIRCRAFT = 1;
export const DICT_ID_UNCOMPRESSED = 0xff;

let _nonAircraftDict: Buffer | null = null;
let _aircraftDict: Buffer | null = null;

export function nonAircraftDict(): Buffer {
  if (!_nonAircraftDict) {
    _nonAircraftDict = fs.readFileSync(path.join(RESOURCES_DIR, "dict_non_aircraft.zstd"));
  }
  return _nonAircraftDict;
}

export function aircraftDict(): Buffer {
  if (!_aircraftDict) {
    _aircraftDict = fs.readFileSync(path.join(RESOURCES_DIR, "dict_aircraft.zstd"));
  }
  return _aircraftDict;
}

export function getDictionary(dictId: number): Buffer | null {
  if (dictId === DICT_ID_NON_AIRCRAFT) return nonAircraftDict();
  if (dictId === DICT_ID_AIRCRAFT) return aircraftDict();
  return null;
}

export function selectDictId(cotTypeId: number, cotTypeStr?: string): number {
  if (cotTypeId !== COTTYPE_OTHER) {
    return isAircraft(cotTypeId) ? DICT_ID_AIRCRAFT : DICT_ID_NON_AIRCRAFT;
  }
  if (cotTypeStr && isAircraftString(cotTypeStr)) return DICT_ID_AIRCRAFT;
  return DICT_ID_NON_AIRCRAFT;
}
