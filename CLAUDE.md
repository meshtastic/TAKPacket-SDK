# CLAUDE.md — TAKPacket-SDK

Instructions for Claude Code sessions working on this repository.

## What this repo is

TAKPacket-SDK is a cross-platform library that converts ATAK Cursor-on-Target (CoT) XML into Meshtastic's `TAKPacketV2` protobuf format and compresses it with zstd dictionary compression for LoRa mesh transport (237-byte MTU, port 78). Five parallel implementations — Kotlin, Swift, Python, TypeScript, C# — produce byte-interoperable wire payloads validated by 41 shared test fixtures.

## Repository layout

```
protobufs/           Git submodule (meshtastic/protobufs @ takv2_geometry)
                     Single source of truth: meshtastic/atak.proto
dictionaries/        Canonical zstd dictionaries (non-aircraft 16KB, aircraft 4KB)
testdata/
  cot_xml/           41 CoT XML fixtures (input)
  golden/            41 .bin compressed wire payloads (Kotlin-generated)
  protobuf/          41 .pb intermediate protobuf bytes (Kotlin-generated)
  malformed/         11 malformed input test files
  compression-report.md   Auto-generated size report
kotlin/              Canonical implementation (Wire 6.2.0 KMP)
swift/               Swift Package (SwiftProtobuf + CZstd)
python/              Python package (protobuf + zstandard)
typescript/          npm package (protobufjs + fzstd)
csharp/              .NET 9 library (Google.Protobuf + ZstdSharp)
```

## Architecture — every platform implements these 5 classes

| Class | Responsibility |
|-------|---------------|
| `CotXmlParser` | CoT XML string -> internal data model |
| `CotXmlBuilder` | Internal data model -> CoT XML string |
| `TakCompressor` | Data model -> `[flags][zstd protobuf]` wire payload, and reverse |
| `CotTypeMapper` | Bidirectional CoT type string <-> enum, aircraft classification |
| `AtakPalette` | ARGB <-> Team enum bidirectional lookup for the 14 ATAK colors |

Plus per-platform: `DictionaryProvider` (loads zstd dicts from resources) and `TakPacketV2Serializer` (Kotlin only — bridges `TakPacketV2Data` <-> Wire-generated proto types).

## Kotlin is canonical

- Kotlin generates all golden `.pb` and `.bin` files via `CompressionTest.generate compression report`
- Other platforms validate AGAINST those goldens; they don't generate them
- When adding a new fixture: drop `.xml` in `testdata/cot_xml/`, run `gradle jvmTest` (auto-discovers via `TestFixtures.kt`), commit the generated goldens
- Proto codegen: Wire 6.2.0 with `boxOneOfsMinSize = 5000` (flattens oneofs to nullable fields)
- JitPack publishes the JVM variant: `com.github.meshtastic.TAKPacket-SDK:takpacket-sdk-jvm:<tag>`

## Build and test commands

```bash
# Individual platforms
cd kotlin && gradle jvmTest --quiet
cd swift && swift test
cd python && python -m pytest tests/ -v
cd typescript && rm -f package-lock.json && npm install && npx vitest run
cd csharp && dotnet test

# All at once
./build.sh test

# Regenerate golden files + compression report (Kotlin only)
cd kotlin && gradle jvmTest
# The "generate compression report" test writes testdata/golden/*.bin,
# testdata/protobuf/*.pb, and testdata/compression-report.md automatically.
```

## Wire format (critical constants)

- **MTU:** 237 bytes (LoRa maximum)
- **Port:** 78 (`ATAK_PLUGIN_V2`)
- **Wire payload:** `[1 byte flags][N bytes zstd-compressed TAKPacketV2 protobuf]`
- **Flags byte:** bits 0-5 = dictionary ID, bits 6-7 = reserved (ignore on receive, zero on send)
- **Dict IDs:** 0 = non-aircraft (16KB dict), 1 = aircraft (4KB dict), 0xFF = uncompressed raw protobuf
- **Max decompressed size:** 4,096 bytes (security guard, reject anything larger)
- **Compression level:** 19 (zstd maximum)
- **Aircraft classification:** 3rd atom of CoT type string = "A" (e.g. `a-n-A-C-F`)

## Key data model patterns

- **`TakPacketV2Data`** has 26 envelope fields + `Payload` sealed class with 11 variants (PLI, Chat, Aircraft, RawDetail, DrawnShape, Marker, RangeAndBearing, Route, CasevacReport, EmergencyAlert, TaskRequest)
- **`EnvironmentData`** and **`SensorFovData`** are optional top-level annotations (not payload variants) — they attach to any event type
- **Delta encoding:** Shape vertices and route waypoints store lat/lon deltas from the event anchor point
- **Dual color encoding:** Every color field carries both a `Team` palette enum (compact) and an exact `_argb` int32 (lossless fallback)
- **Remarks fallback:** `compressWithRemarksFallback()` tries with remarks, strips them if over MTU, returns null if still too big

## Proto schema management

- Lives in the `protobufs` git submodule (`meshtastic/protobufs` repo, branch `takv2_geometry`)
- Package: `meshtastic`, java_package: `org.meshtastic.proto`
- When editing proto: commit + push in the submodule first, then bump the submodule ref in the SDK repo
- Wire generates code into `build/generated/source/wire/commonMain/` — this is NOT checked in
- Swift proto bindings (`atak.pb.swift`) ARE checked in; regenerate with:
  `protoc --proto_path=../protobufs --swift_opt=Visibility=Public --swift_out=swift/Sources/MeshtasticTAK ../protobufs/meshtastic/atak.proto`
- Python/C# proto bindings are also checked in and regenerated manually

## Naming constraints

- **Do NOT name proto messages `Environment`, `State`, `View`, `Task`, `Observable`, or `Notification`** — these collide with SwiftUI/Foundation types in iOS consumers. Prefix with `TAK` (e.g. `TAKEnvironment`).
- Field names on `TAKPacketV2` use snake_case (Wire convention). The SDK's internal data classes use camelCase.
- CotType enum values use `CotType_` prefix. Team enum has a typo: `Unspecifed_Color` (not `Unspecified`) — this is the canonical Wire-generated name, do not "fix" it.

## Unit conventions

| Field | Unit | Notes |
|-------|------|-------|
| `latitude_i` / `longitude_i` | degrees * 1e7 (sfixed32) | Standard Meshtastic convention |
| `speed` | cm/s (uint32) | ATAK sends m/s, multiply by 100 |
| `course` | degrees * 100 (uint32) | ATAK sends degrees, multiply by 100 |
| `altitude` | meters HAE (sint32) | Can be negative |
| `temperature_c_x10` | deci-degrees Celsius (sint32) | 225 = 22.5 C |
| `wind_speed_cm_s` | cm/s (uint32) | Matches speed convention |
| Shape radii (`major_cm`, `minor_cm`) | centimeters | ATAK sends meters, multiply by 100 |
| `range_cm` (R&B) | centimeters | |
| `bearing_cdeg` (R&B) | degrees * 100 | |
| `bullseye_distance_dm` | decimeters | |
| `stroke_weight_x10` | weight * 10 | |

## Test fixture rules

- All 41 fixtures are clustered near Truth or Consequences, NM (~33.13, -107.25)
- Aircraft fixtures use the same area at different altitudes
- `delete_event.xml` uses 0,0 (intentional — delete events have no location)
- Adding a fixture: just drop the `.xml` file; `TestFixtures.kt` auto-discovers from `testdata/cot_xml/`
- After adding: run `gradle jvmTest` to regenerate goldens, then commit the new `.bin`, `.pb`, and updated `compression-report.md`

## Dictionary retraining

- Dictionaries are trained in a separate private repo (`meshtastic/TAKPacket-ZTSD`)
- `DictionaryTrainingTest.kt` generates a ~810-sample training corpus under `testdata/training_corpus/` (gitignored)
- Current dicts: v2 (includes CASEVAC, EmergencyAlert, TaskRequest, GeoChat receipts, Environment/SensorFov samples)
- Retraining is expensive and wire-incompatible (changes dict ID semantics) — only do it in batched major version bumps
- Retraining commands: `zstd --train training_corpus/ -o dict_non_aircraft.zstd --maxdict=16384` and `zstd --train training_corpus/aircraft_* -o dict_aircraft.zstd --maxdict=4096`

## Common pitfalls

1. **Running `gradle test` instead of `gradle jvmTest`** — KMP has no root `test` task; use `jvmTest` for the JVM target
2. **Forgetting `git submodule update --init --recursive`** — proto codegen fails without the protobufs submodule
3. **Stale golden files after fixture changes** — first `gradle jvmTest` run regenerates goldens but `CompatibilityTest.all golden files exist` may fail; second run is steady state
4. **JitPack parent POM pulls iOS klibs** — Android consumers must depend on `takpacket-sdk-jvm` directly, not the parent `TAKPacket-SDK` coordinate, and exclude `zstd-jni` (Android needs the @aar variant)
5. **Swift protoc visibility** — always pass `--swift_opt=Visibility=Public` or the generated types are internal and break downstream consumers
6. **Negative speed/course from ATAK** — ATAK sends `speed="-1.0"` for stationary; the parser clamps negatives to 0 (uint32 field)
7. **IEEE 754 rounding on longitude assertions** — use `roundToInt()` not `toInt()` when comparing `(lon * 1e7)` to `longitudeI`

## Commit conventions

- The repo owner prefers to be the commit author — do not add `Co-Authored-By` trailers
- Commit messages should follow the existing style: imperative mood, detailed body explaining what + why
- Do not auto-commit — stage changes and describe what you did so the user can commit

## CI/CD

- **CI** (`.github/workflows/ci.yml`): all 5 platforms tested on push/PR to main/master
- **Release** (`.github/workflows/release.yml`): manual dispatch, reads `VERSION`, tests all platforms, builds artifacts, creates GitHub Release
- **JitPack** (`jitpack.yml`): triggered by git tags, publishes KMP JVM variant to `jitpack.io`
- JitPack cold build takes ~120-150s; the POM URL to trigger is: `https://jitpack.io/com/github/meshtastic/TAKPacket-SDK/<tag>/TAKPacket-SDK-<tag>.pom`

## Downstream consumers

- **Meshtastic-Android** (`core/takserver`): depends on `takpacket-sdk-jvm` via JitPack, proto submodule at `core/proto/src/main/proto`
- **Meshtastic-Apple**: depends on `MeshtasticTAK` Swift package via remote SPM URL, proto submodule at `protobufs/`, regenerated `atak.pb.swift` at `MeshtasticProtobufs/Sources/meshtastic/`
