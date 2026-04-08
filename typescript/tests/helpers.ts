import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TESTDATA = path.resolve(__dirname, "../../testdata");

export const FIXTURES = [
  "pli_basic", "pli_full", "pli_webtak",
  "geochat_simple", "aircraft_adsb", "aircraft_hostile",
  "delete_event", "casevac", "alert_tic",
];

export function loadFixtureXml(name: string): string {
  return fs.readFileSync(path.join(TESTDATA, "cot_xml", `${name}.xml`), "utf-8");
}

export function loadGolden(name: string): Buffer | null {
  const p = path.join(TESTDATA, "golden", `${name}.bin`);
  if (!fs.existsSync(p)) return null;
  return fs.readFileSync(p);
}
