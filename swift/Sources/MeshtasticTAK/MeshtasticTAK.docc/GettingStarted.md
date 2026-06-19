# Getting Started

Add the package, then compress and decompress a CoT event.

## Add the package

In `Package.swift`:

```swift
.package(url: "https://github.com/meshtastic/TAKPacket-SDK", from: "0.7.0")
```

Then add `MeshtasticTAK` to your target's dependencies and `import MeshtasticTAK`.

## Compress a CoT event for the mesh

Sanitize the raw ATAK XML, parse it, then compress to the wire payload:

```swift
import MeshtasticTAK

let parser = CotXmlParser()
let compressor = TakCompressor()

// Strip display-only detail and normalize before parsing (see ``CotMeshSanitizer``).
var clean = CotMeshSanitizer.normalizeCotXml(cotXmlString)
clean = CotMeshSanitizer.stripNonEssentialForMesh(clean)

let packet = try parser.parse(clean)
let wirePayload = try compressor.compress(packet)   // [flags byte][zstd body], ≤ 237 B
```

## Decompress a received payload

```swift
let received = try compressor.decompress(wirePayload)
let cotXml = CotXmlBuilder().build(received)
```

## Notes

- ``TakCompressor/compress(_:)`` and ``TakCompressor/decompress(_:)`` are stateless per call —
  reuse a single ``TakCompressor`` instance freely across packets.
- The decompressor rejects any frame that would expand past 4096 bytes (a decompression-bomb
  guard).
- Units on the data model follow Meshtastic conventions: latitude/longitude are degrees × 1e7,
  `speed` is cm/s, `course` is degrees × 100, and altitude is meters HAE.
