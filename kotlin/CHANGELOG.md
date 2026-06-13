# Changelog

All notable changes to the TAKPacket-SDK Kotlin module are documented here.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/).

## [Unreleased] — Kotlin Multiplatform migration

Restructured the module from a `jvm()`-only Kotlin Multiplatform project into
the full KMP idiom, with the eight core classes living in `commonMain` behind
two narrow internal SPIs (`ZstdCodec`, `DictionaryLoader`) backed by per-target
actuals. The JVM target keeps zstd-jni; later stages add the native/JS/Wasm
targets with their own actuals.

**The on-wire format is unchanged.** The byte-identical guarantees are
preserved verbatim: the 4-byte zstd magic strip-on-encode / re-prepend-on-decode,
the `0xFF` skip-compress path, the dictionary-ID flags-byte masking (`and 0x3F`),
`dictID`/`contentSize`/`checksum` all OFF on compress, and the
`MAX_DECOMPRESSED_SIZE = 4096` guard. The frozen `testdata` goldens
(`.pb` / `.bin`) are unchanged.

### Changed

- `CotXmlParser` re-ported from xpp3 (`org.xmlpull`) + `java.io` + `java.time`
  to `io.github.pdvrieze.xmlutil:core` + `kotlinx-datetime`, moved to
  `commonMain` (proven `.pb` byte-identical across all 47 fixtures).
- `CotXmlBuilder` time handling re-ported from `java.time` to `kotlinx-datetime`,
  moved to `commonMain`.
- `TakCompressor` routes zstd through the new `ZstdCodec` SPI while keeping all
  wire framing above the SPI, moved to `commonMain`.
- `DictionaryProvider` loads dictionaries through the new `DictionaryLoader` SPI,
  moved to `commonMain`.
- `TakPacketV2Data`, `TakPacketV2Serializer`, `AtakPalette`, `CotTypeMapper`
  moved to `commonMain` unchanged.
- `org.meshtastic:protobufs` is now a `commonMain implementation` dependency.

### Added

- Internal SPIs `ZstdCodec`, `DictionaryLoader` and the typed `ZstdException`.
- A zero-cost `Logger` diagnostics facility (`Logger`, `NoOpLogger`,
  `TakPacketSdk.logger`, inline `trace { }`).
- Tooling: Gradle version catalog (`gradle/libs.versions.toml`),
  `explicitApi()`, `-Xexpect-actual-classes`, binary-compatibility-validator
  (ignoring `org.meshtastic.proto`), Dokka, reproducible archives,
  `.editorconfig`, and this changelog.

### Removed

- The xpp3 (`org.ogce:xpp3`) dependency.
