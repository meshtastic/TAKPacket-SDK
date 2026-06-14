import java.util.Base64
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl

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
        // flag opts in.
        freeCompilerArgs.add("-Xexpect-actual-classes")
        // Treat compiler warnings as errors across all targets (main + test) and
        // opt into progressive mode so deprecations/ambiguities surface early.
        // The whole tree (commonMain + every actual + commonTest + every test
        // leaf) compiles warning-clean under this.
        allWarningsAsErrors.set(true)
        progressiveMode.set(true)
    }

    jvm()

    // STAGE 4 scope: the js / wasmJs / wasmWasi targets (R1/R3/R4). All three
    // get DECOMPRESS for free via the proven pure-Kotlin PureZstdDecoder (no JS
    // dependency). COMPRESS on js + wasmJs goes through @bokuweb/zstd-wasm (the
    // R3-gated path); COMPRESS on wasmWasi throws (no JS host, no cinterop).
    //
    //  - js + wasmJs share a `jsCommonMain` source set (the web ZstdCodec /
    //    DictionaryLoader actuals) so the shared logic lives once. The narrow
    //    `external` interop to @bokuweb is split into js / wasmJs leaves because
    //    js(IR) and wasmJs do NOT share `external` declaration ABIs.
    //  - wasmWasi has its own `wasmWasiMain` (decoder-backed decompress,
    //    throwing compress).
    // IR is the only Kotlin/JS compiler in 2.4+, so the explicit `js(IR)` form is
    // deprecated — plain `js {}` selects it.
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

    // STAGE 3 scope: declare the nine proven-path native targets (R1/R2). JS/Wasm
    // (Stage 4) are declared above. applyDefaultHierarchyTemplate() gives a
    // shared `nativeMain` source set under which all nine share ONE
    // cinterop-backed ZstdCodec/DictionaryLoader actual.
    val nativeTargets = listOf(
        iosArm64(),
        iosSimulatorArm64(),
        iosX64(),
        macosArm64(),
        tvosArm64(),
        tvosSimulatorArm64(),
        linuxX64(),
        linuxArm64(),
        mingwX64(),
    )

    applyDefaultHierarchyTemplate()

    // Register the `zstd` cinterop on each native target's main compilation.
    // The .def is shared; the header (-I) and per-konan-target static-lib search
    // path (-L lib/<konanTarget>) are injected here because they resolve against
    // $projectDir and the target name — see src/nativeInterop/cinterop/zstd.def.
    val cinteropDir = projectDir.resolve("src/nativeInterop/cinterop")
    val zstdIncludeDir = cinteropDir.resolve("include")
    nativeTargets.forEach { target ->
        val konanName = target.konanTarget.name
        target.compilations.getByName("main").cinterops.create("zstd") {
            definitionFile.set(cinteropDir.resolve("zstd.def"))
            // Header search path for the vendored zstd.h.
            includeDirs(zstdIncludeDir)
            // Per-target static libzstd.a search path. `staticLibraries=libzstd.a`
            // in the .def is resolved against this directory at klib link time.
            extraOpts("-libraryPath", cinteropDir.resolve("lib/$konanName").absolutePath)
        }
    }

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
        // Shared native source set: ZstdCodec (cinterop) + DictionaryLoader
        // (embedded dicts) actuals for all nine native targets. The atomicfu
        // library (plain runtime, NO bytecode-transform plugin) provides the
        // multiplatform SynchronizedObject lock the codec uses.
        nativeMain.dependencies {
            implementation(libs.kotlinx.atomicfu)
        }

        // ── Stage 4: js / wasmJs / wasmWasi ──────────────────────────────────
        // jsCommonMain holds the web codec logic shared by js + wasmJs:
        //  - decompress → PureZstdDecoder.decode (no JS lib),
        //  - compress   → a leaf `external` call into @bokuweb/zstd-wasm.
        // The `external` interop ABI differs between js(IR) and wasmJs, so the
        // declaration is split into the js / wasmJs leaves (jsMain / wasmJsMain)
        // and only the shared, target-agnostic logic lives in jsCommonMain.
        val jsCommonMain by creating {
            dependsOn(commonMain.get())
        }
        jsMain {
            dependsOn(jsCommonMain)
            dependencies {
                // The wasm-compiled libzstd backing js compress (R5). Gated on
                // the R3 byte-compat spike. Decompress needs NO JS dep.
                implementation(npm("@bokuweb/zstd-wasm", libs.versions.bokuweb.zstd.wasm.get()))
            }
        }
        named("wasmJsMain") {
            dependsOn(jsCommonMain)
            dependencies {
                implementation(npm("@bokuweb/zstd-wasm", libs.versions.bokuweb.zstd.wasm.get()))
            }
        }
        // wasmWasiMain: decode-capable via PureZstdDecoder; compress throws.
        // No npm dep (WASI has no JS host).

        // ── Cross-platform test source sets (R11) ───────────────────────────
        // The common test suites (RoundTrip / Resilience / Logger / Decode) live
        // in commonTest and run on every target. They need a per-target
        // `zstdCanCompress` capability flag (see commonTest/Capabilities.kt):
        //  - jvm + nativeTest already get it from the default hierarchy template;
        //  - js + wasmJs share a `jsCommonTest` (mirroring jsCommonMain) so the
        //    single `false` actual is declared once;
        //  - wasmWasiTest gets its own leaf actual.
        val jsCommonTest by creating {
            dependsOn(commonTest.get())
        }
        named("jsTest") { dependsOn(jsCommonTest) }
        named("wasmJsTest") { dependsOn(jsCommonTest) }
    }
}

// Binary-compatibility-validator: the generated org.meshtastic.proto.* types are
// an implementation detail, not part of this SDK's public API surface.
apiValidation {
    ignoredPackages.add("org.meshtastic.proto")
}

// Declaring the native targets makes BCV add `klibApiCheck` (and its
// `*MainKlibrary` builds) as a dependency of `apiCheck`. Building those klibs
// requires every target's cinterop — i.e. all nine vendored libzstd.a archives.
// Only the host archive (macos_arm64) is committed in this worktree, so the
// non-host klib builds can't run here, and there is no klib `.api` baseline yet.
//
// Detach klib validation from `apiCheck` so the established JVM `apiCheck` gate
// stays green. The public Kotlin API is identical across targets and the JVM
// baseline (api/takpacket-sdk.api) already guards the surface; every native
// addition (ZstdCodecNative / DictionaryLoaderNative / EmbeddedDictionaries) is
// `internal`. CI re-attaches klib validation once all nine archives are present
// (fetchZstdStatic) and a klib baseline is dumped (`klibApiDump`). [CI-PENDING]
tasks.matching { it.name == "apiCheck" }.configureEach {
    setDependsOn(dependsOn.filterNot { it is TaskProvider<*> && it.name == "klibApiCheck" })
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
// Codegen: embed the canonical zstd dictionaries for Kotlin/Native (R11).
//
// Kotlin/Native has no classpath resources, so DictionaryLoaderNative reads the
// dict bytes from a generated `EmbeddedDictionaries` object. This task reads the
// two canonical dicts (the same files the JVM loads as classpath resources) and
// emits `EmbeddedDictionaries.kt` into a generated source dir wired onto
// nativeMain. The non-aircraft dict is 512 KB, so the bytes are emitted as
// CHUNKED Base64 string constants (each well under any platform constant-size
// limit) joined + Base64-decoded once at first use.
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

        fun emitChunks(propName: String, bytes: ByteArray): String {
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
                append("import kotlin.io.encoding.Base64\n")
                append("import kotlin.io.encoding.ExperimentalEncodingApi\n\n")
                append("/**\n")
                append(" * Canonical zstd dictionaries embedded for Kotlin/Native (no classpath\n")
                append(" * resources). Stored as chunked Base64 and decoded lazily on first use.\n")
                append(" */\n")
                append("@OptIn(ExperimentalEncodingApi::class)\n")
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

// Wire the generated source onto the source sets whose DictionaryLoader actuals
// read EmbeddedDictionaries (no classpath resources on these targets):
//   - nativeMain   (all nine native targets, Stage 3)
//   - jsCommonMain (js + wasmJs, Stage 4)
//   - wasmWasiMain (Stage 4)
// and make every non-JVM compile (and the source-jar) depend on the generator
// so it always runs first.
listOf("nativeMain", "jsCommonMain", "wasmWasiMain").forEach { setName ->
    kotlin.sourceSets.named(setName) {
        kotlin.srcDir(embeddedDictsOutputDir)
    }
}
tasks.matching {
    it.name.startsWith("compileKotlin") &&
        it.name != "compileKotlinJvm" &&
        it.name != "compileKotlinMetadata"
}.configureEach {
    dependsOn(generateEmbeddedDictionaries)
}
// metadata + source-jar tasks also consume the generated sources.
tasks.matching {
    it.name == "compileNativeMainKotlinMetadata" ||
        it.name == "compileJsCommonMainKotlinMetadata" ||
        it.name.endsWith("SourcesJar")
}.configureEach { dependsOn(generateEmbeddedDictionaries) }

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

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun generate() {
        // fixtureName (no .xml) -> XML text, sorted for stable output.
        val xmlByName = cotXml.files
            .filter { it.name.endsWith(".xml") }
            .associate { it.name.removeSuffix(".xml") to it.readText() }
            .toSortedMap()
        // fixtureName -> golden .bin wire frame (Base64), where present.
        val goldenByName = golden.files
            .filter { it.name.endsWith(".bin") }
            .associate { it.name.removeSuffix(".bin") to Base64.getEncoder().encodeToString(it.readBytes()) }
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
                append("// Source: testdata/cot_xml/*.xml (inputs) + testdata/golden/*.bin (wire frames).\n")
                append("package org.meshtastic.tak\n\n")
                append("import kotlin.io.encoding.Base64\n")
                append("import kotlin.io.encoding.ExperimentalEncodingApi\n\n")
                append("/**\n")
                append(" * The 47 CoT XML test fixtures and their golden wire frames, inlined so\n")
                append(" * the commonTest suites run on targets that can't read `../testdata`\n")
                append(" * (Native / JS / Wasm). XML drives parse → compress; the golden frames\n")
                append(" * drive decode, which works on every target via the pure-Kotlin decoder.\n")
                append(" */\n")
                append("@OptIn(ExperimentalEncodingApi::class)\n")
                append("internal object InlinedFixtures {\n\n")

                append("    /** fixtureName (no extension) -> CoT XML source text. */\n")
                append("    val xml: Map<String, String> = mapOf(\n")
                xmlByName.forEach { (name, text) ->
                    append("        \"").append(name).append("\" to ").append(kotlinString(text)).append(",\n")
                }
                append("    )\n\n")

                append("    /** fixtureName (no extension) -> golden `.bin` wire frame, Base64. */\n")
                append("    private val goldenWireB64: Map<String, String> = mapOf(\n")
                goldenByName.forEach { (name, b64) ->
                    append("        \"").append(name).append("\" to \"").append(b64).append("\",\n")
                }
                append("    )\n\n")

                append("    /** fixtureName (no extension) -> golden `.bin` wire frame bytes. */\n")
                append("    val goldenWire: Map<String, ByteArray> by lazy {\n")
                append("        goldenWireB64.mapValues { (_, b64) -> Base64.decode(b64) }\n")
                append("    }\n\n")

                append("    /** Sorted fixture names common to both maps (every fixture has both). */\n")
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
    outputDir.set(inlinedFixturesOutputDir)
}

// Wire the generated fixtures onto commonTest (every target's test compile reads
// them) and make all test compiles depend on the generator.
kotlin.sourceSets.named("commonTest") {
    kotlin.srcDir(inlinedFixturesOutputDir)
}
tasks.matching {
    (it.name.startsWith("compileTestKotlin") || it.name == "compileTestKotlinMetadata") ||
        (it.name.startsWith("compileTestDevelopmentExecutableKotlin")) ||
        it.name.endsWith("TestKotlinMetadata")
}.configureEach {
    dependsOn(generateInlinedFixtures)
}

// ─────────────────────────────────────────────────────────────────────────────
// fetchZstdStatic: provenance helper for the per-konanTarget libzstd.a (R6).
//
// A single macOS dev box cannot cross-build a static libzstd for every target's
// sysroot, so this task documents+checks the vendored archives and prints the
// exact build/fetch commands for any that are missing. CI wires the real
// per-target builds/downloads in here (see src/nativeInterop/cinterop/lib/README.md).
tasks.register("fetchZstdStatic") {
    group = "build setup"
    description = "Report/verify the per-konanTarget vendored static libzstd.a archives (see lib/README.md)."
    val libRoot = projectDir.resolve("src/nativeInterop/cinterop/lib")
    val konanTargets = listOf(
        "ios_arm64", "ios_simulator_arm64", "ios_x64", "macos_arm64",
        "tvos_arm64", "tvos_simulator_arm64", "linux_x64", "linux_arm64", "mingw_x64",
    )
    doLast {
        val readme = libRoot.resolve("README.md").absolutePath
        logger.lifecycle("Vendored static libzstd.a status (expected v1.5.7, matching include/zstd.h):")
        val missing = mutableListOf<String>()
        konanTargets.forEach { t ->
            val archive = libRoot.resolve("$t/libzstd.a")
            if (archive.isFile) {
                logger.lifecycle("  [present] $t  (${archive.length()} bytes)")
            } else {
                logger.lifecycle("  [MISSING] $t")
                missing += t
            }
        }
        if (missing.isEmpty()) {
            logger.lifecycle("All nine archives present.")
        } else {
            logger.lifecycle("")
            logger.lifecycle("Missing archives for: ${missing.joinToString(", ")}")
            logger.lifecycle("Build/fetch each per the per-target commands in: $readme")
            logger.lifecycle("(Only the host target, macos_arm64, is committed; the rest are produced in CI.)")
        }
    }
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
