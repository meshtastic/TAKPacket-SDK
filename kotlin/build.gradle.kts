import org.gradle.api.tasks.bundling.AbstractArchiveTask

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.binary.compat)
}

group = "org.meshtastic"
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.2.3")

repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        mavenContent { snapshotsOnly() }
    }
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    compilerOptions {
        // The 8 core classes live in commonMain behind expect/actual SPIs with
        // per-target actual objects (ZstdCodec, DictionaryLoader). Kotlin still
        // treats expect/actual *classes* as a Beta feature and warns unless this
        // flag opts in. (allWarningsAsErrors / progressiveMode are intentionally
        // deferred to a later stage so the port isn't fighting the linter.)
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    // STAGE 1+2 scope: jvm() only. Native/JS/Wasm targets land in later stages
    // WITH their actuals so the build stays green at every stage.
    jvm()

    sourceSets {
        commonMain.dependencies {
            // Proto types (TAKPacketV2, GeoChat, …) come from the published
            // protobufs KMP SDK and carry wire-runtime + okio transitively on
            // every target. `implementation` (not the old jvm-only compileOnly)
            // is required because Native/JS/Wasm cannot link a compileOnly dep.
            implementation(libs.protobufs)
            implementation(libs.xmlutil.core)
            implementation(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            // zstd-jni backs the JVM ZstdCodec actual. xpp3 is gone — the parser
            // moved to commonMain on xmlutil.
            implementation(libs.zstd.jni)
        }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            implementation(libs.junit.jupiter.params)
            runtimeOnly(libs.junit.platform.launcher)
            // protobufs is inherited from commonMain (implementation), so it is
            // no longer declared here explicitly.
        }
    }
}

// Binary-compatibility-validator: the generated org.meshtastic.proto.* types are
// an implementation detail, not part of this SDK's public API surface.
apiValidation {
    ignoredPackages.add("org.meshtastic.proto")
}

// Reproducible archives: stable file order + zeroed timestamps so published
// artifacts are byte-deterministic across builds.
tasks.withType<AbstractArchiveTask>().configureEach {
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
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
