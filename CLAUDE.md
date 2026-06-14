# CLAUDE.md — TAKPacket-SDK

Instructions for Claude Code sessions working on this repository.

## What this repo is

TAKPacket-SDK is a cross-platform library that converts ATAK Cursor-on-Target (CoT) XML into Meshtastic's `TAKPacketV2` protobuf format and compresses it with zstd dictionary compression for LoRa mesh transport (237-byte MTU, port 78). Five parallel implementations — Kotlin, Swift, Python, TypeScript, C# — produce cross-decodable wire payloads validated by 47 shared test fixtures.

> **Interop nuance (important):** every binding can decode any other binding's frames, and the intermediate protobuf goldens (`.pb`) are byte-identical across bindings (protobuf serialization is deterministic). The *compressed* bytes (`.bin`) may differ slightly per binding (zstd encoders differ), so cross-language tests assert **decodability + a size tolerance**, NOT compressed-byte-identity. Do not write a test that requires byte-identical compressed output across languages.

## Repository layout

```
protobufs/           Git submodule (meshtastic/protobufs @ master)
                     Single source of truth: meshtastic/atak.proto
dictionaries/        Canonical zstd dictionaries (non-aircraft 512KB proto-trained, aircraft 4KB)
testdata/
  cot_xml/           47 CoT XML fixtures (input)
  golden/            47 .bin compressed wire payloads (Kotlin-generated)
  protobuf/          47 .pb intermediate protobuf bytes (Kotlin-generated)
  malformed/         malformed input test files
  compression-report.md   Auto-generated size report
kotlin/              Canonical implementation (full KMP: jvm + js + wasmJs + wasmWasi + 9 native — consumes published org.meshtastic:protobufs)
swift/               Swift Package (SwiftProtobuf + CZstd)
python/              Python package (protobuf + zstandard)
typescript/          npm package (protobufjs + fzstd)
csharp/              .NET 9 library (Google.Protobuf + ZstdSharp)
```

## Architecture — every platform implements these 6 classes

| Class | Responsibility |
|-------|---------------|
| `CotXmlParser` | CoT XML string -> internal data model |
| `CotXmlBuilder` | Internal data model -> CoT XML string |
| `TakCompressor` | Data model -> `[flags][zstd protobuf]` wire payload, and reverse |
| `CotTypeMapper` | Bidirectional CoT type string <-> enum, aircraft classification |
| `AtakPalette` | ARGB <-> Team enum bidirectional lookup for the 14 ATAK colors |
| `CotMeshSanitizer` | CoT-XML hygiene for mesh: `stripNonEssentialForMesh` (drop display-only detail, **preserve voice/marti**) + `normalizeCotXml` (drop `<?xml?>`, collapse inter-tag whitespace). Pure regex; **Kotlin lives in `commonMain`** (so KMP consumers can call it on iOS too). Hoisted from the app strip pipelines so the rules live in one golden-tested place (`testdata/sanitizer/`) and can't drift. |

Plus per-platform: `DictionaryProvider` (loads zstd dicts from resources) and `TakPacketV2Serializer` (Kotlin only — bridges `TakPacketV2Data` <-> Wire-generated proto types).

## Kotlin is canonical

- Kotlin generates all golden `.pb` and `.bin` files via `CompressionTest.generate compression report`
- Other platforms validate AGAINST those goldens; they don't generate them
- When adding a new fixture: drop `.xml` in `testdata/cot_xml/`, run `gradle jvmTest` (auto-discovers via `TestFixtures.kt`), commit the generated goldens
- Proto types come from the published `org.meshtastic:protobufs` KMP artifact (version pinned in `kotlin/build.gradle.kts`, declared as a `commonMain implementation` dependency) — they are not generated in this repo. (It used to be `compileOnly` while the module was jvm-only; the full-KMP migration switched it to `implementation` because Native/JS/Wasm cannot link a `compileOnly` dep, so protobufs now ships transitively in the published POM/metadata. Android consumers already bring the same artifact and own its version.) That artifact is Wire-generated upstream with `boxOneOfsMinSize = 5000` (flattens oneofs to nullable fields), which is why the serializer sees nullable oneof arms.
- Published to **Maven Central** (primary) via the vanniktech maven-publish plugin: `org.meshtastic:takpacket-sdk-jvm:<version>`. **JitPack** remains a fallback: `com.github.meshtastic.TAKPacket-SDK:takpacket-sdk-jvm:<tag>`.

**Environment prerequisites (these cost real time when missed):**
- **Kotlin/Gradle needs JDK 21.** Export before any Gradle call, and use `./gradlew` (not a system `gradle`):
  `export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home`
- **Python uses a venv that has `protobuf` + `zstandard`:** `python/.venv/bin/python`. Run tests as `python/.venv/bin/python -m pytest -q`.
- **Swift tests can't run in a CLI-only macOS env** (they use the Xcode `Testing` module). `swift build` / `swift build --build-tests` still validate compilation; the resilience test deliberately uses XCTest so it compiles everywhere.

```bash
# Individual platforms
cd kotlin && ./gradlew jvmTest --quiet            # needs JAVA_HOME=JDK21
cd swift && swift test                            # needs Xcode Testing module
cd python && .venv/bin/python -m pytest -q
cd typescript && npm run build && npm test
cd csharp && dotnet test

# All at once
./build.sh test

# Regenerate golden files + compression report (Kotlin is canonical for these)
cd kotlin && ./gradlew jvmTest --tests "*CompressionTest*generate compression report*"
# This one test writes testdata/golden/*.bin, testdata/protobuf/*.pb, and
# testdata/compression-report.md. Run it after ANY wire/schema/dict change,
# then re-run the other 4 suites against the new goldens.

# Publish for local Android consumer testing (resolves as org.meshtastic:takpacket-sdk[-jvm]:<VERSION>)
cd kotlin && ./gradlew publishToMavenLocal        # then build Android with -PuseMavenLocal
```

## Wire format (critical constants)

- **MTU:** 237 bytes (LoRa maximum)
- **Port:** 78 (`ATAK_PLUGIN_V2`)
- **Wire payload:** `[1 byte flags][N bytes zstd body]`
- **Flags byte:** bits 0-5 = dictionary ID, bits 6-7 = reserved (ignore on receive, zero on send)
- **Dict IDs:** 0 = non-aircraft (512KB proto-trained dict), 1 = aircraft (4KB dict), 0xFF = uncompressed raw protobuf
- **Frame slimming (v0.4.0, −8 B/packet):** compress with `dictID` / `contentSize` / `checksum` all OFF, then **strip the 4-byte zstd magic** (`28 B5 2F FD`) on encode and re-prepend it on decode. Done **manually and uniformly in all 5 bindings** (NOT zstd native "magicless" — `zstd-napi` in TS can't do that). The on-wire body has no magic number. The magic is a compile-time constant, so this stays fully stateless.
- **Skip-compress (v0.4.0):** if the raw protobuf ≤ the zstd body, emit `[0xFF][raw protobuf]` instead. Tiny/incompressible packets never expand (worst case = raw + 1 flags byte). The `0xFF` path already existed on decode in all bindings.
- **Max decompressed size:** 4,096 bytes (security guard, reject anything larger)
- **Compression level:** 19 (zstd maximum)
- **Aircraft classification:** 3rd atom of CoT type string = "A" (e.g. `a-n-A-C-F`)

> **Resilience invariant (HARD CONSTRAINT — never violate):** every packet must be fully, independently decodable from its own bytes + the static shipped dict. ZERO cross-packet state. Use the one-shot compress/decompress API only — NEVER the zstd streaming API (`compressStream`/`ZSTD_compressStream2`). The dict is static/shipped, never adapted from runtime traffic. `ResilienceTest` in every binding guards this. LoRa is lossy; losing one packet must never affect any other.

## Key data model patterns

- **`TakPacketV2Data`** has 26 envelope fields + `Payload` sealed class with 13 oneof variants (Chat, Aircraft, RawDetail, DrawnShape, Marker, RangeAndBearing, Route, CasevacReport, EmergencyAlert, TaskRequest, TakTalk, TakTalkRoom). **PLI is implicit** (v0.4.0): the `bool pli` oneof arm was removed — a packet with NO payload variant + an `a-f-*` cot type IS a PLI. Proto tag 30 is reserved.
- **`EnvironmentData`** and **`SensorFovData`** are optional top-level annotations (not payload variants) — they attach to any event type
- **Delta encoding:** Route waypoints (`Route.Link.point`) and R&B anchor use `CotGeoPoint` lat/lon deltas. **DrawnShape vertices (v0.4.0)** are two PACKED `repeated sint32` columns — `vertex_lat_deltas` (tag 18) + `vertex_lon_deltas` (tag 19), zigzag deltas from the envelope `latitude_i`/`longitude_i`. Old `repeated CotGeoPoint vertices = 12` is reserved. (CotGeoPoint still exists for Route/R&B.)
- **Dual color encoding:** Every color field carries both a `Team` palette enum (compact) and an exact `_argb` int32 (lossless fallback)
- **Remarks fallback:** `compressWithRemarksFallback()` tries with remarks, strips them if over MTU, returns null if still too big

## Proto schema management

- Schema lives in the `protobufs` git submodule (`meshtastic/protobufs` repo, branch `master`). Package: `meshtastic`, java_package: `org.meshtastic.proto`. The submodule is the schema source of truth and is consumed directly by the Swift/Python/TypeScript/C# bindings; **Kotlin no longer codegens from it** — there is no Wire plugin in this repo, so nothing is generated into `build/generated/source/wire/`.
- When editing proto: commit + push in the submodule first, then bump the submodule ref in the SDK repo. **For Kotlin, additionally** publish a new `org.meshtastic:protobufs` release and bump its version in `kotlin/build.gradle.kts` — Kotlin gets its proto types from that published artifact, not from local codegen.
- Swift proto bindings (`atak.pb.swift`) ARE checked in; regenerate with:
  `protoc --proto_path=../protobufs --swift_opt=Visibility=Public --swift_out=swift/Sources/MeshtasticTAK ../protobufs/meshtastic/atak.proto`
- Python (`atak_pb2.py`) and C# (`Atak.cs`) bindings are also checked in and regenerated manually. TypeScript has no codegen step — it loads `protobufs/meshtastic/atak.proto` at runtime via protobufjs.

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

- Most of the 47 fixtures are clustered near Truth or Consequences, NM (~33.13, -107.25); newer redacted fixtures use DC-area public landmarks (see PII section)
- Aircraft fixtures use the same area at different altitudes
- `delete_event.xml` uses 0,0 (intentional — delete events have no location)
- Adding a fixture: just drop the `.xml` file; `TestFixtures.kt` auto-discovers from `testdata/cot_xml/`
- After adding: run `gradle jvmTest` to regenerate goldens, then commit the new `.bin`, `.pb`, and updated `compression-report.md`

## Dictionary retraining

- Dictionaries are trained in a separate repo (`meshtastic/TAKPacket-ZTSD`)
- **Train on PROTO bytes, not XML.** The SDK compresses serialized `TAKPacketV2`, so the dict must be trained on proto. Use `TAKPacket-ZTSD/train_proto.py` — it re-encodes the corpus XML through the Python SDK to proto, then trains. (The legacy `train.py` trains on raw CoT XML and underperforms badly — an XML-trained dict wastes its budget on XML structural tokens that never hit the wire. This proto-vs-XML mismatch was the single biggest compression win.)
- Current dicts: **non-aircraft 512 KB + aircraft 4 KB**, zstd level 19, proto-trained. 512 KB is the measured "knee" of a 64 KB→1 MB sweep — 1 MB *overfits* (median/worst-case regress). Footprint is unconstrained (phones/Linux), so size at the knee, not the smallest.
- **Deploy:** `cd TAKPacket-ZTSD && python train_proto.py` writes candidates; copy the chosen one to `output/dict_non_aircraft.zstd`, then `bash deploy.sh ../TAKPacket-SDK`. `deploy.sh` copies ONLY the two canonical filenames into each binding resource dir — it must NOT glob `output/*.zstd` (that leaks sweep candidates like `dict_non_aircraft_524288.zstd` into the shipped packages; deploy.sh is hardened against this).
- **After deploy:** re-baseline Kotlin goldens (regen command below), then re-run all 5 suites. Run `train_proto.py` with the SDK's Python venv (`python/.venv`) — it needs both `protobuf` and `zstandard`; ZTSD's own venv lacks protobuf.
- Retraining is wire-incompatible (forces a lockstep mesh upgrade) — batch it into a minor version bump.

## Common pitfalls

1. **Running `gradle test` instead of `gradle jvmTest`** — KMP has no root `test` task; use `jvmTest` for the JVM target
2. **Forgetting `git submodule update --init --recursive`** — proto codegen fails without the protobufs submodule
3. **Stale golden files after fixture changes** — first `gradle jvmTest` run regenerates goldens but `CompatibilityTest.all golden files exist` may fail; second run is steady state
4. **Depend on the `-jvm` artifact, not the KMP parent** — Android consumers must depend on the JVM artifact directly (`org.meshtastic:takpacket-sdk-jvm` on Maven Central, or `com.github.meshtastic.TAKPacket-SDK:takpacket-sdk-jvm` on the JitPack fallback), NOT the parent `takpacket-sdk` / `TAKPacket-SDK` coordinate, and exclude `zstd-jni` (Android needs the @aar variant). The Kotlin module is now full KMP (jvm + js + wasmJs + wasmWasi + 9 native), but Android still consumes the JVM variant — iOS consumers use the `MeshtasticTAK` Swift package rather than the Kotlin/Native klibs.
5. **Swift protoc visibility** — always pass `--swift_opt=Visibility=Public` or the generated types are internal and break downstream consumers
6. **Negative speed/course from ATAK** — ATAK sends `speed="-1.0"` for stationary; the parser clamps negatives to 0 (uint32 field)
7. **IEEE 754 rounding on longitude assertions** — use `roundToInt()` not `toInt()` when comparing `(lon * 1e7)` to `longitudeI`
8. **TypeScript zstd-napi: set `windowLog` BEFORE `loadDictionary`.** `windowLog` is a *compression* parameter (unlike the frame-only `contentSizeFlag`/`checksumFlag`/`dictIDFlag`, which are safe to set after). Setting it after the dict is loaded silently resets the digested dictionary → worse ratios AND "Data corruption" on decode. zstd-napi also does NOT auto-size its window to a large loaded dict, so without an explicit `windowLog` (≥ enough to cover the dict, e.g. 21 for a 512 KB dict) it misses deep dict matches and compresses small inputs much worse than the other bindings. Also set `windowLogMax` on the decompressor so it accepts peers' large-window frames. The other 4 bindings auto-size their window — only TS needs this.
9. **Never `glob` dicts in deploy** — `TAKPacket-ZTSD/deploy.sh` copies the two canonical dict filenames by name. A `cp output/*.zstd` glob leaks sweep candidates (`dict_non_aircraft_<size>.zstd`) into every binding's shipped resources (megabytes of dead weight). The runtime loaders read exact filenames only.
10. **Phantom "optimizations" that were evaluated and NOT shipped** — do not re-introduce or document these as done: `course` stays `uint32` degrees×100 (NOT whole degrees), `uid` stays `string` (NOT 16 raw bytes — UUID case-sensitivity would corrupt ATAK object correlation), `stale_seconds` stays tag 16 (NOT renumbered), `altitude` stays plain `sint32` (the `optional`-to-omit-sentinel attempt *regressed* worst case because the dict already crushes the 9999999 sentinel). Lesson: field-level micro-opts are ≈0 after the dictionary for constant/sentinel values — measure before assuming a saving.
11. **Regenerate goldens after any wire/schema/dict change**, then re-run all 5 suites. `CompatibilityTest` golden mismatches after such a change are EXPECTED — regenerate (item above), don't "fix" the test. The full `jvmTest` run regenerates the report as a side effect, so snapshot `git show HEAD:testdata/compression-report.md` for before/after comparisons, not a `/tmp` copy taken mid-run.
12. **`reserved` inside a `oneof` is invalid proto3** — reserve dropped oneof tags at the *message* level (e.g. `reserved 30;` for the old `bool pli`), not inside the `oneof` block. Wire AND protoc reject it (protoc silently no-ops, leaving stale generated code).

## Commit conventions

- The repo owner prefers to be the commit author — do not add `Co-Authored-By` trailers
- Commit messages should follow the existing style: imperative mood, detailed body explaining what + why
- Do not auto-commit — stage changes and describe what you did so the user can commit

## CI/CD

- **CI** (`.github/workflows/ci.yml`): all 5 platforms tested on push/PR to main/master
- **Release** (`.github/workflows/release.yml`): manual dispatch, reads `VERSION` / `kotlin/gradle.properties:VERSION_NAME`, tests all platforms, **publishes the Kotlin artifacts to Maven Central** (vanniktech `publishAllPublicationsToMavenCentralRepository`, `automaticRelease = true`), and creates a GitHub Release. It probes repo1.maven.org first, so a re-run skips an already-published version.
- **JitPack** (`jitpack.yml`): fallback channel, triggered by git tags — runs `publishToMavenLocal` and serves the artifacts under `com.github.meshtastic:TAKPacket-SDK:<tag>`. Cold build ~120-150s; trigger URL: `https://jitpack.io/com/github/meshtastic/TAKPacket-SDK/<tag>/TAKPacket-SDK-<tag>.pom`

## Downstream consumers

- **Meshtastic-Android** (`core/takserver`): depends on `org.meshtastic:takpacket-sdk-jvm` via Maven Central (JitPack fallback), proto submodule at `core/proto/src/main/proto`. It also depends on the **same `org.meshtastic:protobufs` KMP artifact** directly. The SDK declares protobufs as a `commonMain implementation` dependency (the full-KMP migration could no longer use `compileOnly`, which Native/JS/Wasm can't link), so protobufs is now a transitive dependency in the SDK's POM; the consumer still owns/aligns its own protobufs version.
- **Meshtastic-Apple**: depends on `MeshtasticTAK` Swift package via remote SPM URL, proto submodule at `protobufs/`, regenerated `atak.pb.swift` at `MeshtasticProtobufs/Sources/meshtastic/`

## PII / sensitive-data handling — read before adding any fixture

The repo has had real-world ATAK captures land in test fixtures by accident
before. The compressed `.bin` and `.pb` intermediates retain the leaked
identifiers even after the source XML is fixed, because filter-repo's text
replacement skips files containing null bytes. Treat this as a recurring
hazard.

**Patterns that must never appear in committed fixtures or docs:**

| Pattern | What it leaks | Required substitute |
|---|---|---|
| High-precision lat/lon (5+ decimals) that's not a public landmark | Operator's actual location, often a home | DC-area public landmarks: `38.8895,-77.0353` (Washington Monument), `38.8814,-77.0502` (Lincoln Memorial), or `0.0,0.0` for non-positional events like `m-t-t` / `y-` |
| `ANDROID-[16 hex chars]` where the hex isn't all-zeros | A specific Android device's ANDROID_ID — never changes, can fingerprint the device for years | Sequential placeholder: `ANDROID-0000000000000001`, `…02`, etc. |
| RFC 1918 private IPs (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`) | Home/office network topology | RFC 5737 docs range: `192.0.2.1`, `198.51.100.1`, `203.0.113.1` |
| MAC addresses | Hardware fingerprint | `00:00:00:00:00:0X` placeholder |
| Real operator callsigns (first names, military rank+name, ham calls) | Identity | Generic operator handles: `ALPHA-1`, `BRAVO-2`, `ASPEN`, `ETHEL` |

**When you're handed a real CoT Explorer capture / pg_dump excerpt to add as a fixture:**

1. Don't drop it into `testdata/` raw. Edit a redacted copy in `/tmp/` first.
2. Apply every substitution above. Random UUIDs (message IDs, room IDs, chatgrp UIDs) can stay — they're high-entropy and not identifying on their own.
3. Sanity-grep the redacted file before staging:
   ```bash
   grep -nE '\b\d{1,3}\.\d{5,}\b|ANDROID-[0-9a-f]{12,}|\b(192\.168|10\.|172\.(1[6-9]|2[0-9]|3[01]))\.[0-9]+\.[0-9]+\b' /tmp/<file>.xml
   ```
   Hits on coords with 5+ decimal places, non-sequential ANDROID hex, or RFC 1918 IPs mean it's still dirty. (Sequential `ANDROID-0+\d+` is allowed — that's the test-fake convention.)
4. Regenerate goldens via `gradle jvmTest --tests "CompressionTest.generate compression report" --rerun-tasks` so the derived `.pb` / `.bin` artifacts pick up the clean strings. The Kotlin test writes both into `testdata/golden/` and `testdata/protobuf/`.

**If a leak ships to master:**

The recovery is `git filter-repo` in two passes — text substitution for the XML/code/docs, then a `--blob-callback` for the binary `.pb` and `.bin` files (filter-repo skips text replacement on files with null bytes, so binaries need an explicit callback that swaps the bad blob SHAs for clean re-baselined ones). Force-push every branch and tag. Open a GitHub Support ticket to purge orphaned commits — they remain reachable by SHA URL until cache expires (~30 days). Forks aren't touched by the force-push.

The detailed playbook is in `.github/copilot-instructions.md` under "PII and test-fixture sanitization."
