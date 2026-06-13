plugins {
    kotlin("multiplatform") version "2.4.0"
    id("com.vanniktech.maven.publish") version "0.36.0"
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

    jvm()

    sourceSets {
        val jvmMain by getting {
            dependencies {
                // Proto types (TAKPacketV2, GeoChat, etc.) come from the published
                // protobufs KMP SDK. They are an internal implementation detail —
                // no public SDK signature exposes an org.meshtastic.proto.* type
                // (TakCompressor/TakPacketV2Serializer traffic only in
                // TakPacketV2Data + ByteArray). `compileOnly` therefore keeps the
                // protobufs SDK OFF consumers' classpaths entirely: it is omitted
                // from the published POM (neither compile nor runtime scope), so we
                // never re-export it and never dictate its version. Every consumer
                // (e.g. Meshtastic-Android) already depends on the same
                // org.meshtastic:protobufs KMP artifact directly and owns its
                // version — that dependency satisfies our runtime requirement, and
                // there is exactly one source of truth on the classpath.
                compileOnly("org.meshtastic:protobufs:2.7.25")
                implementation("com.github.luben:zstd-jni:1.5.7-11")
                implementation("org.ogce:xpp3:1.1.6")
                // STAGE 0 SPIKE (temporary, JVM-only): xmlutil + kotlinx-datetime
                // back the side-by-side CotXmlParserXmlUtil re-port that proves the
                // xpp3 -> multiplatform parser swap is .pb byte-identical before any
                // KMP scaffolding. Plain implementation lines; no version catalog yet.
                implementation("io.github.pdvrieze.xmlutil:core:0.91.3")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
            }
        }
        val jvmTest by getting {
            dependencies {
                // jvmTest no longer inherits the proto SDK transitively (jvmMain
                // declares it compileOnly), so the test classpath needs it directly
                // to run the serializer.
                implementation("org.meshtastic:protobufs:2.7.25")
                implementation(kotlin("test"))
                implementation("org.junit.jupiter:junit-jupiter:6.1.0")
                implementation("org.junit.jupiter:junit-jupiter-params:6.1.0")
                runtimeOnly("org.junit.platform:junit-platform-launcher:6.1.0")
            }
        }
    }
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
