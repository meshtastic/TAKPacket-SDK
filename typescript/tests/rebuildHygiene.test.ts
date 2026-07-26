import { describe, it, expect } from "vitest";
import { parseCotXml } from "../src/CotXmlParser.js";
import { buildCotXml } from "../src/CotXmlBuilder.js";
import type { TAKPacketV2 } from "../src/types.js";

/**
 * Locks two rebuild invariants:
 *
 *  1. The raw-detail passthrough only re-emits a fragment that can sit inside
 *     ONE `<detail>` element. A fragment carrying an `<event>` / `<detail>` tag
 *     token would unbalance the wrapper, so it is dropped and the rebuilt
 *     document still parses as a single event.
 *  2. The `<contact>` endpoint is neither stored on parse nor taken from the
 *     packet on rebuild — the `*:-1:stcp` default is always emitted, because a
 *     peer's concrete endpoint is not dialable by other mesh members.
 */
describe("RebuildHygiene", () => {
  function countOccurrences(haystack: string, needle: string): number {
    return haystack.split(needle).length - 1;
  }

  /**
   * XML element names are case-sensitive, but a fragment that spells the
   * wrapper tokens in any case still unbalances the document the same way,
   * so the tag-token census has to ignore case.
   */
  function countOccurrencesIgnoringCase(haystack: string, needle: string): number {
    return countOccurrences(haystack.toLowerCase(), needle.toLowerCase());
  }

  it("drops a raw-detail fragment that would unbalance the event wrapper", () => {
    const packet: TAKPacketV2 = {
      uid: "ALPHA-1",
      callsign: "ALPHA-1",
      latitudeI: 388895000,
      longitudeI: -770353000,
      rawDetail: Buffer.from(
        `<contact callsign="ALPHA-1"/></detail></event><event uid="ALPHA-2" type="a-f-G-U-C"><detail/></event>`,
        "utf-8",
      ),
    };

    const xml = buildCotXml(packet);

    expect(xml).not.toContain("ALPHA-2");
    expect(countOccurrences(xml, "<event")).toBe(1);
    expect(countOccurrences(xml, "</event>")).toBe(1);
    // The rest of the event still builds normally.
    expect(xml).toContain(`uid="ALPHA-1"`);
  });

  it("drops a raw-detail fragment that closes the wrapper with mixed-case tag tokens", () => {
    // XML tag names are case-sensitive, so `</DETAIL></Event>` does not close
    // the lowercase wrapper this builder emits — but a receiver's parser still
    // sees the document run off the rails, and the second `<Event ...>` start
    // tag leaves the rebuilt XML with an unbalanced element census. The guard
    // therefore compares case-insensitively; if it stopped doing so, this
    // fragment would sail through and be re-emitted verbatim.
    const fragment =
      `<remarks>ALPHA-1 checking in</remarks></DETAIL></Event><Event version="2.0" uid="SECOND">`;
    const packet: TAKPacketV2 = {
      uid: "ALPHA-1",
      callsign: "ALPHA-1",
      latitudeI: 388895000,
      longitudeI: -770353000,
      rawDetail: Buffer.from(fragment, "utf-8"),
    };

    const xml = buildCotXml(packet);

    expect(xml).not.toContain(fragment);
    // The distinctive marker of the trailing start tag never reaches the output.
    expect(xml).not.toContain(`uid="SECOND"`);
    // Exactly one event element, counting tag tokens in any case: were the
    // fragment re-emitted, the mixed-case `</Event><Event ...>` pair would
    // push both of these to 2.
    expect(countOccurrencesIgnoringCase(xml, "<event")).toBe(1);
    expect(countOccurrencesIgnoringCase(xml, "</event")).toBe(1);
    // And the wrapper the builder itself emits is still the lowercase one.
    expect(countOccurrences(xml, "<event")).toBe(1);
    expect(countOccurrences(xml, "</event>")).toBe(1);
    // The rest of the event still builds normally.
    expect(xml).toContain(`uid="ALPHA-1"`);
  });

  it("re-emits a benign raw-detail fragment verbatim", () => {
    const fragment = `<foo bar="1"/>`;
    const packet: TAKPacketV2 = {
      uid: "ALPHA-1",
      callsign: "ALPHA-1",
      rawDetail: Buffer.from(fragment, "utf-8"),
    };

    const xml = buildCotXml(packet);

    expect(xml).toContain(fragment);
    expect(countOccurrences(xml, "<event")).toBe(1);
    expect(countOccurrences(xml, "</event>")).toBe(1);
  });

  it("rebuilds with the default endpoint even when the packet carries one", () => {
    const packet: TAKPacketV2 = {
      uid: "ALPHA-1",
      callsign: "ALPHA-1",
      endpoint: "192.0.2.1:4242:tcp",
    };

    const xml = buildCotXml(packet);

    expect(xml).toContain(`endpoint="*:-1:stcp"`);
    expect(xml).not.toContain("192.0.2.1");
  });

  it("never stores the contact endpoint on parse", () => {
    const packet = parseCotXml(`<event version="2.0" uid="ALPHA-1" type="a-f-G-U-C" how="m-g"
      time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-15T14:22:55Z">
      <point lat="38.8895" lon="-77.0353" hae="-22" ce="4.9" le="9999999"/>
      <detail><contact callsign="ALPHA-1" endpoint="192.0.2.1:4242:tcp"/></detail></event>`);

    expect(packet.callsign).toBe("ALPHA-1");
    expect(packet.endpoint ?? "").toBe("");
  });
});
