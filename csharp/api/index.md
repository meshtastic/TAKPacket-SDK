# API reference

The `Meshtastic.TAK` namespace exposes the six core classes shared by every TAKPacket-SDK
binding:

| Type | Responsibility |
|------|----------------|
| `CotXmlParser` | CoT XML → `TakPacketV2Data` |
| `CotXmlBuilder` | `TakPacketV2Data` → CoT XML |
| `TakCompressor` | `TakPacketV2Data` ↔ compressed wire payload |
| `CotTypeMapper` | CoT type string ↔ enum, aircraft classification |
| `AtakPalette` | ATAK 14-color palette ↔ `Team` enum |
| `CotMeshSanitizer` | CoT XML mesh hygiene, applied before parsing |

Use the table of contents on the left to browse members.
