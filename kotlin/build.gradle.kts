import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.KotlinMultiplatform
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.jetbrains.dokka.gradle.engine.parameters.VisibilityModifier
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import java.util.Base64

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.vanniktech.publish)
    alias(libs.plugins.dokka)
    alias(libs.plugins.binary.compat)
    alias(libs.plugins.kover)
    alias(libs.plugins.spotless)
    alias(libs.plugins.detekt)
}

group = "org.meshtastic"
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.2.3")

repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/") {
        mavenContent { snapshotsOnly() }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Static analysis + formatting (§7.1 / §7.2). Spotless (ktlint) enforces the
// .editorconfig ktlint ruleset over all Kotlin sources + this build script;
// detekt runs on top of its default ruleset with a small project override file.
spotless {
    kotlin {
        target("src/**/*.kt")
        // Pin ktlint so local + CI format identically; .editorconfig drives the rules.
        ktlint(libs.versions.ktlint.get())
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint(libs.versions.ktlint.get())
    }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(files("config/detekt/detekt.yml"))
    // Grandfather the pre-existing findings in the hand-written codec so the full
    // default ruleset is enforced on NEW code without a legacy-refactor blocking
    // this CI change. Regenerate with `./gradlew detektBaseline`.
    baseline = file("config/detekt/baseline.xml")
    // KMP has no `src/main`/`src/test` layout, so the default `detekt` task finds
    // no sources. Point it at the whole hand-written tree (every source set);
    // detekt scans recursively for *.kt. Generated code lives under build/ and is
    // excluded automatically.
    source.setFrom(files("src"))
}

kotlin {
    jvmToolchain(21)
    explicitApi()

    compilerOptions {
        // Treat compiler warnings as errors across all targets (main + test) and
        // opt into progressive mode so deprecations/ambiguities surface early.
        // The whole tree (commonMain + every actual + commonTest + every test
        // leaf) compiles warning-clean under this.
        allWarningsAsErrors.set(true)
        progressiveMode.set(true)
    }

    jvm()

    // The codec is the pure-Kotlin `org.meshtastic:kzstd` library on EVERY
    // target, so the js / wasmJs / wasmWasi targets need NO JS dependency:
    // compress AND decompress both run through kzstd. There is no jsCommonMain
    // compress bridge, no @bokuweb, and no wasmWasi throwing path.
    // IR is the only Kotlin/JS compiler in 2.4+, so plain `js {}` selects it.
    js {
        browser()
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmJs {
        browser()
        nodejs()
    }

    @OptIn(ExperimentalWasmDsl::class)
    wasmWasi {
        nodejs()
    }

    // The nine native targets (R1/R2). They compile the SAME pure-Kotlin codec
    // as every other target — no cinterop, no vendored libzstd. applyDefault-
    // HierarchyTemplate() still gives a shared `nativeMain`/`nativeTest`, but the
    // codec/dictionary loader now live entirely in commonMain.
    iosArm64()
    iosSimulatorArm64()
    iosX64()
    macosArm64()
    tvosArm64()
    tvosSimulatorArm64()
    linuxX64()
    linuxArm64()
    mingwX64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        commonMain.dependencies {
            // Proto types (TAKPacketV2, GeoChat, …) come from the published
            // protobufs KMP SDK and carry wire-runtime + okio transitively on
            // every target. `implementation` (not the old jvm-only compileOnly)
            // is required because Native/JS/Wasm cannot link a compileOnly dep.
            implementation(libs.protobufs)
            implementation(libs.xmlutil.core)
            // The zstd codec is the standalone pure-Kotlin `org.meshtastic:kzstd`
            // library (extracted from this SDK's former internal.zstd package).
            // ZstdCodec wraps kzstd's one-shot `Zstd` + digested `ZstdDictionary`
            // API. kzstd's dictionary is immutable, so the SDK no longer needs an
            // atomicfu lock to guard a shared mutable cache — two @Volatile digest
            // holders give safe publication lock-free. Date/time uses the
            // kotlin.time stdlib (no kotlinx-datetime dependency).
            implementation(libs.kzstd)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmTest.dependencies {
            implementation(libs.junit.jupiter)
            implementation(libs.junit.jupiter.params)
            runtimeOnly(libs.junit.platform.launcher)
            // No zstd-jni oracle here anymore: the both-directions libzstd interop
            // gate (our frames decode in real libzstd and vice versa) now lives in
            // kzstd's own test suite, so the SDK does not re-run it. The retained
            // golden-decode oracle exercises kzstd's public API over TAK's wire
            // frames. protobufs is inherited from commonMain (implementation).
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Dokka API documentation (HTML). `./gradlew dokkaGeneratePublicationHtml` writes
// browsable docs to build/dokka/html; the same task feeds the Maven Central
// javadoc jar (see mavenPublishing below) and the unified docs site built in CI.
dokka {
    moduleName.set("TAKPacket-SDK")
    dokkaSourceSets.configureEach {
        // Public API only — the codec internals and the proto-bridge serializer
        // are `internal`/implementation detail and stay out of the published docs.
        documentedVisibilities.set(setOf(VisibilityModifier.Public))
        // Module + package overview prose (the `# Module` / `# Package` sections).
        includes.from("module.md")
        // Deep-link every documented symbol to its source on GitHub.
        sourceLink {
            localDirectory.set(file("src"))
            remoteUrl("https://github.com/meshtastic/TAKPacket-SDK/blob/main/kotlin/src")
            remoteLineSuffix.set("#L")
        }
        // The Wire-generated proto types are an implementation detail (already
        // excluded from binary-compatibility validation) — hide them in docs too.
        perPackageOption {
            matchingRegex.set("org\\.meshtastic\\.proto.*")
            suppress.set(true)
        }
    }
}

// Binary-compatibility-validator: the generated org.meshtastic.proto.* types are
// an implementation detail, not part of this SDK's public API surface.
//
// klib (native/common) ABI validation is enabled alongside the JVM dump so the
// check covers the full cross-platform public surface, not JVM only. `apiDump`
// writes TWO baselines into api/ — the JVM `takpacket-sdk.api` and the merged
// `takpacket-sdk.klib.api` (all 12 non-JVM targets) — and `apiCheck` validates both.
// With the cinterop gone the nine native klibs build from pure Kotlin, so klib
// validation runs cleanly. Targets a CI host can't build (the Apple targets on
// the Linux runner) are skipped by BCV and trusted from the committed dump, so
// regenerate the full 12-target klib dump on a macOS host via `./gradlew apiDump`.
apiValidation {
    ignoredPackages.add("org.meshtastic.proto")

    @OptIn(kotlinx.validation.ExperimentalBCVApi::class)
    klib {
        enabled = true
    }
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
// Codegen: embed the canonical zstd dictionaries into commonMain (R11).
//
// As of v0.6.0 the single commonMain DictionaryLoader reads the dict bytes from
// a generated `EmbeddedDictionaries` object on EVERY target (no classpath
// resources anywhere). This task reads the two canonical dicts and emits
// `EmbeddedDictionaries.kt` into a generated source dir wired onto commonMain.
// The non-aircraft dict is 512 KB, so the bytes are emitted as CHUNKED Base64
// string constants (each well under any platform constant-size limit) joined +
// Base64-decoded once at first use.
abstract class GenerateEmbeddedDictionaries : DefaultTask() {
    @get:InputFiles
    abstract val dictionaries: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @OptIn(ExperimentalStdlibApi::class)
    @TaskAction
    fun generate() {
        val nonAircraft = dictionaries.files.single { it.name == "dict_non_aircraft.zstd" }.readBytes()
        val aircraft = dictionaries.files.single { it.name == "dict_aircraft.zstd" }.readBytes()

        // Base64 chunk length: a multiple of 4 keeps each chunk independently
        // valid Base64 so they concatenate losslessly. 48_000 chars ≈ 36 KB of
        // bytes per chunk — comfortably small for every Native backend.
        val chunkChars = 48_000
        // Standard (padded) Base64; matches kotlin.io.encoding.Base64.decode on
        // the Native side. Concatenating chunks each of length % 4 == 0 keeps
        // any interior padding from appearing mid-string.
        val encoder = Base64.getEncoder()

        fun emitChunks(
            propName: String,
            bytes: ByteArray,
        ): String {
            val b64 = encoder.encodeToString(bytes)
            val chunks = b64.chunked(chunkChars)
            val sb = StringBuilder()
            sb.append("    private val ${propName}_CHUNKS: Array<String> = arrayOf(\n")
            chunks.forEach { sb.append("        \"").append(it).append("\",\n") }
            sb.append("    )\n")
            return sb.toString()
        }

        val pkgDir = outputDir.get().asFile.resolve("org/meshtastic/tak")
        pkgDir.mkdirs()
        val out = pkgDir.resolve("EmbeddedDictionaries.kt")
        out.writeText(
            buildString {
                append("// GENERATED by the `generateEmbeddedDictionaries` Gradle task. DO NOT EDIT.\n")
                append("// Source: kotlin/src/jvmMain/resources/dict_*.zstd (canonical dictionaries).\n")
                append("package org.meshtastic.tak\n\n")
                append("import kotlin.io.encoding.Base64\n\n")
                append("/**\n")
                append(" * Canonical zstd dictionaries embedded for Kotlin/Native (no classpath\n")
                append(" * resources). Stored as chunked Base64 and decoded lazily on first use.\n")
                append(" */\n")
                append("internal object EmbeddedDictionaries {\n")
                append(emitChunks("NON_AIRCRAFT", nonAircraft))
                append("\n")
                append(emitChunks("AIRCRAFT", aircraft))
                append("\n")
                append("    private val nonAircraftBytes: ByteArray by lazy { Base64.decode(NON_AIRCRAFT_CHUNKS.joinToString(\"\")) }\n")
                append("    private val aircraftBytes: ByteArray by lazy { Base64.decode(AIRCRAFT_CHUNKS.joinToString(\"\")) }\n\n")
                append("    fun nonAircraft(): ByteArray = nonAircraftBytes\n")
                append("    fun aircraft(): ByteArray = aircraftBytes\n")
                append("}\n")
            },
        )
    }
}

val embeddedDictsOutputDir = layout.buildDirectory.dir("generated/embeddedDictionaries/kotlin")

val generateEmbeddedDictionaries by tasks.registering(GenerateEmbeddedDictionaries::class) {
    dictionaries.from(
        projectDir.resolve("src/jvmMain/resources/dict_non_aircraft.zstd"),
        projectDir.resolve("src/jvmMain/resources/dict_aircraft.zstd"),
    )
    outputDir.set(embeddedDictsOutputDir)
}

// Wire the generated source onto commonMain — the single DictionaryLoader reads
// EmbeddedDictionaries on every target. Registering the task PROVIDER (not the bare
// output dir) as the srcDir makes every consumer — each main compile, the metadata
// tasks, and every sources jar, including the root `sourcesJar` — carry an implicit
// dependency on the generator, so it always runs first. (Matching consumers by name
// instead silently missed the root `sourcesJar`, breaking Maven Central publishing.)
kotlin.sourceSets.named("commonMain") {
    kotlin.srcDir(generateEmbeddedDictionaries)
}

// ─────────────────────────────────────────────────────────────────────────────
// Codegen: inline the test fixtures for commonTest (R11).
//
// The common test suites (RoundTrip / Resilience / Logger / Decode) run on every
// target — including Native / JS / Wasm, which cannot read `../testdata` off the
// filesystem the way the JVM file-based suites do. This task reads the 47
// `testdata/cot_xml/*.xml` inputs AND their `testdata/golden/*.bin` wire frames
// and emits an internal `InlinedFixtures` object into a generated commonTest
// source dir, so every target gets the inputs (for parse → compress where the
// codec supports it) and the precompressed frames (for decode, which works on
// ALL targets via the pure-Kotlin decoder).
//
// Incremental: @InputFiles + @OutputDirectory, so it only re-runs when a fixture
// or golden changes. It is purely a TEST input generator — it NEVER writes to
// `testdata/`.
abstract class GenerateInlinedFixtures : DefaultTask() {
    @get:InputFiles
    abstract val cotXml: ConfigurableFileCollection

    @get:InputFiles
    abstract val golden: ConfigurableFileCollection

    @get:InputFiles
    abstract val protobuf: ConfigurableFileCollection

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        // fixtureName (no .xml) -> XML text, sorted for stable output.
        val xmlByName =
            cotXml.files
                .filter { it.name.endsWith(".xml") }
                .associate { it.name.removeSuffix(".xml") to it.readText() }
                .toSortedMap()
        // fixtureName -> golden .bin wire frame (Base64), where present.
        val goldenByName =
            golden.files
                .filter { it.name.endsWith(".bin") }
                .associate { it.name.removeSuffix(".bin") to Base64.getEncoder().encodeToString(it.readBytes()) }
                .toSortedMap()
        // fixtureName -> intermediate .pb protobuf bytes (Base64), where present.
        val protobufByName =
            protobuf.files
                .filter { it.name.endsWith(".pb") }
                .associate { it.name.removeSuffix(".pb") to Base64.getEncoder().encodeToString(it.readBytes()) }
                .toSortedMap()

        fun kotlinString(s: String): String {
            // Emit as a raw triple-quoted string. Fixtures are plain CoT XML with
            // no `"""` runs and no `$` that needs to interpolate, but escape `$`
            // defensively so a future fixture can't accidentally interpolate.
            val escaped = s.replace("\$", "\${'$'}")
            return "\"\"\"$escaped\"\"\""
        }

        val pkgDir = outputDir.get().asFile.resolve("org/meshtastic/tak")
        pkgDir.mkdirs()
        pkgDir.resolve("InlinedFixtures.kt").writeText(
            buildString {
                append("// GENERATED by the `generateInlinedFixtures` Gradle task. DO NOT EDIT.\n")
                append(
                    "// Source: testdata/cot_xml/*.xml (inputs) + testdata/golden/*.bin (wire frames)" +
                        " + testdata/protobuf/*.pb (intermediate protobuf).\n",
                )
                append("package org.meshtastic.tak\n\n")
                append("import kotlin.io.encoding.Base64\n\n")
                append("/**\n")
                append(" * The 47 CoT XML test fixtures with their golden wire frames AND their\n")
                append(" * intermediate `.pb` protobuf bytes, inlined so the commonTest suites run\n")
                append(" * on targets that can't read `../testdata` (Native / JS / Wasm). XML drives\n")
                append(" * parse → compress; the golden frames drive decode (works on every target\n")
                append(" * via the pure-Kotlin decoder); the `.pb` bytes are the byte-exact decoder\n")
                append(" * oracle (decode of a golden frame must equal the matching `.pb`).\n")
                append(" */\n")
                append("internal object InlinedFixtures {\n\n")

                append("    /** fixtureName (no extension) -> CoT XML source text. */\n")
                append("    val xml: Map<String, String> = mapOf(\n")
                xmlByName.forEach { (name, text) ->
                    append("        \"")
                        .append(name)
                        .append("\" to ")
                        .append(kotlinString(text))
                        .append(",\n")
                }
                append("    )\n\n")

                append("    /** fixtureName (no extension) -> golden `.bin` wire frame, Base64. */\n")
                append("    private val goldenWireB64: Map<String, String> = mapOf(\n")
                goldenByName.forEach { (name, b64) ->
                    append("        \"")
                        .append(name)
                        .append("\" to \"")
                        .append(b64)
                        .append("\",\n")
                }
                append("    )\n\n")

                append("    /** fixtureName (no extension) -> golden `.bin` wire frame bytes. */\n")
                append("    val goldenWire: Map<String, ByteArray> by lazy {\n")
                append("        goldenWireB64.mapValues { (_, b64) -> Base64.decode(b64) }\n")
                append("    }\n\n")

                append("    /** fixtureName (no extension) -> intermediate `.pb` protobuf, Base64. */\n")
                append("    private val protobufB64: Map<String, String> = mapOf(\n")
                protobufByName.forEach { (name, b64) ->
                    append("        \"")
                        .append(name)
                        .append("\" to \"")
                        .append(b64)
                        .append("\",\n")
                }
                append("    )\n\n")

                append("    /** fixtureName (no extension) -> intermediate `.pb` protobuf bytes. */\n")
                append("    val protobuf: Map<String, ByteArray> by lazy {\n")
                append("        protobufB64.mapValues { (_, b64) -> Base64.decode(b64) }\n")
                append("    }\n\n")

                append("    /** Sorted fixture names (every fixture has xml + golden + protobuf). */\n")
                append("    val names: List<String> = xml.keys.sorted()\n")
                append("}\n")
            },
        )
    }
}

val inlinedFixturesOutputDir = layout.buildDirectory.dir("generated/inlinedFixtures/kotlin")

val generateInlinedFixtures by tasks.registering(GenerateInlinedFixtures::class) {
    cotXml.from(fileTree(projectDir.resolve("../testdata/cot_xml")) { include("*.xml") })
    golden.from(fileTree(projectDir.resolve("../testdata/golden")) { include("*.bin") })
    protobuf.from(fileTree(projectDir.resolve("../testdata/protobuf")) { include("*.pb") })
    outputDir.set(inlinedFixturesOutputDir)
}

// Wire the generated fixtures onto commonTest (every target's test compile reads
// them). Registering the task PROVIDER as the srcDir carries an implicit dependency
// to every consumer, so the generator always runs first (same rationale as the
// embedded-dictionaries wiring above).
kotlin.sourceSets.named("commonTest") {
    kotlin.srcDir(generateInlinedFixtures)
}

// ─────────────────────────────────────────────────────────────────────────────
// Maven Central publishing via Vanniktech maven-publish plugin.
// Coordinates are read from gradle.properties: GROUP, POM_ARTIFACT_ID, VERSION_NAME.
// Signing is conditional — only applied when CI provides the key via Gradle properties.
mavenPublishing {
    // Ship browsable Dokka HTML as the `-javadoc.jar` on Maven Central (Central
    // requires a javadoc jar; the default empty one would waste that slot).
    // sourcesJar stays on (vanniktech default). JavadocJar.Dokka points at the
    // Dokka Gradle Plugin v2 HTML task whose output becomes the jar.
    configure(KotlinMultiplatform(javadocJar = JavadocJar.Dokka("dokkaGeneratePublicationHtml")))

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

// Security floors for the Kotlin/JS test harness (karma/webpack/mocha stack in
// kotlin-js-store/yarn.lock). Dev-time only — nothing here ships in published
// artifacts. Each pin clears an open Dependabot alert; drop a resolution once
// the transitive tree requires at least that version on its own.
// After changing these, regenerate the lock: ./gradlew kotlinUpgradeYarnLock
plugins.withType<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnPlugin> {
    the<org.jetbrains.kotlin.gradle.targets.js.yarn.YarnRootExtension>().apply {
        resolution("ws", "8.21.0") // GHSA memory-exhaustion DoS (< 8.21.0)
        resolution("serialize-javascript", "7.0.5") // RCE (< 7.0.3) + CPU-exhaustion DoS (< 7.0.5)
        resolution("webpack", "5.104.1") // buildHttp allow-list bypasses (< 5.104.1)
        resolution("diff", "8.0.3") // parsePatch/applyPatch DoS (< 8.0.3)
        resolution("brace-expansion", "2.1.2") // Dependabot HIGH ReDoS (< 1.1.16 / < 2.1.2)
        resolution("fast-uri", "3.1.4") // Dependabot HIGH (< 3.1.3 / < 3.1.4)
        resolution("js-yaml", "4.3.0") // Dependabot HIGH (< 4.3.0)
        resolution("body-parser", "1.20.6") // Dependabot LOW (< 1.20.6)
    }
}
