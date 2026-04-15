package org.meshtastic.tak

import java.io.File
import java.util.stream.Stream

/**
 * Single source of truth for the test-fixture list used by RoundTripTest,
 * CompatibilityTest, and CompressionTest.
 *
 * Previously each test class held its own hard-coded `@ValueSource(strings = [...])`
 * of fixture filenames. When we added the 9 ATAK-CIV canonical samples (drawings,
 * markers, ranging tools, waypoint) we would have had to update seven separate
 * lists across three files and keep them in lockstep forever. Instead, every
 * test parameterizes off this helper's `filenames` / `fixtureNames` methods,
 * and adding a new fixture is a one-step operation: drop a `.xml` file into
 * `testdata/cot_xml/` and re-run the tests.
 *
 * Note: the fixtures directory is resolved relative to the process working
 * directory, which for a Gradle test run is the Kotlin module root (one level
 * above `testdata/`). All existing tests were already hard-coding
 * `"../testdata/cot_xml/..."` paths, so this matches their behavior.
 */
object TestFixtures {

    /** Directory containing the input CoT XML fixtures. */
    val cotXmlDir: File = File("../testdata/cot_xml")

    /** Directory containing the expected golden compressed wire payloads. */
    val goldenDir: File = File("../testdata/golden")

    /** Directory containing the expected intermediate protobuf bytes. */
    val protobufDir: File = File("../testdata/protobuf")

    /**
     * All fixture filenames including the `.xml` suffix, sorted alphabetically
     * for stable test output ordering. Used by tests that operate on the raw
     * XML filename (e.g. `@ValueSource(strings = [...])` style tests).
     */
    val filenames: List<String> by lazy {
        require(cotXmlDir.exists()) { "cot_xml fixture dir missing: ${cotXmlDir.absolutePath}" }
        cotXmlDir.listFiles { _, name -> name.endsWith(".xml") }
            .orEmpty()
            .map { it.name }
            .sorted()
    }

    /**
     * Fixture names without the `.xml` suffix, used by `CompatibilityTest`
     * which builds matching `.pb` and `.bin` paths from the base name.
     */
    val fixtureNames: List<String> by lazy {
        filenames.map { it.removeSuffix(".xml") }
    }

    /**
     * JUnit `@MethodSource` provider that yields fixture filenames (e.g.
     * `"pli_basic.xml"`). Used by RoundTripTest and CompressionTest's MTU test.
     */
    @JvmStatic
    fun allFixtureFilenames(): Stream<String> = filenames.stream()

    /**
     * JUnit `@MethodSource` provider that yields fixture base names (e.g.
     * `"pli_basic"`). Used by CompatibilityTest's protobuf and golden byte checks.
     */
    @JvmStatic
    fun allFixtureNames(): Stream<String> = fixtureNames.stream()

    /** Read the XML text for a fixture by full filename. */
    fun loadFixture(filename: String): String = File(cotXmlDir, filename).readText()

    /** Read the golden compressed wire payload by base name (returns null if absent). */
    fun loadGolden(fixtureName: String): ByteArray? {
        val f = File(goldenDir, "$fixtureName.bin")
        return if (f.exists()) f.readBytes() else null
    }

    /** Read the expected protobuf bytes by base name (returns null if absent). */
    fun loadProtobuf(fixtureName: String): ByteArray? {
        val f = File(protobufDir, "$fixtureName.pb")
        return if (f.exists()) f.readBytes() else null
    }
}
