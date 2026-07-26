package org.meshtastic.tak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Locks two rebuild invariants of [CotXmlBuilder] / [CotXmlParser]:
 *
 * 1. Whatever a raw-detail packet carries, the rebuilt document is always ONE
 *    well-formed `<event>` — a stored fragment that would unbalance the
 *    `<detail>`/`<event>` wrapper is left out rather than spliced in.
 * 2. A `<contact>` always advertises the TAK server-reply endpoint. The parser
 *    never stores an endpoint, and the builder never echoes one back, so a
 *    receiving client is never pointed at a socket it cannot open.
 */
class RebuildHygieneTest {
    private val parser = CotXmlParser()
    private val builder = CotXmlBuilder()

    /** Count of non-overlapping [needle] occurrences in [haystack]. */
    private fun countOf(
        haystack: String,
        needle: String,
    ): Int = haystack.split(needle).size - 1

    /** [countOf], ignoring the case of both sides. */
    private fun countOfIgnoringCase(
        haystack: String,
        needle: String,
    ): Int = countOf(haystack.lowercase(), needle.lowercase())

    /** A PLI-shaped packet whose detail block is shipped as raw bytes. */
    private fun rawDetailPacket(fragment: String) =
        TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
            how = CotTypeMapper.COTHOW_M_G,
            callsign = "ALPHA-1",
            latitudeI = 340522000,
            longitudeI = -1182437000,
            altitude = 100,
            uid = "ALPHA-1-RAWDETAIL",
            payload = TakPacketV2Data.Payload.RawDetail(fragment.encodeToByteArray()),
        )

    @Test
    fun `raw detail fragment holding event and detail tag tokens is left out of the rebuild`() {
        // Detail inner content escapes a literal '<' as '&lt;', so a raw '<detail'
        // or '<event' token means the stored fragment is malformed. Appending it
        // verbatim would close the builder's own wrapper early and leave the
        // receiver with something it can't read as a single event.
        val fragment = """<foo bar="1"/></detail></event><event version="2.0" uid="ALPHA-1-SPLICED">"""
        val rebuilt = builder.build(rawDetailPacket(fragment))

        assertFalse(
            rebuilt.contains("ALPHA-1-SPLICED"),
            "a malformed raw-detail fragment must not be re-emitted",
        )
        assertEquals(
            1,
            countOf(rebuilt, "<event"),
            "rebuilt document must open exactly one <event>",
        )
        assertEquals(
            1,
            countOf(rebuilt, "</event>"),
            "rebuilt document must close exactly one </event>",
        )
        // The rest of the event still has to build normally — dropping the
        // fragment is not an excuse to abandon the document.
        assertTrue(
            rebuilt.contains("""uid="ALPHA-1-RAWDETAIL""""),
            "every other part of the event must still be built",
        )
    }

    @Test
    fun `raw detail fragment closing the wrapper in mixed case is left out of the rebuild`() {
        // Companion to the lowercase case above. XML element names are
        // case-sensitive, so `</DETAIL></Event>` does not literally close the
        // `<detail>`/`<event>` pair this builder opened — but an XML reader still
        // has to see them as end tags with no matching start tag, and the trailing
        // `<Event …>` is a second, never-closed element. Appending any of that
        // verbatim leaves the receiver with a document that is not one event, so
        // the guard has to recognise the tag tokens in ANY case, not just the
        // lowercase spelling every other test here happens to use.
        val fragment =
            """<remarks>ALPHA-1 checking in</remarks></DETAIL></Event><Event version="2.0" uid="SECOND">"""
        val rebuilt = builder.build(rawDetailPacket(fragment))

        // The distinctive marker of the fragment's trailing element. If the guard
        // stopped folding case, the whole fragment would be appended and this uid
        // would show up in the output.
        assertFalse(
            rebuilt.contains("""uid="SECOND""""),
            "a fragment spelling the wrapper tokens in mixed case must not be re-emitted",
        )
        assertFalse(
            rebuilt.contains("</DETAIL>"),
            "an uppercase end tag from the fragment must not reach the rebuilt XML",
        )
        // Case-folded counts: a lowercase-only tally would happily read `<Event`
        // as "not an event tag" and pass even with the fragment spliced in.
        assertEquals(
            1,
            countOfIgnoringCase(rebuilt, "<event"),
            "rebuilt document must open exactly one event element, in any spelling",
        )
        assertEquals(
            1,
            countOfIgnoringCase(rebuilt, "</event>"),
            "rebuilt document must close exactly one event element, in any spelling",
        )
        assertEquals(
            1,
            countOfIgnoringCase(rebuilt, "</detail>"),
            "rebuilt document must close exactly one detail element, in any spelling",
        )
        // Dropping the fragment is not an excuse to abandon the document.
        assertTrue(
            rebuilt.contains("""uid="ALPHA-1-RAWDETAIL""""),
            "every other part of the event must still be built",
        )
    }

    @Test
    fun `benign raw detail fragment still round-trips verbatim`() {
        val fragment = """<foo bar="1"/>"""
        val rebuilt = builder.build(rawDetailPacket(fragment))

        assertTrue(
            rebuilt.contains(fragment),
            "a well-formed fragment must reach the rebuilt XML byte-for-byte",
        )
        assertEquals(
            1,
            countOf(rebuilt, "<event"),
            "rebuilt document must open exactly one <event>",
        )
        assertEquals(
            1,
            countOf(rebuilt, "</event>"),
            "rebuilt document must close exactly one </event>",
        )
    }

    @Test
    fun `builder emits the server-reply endpoint even when the packet carries a concrete one`() {
        // Packets from older senders may still carry a concrete endpoint on the
        // wire; the builder must ignore it and emit the constant.
        val packet =
            TakPacketV2Data(
                cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
                how = CotTypeMapper.COTHOW_M_G,
                callsign = "ALPHA-1",
                latitudeI = 340522000,
                longitudeI = -1182437000,
                altitude = 100,
                uid = "ALPHA-1-ENDPOINT",
                endpoint = "192.0.2.1:4242:tcp",
                payload = TakPacketV2Data.Payload.Pli(true),
            )
        val rebuilt = builder.build(packet)

        assertTrue(
            rebuilt.contains("""<contact callsign="ALPHA-1" endpoint="*:-1:stcp"/>"""),
            "contact must always advertise the TAK server-reply endpoint",
        )
        assertFalse(
            rebuilt.contains("192.0.2.1"),
            "a concrete endpoint must never reach the rebuilt XML",
        )
    }

    @Test
    fun `parser never stores the contact endpoint attribute`() {
        val xml = """<event version="2.0" uid="ALPHA-1-PARSE" type="a-f-G-U-C" how="m-g" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-15T14:24:10Z"><point lat="34.0522" lon="-118.2437" hae="100" ce="9999999" le="9999999"/><detail><contact callsign="ALPHA-1" endpoint="192.0.2.1:4242:tcp"/><__group role="Team Member" name="Cyan"/><status battery="88"/><uid Droid="ALPHA-1"/></detail></event>"""
        val packet = parser.parse(xml)

        assertEquals(
            "ALPHA-1",
            packet.callsign,
            "the callsign attribute is still captured from <contact>",
        )
        assertEquals(
            "",
            packet.endpoint,
            "a concrete endpoint is unreachable across the mesh — the parser must not store it",
        )
    }
}
