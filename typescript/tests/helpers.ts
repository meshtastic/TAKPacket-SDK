import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TESTDATA = path.resolve(__dirname, "../../testdata");

// Dynamically enumerate all XML fixtures in the shared testdata directory so
// new fixtures can be added without editing this list. Kotlin's CompressionTest
// class is the canonical generator for the corresponding .pb and .bin files —
// run it first when adding new fixtures, then the TypeScript suite picks them
// up on the next `npm test` run. Sorted for stable test ordering.
export const FIXTURES: string[] = fs
  .readdirSync(path.join(TESTDATA, "cot_xml"))
  .filter((f) => f.endsWith(".xml"))
  .map((f) => f.replace(/\.xml$/, ""))
  .sort();

export function loadFixtureXml(name: string): string {
  return fs.readFileSync(path.join(TESTDATA, "cot_xml", `${name}.xml`), "utf-8");
}

export function loadGolden(name: string): Buffer | null {
  const p = path.join(TESTDATA, "golden", `${name}.bin`);
  if (!fs.existsSync(p)) return null;
  return fs.readFileSync(p);
}
