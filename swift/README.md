# TAKPacket-SDK — Swift

The Swift binding of [TAKPacket-SDK](https://github.com/meshtastic/TAKPacket-SDK): convert ATAK
Cursor-on-Target (CoT) XML to Meshtastic's `TAKPacketV2` protobuf and compress it with zstd
dictionary compression for LoRa mesh transport (237-byte MTU, port 78). Wire payloads are
byte-interoperable with the Kotlin, Python, TypeScript, and C# bindings.

Platforms: **iOS 15+**, **macOS 12+**. Uses [SwiftProtobuf](https://github.com/apple/swift-protobuf)
and a bundled `CZstd` (libzstd) target.

📚 **[API reference (DocC)](https://meshtastic.github.io/TAKPacket-SDK/swift/)**

## Install

Swift Package Manager:

```swift
dependencies: [
    .package(url: "https://github.com/meshtastic/TAKPacket-SDK", from: "0.7.0"),
],
targets: [
    .target(name: "YourApp", dependencies: [
        .product(name: "MeshtasticTAK", package: "TAKPacket-SDK"),
    ]),
]
```

## Quick start

```swift
import MeshtasticTAK

let parser = CotXmlParser()
let compressor = TakCompressor()

// Sanitize raw ATAK CoT XML before parsing.
var clean = CotMeshSanitizer.normalizeCotXml(cotXmlString)
clean = CotMeshSanitizer.stripNonEssentialForMesh(clean)

let packet = try parser.parse(clean)
let wirePayload = try compressor.compress(packet)   // [flags][zstd body], ≤ 237 B

// Receive side
let received = try compressor.decompress(wirePayload)
let cotXml = CotXmlBuilder().build(received)
```

## Core classes

| Class | Responsibility |
|-------|----------------|
| `CotMeshSanitizer` | Mesh hygiene on raw CoT XML *before* parsing |
| `CotXmlParser` | CoT XML → `TakPacketV2` |
| `CotXmlBuilder` | `TakPacketV2` → CoT XML |
| `TakCompressor` | `TakPacketV2` ↔ compressed wire payload |
| `CotTypeMapper` | CoT type string ↔ enum, aircraft classification |
| `AtakPalette` | ATAK 14-color palette ↔ `Team` enum |
| `DictionaryProvider` | Selects and loads the embedded zstd dictionaries |

## Errors

`compress` / `decompress` / `parse` are `throws`. `decompress` throws on a malformed frame or one
that would expand past 4096 bytes (decompression-bomb guard).

## Build & test (contributors)

```sh
cd swift && swift build                       # compile the library
swift package generate-documentation \        # DocC site (macOS)
  --target MeshtasticTAK --transform-for-static-hosting \
  --hosting-base-path TAKPacket-SDK/swift --output-path ../site/swift
```

> **Note:** the unit tests use the Xcode `Testing` module and don't run in a CLI-only
> environment; `swift build` / `swift build --build-tests` still validate compilation. The
> `swift-docc-plugin` dependency is a command plugin only — it is **not** linked into the
> product.

See the repository [CONTRIBUTING guide](https://github.com/meshtastic/TAKPacket-SDK/blob/master/CONTRIBUTING.md)
and [WIRE_FORMAT.md](https://github.com/meshtastic/TAKPacket-SDK/blob/master/WIRE_FORMAT.md).
