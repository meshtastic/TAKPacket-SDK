# TAKPacket-SDK — Kotlin / Kotlin Multiplatform

The **canonical** binding of [TAKPacket-SDK](https://github.com/meshtastic/TAKPacket-SDK):
convert ATAK Cursor-on-Target (CoT) XML to Meshtastic's `TAKPacketV2` protobuf and compress it
with zstd dictionary compression for LoRa mesh transport (237-byte MTU, port 78). Wire payloads
are byte-interoperable with the Swift, Python, TypeScript, and C# bindings.

This module is full Kotlin Multiplatform: **JVM** (Android), **9 native** targets (iOS, macOS,
tvOS, Linux, Windows), **JS**, **wasmJs**, and **wasmWasi** — one pure-Kotlin codebase, no
native `libzstd`.

[![License: GPL-3.0-or-later](https://img.shields.io/badge/License-GPL--3.0--or--later-blue.svg)](../LICENSE)
[![Maven Central](https://img.shields.io/maven-central/v/org.meshtastic/takpacket-sdk-jvm)](https://central.sonatype.com/artifact/org.meshtastic/takpacket-sdk-jvm)
[![CI](https://github.com/meshtastic/TAKPacket-SDK/actions/workflows/ci.yml/badge.svg)](https://github.com/meshtastic/TAKPacket-SDK/actions/workflows/ci.yml)
[![API Docs](https://img.shields.io/badge/docs-Dokka-blue)](https://meshtastic.github.io/TAKPacket-SDK/)

📚 **[API reference (Dokka)](https://meshtastic.github.io/TAKPacket-SDK/kotlin/)**

## Install

Published to **Maven Central**. Android / JVM consumers depend on the **`-jvm`** artifact:

```kotlin
// build.gradle.kts
dependencies {
    implementation("org.meshtastic:takpacket-sdk-jvm:0.8.0")
}
```

> **Depend on `takpacket-sdk-jvm`, not the parent `takpacket-sdk` coordinate.** The parent is the
> KMP metadata module. The `org.meshtastic:protobufs` artifact ships transitively; align its
> version with your app if you already use it.

**JitPack** is a fallback channel:
`com.github.meshtastic.TAKPacket-SDK:takpacket-sdk-jvm:<tag>`.

## Quick start

```kotlin
import org.meshtastic.tak.*

val parser = CotXmlParser()
val compressor = TakCompressor()

// Sanitize raw ATAK CoT XML before parsing.
var clean = CotMeshSanitizer.normalizeCotXml(cotXmlString)
clean = CotMeshSanitizer.stripNonEssentialForMesh(clean)

val packet = parser.parse(clean)
val wirePayload: ByteArray = compressor.compress(packet)   // [flags][zstd body], ≤ 237 B

// Receive side
val received = compressor.decompress(wirePayload)
val cotXml = CotXmlBuilder().build(received)
```

For payloads that may exceed the MTU, `compressor.compressWithRemarksFallback(packet)` retries
without remarks and returns `null` if it still won't fit; `compressWithStats(packet)` returns the
payload plus size/ratio diagnostics.

## Core classes

| Class | Responsibility |
|-------|----------------|
| `CotMeshSanitizer` | Mesh hygiene on raw CoT XML *before* parsing (drop display-only detail, preserve voice/marti) |
| `CotXmlParser` | CoT XML → `TakPacketV2Data` |
| `CotXmlBuilder` | `TakPacketV2Data` → CoT XML |
| `TakCompressor` | `TakPacketV2Data` ↔ compressed wire payload |
| `CotTypeMapper` | CoT type string ↔ enum, aircraft classification |
| `AtakPalette` | ATAK 14-color palette ↔ `Team` enum |
| `DictionaryProvider` | Selects and loads the embedded zstd dictionaries |

## Errors

`TakCompressor.decompress` throws `ZstdException` on a malformed/oversized frame (the decoder
rejects anything that would expand past 4096 bytes — a decompression-bomb guard). The parser is
hardened against XXE / entity-expansion attacks.

## Build & test (contributors)

Requires **JDK 21**:

```sh
export JAVA_HOME=/path/to/jdk21
./gradlew jvmTest                         # JVM unit tests
./gradlew dokkaGeneratePublicationHtml    # API docs → build/dokka/html
./gradlew publishToMavenLocal             # local publish (resolves as org.meshtastic:takpacket-sdk[-jvm]:0.8.0)
```

See the repository [CONTRIBUTING guide](https://github.com/meshtastic/TAKPacket-SDK/blob/main/CONTRIBUTING.md)
for the full workflow, and [WIRE_FORMAT.md](https://github.com/meshtastic/TAKPacket-SDK/blob/main/WIRE_FORMAT.md)
for the wire specification.
