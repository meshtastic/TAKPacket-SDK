# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/),
and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Kotlin Multiplatform support: JVM, iOS (arm64, simulatorArm64, x64)
- Maven Central publishing via vanniktech plugin (manual workflow dispatch)
- Wire KMP protobuf generation (replaces protobuf-javalite)
- xmlutil streaming XmlReader parser (replaces XmlPullParser)
- iOS zstd compression via C interop with embedded Base64 dictionaries
- Full payload type coverage: PLI, GeoChat, Aircraft, DrawnShape, Marker,
  RangeAndBearing, Route, CasevacReport, EmergencyAlert, TaskRequest, RawDetail
- `CotXmlBuilder` for reconstructing CoT XML from parsed data
- `TakCompressor.compressBestOf()` for optimal compression strategy selection
- `TakCompressor.compressWithRemarksFallback()` for MTU-aware compression
- `explicitApi()` mode with full KDoc documentation on all public API surfaces
- Binary compatibility validator (BCV) with API dump
- Dokka 2.2 for javadoc generation
- 308 JVM tests (0 failures) including cross-platform commonTest suite

### Changed
- Source sets restructured: `src/main/` to `commonMain/` + `jvmMain/` + `iosMain/`
- `wire-runtime` scoped as `implementation()` (Wire types not exposed in public API)
- `DictionaryProvider`, `ZstdCodec`, `DictionaryLoader` made `internal`
- `Payload` changed from `sealed class` to `sealed interface`; `None` is `data object`
- All `when` blocks over `Payload` made exhaustive (no `else` catch-all)
- Upgraded to Gradle 9.4.1, Kotlin 2.3.20, Wire 6.2.0, kotlinx-datetime 0.7.1

[Unreleased]: https://github.com/meshtastic/TAKPacket-SDK/compare/main...HEAD
