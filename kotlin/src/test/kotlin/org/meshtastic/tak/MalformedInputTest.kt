package org.meshtastic.tak

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
        val packet = compressor.decompress(loadMalformed("reserved_bits_set.bin"))
        assert(packet.uid.isNotEmpty()) { "Should decompress despite reserved bits" }
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
}
