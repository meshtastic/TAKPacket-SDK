package org.meshtastic.tak

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDictDecompress
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Test
import org.meshtastic.tak.internal.zstd.PureZstdDecoder
import org.meshtastic.tak.internal.zstd.PureZstdEncoder

/**
 * Edge-case coverage for [PureZstdEncoder] that the 47 well-compressing fixtures
 * don't exercise: inputs shorter than MIN_MATCH, an all-literals (no-match)
 * payload, the Raw_Block fallback (when the compressed body wouldn't beat raw),
 * and degenerate repetitive inputs. Each must round-trip byte-exactly through
 * BOTH real libzstd (zstd-jni) and the pure-Kotlin decoder.
 */
class PureZstdEncoderEdgeTest {

    private val dict: ByteArray by lazy {
        PureZstdEncoderEdgeTest::class.java.classLoader
            ?.getResourceAsStream("dict_non_aircraft.zstd")
            ?.use { it.readBytes() }
            ?: error("dictionary resource not found")
    }

    private fun roundTrip(data: ByteArray, ctx: String) {
        val frame = PureZstdEncoder.encode(data, dict, 19)
        val viaJni = Zstd.decompress(frame, ZstdDictDecompress(dict), TakCompressor.MAX_DECOMPRESSED_SIZE)
        assertArrayEquals(data, viaJni, "zstd-jni round-trip mismatch ($ctx, ${data.size}B)")
        val viaPure = PureZstdDecoder.decode(frame, dict, TakCompressor.MAX_DECOMPRESSED_SIZE)
        assertArrayEquals(data, viaPure, "pure decoder round-trip mismatch ($ctx, ${data.size}B)")
    }

    @Test fun empty() = roundTrip(ByteArray(0), "empty")

    @Test fun oneByte() = roundTrip(byteArrayOf(7), "one byte")

    @Test fun belowMinMatch() = roundTrip(byteArrayOf(1, 2, 3), "3 bytes < min match")

    @Test
    fun incompressibleAllLiterals() {
        // Pseudo-random bytes unlikely to match the dict → all-literals (or
        // near-it), exercising the trailing-literals / Raw_Block-fallback paths.
        val b = ByteArray(50) { ((it * 131 + 7) and 0xFF).toByte() }
        roundTrip(b, "incompressible")
    }

    @Test
    fun highlyRepetitive() = roundTrip(ByteArray(200) { (it % 4).toByte() }, "repetitive")

    @Test
    fun allSameByte() = roundTrip(ByteArray(100) { 0x41 }, "all same")
}
