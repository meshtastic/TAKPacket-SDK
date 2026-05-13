plugins {
    kotlin("multiplatform") version "2.1.20"
    id("com.squareup.wire") version "6.2.0"
    // Required so JitPack (and any other Maven consumer) can resolve the
    // KMP artifacts via `publishToMavenLocal`. kotlin("multiplatform") does
    // not auto-apply this plugin — it has to be declared explicitly.
    `maven-publish`
}

group = "org.meshtastic"
version = "0.2.1"

repositories {
    mavenCentral()
}

kotlin {
    jvmToolchain(17)

    jvm()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        val commonMain by getting {
            dependencies {
                api("com.squareup.wire:wire-runtime:6.2.0")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("com.github.luben:zstd-jni:1.5.7-7")
                implementation("org.ogce:xpp3:1.1.6")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter:5.12.2")
                implementation("org.junit.jupiter:junit-jupiter-params:5.12.2")
                runtimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
            }
        }
    }
}

// Wire 6.2 KMP config — mirrors Meshtastic-Android's core:proto module so the
// SDK's generated TAKPacketV2 type matches theirs exactly. The `protobufs`
// submodule is the single source of truth; Wire reads `meshtastic/atak.proto`
// directly from it and emits Kotlin to `build/generated/source/wire/` in the
// commonMain source set.
//
// NOTE: the submodule contains ~22 other .proto files (mesh/config/admin/…)
// and a top-level `nanopb.proto` that imports `google/protobuf/descriptor.proto`
// — none of which we want Wire to try to compile. The `include(...)` filter on
// sourcePath restricts Wire to just `atak.proto`, which is self-contained and
// has no imports, so this keeps the build hermetic.
wire {
    sourcePath {
        srcDir("../protobufs")
        include("meshtastic/atak.proto")
    }
    kotlin {
        // Skip defensive copies of repeated / map fields on decode — matches
        // Meshtastic-Android's performance tuning.
        makeImmutableCopies = false
        // Sentinel value: flatten every oneof to nullable properties on the
        // parent class. With 11 cases in TAKPacketV2.payload_variant, this
        // produces `pli: Boolean?`, `chat: GeoChat?`, `casevac: CasevacReport?`,
        // etc. as top-level fields instead of an intermediate sealed class.
        boxOneOfsMinSize = 5000
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// ─────────────────────────────────────────────────────────────────────────────
// JVM JAR packaging (fix for https://github.com/meshtastic/TAKPacket-SDK/issues/5)
//
// Pre-0.2.0 the JVM JAR bundled the Wire-generated `org.meshtastic.proto.*`
// classes alongside the SDK's own `org.meshtastic.tak.*` classes. Any consumer
// that also generated those proto classes from the same submodule (e.g.
// Meshtastic-Android's `core:proto` module, which runs its own Wire codegen
// against the same `meshtastic/atak.proto` file) hit R8 "Type ... is defined
// multiple times" errors during release builds.
//
// Fix: the JVM JAR no longer ships `org/meshtastic/proto/**`. The SDK's
// compiled bytecode still REFERENCES those classes — `TAKPacketV2`, `CotType`,
// `GeoChat`, etc. — but they must come from elsewhere on the consumer's
// runtime classpath. Two valid sources:
//
//   1. The consumer has its own Wire codegen target for the same proto file
//      (the Meshtastic-Android `core:proto` module is the canonical case;
//      it's a `kotlin("multiplatform") + com.squareup.wire` module that emits
//      identical `org.meshtastic.proto.*` classes from the shared protobufs
//      submodule).
//   2. The consumer manually depends on a published artifact carrying those
//      classes, or generates them with `protoc` / Wire / Protobuf-Lite / etc.
//      against `meshtastic/atak.proto`.
//
// We don't ship a sibling "protos" JAR because every Meshtastic ecosystem
// consumer already has a proto module of its own — bundling them would just
// recreate the duplicate-class problem in a slightly different shape.
//
// iosArm64 / iosSimulatorArm64 klibs are unchanged — proto classes still ship
// inside them because iOS doesn't have R8's duplicate-class issue and there's
// no analogous proto module on the Apple side.
tasks.named<Jar>("jvmJar") {
    exclude("org/meshtastic/proto/**")
}
