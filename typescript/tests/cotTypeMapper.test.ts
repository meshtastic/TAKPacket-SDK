import { describe, it, expect } from "vitest";
import { typeToEnum, typeToString, howToEnum, howToString, isAircraft, isAircraftString, COTTYPE_OTHER } from "../src/CotTypeMapper.js";

describe("CotTypeMapper", () => {
  it("maps known types correctly", () => {
    expect(typeToEnum("a-f-G-U-C")).toBe(1);
    expect(typeToEnum("a-n-A-C-F")).toBe(3);
    expect(typeToEnum("t-x-d-d")).toBe(14);
    expect(typeToEnum("b-t-f")).toBe(25);
    expect(typeToEnum("b-r-f-h-c")).toBe(26);
  });

  it("returns OTHER for unknown types", () => {
    expect(typeToEnum("z-unknown")).toBe(0);
    expect(typeToEnum("")).toBe(0);
  });

  it("OTHER returns undefined string", () => {
    expect(typeToString(COTTYPE_OTHER)).toBeUndefined();
  });

  it("classifies aircraft strings correctly", () => {
    expect(isAircraftString("a-f-A-M-H")).toBe(true);
    expect(isAircraftString("a-n-A-C-F")).toBe(true);
    expect(isAircraftString("a-f-G-U-C")).toBe(false);
    expect(isAircraftString("b-t-f")).toBe(false);
    expect(isAircraftString("a-f-S")).toBe(false);
  });

  it("classifies aircraft string edge cases correctly", () => {
    // Fewer than 3 atoms -> always non-aircraft (guards atoms.length >= 3)
    expect(isAircraftString("")).toBe(false);
    expect(isAircraftString("A")).toBe(false);
    expect(isAircraftString("a")).toBe(false);
    expect(isAircraftString("a-f")).toBe(false);

    // 'A' present but NOT at index 2 -> non-aircraft
    expect(isAircraftString("A-f-G")).toBe(false); // index 0
    expect(isAircraftString("a-A-G")).toBe(false); // index 1
    expect(isAircraftString("a---A")).toBe(false); // index 3 after empty atoms
    expect(isAircraftString("a-f-G-A")).toBe(false); // index 3

    // Exactly 3 atoms with A at index 2 -> minimal valid aircraft type
    expect(isAircraftString("a-f-A")).toBe(true);
    expect(isAircraftString("a-h-A")).toBe(true);
  });

  it("round-trips type enum <-> string", () => {
    for (let i = 1; i <= 75; i++) {
      const s = typeToString(i);
      if (s) expect(typeToEnum(s)).toBe(i);
    }
  });

  it("classifies aircraft correctly", () => {
    expect(isAircraft(3)).toBe(true);   // a-n-A-C-F
    expect(isAircraft(6)).toBe(true);   // a-f-A-M-H
    expect(isAircraft(11)).toBe(true);  // a-h-A-M-F-F
    expect(isAircraft(44)).toBe(true);  // a-h-A

    expect(isAircraft(1)).toBe(false);  // a-f-G-U-C
    expect(isAircraft(14)).toBe(false); // t-x-d-d
    expect(isAircraft(25)).toBe(false); // b-t-f
    expect(isAircraft(17)).toBe(false); // a-f-S
  });

  it("maps how values correctly", () => {
    expect(howToEnum("h-e")).toBe(1);
    expect(howToEnum("m-g")).toBe(2);
    expect(howToEnum("m-r")).toBe(4);
    expect(howToEnum("unknown")).toBe(0);
  });

  it("round-trips how enum <-> string", () => {
    for (let i = 1; i <= 7; i++) {
      const s = howToString(i);
      if (s) expect(howToEnum(s)).toBe(i);
    }
  });
});
