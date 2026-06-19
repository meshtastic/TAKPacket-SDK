# TAKPacket-SDK — Python

The Python binding of [TAKPacket-SDK](https://github.com/meshtastic/TAKPacket-SDK): convert ATAK
Cursor-on-Target (CoT) XML to Meshtastic's `TAKPacketV2` protobuf and compress it with zstd
dictionary compression for LoRa mesh transport (237-byte MTU, port 78). Wire payloads are
byte-interoperable with the Kotlin, Swift, TypeScript, and C# bindings.

Requires **Python 3.9+**. Depends on `protobuf` and `zstandard`.

📚 **[API reference (pdoc)](https://meshtastic.github.io/TAKPacket-SDK/python/)**

## Install

```sh
pip install meshtastic-tak
```

## Quick start

```python
from meshtastic_tak import (
    CotXmlParser, CotXmlBuilder, TakCompressor, CotMeshSanitizer,
)

parser = CotXmlParser()
compressor = TakCompressor()

# Sanitize raw ATAK CoT XML before parsing.
clean = CotMeshSanitizer.normalize_cot_xml(cot_xml_string)
clean = CotMeshSanitizer.strip_non_essential_for_mesh(clean)

packet = parser.parse(clean)
wire_payload = compressor.compress(packet)   # bytes: [flags][zstd body], <= 237 B

# Receive side
received = compressor.decompress(wire_payload)
cot_xml = CotXmlBuilder().build(received)
```

## Public API

| Symbol | Responsibility |
|--------|----------------|
| `CotMeshSanitizer` (`normalize_cot_xml`, `strip_non_essential_for_mesh`) | Mesh hygiene on raw CoT XML *before* parsing |
| `CotXmlParser` | CoT XML → `TakPacketV2Data` |
| `CotXmlBuilder` | `TakPacketV2Data` → CoT XML |
| `TakCompressor` | `TakPacketV2Data` ↔ compressed wire payload |
| `CotTypeMapper` | CoT type string ↔ enum, aircraft classification |
| `DictionaryProvider` | Selects and loads the embedded zstd dictionaries |

## Errors

`TakCompressor.decompress` raises on a malformed frame or one that would expand past 4096 bytes
(decompression-bomb guard). The parser is hardened against XXE / entity-expansion attacks.

## Build & test (contributors)

```sh
cd python
python -m venv .venv && .venv/bin/pip install -e ".[dev]"
.venv/bin/python -m pytest -q            # run the test suite
.venv/bin/pdoc meshtastic_tak -o docs    # generate the API docs
```

See the repository [CONTRIBUTING guide](https://github.com/meshtastic/TAKPacket-SDK/blob/master/CONTRIBUTING.md)
and [WIRE_FORMAT.md](https://github.com/meshtastic/TAKPacket-SDK/blob/master/WIRE_FORMAT.md).
