plugins {
    kotlin("multiplatform") version "2.1.20"
    id("com.squareup.wire") version "6.2.0"
    // Required so JitPack (and any other Maven consumer) can resolve the
    // KMP artifacts via `publishToMavenLocal`. kotlin("multiplatform") does
    // not auto-apply this plugin — it has to be declared explicitly.
    `maven-publish`
}

group = "org.meshtastic"
version = "0.2.0"

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
// Split-JAR packaging (fix for https://github.com/meshtastic/TAKPacket-SDK/issues/5)
//
// Pre-0.2.0 the JVM publication bundled the Wire-generated `org.meshtastic.proto.*`
// classes alongside the SDK's own `org.meshtastic.tak.*` classes. Any consumer
// that also generated those proto classes from the same submodule (e.g. the
// Meshtastic-Android `core:proto` module) hit R8 "Type ... is defined multiple
// times" errors during release builds.
//
// The fix: the main `takpacket-sdk-jvm` JAR now excludes `org/meshtastic/proto/**`,
// and a sibling `takpacket-sdk-protos-jvm` JAR contains only those classes,
// published as a separate Maven publication. The main JAR declares the protos
// JAR as a `runtime` dependency in its POM, so:
//
//   * Standalone consumers pick up both jars transitively — no behavior change.
//   * Consumers that already have the proto classes on their classpath exclude
//     the transitive `takpacket-sdk-protos-jvm`:
//
//       implementation("com.github.meshtastic.TAKPacket-SDK:takpacket-sdk-jvm:vX.Y.Z") {
//           exclude(group = "com.github.meshtastic.TAKPacket-SDK", module = "takpacket-sdk-protos-jvm")
//       }
//
// This approach avoids restructuring into Gradle multi-project (which fights
// the KMP plugin's strong assumptions about being a single project) — it ships
// two artifacts from one project.

// Mutate the JVM jar task in place to drop the proto/ classes. The Wire-
// generated classes are part of `commonMain`, so iosArm64 / iosSimulatorArm64
// klibs (which package their own representation of commonMain) are unaffected
// — this exclusion applies only to the JVM target's `.jar`.
tasks.named<Jar>("jvmJar") {
    exclude("org/meshtastic/proto/**")
}

// Sibling JAR carrying only the proto classes, built from the SAME compiled
// classes the JVM target produces. Done via `tasks.named("compileKotlinJvm")`
// so the classes are guaranteed to be on disk by the time this jar runs.
val jvmProtosJar = tasks.register<Jar>("jvmProtosJar") {
    description = "JVM JAR containing only the Wire-generated org.meshtastic.proto.* classes (issue #5)"
    group = "build"
    archiveBaseName.set("takpacket-sdk-protos")
    archiveClassifier.set("")
    dependsOn(tasks.named("compileKotlinJvm"))
    from(layout.buildDirectory.dir("classes/kotlin/jvm/main")) {
        include("org/meshtastic/proto/**")
    }
}

afterEvaluate {
    publishing {
        publications {
            // Add the protos JAR as a separate publication. Standalone
            // consumers pull it transitively via the dependency we add to
            // the JVM publication's POM below; consumers with their own
            // proto module can `exclude` it.
            create<MavenPublication>("jvmProtos") {
                artifactId = "takpacket-sdk-protos-jvm"
                artifact(jvmProtosJar.get())
                pom.withXml {
                    val deps = asNode().appendNode("dependencies")
                    val dep = deps.appendNode("dependency")
                    dep.appendNode("groupId", "com.squareup.wire")
                    dep.appendNode("artifactId", "wire-runtime-jvm")
                    dep.appendNode("version", "6.2.0")
                    dep.appendNode("scope", "compile")
                }
            }

            // Declare the protos JAR as a transitive runtime dep in the
            // JVM publication's POM so standalone consumers pick it up
            // automatically when they depend on `takpacket-sdk-jvm`.
            named<MavenPublication>("jvm") {
                pom.withXml {
                    val deps = (asNode().children().find {
                        (it as groovy.util.Node).name().toString().endsWith("dependencies")
                    } as? groovy.util.Node) ?: asNode().appendNode("dependencies")
                    val dep = deps.appendNode("dependency")
                    dep.appendNode("groupId", project.group)
                    dep.appendNode("artifactId", "takpacket-sdk-protos-jvm")
                    dep.appendNode("version", project.version)
                    dep.appendNode("scope", "runtime")
                }
            }
        }
    }
}
