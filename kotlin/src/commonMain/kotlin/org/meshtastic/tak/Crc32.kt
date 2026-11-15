package org.meshtastic.tak

/**
 * CRC-32/IEEE (reflected, polynomial 0xEDB88320, init and xor-out 0xFFFFFFFF),
 * the same checksum as zlib's `crc32` and `java.util.zip.CRC32`.
 *
 * Implemented here rather than taken from the platform so it is available in
 * `commonMain` on every target. Fragmented messages carry one of these per
 * message to catch mis-assembled fragment sets (see
 * `docs/design/large-payload-chunking.md` §5.2).
 */
internal object Crc32 {
    /** The reflected IEEE polynomial, 0xEDB88320, as a signed Int. */
    private const val POLY: Int = 0xEDB88320.toInt()
    private const val TABLE_SIZE: Int = 256

    private val TABLE: IntArray =
        IntArray(TABLE_SIZE) { n ->
            var c = n
            repeat(BYTE_BITS) { c = if (c and 1 != 0) (c ushr 1) xor POLY else c ushr 1 }
            c
        }

    /** CRC-32 of `data[offset until offset + length]`, as an unsigned 32-bit value in an Int. */
    fun compute(
        data: ByteArray,
        offset: Int = 0,
        length: Int = data.size - offset,
    ): Int {
        var crc = -1
        for (i in offset until offset + length) {
            crc = TABLE[(crc xor data[i].toInt()) and BYTE_MASK] xor (crc ushr BYTE_BITS)
        }
        return crc.inv()
    }
}
