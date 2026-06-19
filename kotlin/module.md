# Module TAKPacket-SDK

Cross-platform conversion of ATAK **Cursor-on-Target (CoT)** XML into Meshtastic's
`TAKPacketV2` protobuf, compressed with zstd dictionary compression for transport over LoRa
mesh networks (237-byte MTU, Meshtastic port 78 / `ATAK_PLUGIN_V2`).

This is the Kotlin Multiplatform binding — the **canonical** implementation of the SDK. It
targets the JVM (Android), the Apple/Linux/Windows native platforms, JS, and Wasm, and
produces wire payloads that are cross-decodable with the Swift, Python, TypeScript, and C#
bindings.

## Pipeline

Sending an event:

```
CoT XML ──CotMeshSanitizer──▶ clean XML ──CotXmlParser──▶ TakPacketV2Data
        ──TakCompressor──▶ [1-byte flags][zstd-compressed protobuf]   (≤ 237 B on the wire)
```

Receiving reverses it: `TakCompressor.decompress` → `TakPacketV2Data` → `CotXmlBuilder.build`
→ CoT XML.

## Core classes

| Type | Role |
|------|------|
| `CotMeshSanitizer` | Mesh hygiene applied to raw CoT XML *before* parsing (drop display-only detail, preserve voice/marti) |
| `CotXmlParser` | CoT XML → `TakPacketV2Data` |
| `CotXmlBuilder` | `TakPacketV2Data` → CoT XML |
| `TakCompressor` | `TakPacketV2Data` ↔ compressed wire payload (with remarks-aware and stats variants) |
| `CotTypeMapper` | CoT type string ↔ enum, aircraft classification |
| `AtakPalette` | ATAK's 14-color palette ↔ `Team` enum |
| `DictionaryProvider` | Selects and loads the embedded zstd dictionaries |

## Wire format

The on-wire payload is a single flags byte followed by the zstd-compressed protobuf body
(with the 4-byte zstd magic stripped to save space). Flags bits 0–5 carry the dictionary ID
(0 = non-aircraft, 1 = aircraft); `0xFF` marks an uncompressed raw protobuf. See the
repository's `WIRE_FORMAT.md` for the full specification.

## Resilience invariant

Every packet is fully, independently decodable from its own bytes plus the static shipped
dictionary. There is **zero cross-packet state** and only the **one-shot** zstd API is used —
LoRa is lossy, and losing one packet must never affect any other.

# Package org.meshtastic.tak

The complete public API of the TAKPacket-SDK Kotlin binding: the six core CoT/compression
classes listed above, the `TakPacketV2Data` data model (26 envelope fields plus the `Payload`
sealed hierarchy of typed variants), and supporting types such as `ZstdException`.
