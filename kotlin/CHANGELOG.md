# Changelog

All notable changes to the TAKPacket-SDK Kotlin module are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [0.8.1] — CoT rebuild hygiene

Bug-fix release. The wire format is unchanged (all `.bin`/`.pb` goldens are
byte-identical to v0.8.0) and no public API changed — only the values the
parser stores and the builder re-emits.

- **A peer's `<contact>` endpoint no longer travels over the mesh** (#122).
  The parser previously stored any `endpoint` attribute that was not one of the
  two known defaults, and the builder re-emitted it on rebuild. That address is
  a host on the *sending* peer's own LAN, so every other mesh member handed its
  TAK client an address it has no route to — surfacing as a transmission socket
  that could not be created for `<peer-lan-ip>:4243`. The parser now never
  stores a contact endpoint and the builder always emits the TAK server-reply
  form.
- **Malformed raw detail is dropped rather than re-emitted** (#122).
- Both fixes are applied consistently across all five bindings (Kotlin, Swift,
  Python, TypeScript, C#), each with a `RebuildHygiene` test suite.

### Tooling and CI

No effect on published artifacts.

- Native and Apple targets now actually **execute** their test suites in CI: a
  `macos-latest` leg runs `macosArm64Test` and `iosSimulatorArm64Test`, and the
  Linux leg adds `jsTest`, `wasmJsTest`, and `linuxX64Test`. Previously only
  `jvmTest` ran, leaving 12 of 13 targets compile-checked but never tested
  (#121).
- Spotless (ktlint) and detekt now gate the Kotlin module (#121).
- klib ABI validation enforced in CI, with the native/common surface dumped
  alongside the JVM one (#111).
- Every third-party GitHub Action is pinned to a full commit SHA (#117, #119);
  CodeQL and OpenSSF Scorecard analysis added (#112); Codecov reports gate on
  coverage regression (#118); JS test-harness dependencies pinned to clear
  Dependabot alerts (#114).
- Stale `master` references repointed at `main` after the branch rename,
  including the `bump-version` workflow's PR base, which had been broken since
  the rename (#113, #121).
- Community-health files, `CODEOWNERS`, Gradle wrapper checksum pinning, and a
  refreshed Kotlin README badge row (#115, #116, #120).

## [0.8.0] — Kotlin 2.4.10 toolchain, protobufjs 8, xmlutil 1.0

Dependency-refresh release; the wire format is unchanged (`atak.proto` is
byte-identical to v0.7.0 — the protobufs submodule updates over this window
touched other files only, and all `.bin`/`.pb` goldens are unchanged).

- **Kotlin 2.4.10** (was 2.3.x) (#100). Native/iOS consumers of the KMP
  artifact need a Kotlin 2.4.x toolchain to consume the published klibs;
  JVM/Android consumers are unaffected. This is why the version is 0.8.0
  rather than 0.7.1.
- **TypeScript: protobufjs 8** (#62). protobufjs 8 elides set-to-default
  values on encode (proto3-canonical; 7.x wrote them), so `decompress()` now
  materializes proto3 defaults via `toObject(msg, { defaults: true })` —
  absent scalars come back as `0`/`""`/`false`, exactly like the
  Wire/protobuf runtimes in the Kotlin/Swift/Python/C# bindings. TS consumers
  that distinguished `undefined` from `0`/`""` on decompressed packets must
  use proto3 semantics instead.
- **Kotlin: xmlutil 1.0** (#85) — API-stabilization release of the CoT XML
  parsing dependency; no `CotXmlParser` changes needed.
- Per-platform API docs and publishing for all 5 bindings (#69).
- Security: yarn resolution floors for the Kotlin/JS **test harness**
  (ws 8.21.0, serialize-javascript 7.0.5, webpack 5.104.1, diff 8.0.3),
  clearing all six open Dependabot alerts; dev-time only, nothing ships in
  published artifacts (#106).
- Toolchain/CI: Gradle 9.6.1, JUnit 6.1.2, vanniktech publish 0.37.0,
  swift-protobuf 1.38.1, zstd-napi 0.0.13, .NET test SDK 18.8.1, Python 3.14,
  GitHub Actions updates; Renovate now batches protobufs digest bumps weekly
  and holds typescript <7 until a stable typedoc supports it (#105).

## [0.7.0] — zstd codec extracted to the standalone kzstd library

The pure-Kotlin zstd codec introduced in 0.6.0 now lives in its own published
library, **`org.meshtastic:kzstd`**. This SDK deletes its vendored `internal.zstd`
copy (~3,200 lines) and consumes kzstd as a `commonMain` dependency, so the codec
is maintained and tested in exactly ONE place — there is no longer a near-identical
second copy to keep in sync.

This is **wire-transparent: kzstd IS the same engine**, just relocated. The `.bin`
wire goldens and the `.pb` protobuf goldens are byte-for-byte unchanged, and old
and new nodes stay fully interoperable in BOTH directions. kzstd produces and reads
full, standard zstd frames that real libzstd — and therefore the Swift/Python/
TypeScript/C# bindings — interoperate with; that interop gate now lives in kzstd's
own test suite. The SDK keeps only a golden-decode integration oracle
(`GoldenDecodeCommonTest`) over its own dictionaries and wire frames.

### Changed

- **Codec extracted to `org.meshtastic:kzstd`** (pinned `0.1.0`, declared as a
  `commonMain implementation` dependency, so it ships transitively in the published
  POM/metadata alongside `protobufs`). `ZstdCodec` is now a thin adapter over
  kzstd's one-shot `Zstd` API plus its immutable, digested `ZstdDictionary`: it
  digests each shipped dictionary ONCE into a `@Volatile` holder, reuses it for
  every packet, and normalizes kzstd's exception into the SDK's public
  `ZstdException`. The codec's internal signatures are unchanged, so `TakCompressor`
  and the public API surface are untouched. All wire framing (the 4-byte magic
  strip, the `0xFF` skip-compress path, the `0x3F` flags masking, and the
  `MAX_DECOMPRESSED_SIZE = 4096` guard) stays ABOVE the codec in `TakCompressor`,
  exactly as before.
- `org.meshtastic:protobufs` digest bumped to `da60cee` (#63).
- Dependency and tooling bumps: Dokka 2.2.0 (#58), JUnit 6 (#59),
  kotlinx-atomicfu 0.33.0 (#57, subsequently removed — see below), and automated
  version bumps across all five release sources (`VERSION`, Kotlin, C#, Python,
  TypeScript) (#60).

### Removed

- **The vendored `internal.zstd` package** — `BitReader`, `BitWriter`, `Fse`,
  `FseEncoder`, `Huffman`, `MatchIndex`, `OutputBuffer`, `SequenceTables`,
  `ZstdDecoder`, `ZstdEncoder`, `ZstdDictionary`, and `ZstdFormatException`
  (~3,200 LOC) — extracted wholesale into the standalone kzstd library, along with
  the SDK-side encoder/decoder unit suites that now live and run in kzstd.
- **`kotlinx-atomicfu`** as a dependency. kzstd's `ZstdDictionary` is immutable, so
  the old `SynchronizedObject`-guarded reference-keyed caches are replaced by two
  `@Volatile` digest holders — safe lock-free publication; a benign double-build
  race only wastes work.

### Fixed

- The decoder accepts 0-bit Huffman weight FSE transitions (#64). This fix landed
  in the vendored decoder first and is now carried by kzstd.

## [0.6.0] — Kotlin Multiplatform migration + pure-Kotlin zstd codec unification

This release does two large things in lockstep, both shipped under the same version
bump: it migrates the module to **full Kotlin Multiplatform** (13 targets), then
**unifies every target on a single pure-Kotlin zstd codec** with no per-platform
compression backend. The codec it introduces here is the one later extracted to
`org.meshtastic:kzstd` in 0.7.0.

### Kotlin Multiplatform migration

Migrated the module from a `jvm()`-only Kotlin Multiplatform project to **full KMP
across 13 declared targets** — 13 of the 14 targets the published
`org.meshtastic:protobufs` artifact supports (the 14th, `android`, is intentionally
not declared; Android is served via the `-jvm` artifact, R8). The eight core classes
now live in `commonMain` behind two narrow internal SPIs (`ZstdCodec`,
`DictionaryLoader`). The same parser, serializer, compressor, and pure-Kotlin zstd
decoder run on the JVM, every Apple/Linux/Windows native target, and the web
(js / wasmJs / wasmWasi) targets.

**The wire format is UNCHANGED** — this migration is a pure port. The frozen
`testdata` goldens (`.pb` and `.bin`) are byte-for-byte unchanged, and
`CompatibilityTest` passes with `.pb` byte-identical / `.bin` within the existing
cross-binding size tolerance. The byte-level guarantees are preserved verbatim: the
4-byte zstd magic (`28 B5 2F FD`) strip-on-encode / re-prepend-on-decode, the
`0xFF` skip-compress path, the dictionary-ID flags-byte masking (`and 0x3F`),
`dictID` / `contentSize` / `checksum` all OFF on compress, and the
`MAX_DECOMPRESSED_SIZE = 4096` decompression-bomb guard.

The 13 declared targets:

- **`jvm`** — Android consumers keep consuming the `-jvm` artifact (no
  `androidTarget()` / AGP is added).
- **9 native targets** — `iosArm64`, `iosSimulatorArm64`, `iosX64`, `macosArm64`,
  `tvosArm64`, `tvosSimulatorArm64`, `linuxX64`, `linuxArm64`, `mingwX64`.
- **`js` + `wasmJs`** and **`wasmWasi`** — full pure-Kotlin code (parser, builder,
  serializer, type/palette mappers, sanitizer) plus the pure-Kotlin decoder.

#### Changed

- `CotXmlParser` re-ported from xpp3 (`org.xmlpull`) + `java.io` + `java.time` to
  `io.github.pdvrieze.xmlutil:core` + `kotlinx-datetime`, moved to `commonMain`
  (proven `.pb` byte-identical across all 47 fixtures by the Stage-0 parser-parity
  spike before any KMP scaffolding).
- `CotXmlBuilder` time handling re-ported from `java.time` to `kotlinx-datetime`,
  moved to `commonMain`.
- `TakCompressor` routes zstd through the `ZstdCodec` SPI while keeping ALL wire
  framing (magic strip, `0xFF` path, flags masking, size guard) above the SPI in one
  place; moved to `commonMain`.
- `DictionaryProvider` loads dictionaries through the `DictionaryLoader` SPI
  (JVM = classpath resources; native/js/wasm = embedded bytes), moved to
  `commonMain`.
- `TakPacketV2Data`, `TakPacketV2Serializer`, `AtakPalette`, `CotTypeMapper` moved
  to `commonMain` unchanged. `CotMeshSanitizer` already lived there.
- `org.meshtastic:protobufs` is now a `commonMain implementation` dependency (was a
  jvm-only `compileOnly`) — `compileOnly` can't link on Native/JS/Wasm.

#### Added

- **Internal SPIs** `ZstdCodec` and `DictionaryLoader`, plus the typed
  `ZstdException` so callers catch one platform-independent exception.
- **Pure-Kotlin, dictionary-aware zstd DECODER** in `commonMain` (`internal.zstd`:
  BitReader, FSE, Huffman, sequence tables, decoder). It decodes the SDK's golden
  frames byte-for-byte against the trained dictionaries.
- A zero-cost `Logger` diagnostics facility (`Logger` `fun interface`, `NoOpLogger`
  sentinel, `TakPacketSdk.logger`, inline `trace { }` that never builds the message
  string when no logger is installed).
- **Cross-platform test suites** (`kotlin.test`, run on every target):
  `RoundTripCommonTest`, `ResilienceCommonTest`, `LoggerCommonTest`,
  `PureZstdDecoderCommonTest`, and a wasmWasi-specific `WasmWasiCodecTest`, over
  codegen-inlined fixtures. The JVM file-based suites remain the comprehensive
  golden oracle; these are additive cross-platform coverage.
- **Codegen tasks**: `generateInlinedFixtures` (emits an `InlinedFixtures` object so
  common tests run without filesystem access on Native/JS/Wasm) and
  `generateEmbeddedDictionaries` (embeds the canonical dictionaries for targets with
  no classpath resources).
- **Tooling**: Gradle version catalog (`gradle/libs.versions.toml`), `explicitApi()`,
  `-Xexpect-actual-classes`, binary-compatibility-validator (ignoring
  `org.meshtastic.proto`), Dokka, reproducible archives, `.editorconfig`, and this
  changelog.

#### Removed

- The xpp3 (`org.ogce:xpp3`) dependency.

### Pure-Kotlin zstd codec unification

With the KMP scaffolding in place, the pure-Kotlin zstd codec became **THE** codec
on every target. The per-platform compression backends are gone: no `zstd-jni`
(JVM), no cinterop `libzstd` (the 9 native targets), no `@bokuweb/zstd-wasm`
(js / wasmJs). Every binding compresses AND decompresses through the same
`commonMain` `PureZstdEncoder` / `PureZstdDecoder`. (In 0.7.0 this codec is
extracted to the published `org.meshtastic:kzstd` library.)

This is a **compression-engine swap, not a breaking wire change.** Old and new nodes
stay fully interoperable in BOTH directions: our decoder reads real libzstd frames
(proven by decoding every golden), and our encoder's frames are read by real libzstd
(proven by the zstd-jni cross-decode oracle and by the Python/Swift/C#/TypeScript
bindings, which all use libzstd). The flags byte, the 4-byte magic strip, the `0xFF`
skip-compress path, the `0x3F` dict-ID masking, and the `MAX_DECOMPRESSED_SIZE = 4096`
guard are all unchanged.

#### Changed

- **One codec on all targets.** `ZstdCodec` and `DictionaryLoader` collapsed from
  `expect`/`actual` (one per target) to a single plain `commonMain object` each,
  delegating to `PureZstdEncoder.encode` / `PureZstdDecoder.decode` and the generated
  `EmbeddedDictionaries`. The internal codec signatures are unchanged, so
  `TakCompressor` and the public API surface are untouched.
- **`.bin` goldens regenerated** with the pure-Kotlin encoder. The intermediate `.pb`
  protobuf goldens are byte-for-byte unchanged (only the compression engine changed,
  not the proto). All 47 frames stay under the 237 B LoRa MTU; the pure encoder's
  slightly simpler strategy nudges the median ~87→89 B and the worst case 184→220 B
  (still 92% of MTU), well within the cross-binding size tolerance.
- The codec's parsed-dictionary and match-index caches (shared by the now-common
  `ZstdCodec` singleton) are guarded by a kotlinx-atomicfu `SynchronizedObject` lock.
  (Removed in 0.7.0 once the codec moved to kzstd's immutable dictionary.)

#### Removed

- **`zstd-jni`** as a runtime dependency (retained only as a jvmTest oracle).
- **The entire native cinterop**: `zstd.def`, the vendored `zstd.h` /
  `zstd_errors.h`, the per-konanTarget `lib/<target>/libzstd.a` vendoring, the
  `fetchZstdStatic` task, and `kotlin.mpp.enableCInteropCommonization`. The 9 native
  targets remain declared — they now compile pure Kotlin. This also eliminates the 8
  CI-pending `libzstd.a` archives entirely.
- **`@bokuweb/zstd-wasm`** from js / wasmJs (and its npm lockfile entries). js,
  wasmJs, and wasmWasi are now fully dependency-free. The public
  `ensureZstdWasmInitialized()` web entry point is gone (no async wasm init to
  await), and the per-leaf web compress bridge / `jsCommonMain` split is removed.
- The `zstdCanCompress` test capability flag and its per-leaf actuals — compress now
  runs on every target, so the common round-trip / resilience suites are ungated.

#### Fixed

- BCV `klibApiCheck` is **attached** to `apiCheck`. (During the interim cinterop
  stage it was detached because the non-host native `libzstd.a` archives were
  missing; once the codec became pure Kotlin those archives no longer exist, so the
  klib API surface is gated on every build.)
