# Changelog

All notable changes to the TAKPacket-SDK Kotlin module are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased] — Kotlin Multiplatform migration

Migrated the module from a `jvm()`-only Kotlin Multiplatform project to **full
KMP across 13 declared targets** — 13 of the 14 targets the published
`org.meshtastic:protobufs` artifact supports (the 14th, `android`, is
intentionally not declared; Android is served via the `-jvm` artifact, R8). The
eight core classes now live in `commonMain` behind two
narrow internal SPIs (`ZstdCodec`, `DictionaryLoader`) satisfied by per-target
`actual` objects. The same parser, serializer, compressor, and pure-Kotlin zstd
decoder now run on the JVM, every Apple/Linux/Windows native target, and the
web (js / wasmJs / wasmWasi) targets.

### The wire format is UNCHANGED

This migration is a **pure port** — the on-wire bytes are identical. Verified:
the frozen `testdata` goldens (`.pb` and `.bin`) are byte-for-byte unchanged,
and `CompatibilityTest` passes with `.pb` byte-identical / `.bin` within the
existing cross-binding size tolerance. The byte-level guarantees are preserved
verbatim:

- the 4-byte zstd magic (`28 B5 2F FD`) strip-on-encode / re-prepend-on-decode,
- the `0xFF` skip-compress path (raw protobuf when compression doesn't pay),
- the dictionary-ID flags-byte masking (`and 0x3F`),
- `dictID` / `contentSize` / `checksum` all OFF on compress,
- the `MAX_DECOMPRESSED_SIZE = 4096` decompression-bomb guard.

### Targets

The module now declares 13 targets (13 of the 14 protobufs-supported targets;
`android` is not declared — see `jvm` below):

- **`jvm`** — full compress + decompress via `zstd-jni`. Android consumers keep
  consuming the `-jvm` artifact (no `androidTarget()` / AGP is added).
- **9 native targets** — `iosArm64`, `iosSimulatorArm64`, `iosX64`,
  `macosArm64`, `tvosArm64`, `tvosSimulatorArm64`, `linuxX64`, `linuxArm64`,
  `mingwX64`. Full compress + decompress via a single shared `nativeMain`
  cinterop to a statically-linked `libzstd` (so consumer klibs are
  self-contained — no system `-lzstd` setup).
- **`js` + `wasmJs`** — decompress via the pure-Kotlin decoder (no JS
  dependency); compress via `@bokuweb/zstd-wasm` (wasm-compiled libzstd), gated
  on the R3 byte-compatibility spike. The compress library needs an async
  one-time `init()` before the first synchronous compress
  (`ensureZstdWasmInitialized`).
- **`wasmWasi`** — **decode-only**. All pure-Kotlin code (parser, builder,
  serializer, type/palette mappers, sanitizer) plus the pure-Kotlin decoder
  compile and run; `compress` throws a clear `ZstdException` (no JS host, no
  cinterop, no pure-Kotlin encoder).

### Changed

- `CotXmlParser` re-ported from xpp3 (`org.xmlpull`) + `java.io` + `java.time`
  to `io.github.pdvrieze.xmlutil:core` + `kotlinx-datetime`, moved to
  `commonMain` (proven `.pb` byte-identical across all 47 fixtures by the
  Stage-0 parser-parity spike before any KMP scaffolding).
- `CotXmlBuilder` time handling re-ported from `java.time` to `kotlinx-datetime`,
  moved to `commonMain`.
- `TakCompressor` routes zstd through the new `ZstdCodec` SPI while keeping ALL
  wire framing (magic strip, `0xFF` path, flags masking, size guard) above the
  SPI in one place; moved to `commonMain`.
- `DictionaryProvider` loads dictionaries through the new `DictionaryLoader` SPI
  (JVM = classpath resources; native/js/wasm = embedded bytes), moved to
  `commonMain`.
- `TakPacketV2Data`, `TakPacketV2Serializer`, `AtakPalette`, `CotTypeMapper`
  moved to `commonMain` unchanged. `CotMeshSanitizer` already lived there.
- `org.meshtastic:protobufs` is now a `commonMain implementation` dependency
  (was a jvm-only `compileOnly`) — `compileOnly` can't link on Native/JS/Wasm.

### Added

- **Internal SPIs** `ZstdCodec` and `DictionaryLoader`, plus the typed
  `ZstdException` so callers catch one platform-independent exception.
- **Pure-Kotlin, dictionary-aware zstd DECODER** in `commonMain`
  (`internal.zstd`: BitReader, FSE, Huffman, sequence tables, decoder). It
  decodes the SDK's golden frames byte-for-byte against the trained
  dictionaries and is the decompress backend on js / wasmJs / wasmWasi. (A
  pure-Kotlin ENCODER, R14b, remains deferred.)
- A zero-cost `Logger` diagnostics facility (`Logger` `fun interface`,
  `NoOpLogger` sentinel, `TakPacketSdk.logger`, inline `trace { }` that never
  builds the message string when no logger is installed).
- **Cross-platform test suites** (`kotlin.test`, run on every target):
  `RoundTripCommonTest`, `ResilienceCommonTest`, `LoggerCommonTest`,
  `PureZstdDecoderCommonTest`, and a wasmWasi-specific `WasmWasiCodecTest`. They
  exercise the full pipeline (parse → compress → decompress where the codec can
  compress; parse + golden-frame decode everywhere) over codegen-inlined
  fixtures. The JVM file-based suites remain the comprehensive golden oracle;
  these are additive cross-platform coverage.
- **Codegen tasks**: `generateInlinedFixtures` (emits an `InlinedFixtures`
  object — 47 fixtures' XML + golden wire frames — so common tests run without
  filesystem access on Native/JS/Wasm) and `generateEmbeddedDictionaries`
  (embeds the canonical dictionaries for targets with no classpath resources).
- **Tooling**: Gradle version catalog (`gradle/libs.versions.toml`),
  `explicitApi()`, `-Xexpect-actual-classes`, binary-compatibility-validator
  (ignoring `org.meshtastic.proto`), Dokka, reproducible archives,
  `.editorconfig`, and this changelog.

### Removed

- The xpp3 (`org.ogce:xpp3`) dependency.

### CI-pending

These are wired but require the CI cross-toolchain to complete; they do not
affect the JVM gate or the wire format:

- **8 of 9 native `libzstd.a` archives.** Only the host archive (`macos_arm64`)
  is committed in this worktree, so only `macosArm64` links + tests locally; the
  other 8 native targets cross-compile/link in CI once `fetchZstdStatic`
  provisions their archives. The shared `nativeMain` codec + the common test
  code are identical across all 9, and `macosArm64Test` runs the full common
  suite green locally with the real cinterop codec.
- **klib binary-compatibility baseline.** BCV's `klibApiCheck` is detached from
  `apiCheck` until all 9 archives are present and a klib `.api` baseline is
  dumped; the JVM `apiCheck` gate (`api/takpacket-sdk.api`) guards the public
  surface in the meantime (every native/web addition is `internal`).
