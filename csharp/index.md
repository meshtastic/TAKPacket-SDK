# Meshtastic.TAK (C#)

The .NET binding of the **TAKPacket-SDK**: convert ATAK Cursor-on-Target (CoT) XML to
Meshtastic's `TAKPacketV2` protobuf and compress it with zstd dictionary compression for LoRa
mesh transport (237-byte MTU, port 78). Payloads are byte-interoperable with the Kotlin, Swift,
Python, and TypeScript bindings.

## Install

```sh
dotnet add package Meshtastic.TAK
```

## Compress and decompress

```csharp
using Meshtastic.TAK;

var parser = new CotXmlParser();
var compressor = new TakCompressor();
var builder = new CotXmlBuilder();

// Compress
var packet = parser.Parse(cotXmlString);
byte[] wirePayload = compressor.Compress(packet);   // [flags byte][zstd body], ≤ 237 B

// Decompress
var received = compressor.Decompress(wirePayload);
string cotXml = builder.Build(received);
```

## Where to go next

- **[API reference](api/)** — every public type, generated from the XML doc comments.
- **[Repository README](https://github.com/meshtastic/TAKPacket-SDK#readme)** — architecture,
  wire format, and payload-type catalog.
- **[Wire format spec](https://github.com/meshtastic/TAKPacket-SDK/blob/master/WIRE_FORMAT.md)**.
