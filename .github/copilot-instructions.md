# Copilot Instructions — TAKPacket-SDK

## Project context

This is a cross-platform SDK for converting ATAK Cursor-on-Target (CoT) XML to Meshtastic's TAKPacketV2 protobuf wire format with zstd dictionary compression. Five parallel implementations (Kotlin, Swift, Python, TypeScript, C#) produce byte-interoperable payloads for LoRa mesh transport on port 78 with a 237-byte MTU.

## Code organization

Every platform implements the same 5 core classes with identical behavior:

- **`CotXmlParser`** — parses CoT XML event string into the internal data model
- **`CotXmlBuilder`** — reconstructs CoT XML from the data model
- **`TakCompressor`** — compresses data model to `[flags][zstd protobuf]` wire payload, and decompresses back
- **`CotTypeMapper`** — bidirectional CoT type string <-> enum, plus aircraft classification
- **`AtakPalette`** — ARGB <-> Team enum color palette lookup

Kotlin is the canonical implementation. It generates the golden test artifacts (`.pb` and `.bin` files in `testdata/`) consumed by the other 4 platforms.

## Proto schema

The protobuf schema lives in the `protobufs` git submodule (branch `takv2_geometry`). The key file is `protobufs/meshtastic/atak.proto`. Kotlin uses Wire 6.2.0 for codegen; other platforms use pre-generated bindings that are checked in.

**`TAKPacketV2`** has 26 top-level fields (tags 1-26) plus a `payload_variant` oneof at tags 30-40 with 11 typed payload cases: PLI, GeoChat, AircraftTrack, raw_detail, DrawnShape, Marker, RangeAndBearing, Route, CasevacReport, EmergencyAlert, TaskRequest.

Tags 25-26 are payload-agnostic annotations: `optional TAKEnvironment environment = 25` and `optional SensorFov sensor_fov = 26`. Tags 27-29 are reserved.

## Critical constants

```
LoRa MTU = 237 bytes
Port = 78 (ATAK_PLUGIN_V2)
Dict 0 = non-aircraft (16KB), Dict 1 = aircraft (4KB), 0xFF = uncompressed
Max decompressed size = 4096 bytes
Compression level = 19
Coordinates: degrees * 1e7 (sfixed32)
Speed: cm/s (uint32), Course: degrees * 100 (uint32)
```

## Build and test

```bash
cd kotlin && gradle jvmTest          # NOT `gradle test` — KMP has no root test task
cd swift && swift test
cd python && python -m pytest tests/
cd typescript && npx vitest run
cd csharp && dotnet test
./build.sh test                      # all platforms
```

## When modifying code

### Adding a new CoT element to the proto schema
1. Edit `protobufs/meshtastic/atak.proto` — add the message and field
2. Commit + push the submodule
3. Bump the submodule ref in the SDK repo
4. Kotlin: update `TakPacketV2Data.kt` (data class), `CotXmlParser.kt` (parse branch), `CotXmlBuilder.kt` (emit block), `TakPacketV2Serializer.kt` (Wire bridge)
5. Create a test fixture XML in `testdata/cot_xml/`
6. Run `gradle jvmTest` — generates golden files
7. Port to other languages following the same parser/builder/serializer pattern

### Adding a new test fixture
1. Drop the `.xml` file in `testdata/cot_xml/` — `TestFixtures.kt` auto-discovers it
2. Run `gradle jvmTest` twice (first run generates goldens, second is steady state)
3. Commit the new `.xml`, `.bin`, `.pb`, and updated `compression-report.md`

### Proto message naming
Do NOT use bare names that collide with framework types in target languages:
- **Swift:** `Environment`, `State`, `View`, `Task`, `Observable`, `Notification` all collide with SwiftUI/Foundation
- Prefix with `TAK` (e.g. `TAKEnvironment`) to avoid ambiguity

### Unit conventions
- Coordinates: degrees * 1e7 (sfixed32)
- Speed/wind: cm/s (uint32)
- Course/bearing: degrees * 100 (uint32)
- Temperature: deci-degrees Celsius (sint32, 225 = 22.5 C)
- Shape radii: centimeters
- Use sint32 for fields that can be negative (temperature, altitude, elevation, roll)

## Patterns to follow

- **Delta encoding**: shape vertices and route waypoints are delta-encoded from the event anchor point to save wire bytes
- **Dual color fields**: every color carries both a Team palette enum (2 bytes) and an _argb int32 fallback (5 bytes)
- **Remarks fallback**: `compressWithRemarksFallback()` tries with remarks first, strips them if over MTU
- **Forward compatibility**: unknown CoT types use `COTTYPE_OTHER (0)` + `cot_type_str` string. Unknown dict IDs must be rejected.
- **Parser clamps negatives**: ATAK sends `speed="-1.0"` for stationary. Parser clamps to 0 since proto field is uint32.

## Style

- Kotlin: standard Kotlin conventions, 4-space indent
- Swift: SwiftLint defaults
- Python: PEP 8, snake_case
- TypeScript: camelCase, 2-space indent
- C#: standard .NET conventions, PascalCase

Kotlin `TakPacketV2Data` uses camelCase field names. Wire-generated proto types use snake_case. The serializer bridges between them.

## Things NOT to do

- Don't run `gradle test` — use `gradle jvmTest` (KMP requires target-specific test task)
- Don't publish the parent JitPack coordinate to Android — use `takpacket-sdk-jvm` directly and exclude `zstd-jni`
- Don't regenerate Swift proto with bare `protoc --swift_out` — must include `--swift_opt=Visibility=Public`
- Don't change the `Team.Unspecifed_Color` typo — it's the canonical Wire-generated name
- Don't retrain dictionaries without coordinating a version bump — new dicts break wire compatibility with old receivers
- Don't use `toInt()` for `(longitude * 1e7)` comparisons — IEEE 754 rounding requires `roundToInt()`
