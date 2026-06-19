# TAKPacket-SDK — C# / .NET

The .NET binding of [TAKPacket-SDK](https://github.com/meshtastic/TAKPacket-SDK): convert ATAK
Cursor-on-Target (CoT) XML to Meshtastic's `TAKPacketV2` protobuf and compress it with zstd
dictionary compression for LoRa mesh transport (237-byte MTU, port 78). Wire payloads are
byte-interoperable with the Kotlin, Swift, Python, and TypeScript bindings.

Targets **.NET 9**. Depends on `Google.Protobuf` and `ZstdSharp.Port`.

📚 **[API reference (DocFX)](https://meshtastic.github.io/TAKPacket-SDK/csharp/)**

## Install

```sh
dotnet add package Meshtastic.TAK
```

## Quick start

```csharp
using Meshtastic.TAK;

var parser = new CotXmlParser();
var compressor = new TakCompressor();
var builder = new CotXmlBuilder();

// Sanitize raw ATAK CoT XML before parsing.
var clean = CotMeshSanitizer.NormalizeCotXml(cotXmlString);
clean = CotMeshSanitizer.StripNonEssentialForMesh(clean);

var packet = parser.Parse(clean);
byte[] wirePayload = compressor.Compress(packet);   // [flags][zstd body], ≤ 237 B

// Receive side
var received = compressor.Decompress(wirePayload);
string cotXml = builder.Build(received);
```

## Core classes

| Class | Responsibility |
|-------|----------------|
| `CotMeshSanitizer` | Mesh hygiene on raw CoT XML *before* parsing |
| `CotXmlParser` | CoT XML → `TakPacketV2Data` |
| `CotXmlBuilder` | `TakPacketV2Data` → CoT XML |
| `TakCompressor` | `TakPacketV2Data` ↔ compressed wire payload |
| `CotTypeMapper` | CoT type string ↔ enum, aircraft classification |
| `AtakPalette` | ATAK 14-color palette ↔ `Team` enum |
| `DictionaryProvider` | Selects and loads the embedded zstd dictionaries |

## Errors

`TakCompressor.Decompress` throws on a malformed frame or one that would expand past 4096 bytes
(decompression-bomb guard). The parser rejects DTD/entity declarations (XXE hardening).

## Build & test (contributors)

```sh
cd csharp
dotnet build
dotnet test
dotnet tool install -g docfx && docfx docfx.json   # API docs → _site/
```

See the repository [CONTRIBUTING guide](https://github.com/meshtastic/TAKPacket-SDK/blob/master/CONTRIBUTING.md)
and [WIRE_FORMAT.md](https://github.com/meshtastic/TAKPacket-SDK/blob/master/WIRE_FORMAT.md).
