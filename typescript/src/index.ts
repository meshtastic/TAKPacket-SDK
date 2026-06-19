/**
 * TAKPacket-SDK — TypeScript binding.
 *
 * Converts ATAK Cursor-on-Target (CoT) XML into Meshtastic's `TAKPacketV2`
 * protobuf and compresses it with zstd dictionary compression for transport
 * over a LoRa mesh (237-byte MTU, Meshtastic port 78 / `ATAK_PLUGIN_V2`).
 * Frames emitted by this binding are cross-decodable with the Kotlin, Swift,
 * Python, and C# bindings of the same SDK.
 *
 * @remarks
 * The pipeline has three stages, each exposed as a standalone entry point so
 * callers can compose them or use them independently:
 *
 * 1. **Parse** — {@link parseCotXml} turns a CoT XML event string into a
 *    {@link TAKPacketV2} data object (and {@link buildCotXml} reverses it).
 * 2. **Type/dictionary classification** — the `CotTypeMapper` helpers map CoT
 *    type strings to/from the `CotType` enum and decide aircraft vs.
 *    non-aircraft; the `DictionaryProvider` helpers load the matching zstd
 *    dictionary.
 * 3. **Compress** — {@link TakCompressor.compress} produces the on-wire
 *    `[flags][zstd body]` payload and {@link TakCompressor.decompress} reverses
 *    it.
 *
 * Supporting modules: the `AtakPalette` helpers (ARGB ↔ Team color enum) and
 * the `CotMeshSanitizer` helpers (pre-parse CoT-XML hygiene for mesh transport).
 *
 * **Wire format.** Every payload is `[1 byte flags][N bytes body]`, total ≤ 237
 * bytes. Flags bits 0–5 carry the dictionary ID (0 = non-aircraft, 1 =
 * aircraft); the special flags value `0xFF` means the body is raw,
 * uncompressed protobuf. The 4-byte zstd magic number is stripped on encode and
 * re-prepended on decode to save bytes.
 *
 * **Resilience invariant.** Every packet is fully and independently decodable
 * from its own bytes plus the static shipped dictionary — there is zero
 * cross-packet state, and only the one-shot zstd API is used (never streaming).
 * A position report carrying no payload variant with an `a-f-*` CoT type is an
 * implicit PLI (there is no `pli` boolean on the wire).
 *
 * @packageDocumentation
 */

export * from "./types.js";
export * from "./CotTypeMapper.js";
export * from "./DictionaryProvider.js";
export * from "./TakCompressor.js";
export * from "./CotMeshSanitizer.js";
export * from "./CotXmlParser.js";
export * from "./CotXmlBuilder.js";
export * from "./proto.js";
