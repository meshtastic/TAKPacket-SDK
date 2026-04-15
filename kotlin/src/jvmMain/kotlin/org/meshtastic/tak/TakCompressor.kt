package org.meshtastic.tak

import com.github.luben.zstd.Zstd
import com.github.luben.zstd.ZstdDictCompress
import com.github.luben.zstd.ZstdDictDecompress

/**
 * Compresses TAKPacketV2 protobuf bytes using zstd with pre-trained dictionaries,
 * and decompresses received wire payloads back to protobuf bytes.
 *
 * Wire format: [1 byte flags][zstd-compressed protobuf bytes]
 * Flags byte bits 0-5 = dictionary ID, bits 6-7 = reserved.
 * Special value 0xFF = uncompressed raw protobuf.
 */
class TakCompressor(
    private val compressionLevel: Int = 19,
) {
    companion object {
        /** Maximum allowed decompressed payload size (bytes). Prevents decompression bombs. */
        const val MAX_DECOMPRESSED_SIZE = 4096
    }
    private val compressors = mutableMapOf<Int, ZstdDictCompress>()
    private val decompressors = mutableMapOf<Int, ZstdDictDecompress>()

    init {
        // Pre-load dictionaries
        DictionaryProvider.nonAircraftDict.let { dict ->
            compressors[DictionaryProvider.DICT_ID_NON_AIRCRAFT] = ZstdDictCompress(dict, compressionLevel)
            decompressors[DictionaryProvider.DICT_ID_NON_AIRCRAFT] = ZstdDictDecompress(dict)
        }
        DictionaryProvider.aircraftDict.let { dict ->
            compressors[DictionaryProvider.DICT_ID_AIRCRAFT] = ZstdDictCompress(dict, compressionLevel)
            decompressors[DictionaryProvider.DICT_ID_AIRCRAFT] = ZstdDictDecompress(dict)
        }
    }

    /**
     * Compress a TakPacketV2Data into a wire payload:
     * [flags byte][zstd-compressed protobuf]
     */
    fun compress(packet: TakPacketV2Data): ByteArray {
        val protobufBytes = TakPacketV2Serializer.serialize(packet)
        val dictId = DictionaryProvider.selectDictId(packet.cotTypeId, packet.cotTypeStr)
        val compressor = compressors[dictId]
            ?: throw IllegalStateException("No compressor for dictionary ID $dictId")

        val compressed = Zstd.compress(protobufBytes, compressor)

        // Build wire payload: [flags][compressed bytes]
        val flagsByte = (dictId and 0x3F).toByte()
        val wirePayload = ByteArray(1 + compressed.size)
        wirePayload[0] = flagsByte
        System.arraycopy(compressed, 0, wirePayload, 1, compressed.size)
        return wirePayload
    }

    /**
     * Decompress a wire payload back to a TakPacketV2Data.
     * Handles both compressed (dict-based) and uncompressed (0xFF) payloads.
     *
     * Throws [IllegalArgumentException] for input the spec says to reject
     * (payload < 2 bytes, unknown dictionary ID, decompressed bytes > MAX_DECOMPRESSED_SIZE)
     * and [RuntimeException] wrapping the underlying cause for zstd / protobuf
     * failures (so callers can still inspect the original exception via `cause`).
     */
    fun decompress(wirePayload: ByteArray): TakPacketV2Data {
        require(wirePayload.size >= 2) { "Wire payload too short: ${wirePayload.size} bytes" }

        val flagsByte = wirePayload[0].toInt() and 0xFF
        val compressedBytes = wirePayload.copyOfRange(1, wirePayload.size)

        val protobufBytes = if (flagsByte == DictionaryProvider.DICT_ID_UNCOMPRESSED) {
            // Uncompressed raw protobuf (e.g. from TAK_TRACKER firmware). Enforce
            // the decompressed-size cap here because there's no zstd pass to do it.
            if (compressedBytes.size > MAX_DECOMPRESSED_SIZE) {
                throw IllegalArgumentException(
                    "Uncompressed payload size ${compressedBytes.size} exceeds limit $MAX_DECOMPRESSED_SIZE"
                )
            }
            compressedBytes
        } else {
            val dictId = flagsByte and 0x3F
            val decompressor = decompressors[dictId]
                ?: throw IllegalArgumentException("Unknown dictionary ID: $dictId")

            try {
                // Zstd.decompress with a size limit already guards the 4096B cap —
                // a bomb that expands past the limit throws inside the zstd library.
                Zstd.decompress(compressedBytes, decompressor, MAX_DECOMPRESSED_SIZE)
            } catch (e: Exception) {
                throw RuntimeException(
                    "Zstd decompression failed " +
                        "(dictId=$dictId, compressedSize=${compressedBytes.size}): " +
                        (e.message ?: e::class.simpleName ?: "unknown"),
                    e,
                )
            }
        }

        try {
            return TakPacketV2Serializer.deserialize(protobufBytes)
        } catch (e: Exception) {
            throw RuntimeException(
                "Protobuf parsing failed " +
                    "(flagsByte=0x${"%02X".format(flagsByte)}, protobufSize=${protobufBytes.size}): " +
                    (e.message ?: e::class.simpleName ?: "unknown"),
                e,
            )
        }
    }

    /**
     * Compress a packet, stripping remarks if the result exceeds [maxWireBytes].
     *
     * First attempts compression with remarks intact. If the wire payload
     * fits within [maxWireBytes], returns it as-is. Otherwise, clears the
     * remarks field and re-compresses. Returns null if even the stripped
     * packet exceeds the limit (caller should drop the packet).
     *
     * This is a thin wrapper over [compressWithRemarksFallbackDetailed] that
     * discards the `remarksStripped` flag. Use the Detailed variant if you
     * need to tell "fit as-is", "fit after strip", and "dropped" apart — e.g.
     * for observability or metrics.
     *
     * @param packet The packet with remarks populated.
     * @param maxWireBytes Maximum allowed wire payload size (e.g. 225).
     * @return The wire payload, or null if the packet is too large even
     *         without remarks.
     */
    fun compressWithRemarksFallback(
        packet: TakPacketV2Data,
        maxWireBytes: Int,
    ): ByteArray? = compressWithRemarksFallbackDetailed(packet, maxWireBytes).wirePayload

    /**
     * Compress a packet, stripping remarks if needed, and return a detailed result
     * that distinguishes the four possible outcomes:
     *
     * | `wirePayload` | `remarksStripped` | Meaning                                           |
     * |---------------|-------------------|---------------------------------------------------|
     * | bytes         | false             | Fit as-is, no stripping needed                    |
     * | bytes         | true              | Stripped remarks to make it fit                   |
     * | null          | false             | Too big, had no remarks to strip                  |
     * | null          | true              | Stripped remarks, still too big                   |
     *
     * Callers that want to log/meter "how often does remarks-stripping save a
     * packet" or "how often do we drop oversized packets" should use this
     * variant; [compressWithRemarksFallback] loses the distinction.
     */
    fun compressWithRemarksFallbackDetailed(
        packet: TakPacketV2Data,
        maxWireBytes: Int,
    ): RemarksFallbackResult {
        val full = compress(packet)
        if (full.size <= maxWireBytes) {
            return RemarksFallbackResult(wirePayload = full, remarksStripped = false)
        }

        // Nothing to strip — caller must drop.
        if (packet.remarks.isEmpty()) {
            return RemarksFallbackResult(wirePayload = null, remarksStripped = false)
        }

        val stripped = compress(packet.copy(remarks = ""))
        return if (stripped.size <= maxWireBytes) {
            RemarksFallbackResult(wirePayload = stripped, remarksStripped = true)
        } else {
            RemarksFallbackResult(wirePayload = null, remarksStripped = true)
        }
    }

    /**
     * Result of [compressWithRemarksFallbackDetailed].
     *
     * @param wirePayload The compressed wire bytes if the packet fit under the
     *        limit, or `null` if the caller should drop the packet.
     * @param remarksStripped `true` if this call stripped the remarks field
     *        before compressing — either successfully ([wirePayload] is
     *        non-null) or unsuccessfully ([wirePayload] is null).
     */
    data class RemarksFallbackResult(
        val wirePayload: ByteArray?,
        val remarksStripped: Boolean,
    ) {
        /** Convenience: did this call produce a sendable wire payload? */
        val fits: Boolean get() = wirePayload != null

        override fun equals(other: Any?): Boolean =
            other is RemarksFallbackResult &&
                remarksStripped == other.remarksStripped &&
                (wirePayload?.contentEquals(other.wirePayload) ?: (other.wirePayload == null))

        override fun hashCode(): Int =
            31 * (wirePayload?.contentHashCode() ?: 0) + remarksStripped.hashCode()
    }

    /**
     * Compress and return both the wire payload and intermediate sizes for reporting.
     */
    fun compressWithStats(packet: TakPacketV2Data): CompressionResult {
        val protobufBytes = TakPacketV2Serializer.serialize(packet)
        val wirePayload = compress(packet)
        val dictId = DictionaryProvider.selectDictId(packet.cotTypeId, packet.cotTypeStr)

        return CompressionResult(
            protobufSize = protobufBytes.size,
            compressedSize = wirePayload.size,
            dictId = dictId,
            wirePayload = wirePayload,
        )
    }

    data class CompressionResult(
        val protobufSize: Int,
        val compressedSize: Int,
        val dictId: Int,
        val wirePayload: ByteArray,
    ) {
        val dictName: String get() = when (dictId) {
            DictionaryProvider.DICT_ID_NON_AIRCRAFT -> "non-aircraft"
            DictionaryProvider.DICT_ID_AIRCRAFT -> "aircraft"
            else -> "unknown"
        }

        override fun equals(other: Any?): Boolean =
            other is CompressionResult && protobufSize == other.protobufSize &&
                compressedSize == other.compressedSize && dictId == other.dictId &&
                wirePayload.contentEquals(other.wirePayload)
        override fun hashCode(): Int = wirePayload.contentHashCode()
    }
}
