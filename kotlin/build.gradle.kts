plugins {
    kotlin("multiplatform") version "2.1.20"
    kotlin("plugin.serialization") version "2.1.20"
    id("com.squareup.wire") version "5.2.0"
    id("com.vanniktech.maven.publish") version "0.34.0"
}

group = "org.meshtastic"
version = "0.1.0"

repositories {
    mavenCentral()
}

// Copy only atak.proto from the protobufs submodule into a staging dir for Wire
val copyProto = tasks.register<Copy>("copyAtakProto") {
    from("../protobufs/meshtastic/atak.proto")
    into(layout.buildDirectory.dir("proto-staging/meshtastic"))
}

wire {
    sourcePath {
        srcDir(layout.buildDirectory.dir("proto-staging"))
    }
    kotlin {
        out = "src/commonMain/generated"
    }
}

// Ensure proto is copied before Wire reads it
tasks.matching { it.name.startsWith("generate") && it.name.contains("Proto", ignoreCase = true) }
    .configureEach { dependsOn(copyProto) }

kotlin {
    jvmToolchain(17)

    // Suppress expect/actual beta warnings
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    jvm()

    // iOS targets with zstd C interop for dictionary compression
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.compilations.getByName("main") {
            cinterops {
                val zstd by creating {
                    defFile(project.file("src/nativeInterop/cinterop/zstd.def"))
                    includeDirs(project.file("src/nativeInterop/cinterop/include"))
                }
            }
        }
    }

    // Shared iOS source set
    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain {
            // Wire-generated sources
            kotlin.srcDir("src/commonMain/generated")

            dependencies {
                api("com.squareup.wire:wire-runtime:5.2.0")
                implementation("io.github.pdvrieze.xmlutil:core:0.90.3")
                implementation("io.github.pdvrieze.xmlutil:serialization:0.90.3")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.2")
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmMain.dependencies {
            implementation("com.github.luben:zstd-jni:1.5.7-7")
        }

        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation("org.junit.jupiter:junit-jupiter:5.12.2")
            implementation("org.junit.jupiter:junit-jupiter-params:5.12.2")
            runtimeOnly("org.junit.platform:junit-platform-launcher:1.12.2")
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

mavenPublishing {
    publishToMavenCentral()

    signAllPublications()

    coordinates(group.toString(), "takpacket-sdk", version.toString())

    pom {
        name = "TAKPacket-SDK"
        description = "Cross-platform CoT XML to TAKPacketV2 conversion and zstd dictionary compression for Meshtastic."
        inceptionYear = "2025"
        url = "https://github.com/meshtastic/TAKPacket-SDK"
        licenses {
            license {
                name = "GNU General Public License v3.0"
                url = "https://www.gnu.org/licenses/gpl-3.0.txt"
                distribution = "https://www.gnu.org/licenses/gpl-3.0.txt"
            }
        }
        developers {
            developer {
                id = "meshtastic"
                name = "Meshtastic"
                url = "https://github.com/meshtastic"
            }
        }
        scm {
            url = "https://github.com/meshtastic/TAKPacket-SDK"
            connection = "scm:git:git://github.com/meshtastic/TAKPacket-SDK.git"
            developerConnection = "scm:git:ssh://git@github.com/meshtastic/TAKPacket-SDK.git"
        }
    }
}
