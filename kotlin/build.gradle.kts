plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.wire)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.binary.compat)
}

group = "org.meshtastic"
version = file("../VERSION").readText().trim()

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

    // Published library: enforce explicit visibility on all declarations
    explicitApi()

    // Suppress expect/actual beta warnings (still beta as of Kotlin 2.3.20)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
        allWarningsAsErrors.set(true)
        progressiveMode.set(true)
    }

    jvm()

    // iOS targets with zstd C interop for dictionary compression
    listOf(iosArm64(), iosSimulatorArm64(), iosX64()).forEach { target ->
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
                implementation(libs.wire.runtime)
                implementation(libs.xmlutil.core)
                implementation(libs.kotlinx.datetime)
            }
        }

        commonTest.dependencies {
            implementation(kotlin("test"))
        }

        jvmMain.dependencies {
            implementation(libs.zstd.jni)
        }

        jvmTest.dependencies {
            implementation(kotlin("test-junit5"))
            implementation(libs.junit.jupiter)
            implementation(libs.junit.jupiter.params)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.named<Test>("jvmTest") {
    useJUnitPlatform()
}

// Binary compatibility validator: dump API with `./gradlew apiDump`,
// check for breaking changes with `./gradlew apiCheck`.
apiValidation {
    // Wire-generated proto classes are not part of our public API surface
    ignoredPackages.add("org.meshtastic.proto")
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

// Reproducible builds: strip timestamps and sort entries in all archives
tasks.withType<AbstractArchiveTask>().configureEach {
    isReproducibleFileOrder = true
    isPreserveFileTimestamps = false
}
