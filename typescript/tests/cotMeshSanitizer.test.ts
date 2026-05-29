import { describe, it, expect } from "vitest";
import fs from "fs";
import path from "path";
import { fileURLToPath } from "url";
import { stripNonEssentialForMesh, normalizeCotXml } from "../src/CotMeshSanitizer.js";

/**
 * Locks byte-for-byte parity with the canonical Kotlin `CotMeshSanitizer`
 * against the shared golden fixtures under `testdata/sanitizer/`. The Kotlin
 * test is the canonical generator of the `.out.xml` files — if this drifts,
 * fix the TypeScript regexes, not the goldens.
 */
const __dirname = path.dirname(fileURLToPath(import.meta.url));
const SANITIZER_DIR = path.resolve(__dirname, "../../testdata", "sanitizer");

function load(name: string): string {
  return fs.readFileSync(path.join(SANITIZER_DIR, name), "utf-8");
}

describe("CotMeshSanitizer", () => {
  it("strips non-essential mesh content to match the Kotlin golden", () => {
    const input = load("strip.in.xml");
    const expected = load("strip.out.xml");
    expect(stripNonEssentialForMesh(input).trimEnd()).toBe(expected.trimEnd());
  });

  it("normalizes CoT XML to match the Kotlin golden", () => {
    const input = load("normalize.in.xml");
    const expected = load("normalize.out.xml");
    expect(normalizeCotXml(input).trimEnd()).toBe(expected.trimEnd());
  });

  it("preserves TAK-Talk essentials and removes display-only content", () => {
    const out = stripNonEssentialForMesh(load("strip.in.xml"));
    // TAK-Talk essentials MUST survive.
    expect(out).toContain("<voice/>");
    expect(out).toContain('dest callsign="ETHEL"');
    // Display-only element and route-link uid MUST be gone.
    expect(out).not.toContain("<takv");
    expect(out).not.toContain('uid="LINK-UUID');
  });
});
