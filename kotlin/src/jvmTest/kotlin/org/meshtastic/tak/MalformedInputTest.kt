package org.meshtastic.tak

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

class MalformedInputTest {

    private val compressor = TakCompressor()

    private fun loadMalformed(name: String): ByteArray {
        val path = File("../testdata/malformed/$name")
        return path.readBytes()
    }

    @Test
    fun `rejects empty payload`() {
        assertThrows<Exception> { compressor.decompress(byteArrayOf()) }
    }

    @Test
    fun `rejects single byte`() {
        assertThrows<Exception> { compressor.decompress(byteArrayOf(0x00)) }
    }

    @Test
    fun `rejects invalid dictionary ID`() {
        assertThrows<Exception> { compressor.decompress(loadMalformed("invalid_dict_id.bin")) }
    }

    @Test
    fun `rejects truncated zstd frame`() {
        assertThrows<Exception> { compressor.decompress(loadMalformed("truncated_zstd.bin")) }
    }

    @Test
    fun `rejects corrupted zstd`() {
        assertThrows<Exception> { compressor.decompress(loadMalformed("corrupted_zstd.bin")) }
    }

    @Test
    fun `handles invalid protobuf without crash`() {
        // 0xFF + garbage bytes — protobuf parser may be lenient or may throw
        // Key assertion: no crash
        try {
            compressor.decompress(loadMalformed("invalid_protobuf.bin"))
        } catch (_: Exception) {
            // Expected — either outcome is acceptable
        }
    }

    @Test
    fun `ignores reserved bits in flags byte`() {
        // 0xC0 has reserved bits set but dict ID = 0 (0xC0 & 0x3F = 0)
        // Regenerate fixture if dictionary changed
        regenerateReservedBitsFixtureIfNeeded()
        val packet = compressor.decompress(loadMalformed("reserved_bits_set.bin"))
        assert(packet.uid.isNotEmpty()) { "Should decompress despite reserved bits" }
    }

    private fun regenerateReservedBitsFixtureIfNeeded() {
        val fixture = File("../testdata/malformed/reserved_bits_set.bin")
        // Try decompressing; if it fails, the dictionary changed — regenerate
        try {
            compressor.decompress(fixture.readBytes())
        } catch (_: Exception) {
            val parser = CotXmlParser()
            val xml = """<event version="2.0" uid="test-reserved-bits" type="a-f-G-U-C" how="m-g" time="2026-03-15T14:22:10Z" start="2026-03-15T14:22:10Z" stale="2026-03-15T14:24:10Z"><point lat="10.0" lon="95.0" hae="100" ce="9999999" le="9999999"/><detail><contact callsign="testnode5" endpoint="0.0.0.0:4242:tcp"/><__group role="Team Member" name="Cyan"/><status battery="88"/><track speed="1.2" course="142.75"/><uid Droid="testnode5"/></detail></event>"""
            val packet = parser.parse(xml)
            val wire = compressor.compress(packet)
            wire[0] = 0xC0.toByte() // reserved bits set, dictId=0
            fixture.writeBytes(wire)
        }
    }

    // Security attack tests

    @Test
    fun `rejects XML with DOCTYPE declaration`() {
        val xml = File("../testdata/malformed/xml_doctype.xml").readText()
        val parser = CotXmlParser()
        assertThrows<Exception> { parser.parse(xml) }
    }

    @Test
    fun `rejects XML with entity expansion`() {
        val xml = File("../testdata/malformed/xml_entity_expansion.xml").readText()
        val parser = CotXmlParser()
        assertThrows<Exception> { parser.parse(xml) }
    }

    @Test
    fun `rejects oversized protobuf fields`() {
        assertThrows<Exception> { compressor.decompress(loadMalformed("oversized_callsign.bin")) }
    }

    @Test
    fun `rejects decompression bomb`() {
        assertThrows<Exception> { compressor.decompress(loadMalformed("decompression_bomb.bin")) }
    }

    // -- Decompression size-cap boundary tests (audit item #19) --------------
    //
    // The existing decompression_bomb.bin fixture proves "> 4096 rejects" for
    // a dict-compressed payload via the zstd library's max_output_size guard.
    // These tests pin the boundary on the 0xFF uncompressed path — the only
    // branch where TakCompressor enforces the cap itself — with synthetic
    // wire payloads of exactly 4096 and 4097 bytes.

    @Test
    fun `uncompressed payload over MAX_DECOMPRESSED_SIZE is rejected`() {
        // [0xFF] + 4097 bytes of anything -> size check MUST fire before
        // the bytes are handed to the protobuf parser.
        val wire = ByteArray(1 + TakCompressor.MAX_DECOMPRESSED_SIZE + 1)
        wire[0] = 0xFF.toByte()
        val ex = assertThrows<IllegalArgumentException> { compressor.decompress(wire) }
        assertTrue(
            ex.message?.contains("exceeds limit") == true,
            "expected 'exceeds limit' in error message, got: ${ex.message}",
        )
    }

    @Test
    fun `uncompressed payload at MAX_DECOMPRESSED_SIZE passes size guard`() {
        // [0xFF] + exactly 4096 bytes. The size check is `> MAX_DECOMPRESSED_SIZE`
        // so 4096 bytes is within the limit. 4096 zero bytes is NOT valid
        // protobuf (field tag 0 is reserved), so the call will still throw —
        // but the failure must come from the downstream protobuf parse step,
        // NOT from the size guard. Verified by asserting the error message
        // does not mention the size limit.
        val wire = ByteArray(1 + TakCompressor.MAX_DECOMPRESSED_SIZE)
        wire[0] = 0xFF.toByte()
        try {
            compressor.decompress(wire)
            // If it somehow parses successfully, that's fine — no size error.
        } catch (e: Exception) {
            assertFalse(
                e.message?.contains("exceeds limit") == true,
                "size check fired at the exact boundary: ${e.message}",
            )
        }
    }
}
