/**
 * Stateless CoT-XML hygiene for LoRa-mesh transport.
 *
 * Centralized here so every consumer (Meshtastic-Android `takserver`,
 * Meshtastic-Apple `AccessoryManager`, …) shares ONE golden-tested
 * implementation instead of each maintaining its own regex list. Those lists
 * had drifted and silently broken features — most recently TAK-Talk `<voice>`
 * and `<marti>` were re-added to one side's strip set, so directed/voice
 * TAK-Talk stopped surfacing end-to-end.
 *
 * Pure string transforms — no platform, protobuf, or compression dependencies.
 * Mirrors the canonical Kotlin `CotMeshSanitizer` byte-for-byte; the
 * cross-binding fixtures under `testdata/sanitizer/` lock parity.
 *
 * Regexes use `[\s\S]` rather than the `s`/dotAll flag so behaviour is
 * identical across all five language bindings, and the global `g` flag so each
 * `replace()` replaces ALL occurrences (matching Kotlin's `Regex.replace`).
 */

// Display-only / receiver-rederivable elements that add ~100–200 wire bytes.
//
// DELIBERATELY ABSENT: <voice> and <marti>. They are TAK-Talk essentials —
// <voice/> marks a push-to-talk (voice) message and <marti><dest
// callsign="…"/></marti> carries the directed-routing recipients. Stripping
// either breaks TAK-Talk: the receiving ATAK plugin can neither play nor
// route the m-t-t. The SDK carries both compactly (voice→bool,
// marti→repeated string) and re-emits them on rebuild, omitting an empty
// marti — so there is nothing to gain by stripping and a feature to lose.
const STRIP_ELEMENTS: RegExp[] = [
  /<takv[^>]*\/>/g,
  /<takv[^>]*>[\s\S]*?<\/takv>/g,
  /<__geofence[^>]*\/>/g,
  /<__geofence[^>]*>[\s\S]*?<\/__geofence>/g,
  /<tog[^>]*\/>/g,
  /<archive[^>]*\/>/g,
  /<__shapeExtras[^>]*\/>/g,
  /<__shapeExtras[^>]*>[\s\S]*?<\/__shapeExtras>/g,
  /<creator[^>]*\/>/g,
  /<creator[^>]*>[\s\S]*?<\/creator>/g,
  /<remarks[^>]*\/>/g,
  /<remarks[^>]*><\/remarks>/g,
  /<strokeStyle[^>]*\/>/g,
  /<precisionlocation[^>]*\/>/g,
  /<precisionlocation[^>]*>[\s\S]*?<\/precisionlocation>/g,
  /<precisionLocation[^>]*\/>/g,
  /<precisionLocation[^>]*>[\s\S]*?<\/precisionLocation>/g,
];

// Strip any attribute whose value is the literal placeholder "???".
const UNKNOWN_ATTR = /\s+\w+\s*=\s*"\?{3}"/g;

// Display-only attributes the SDK doesn't carry. Empty callsign/phone only
// (a populated callsign — e.g. <contact>, <dest> — is preserved).
const STRIP_ATTRS: RegExp[] = [
  /\s+routetype\s*=\s*"[^"]*"/g,
  /\s+order\s*=\s*"[^"]*"/g,
  /\s+color\s*=\s*"[^"]*"/g,
  /\s+access\s*=\s*"[^"]*"/g,
  /\s+callsign\s*=\s*""/g,
  /\s+phone\s*=\s*""/g,
];

// Route-waypoint / shape-vertex <link> elements carry full 36-char UUIDs
// (~40 wire bytes each) the receiver re-derives. Strip uid ONLY from <link>
// elements that have a point= attribute, never from other elements.
const ROUTE_LINK = /<link\s[^>]*\bpoint="[^"]*"[^>]*\/>/g;
const LINK_UID = /\s+uid="[^"]*"/g;

const XML_DECL = /<\?xml[^>]*\?>/g;
const INTER_TAG_WS = />\s+</g;

/**
 * Strip display-only CoT `<detail>` content to fit the LoRa MTU, preserving
 * everything the receiver needs to render/route — including TAK-Talk
 * `<voice>` and `<marti>`. Safe to run on any CoT XML; a no-op when there is
 * nothing to strip.
 */
export function stripNonEssentialForMesh(xml: string): string {
  let result = xml;
  for (const re of STRIP_ELEMENTS) result = result.replace(re, "");
  result = result.replace(UNKNOWN_ATTR, "");
  for (const re of STRIP_ATTRS) result = result.replace(re, "");
  result = result.replace(ROUTE_LINK, (m) => m.replace(LINK_UID, ""));
  return result;
}

/**
 * Normalize CoT XML for the TAK TCP stream: drop the `<?xml …?>` declaration
 * and collapse inter-tag whitespace (`>   <` → `><`). TAK clients read a
 * continuous stream of single-line events and choke on a pretty-printed,
 * multi-line document with a prologue. Whitespace inside text nodes is left
 * intact (only `>`-whitespace-`<` runs collapse).
 */
export function normalizeCotXml(xml: string): string {
  let result = xml.replace(XML_DECL, "");
  result = result.replace(INTER_TAG_WS, "><");
  return result.trim();
}
