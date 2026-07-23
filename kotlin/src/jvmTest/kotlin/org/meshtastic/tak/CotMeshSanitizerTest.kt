package org.meshtastic.tak

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Canonical tests for [CotMeshSanitizer]. Kotlin is the source of truth: this
 * generates the shared golden `.out.xml` outputs under `testdata/sanitizer`,
 * which the Swift / Python / TypeScript / C# suites assert against byte-for-byte
 * to lock cross-binding parity.
 */
class CotMeshSanitizerTest {
    private val dir = File("../testdata/sanitizer")

    @Test
    fun `strip preserves TAK-Talk voice + marti and removes non-essentials`() {
        val input = File(dir, "strip.in.xml").readText()
        val out = CotMeshSanitizer.stripNonEssentialForMesh(input)
        File(dir, "strip.out.xml").writeText(out) // golden (Kotlin canonical)

        // TAK-Talk essentials MUST survive (the regression that motivated this).
        assertTrue(out.contains("<voice/>"), "voice PTT marker must survive")
        assertTrue(
            out.contains("<marti>") && out.contains("dest callsign=\"ETHEL\""),
            "marti directed-routing must survive",
        )
        // Other must-keep content.
        assertTrue(out.contains("point=\"33.1300,-107.2500\""), "route waypoint point must survive")
        assertTrue(out.contains("callsign=\"ASPEN\""), "populated contact callsign must survive")
        assertTrue(out.contains("uid=\"SANITIZER-TEST\""), "event uid must survive (only <link> uids stripped)")

        // Non-essentials removed.
        listOf(
            "<takv",
            "<precisionlocation",
            "<creator",
            "<archive",
            "<tog",
            "<__geofence",
            "<__shapeExtras",
            "<strokeStyle",
            "<remarks>",
            "readiness=\"???\"",
            "uid=\"LINK-UUID",
            "routetype=",
            "order=",
            "color=",
            "callsign=\"\"",
            "phone=\"\"",
            "access=",
        ).forEach { gone -> assertFalse(out.contains(gone), "should be stripped: $gone") }
    }

    @Test
    fun `normalize drops xml declaration and collapses inter-tag whitespace`() {
        val input = File(dir, "normalize.in.xml").readText()
        val out = CotMeshSanitizer.normalizeCotXml(input)
        File(dir, "normalize.out.xml").writeText(out) // golden (Kotlin canonical)

        assertFalse(out.contains("<?xml"), "xml declaration must be dropped")
        assertFalse(Regex(">\\s+<").containsMatchIn(out), "inter-tag whitespace must be collapsed")
        assertTrue(out.contains("<text>Hello world from ASPEN</text>"), "text-node whitespace preserved")
        assertTrue(out.startsWith("<event"), "must start at <event>")
    }
}
