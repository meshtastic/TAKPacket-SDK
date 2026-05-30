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
        val commonMain by getting {
            dependencies {
                // Proto types (TAKPacketV2, GeoChat, etc.) come from the published
                // protobufs SDK. Using `implementation` ensures we do NOT re-export
                // them to consumers — they bring their own protobufs SDK dependency
                // and there is exactly one source of truth on the classpath.
                implementation("org.meshtastic:protobufs:2.7.25-SNAPSHOT")
            }
        }
        val jvmMain by getting {
            dependencies {
                implementation("com.github.luben:zstd-jni:1.5.7-10")
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
