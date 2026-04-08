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
     */
    fun decompress(wirePayload: ByteArray): TakPacketV2Data {
        require(wirePayload.size >= 2) { "Wire payload too short: ${wirePayload.size} bytes" }

        val flagsByte = wirePayload[0].toInt() and 0xFF
        val compressedBytes = wirePayload.copyOfRange(1, wirePayload.size)

        val protobufBytes = if (flagsByte == DictionaryProvider.DICT_ID_UNCOMPRESSED) {
            // Uncompressed raw protobuf (e.g. from TAK_TRACKER firmware)
            compressedBytes
        } else {
            val dictId = flagsByte and 0x3F
            val decompressor = decompressors[dictId]
                ?: throw IllegalArgumentException("Unknown dictionary ID: $dictId")

            // Use the (src, ZstdDictDecompress, originalSize) overload
            val decompressedSize = Zstd.getFrameContentSize(compressedBytes)
            val maxSize = if (decompressedSize > 0) decompressedSize.toInt() else 4096
            try {
                Zstd.decompress(compressedBytes, decompressor, maxSize)
            } catch (e: Exception) {
                throw RuntimeException("Zstd decompression failed: ${e.message}", e)
            }
        }

        return TakPacketV2Serializer.deserialize(protobufBytes)
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
