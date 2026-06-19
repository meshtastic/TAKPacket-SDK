# ``MeshtasticTAK``

Convert ATAK Cursor-on-Target (CoT) XML to Meshtastic's `TAKPacketV2` protobuf and compress
it with zstd dictionary compression for LoRa mesh transport.

## Overview

`MeshtasticTAK` is the Swift binding of the TAKPacket-SDK. It turns a CoT XML event into a
compact wire payload that fits the 237-byte LoRa MTU (Meshtastic port 78), and reverses the
process on receive. Its payloads are byte-interoperable with the Kotlin, Python, TypeScript,
and C# bindings.

The send pipeline:

```
CoT XML → CotMeshSanitizer → CotXmlParser → TakPacketV2 → TakCompressor → [flags][zstd body]
```

Receiving reverses it: ``TakCompressor/decompress(_:)`` → `TakPacketV2` →
``CotXmlBuilder/build(_:)`` → CoT XML.

### Wire format

The on-wire payload is a single flags byte followed by the zstd-compressed protobuf body (with
the 4-byte zstd magic stripped). Flags bits 0–5 carry the dictionary ID (0 = non-aircraft,
1 = aircraft); `0xFF` marks an uncompressed raw protobuf.

### Resilience

Every packet is fully, independently decodable from its own bytes plus the static shipped
dictionary — zero cross-packet state, one-shot zstd only. LoRa is lossy, and losing one packet
must never affect any other.

## Topics

### Essentials

- <doc:GettingStarted>

### Parsing & building CoT

- ``CotXmlParser``
- ``CotXmlBuilder``
- ``CotMeshSanitizer``

### Compression

- ``TakCompressor``
- ``DictionaryProvider``

### CoT type & color helpers

- ``CotTypeMapper``
- ``AtakPalette``
