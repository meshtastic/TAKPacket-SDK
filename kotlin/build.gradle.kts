plugins {
    kotlin("multiplatform") version "2.3.21"
    id("com.squareup.wire") version "6.4.0"
    id("com.vanniktech.maven.publish") version "0.36.0"
}

group = "org.meshtastic"
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.2.3")

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
                api("com.squareup.wire:wire-runtime:6.4.0")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("com.github.luben:zstd-jni:1.5.7-9")
                implementation("org.ogce:xpp3:1.1.6")
            }
        }
        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter:6.1.0")
                implementation("org.junit.jupiter:junit-jupiter-params:6.1.0")
                runtimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
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
// JVM JAR packaging — proto classes are SHIPPED with two surgical exceptions.
//
// Background: issue #5 was the inverse of issue #6.
//
//   issue #5 (0.2.0–0.2.1): the JVM JAR bundled `org.meshtastic.proto.*`
//   alongside `org.meshtastic.tak.*`. Consumers that also ran Wire codegen
//   against the shared `meshtastic/atak.proto` (notably Meshtastic-Android's
//   `core:proto` module) hit R8 "Type is defined multiple times" errors
//   during release builds. The fix at the time was to strip ALL proto
//   classes from the JAR so the consumer's codegen became the single source.
//
//   issue #6 (this change): that strategy is ABI-fragile. The SDK's
//   bytecode REFERENCES the proto classes — `SensorFov.getRange_m()`,
//   `TAKPacketV2.Builder.payload(...)`, etc. — and those signatures shift
//   whenever the proto file changes (e.g. when `range_m` flipped from
//   `uint32` to `optional uint32`, Wire's generated accessor went from
//   `int getRange_m()` to `Integer getRange_m()`). If the SDK was compiled
//   against the new proto but the consumer's `core:proto` regenerated
//   against an older submodule pointer (or vice versa), the runtime
//   classpath had method signatures the SDK's call sites didn't match,
//   producing NoSuchMethodError at first invocation.
//
// New strategy (#6): the SDK is the single owner of `atak.proto` codegen
// for ALMOST every type. The JAR ships `org.meshtastic.proto.**` (115
// classes) alongside `org.meshtastic.tak.**`. Consumers prune the same
// types from their own Wire codegen and pull them transitively from this
// JAR. No second codegen, no ABI drift.
//
// The two exceptions are `meshtastic.Team` and `meshtastic.MemberRole`.
// Both enums are referenced from `meshtastic/module_config.proto`'s
// `ModuleConfig.TAKConfig` (`Team team = 1`, `MemberRole role = 2`), so
// consumer Wire codegen REFUSES to prune them — pruning would propagate
// to those fields too, leaving consumer code with no way to read
// `takConfig.team`. We strip them from this JAR so the consumer's own
// codegen remains the single source; the SDK's bytecode references to
// `Team` / `MemberRole` resolve transitively at the consumer's classpath.
// These two enums are tiny and stable (no field shape to ABI-drift), so
// the issue #6 ABI argument doesn't apply meaningfully to them.
//
// iOS klibs ship all proto classes unconditionally — no R8 step on Apple.
//
// See https://github.com/meshtastic/TAKPacket-SDK/issues/6 for the full
// rationale and the Meshtastic-Android-side prune list.
tasks.named<Jar>("jvmJar") {
    // Strip ONLY Team and MemberRole (and their nested ProtoAdapter inner
    // classes). Everything else in org.meshtastic.proto.** stays in the JAR.
    exclude("org/meshtastic/proto/Team.class")
    exclude("org/meshtastic/proto/Team\$*.class")
    exclude("org/meshtastic/proto/MemberRole.class")
    exclude("org/meshtastic/proto/MemberRole\$*.class")
}

// ─────────────────────────────────────────────────────────────────────────────
// Maven Central publishing via Vanniktech maven-publish plugin.
// Coordinates are read from gradle.properties: GROUP, POM_ARTIFACT_ID, VERSION_NAME.
// Signing is conditional — only applied when CI provides the key via Gradle properties.
mavenPublishing {
    // automaticRelease = true makes the Sonatype Central Portal publish the
    // upload directly to Maven Central instead of leaving it in a pending
    // "validated, awaiting approval" state. Without this, each release
    // requires a manual click in the Sonatype Central UI after the workflow
    // finishes — bit us once on v0.3.0 where the workflow reported success
    // but the artifact never synced to repo1.maven.org.
    publishToMavenCentral(automaticRelease = true)
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    pom {
        name.set("TAKPacket-SDK")
        description.set("Cross-platform CoT XML to TAKPacketV2 protobuf conversion with zstd dictionary compression for Meshtastic LoRa mesh transport.")
        inceptionYear.set("2025")
        url.set("https://github.com/meshtastic/TAKPacket-SDK")
        licenses {
            license {
                name.set("GNU General Public License, Version 3.0")
                url.set("https://www.gnu.org/licenses/gpl-3.0.html")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("meshtastic")
                name.set("Meshtastic")
                url.set("https://meshtastic.org")
            }
        }
        scm {
            url.set("https://github.com/meshtastic/TAKPacket-SDK")
            connection.set("scm:git:git://github.com/meshtastic/TAKPacket-SDK.git")
            developerConnection.set("scm:git:ssh://git@github.com/meshtastic/TAKPacket-SDK.git")
        }
    }
}
