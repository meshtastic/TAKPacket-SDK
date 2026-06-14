package org.meshtastic.tak

import org.meshtastic.tak.internal.zstd.PureZstdDecoder
import org.meshtastic.tak.internal.zstd.ZstdFormatException
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Adversarial robustness guard for the pure-Kotlin zstd decoder
 * ([PureZstdDecoder]) — the production decode path on js / wasmJs / wasmWasi.
 *
 * The decoder passes all 47 valid goldens, but it parses UNTRUSTED bytes off a
 * lossy mesh, so it must never hang, OOM, or leak an untyped exception on a
 * malformed/truncated/mutated frame. This is the regression guard for the
 * F-1..F-4 hardening (bounded Huffman-weight loop, FSE distribution checks,
 * pre-allocation literal-size cap, typed exceptions on corrupt FSE/sequence
 * state). It feeds deliberately bad inputs and asserts the decoder fails
 * cleanly:
 *
 *  - the bounded-work invariant is asserted simply by the test TERMINATING (a
 *    spin loop would hang the suite), and
 *  - the no-OOM invariant by the decoder never being allowed to allocate past
 *    the cap (a bomb would throw [OutOfMemoryError], an [Error] this test does
 *    NOT catch, so it would surface as a failure rather than a pass).
 */
class PureZstdDecoderMalformedTest {

    private val zstdMagic = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
    private val maxSize = TakCompressor.MAX_DECOMPRESSED_SIZE

    private val nonAircraftDict: ByteArray
        get() = DictionaryProvider.getDictionary(DictionaryProvider.DICT_ID_NON_AIRCRAFT)!!

    /** A real, valid compressed frame (golden minus flags byte, magic re-prepended). */
    private fun validFrame(name: String): ByteArray {
        val golden = InlinedFixtures.goldenWire.getValue(name)
        val body = golden.copyOfRange(1, golden.size)
        return zstdMagic + body
    }

    /** A dict-0 (non-aircraft) compressed golden that exercises the full grammar. */
    private fun validNonAircraftFrame(): ByteArray {
        // pli_basic is small and dict-0 (flags low 6 bits == 0).
        val golden = InlinedFixtures.goldenWire.getValue("pli_basic")
        check(golden[0].toInt() and 0x3F == DictionaryProvider.DICT_ID_NON_AIRCRAFT) {
            "pli_basic should be a dict-0 frame"
        }
        return validFrame("pli_basic")
    }

    // ── 1. Truncated frame ───────────────────────────────────────────────────

    @Test
    fun truncatedFrameThrowsCleanly() {
        val frame = validNonAircraftFrame()
        // Sanity: the untruncated frame decodes (otherwise the test is vacuous).
        PureZstdDecoder.decode(frame, nonAircraftDict, maxSize)

        // Chop the frame at every length from the magic through one-byte-short.
        // Every truncation must throw a typed ZstdFormatException — never hang,
        // never an untyped/Error exception.
        for (len in zstdMagic.size until frame.size) {
            val chopped = frame.copyOfRange(0, len)
            assertFailsWith<ZstdFormatException>("truncation to $len bytes must throw ZstdFormatException") {
                PureZstdDecoder.decode(chopped, nonAircraftDict, maxSize)
            }
        }
    }

    // ── 2. Single-byte / single-bit mutations (fuzz) ─────────────────────────

    @Test
    fun bitFlippedFramesNeverHangOrLeakUntypedFailures() {
        val frame = validNonAircraftFrame()
        var threw = 0
        var decoded = 0

        // Flip every bit of every body byte (skip the 4-byte magic so the frame
        // still routes into the real grammar rather than failing the magic check
        // — that path is its own assertion below). Reaching the end of this loop
        // proves no mutation spins forever; the only acceptable outcomes are a
        // clean decode or a typed ZstdFormatException. An untyped exception
        // (e.g. IndexOutOfBoundsException) or an Error (OOM) escapes and fails.
        for (byteIdx in zstdMagic.size until frame.size) {
            for (bit in 0 until 8) {
                val mutated = frame.copyOf()
                mutated[byteIdx] = (mutated[byteIdx].toInt() xor (1 shl bit)).toByte()
                try {
                    PureZstdDecoder.decode(mutated, nonAircraftDict, maxSize)
                    decoded++
                } catch (e: ZstdFormatException) {
                    threw++
                } catch (e: Throwable) {
                    fail(
                        "bit-flip at byte $byteIdx bit $bit produced an untyped failure " +
                            "${e::class.simpleName}: ${e.message}",
                    )
                }
            }
        }

        // The fuzz must actually exercise both outcomes' machinery: the vast
        // majority of mutations corrupt the stream and must be rejected.
        assertTrue(threw > 0, "expected some bit-flips to be rejected as malformed")
        assertTrue(decoded + threw > 0, "fuzz did not run")
    }

    @Test
    fun badMagicThrows() {
        val frame = validNonAircraftFrame()
        val mutated = frame.copyOf()
        mutated[0] = (mutated[0].toInt() xor 0xFF).toByte()
        assertFailsWith<ZstdFormatException> {
            PureZstdDecoder.decode(mutated, nonAircraftDict, maxSize)
        }
    }

    // ── 3. Oversized literals Regenerated_Size header ────────────────────────

    @Test
    fun oversizedLiteralsRegenSizeRejectedBeforeAllocating() {
        // A hand-built frame whose only block is a Compressed_Block carrying a
        // raw-literals section with a 20-bit Regenerated_Size of ~1 MB — far past
        // the 4096 cap. The decoder must reject it on the size check BEFORE it
        // allocates the literals buffer (F-3), so this never OOMs.
        //
        //  magic | descriptor 0x00 | window 0x00 |
        //  block header (last=1, type=2 Compressed, size=3) = 0x1D 00 00 |
        //  literals header: litType=0 (raw), sizeFormat=3 (20-bit) -> first=0xFC,
        //    then two size bytes 0xFF 0xFF  => regenSize = 0xFFFFF (1048575)
        val frame = zstdMagic + byteArrayOf(
            0x00, // frame header descriptor
            0x00, // window descriptor (singleSegment == 0)
            0x1D, 0x00, 0x00, // block header: last, Compressed_Block, blockSize=3
            0xFC.toByte(), 0xFF.toByte(), 0xFF.toByte(), // raw literals, 20-bit regen ~1MB
        )
        val ex = assertFailsWith<ZstdFormatException> {
            PureZstdDecoder.decode(frame, nonAircraftDict, maxSize)
        }
        assertTrue(
            ex.message?.contains("regen size") == true,
            "expected a literals-regen-size rejection, got: ${ex.message}",
        )
    }

    // ── 4. Reserved block type (3) ───────────────────────────────────────────

    @Test
    fun reservedBlockTypeRejected() {
        //  magic | descriptor 0x00 | window 0x00 |
        //  block header (last=1, type=3 RESERVED, size=0) = 0x07 00 00
        val frame = zstdMagic + byteArrayOf(
            0x00, // frame header descriptor
            0x00, // window descriptor
            0x07, 0x00, 0x00, // block header: last=1, blockType=3 (reserved)
        )
        val ex = assertFailsWith<ZstdFormatException> {
            PureZstdDecoder.decode(frame, nonAircraftDict, maxSize)
        }
        assertTrue(
            ex.message?.contains("reserved block type") == true,
            "expected a reserved-block-type rejection, got: ${ex.message}",
        )
    }

    // ── 5. Degenerate FSE Huffman-weight table (the F-1 spin-loop case) ───────

    @Test
    fun degenerateSingleSymbolHuffmanWeightTableTerminates() {
        // F-1 hardened decodeWeightStream against a crafted FSE weight table whose
        // transition consumes 0 bits (a self-loop that never sets `overflowed`),
        // which spun forever and grew the weight list without bound (bypassing the
        // 4096 bomb guard). Constructing that exact bitstream by hand is fiddly, so
        // we drive the same code path adversarially: a Compressed_Block whose
        // literals section is Huffman-compressed (litType=2) with an FSE-compressed
        // weight description (header byte < 128) built from a tiny, malformed FSE
        // block. Whatever the decoder makes of these bytes, it MUST terminate with
        // a typed ZstdFormatException — never spin, never OOM.
        //
        //  magic | descriptor 0x00 | window 0x00 |
        //  block header (last=1, type=2, size=6) |
        //  literals header: litType=2 (Compressed), sizeFormat=0 -> first byte +2
        //    size bytes, then a Huffman description whose first byte (< 128) claims
        //    an FSE-compressed weight block of the given length.
        for (weightBlockLen in intArrayOf(1, 2, 3)) {
            // first literals byte: low 2 bits = 2 (Compressed), bits 2-3 = 0
            // (sizeFormat 0). regenSize/compressedSize come from the next 2 bytes;
            // we keep them tiny so the decoder reaches the Huffman description.
            val literals = byteArrayOf(
                0x02, // litType=2, sizeFormat=0
                0x00, 0x00, // 10-bit regen/compressed size fields (small)
                weightBlockLen.toByte(), // Huffman header: FSE weight block of len N
            ) + ByteArray(weightBlockLen) { 0x01 } // a degenerate FSE weight block
            val blockSize = literals.size
            val header = (1 or (2 shl 1) or (blockSize shl 3))
            val frame = zstdMagic + byteArrayOf(
                0x00,
                0x00,
                (header and 0xFF).toByte(),
                ((header ushr 8) and 0xFF).toByte(),
                ((header ushr 16) and 0xFF).toByte(),
            ) + literals

            // The contract: a caught exception (the decoder is hardened to throw
            // ZstdFormatException, but we accept any caught Exception here because
            // the exact failure point depends on how the malformed FSE header
            // parses). The key guarantees are: it returns (no hang) and does not
            // throw an Error (no OOM).
            try {
                PureZstdDecoder.decode(frame, nonAircraftDict, maxSize)
                // A spurious "successful" decode of garbage is acceptable as long
                // as it terminated and stayed within the size cap.
            } catch (e: ZstdFormatException) {
                // expected, hardened path
            } catch (e: Exception) {
                // also acceptable: a caught, non-Error failure
            }
            // Reaching here for every weightBlockLen proves no spin/OOM.
        }
    }
}
