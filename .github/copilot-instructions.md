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

## PII and test-fixture sanitization (CRITICAL)

**Never commit unredacted real-world ATAK captures.** CoT XML carries position
data (lat/lon to sub-meter precision), Android device IDs that act as
permanent device fingerprints, private LAN IPs that reveal home network
topology, and personal callsigns. Anything captured from an operator's actual
deployment must be sanitized BEFORE landing in `testdata/`, the SDK README,
or the proto submodule — once it's in a git commit pushed to a public mirror,
no amount of force-pushing scrubs it from caches, forks, or pre-existing
clones reliably.

### Required replacements when sanitizing a real capture

| What | Pattern | Replace with |
|---|---|---|
| GPS coordinates | any real lat/lon | Public landmark coords (e.g. `38.8895, -77.0353` Washington Monument; `38.8814, -77.0502` Lincoln Memorial). For routes/polygons, pick distinct landmark pairs so the path topology survives. |
| Stationary / not-a-place markers | — | `lat="0.0" lon="0.0"` (matches ATAK's "null island" convention for non-positional events like `m-t-t` and `y-`) |
| Android device IDs (`ANDROID-[16 hex chars]`) | real device fingerprint | Sequential placeholders: `ANDROID-0000000000000001`, `…02`, etc. (Test fakes are already in this format — extend the numbering.) |
| Private LAN IPs (`192.168.x.x`, `10.x.x.x`, `172.16-31.x.x`) | real network IP | RFC 5737 documentation range: `192.0.2.1`, `198.51.100.1`, `203.0.113.1` (these ranges are reserved for examples and never route on the public internet). |
| MAC addresses | real radio MAC | Random or `00:00:00:00:00:0X` placeholder |
| Real callsigns | personally-identifying handles | Generic operator handles: `ALPHA-1`, `BRAVO-2`, `ASPEN`, `ETHEL`, `CHARLIE` are fine — they're well-worn placeholders. Avoid the actual operator's first name, military rank+last-name, or amateur radio call. |
| UUIDs | (no action) | Random UUIDs are high-entropy and not by themselves identifying. Keep them. |
| Voice profile IDs, room IDs, chatgrp UIDs | (no action unless they look like a real account ID) | Usually safe — they're UUIDs. |

### Workflow when adding a new fixture from a real capture

1. **Diff-redact, don't just rename.** Pull the source XML into `/tmp/` and edit there. Apply every replacement above. Save back into `testdata/cot_xml/`.
2. **Scan before staging.** Run this against the new file:
   ```bash
   grep -nE '\b\d{1,3}\.\d{5,}\b|ANDROID-[0-9a-f]{12,}|\b(192\.168|10\.|172\.(1[6-9]|2[0-9]|3[01]))\.[0-9]+\.[0-9]+\b' testdata/cot_xml/<your-new-fixture>.xml
   ```
   Hits on the coord regex with 5+ decimal places, on a non-sequential ANDROID hex ID, or on an RFC 1918 IP, mean it isn't sanitized yet. The sequential `ANDROID-0+\d+` pattern is fine — that's the test-fake convention.
3. **Add a redaction note** in the commit message: "redacted: coords → DC landmarks; android ids → 000…0NN; LAN IP → 192.0.2.1".
4. **Regenerate goldens twice.** `gradle jvmTest --tests CompressionTest.generate compression report --rerun-tasks` — the first pass mints `.pb`/`.bin`; rerun to confirm steady state.
5. **Never assume binary blobs auto-sanitize.** Filter-repo's `--replace-text` skips files containing null bytes, so the `.pb` protobuf intermediates and `.bin` golden files retain whatever bytes were generated. After scrubbing the source XML, ALWAYS regenerate the derived binaries.

### Pre-push self-check

Before `git push`, especially when pushing fixture changes, run from repo root:

```bash
# Quick PII sweep
grep -rEon '\b34\.[0-9]{1,2}\.[0-9]{5,}|\b\d{1,3}\.\d{5,}\b|ANDROID-[0-9a-f]{12,}|\b(192\.168|10\.|172\.(1[6-9]|2[0-9]|3[01]))\.[0-9]+\.[0-9]+\b' \
  --include='*.xml' --include='*.md' --include='*.swift' --include='*.kt' \
  --include='*.cs' --include='*.py' --include='*.ts' \
  | grep -v 'ANDROID-0\{12\}'
```

Empty output = clean. Any hit gets eyeballed before push.

### If real PII slips through

If you discover real PII in a commit that has already been pushed:
1. **Stop pushing** anything that builds on the leak.
2. Use `git filter-repo --replace-text` for text-format leaks (XML, README, code).
3. Follow up with `git filter-repo --blob-callback` for binary leaks (`.pb`, `.bin`) — filter-repo's `--replace-text` SKIPS files with null bytes, so binaries need an explicit callback that swaps the bad blob SHA for a clean re-baselined version.
4. Force-push every branch and tag (`git push --force origin master --tags`).
5. Open a GitHub Support ticket to purge the orphaned commits from cache — they remain accessible by SHA URL for 30+ days otherwise.
6. Reach out to fork owners — your force-push doesn't propagate.
