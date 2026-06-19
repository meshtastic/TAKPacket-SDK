# TAKPacket-SDK — TypeScript

The TypeScript binding of [TAKPacket-SDK](https://github.com/meshtastic/TAKPacket-SDK): convert
ATAK Cursor-on-Target (CoT) XML to Meshtastic's `TAKPacketV2` protobuf and compress it with zstd
dictionary compression for LoRa mesh transport (237-byte MTU, port 78). Wire payloads are
byte-interoperable with the Kotlin, Swift, Python, and C# bindings.

Ships ESM with bundled type declarations. Compression uses the native
[`zstd-napi`](https://www.npmjs.com/package/zstd-napi) addon, so this package targets **Node.js**
(not the browser).

📚 **[API reference (TypeDoc)](https://meshtastic.github.io/TAKPacket-SDK/typescript/)**

## Install

```sh
npm install @meshtastic/takpacket-sdk
```

## Quick start

`compress` / `decompress` are **async** (they initialize the protobuf schema and zstd codec):

```ts
import {
  parseCotXml, buildCotXml, TakCompressor,
  normalizeCotXml, stripNonEssentialForMesh,
} from "@meshtastic/takpacket-sdk";

const compressor = new TakCompressor();

// Sanitize raw ATAK CoT XML before parsing.
let clean = normalizeCotXml(cotXmlString);
clean = stripNonEssentialForMesh(clean);

const packet = parseCotXml(clean);
const wirePayload = await compressor.compress(packet);   // Uint8Array: [flags][zstd body], ≤ 237 B

// Receive side
const received = await compressor.decompress(wirePayload);
const cotXml = buildCotXml(received);
```

## Public API

| Symbol | Responsibility |
|--------|----------------|
| `normalizeCotXml` / `stripNonEssentialForMesh` | Mesh hygiene on raw CoT XML *before* parsing |
| `parseCotXml` | CoT XML → `TAKPacketV2` |
| `buildCotXml` | `TAKPacketV2` → CoT XML |
| `TakCompressor` | `TAKPacketV2` ↔ compressed wire payload (`compress`/`decompress`/`compressWithStats`) |
| `CotTypeMapper` helpers | CoT type string ↔ enum, aircraft classification |
| `DictionaryProvider` | Selects and loads the embedded zstd dictionaries |

## Errors

`decompress` rejects on a malformed frame or one that would expand past 4096 bytes
(decompression-bomb guard). Window sizing for the large non-aircraft dictionary is handled
internally.

## Build & test (contributors)

```sh
cd typescript
npm install
npm run build      # tsc → dist/ (ships index.d.ts with TSDoc for IntelliSense)
npm test           # vitest
npm run docs       # TypeDoc → docs/
```

See the repository [CONTRIBUTING guide](https://github.com/meshtastic/TAKPacket-SDK/blob/master/CONTRIBUTING.md)
and [WIRE_FORMAT.md](https://github.com/meshtastic/TAKPacket-SDK/blob/master/WIRE_FORMAT.md).
