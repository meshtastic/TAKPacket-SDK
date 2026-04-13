package org.meshtastic.tak

/**
 * Compresses TAKPacketV2 protobuf bytes using zstd with pre-trained dictionaries,
 * and decompresses received wire payloads back to protobuf bytes.
 *
 * Wire format: `[1 byte flags][zstd-compressed protobuf bytes]`
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

    /**
     * Compress a [TakPacketV2Data] into a wire payload:
     * `[flags byte][zstd-compressed protobuf]`
     */
    fun compress(packet: TakPacketV2Data): ByteArray {
        val protobufBytes = TakPacketV2Serializer.serialize(packet)
        val dictId = DictionaryProvider.selectDictId(packet.cotTypeId, packet.cotTypeStr)

        val compressed = ZstdCodec.compressWithDict(protobufBytes, dictId, compressionLevel)

        // Build wire payload: [flags][compressed bytes]
        val flagsByte = (dictId and 0x3F).toByte()
        val wirePayload = ByteArray(1 + compressed.size)
        wirePayload[0] = flagsByte
        compressed.copyInto(wirePayload, destinationOffset = 1)
        return wirePayload
    }

    /**
     * Decompress a wire payload back to a [TakPacketV2Data].
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
            try {
                val result = ZstdCodec.decompressWithDict(compressedBytes, dictId, MAX_DECOMPRESSED_SIZE)
                if (result.size > MAX_DECOMPRESSED_SIZE) {
                    throw RuntimeException("Decompressed size ${result.size} exceeds limit $MAX_DECOMPRESSED_SIZE")
                }
                result
            } catch (e: Exception) {
                throw RuntimeException("Zstd decompression failed: ${e.message}", e)
            }
        }

        if (protobufBytes.size > MAX_DECOMPRESSED_SIZE) {
            throw IllegalArgumentException("Payload size ${protobufBytes.size} exceeds limit $MAX_DECOMPRESSED_SIZE")
        }

        try {
            return TakPacketV2Serializer.deserialize(protobufBytes)
        } catch (e: Exception) {
            throw RuntimeException("Protobuf parsing failed: ${e.message}", e)
        }
    }

    /**
     * Compress a packet using whichever format yields the smaller wire payload:
     * the fully-typed [TAKPacketV2] (default [compress] path), or a `raw_detail`
     * fallback carrying the original `<detail>` bytes.
     *
     * On every bundled fixture the typed path wins — delta-encoded geometry
     * and palette-enum colors compress much tighter than repeated XML tag
     * names, even with a 16KB zstd dictionary.  [compressBestOf] is a safety
     * net for:
     *
     *   * CoT types the parser can't structurally decompose (everything the
     *     current `CotXmlParser` doesn't recognize falls back to `Pli(true)`
     *     today, silently losing the detail children).
     *   * Shapes with geometry beyond [CotXmlParser.MAX_VERTICES] that would
     *     otherwise be silently truncated by the typed parser.
     *   * Any detail content that carries fields outside the typed schema.
     *
     * The fallback path strips the detail-derived top-level fields
     * (`callsign`, `takVersion`, etc.) from the alternate packet so the
     * `<detail>` content isn't duplicated on the wire, and relies on the
     * receiver re-parsing those fields out of the raw bytes.
     *
     * @param packet         The typed-variant packet (from [CotXmlParser.parse]).
     * @param rawDetailBytes The raw `<detail>` inner bytes (from
     *                       [CotXmlParser.extractRawDetailBytes]).
     * @return Whichever wire payload is smaller.  Ties go to the typed
     *         packet since it preserves strong typing on the receiver side.
     */
    fun compressBestOf(packet: TakPacketV2Data, rawDetailBytes: ByteArray): ByteArray {
        val typedWire = compress(packet)
        if (rawDetailBytes.isEmpty()) return typedWire

        // Strip every detail-derived top-level field in the fallback packet.
        // These come from <contact>, <__group>, <status>, <track>, <takv>,
        // <precisionlocation>, and <uid Droid="…"/> — all of which live
        // inside <detail>, so they'd be duplicated if we shipped them both
        // in the top-level proto fields and in raw_detail.  The envelope
        // (uid, cot_type_id, how, stale, lat, lon, altitude) stays intact
        // because those come from the <event> and <point> attributes.
        val rawPacket = packet.copy(
            callsign = "",
            team = 0,
            role = 0,
            battery = 0,
            speed = 0,
            course = 0,
            deviceCallsign = "",
            takVersion = "",
            takDevice = "",
            takPlatform = "",
            takOs = "",
            endpoint = "",
            phone = "",
            geoSrc = 0,
            altSrc = 0,
            payload = TakPacketV2Data.Payload.RawDetail(rawDetailBytes),
        )
        val rawWire = compress(rawPacket)

        return if (typedWire.size <= rawWire.size) typedWire else rawWire
    }

    /**
     * Compress a packet, stripping remarks if the result exceeds [maxWireBytes].
     *
     * First attempts compression with remarks intact. If the wire payload
     * fits within [maxWireBytes], returns it as-is. Otherwise, clears the
     * remarks field and re-compresses. Returns null if even the stripped
     * packet exceeds the limit (caller should drop the packet).
     *
     * @param packet The packet with remarks populated.
     * @param maxWireBytes Maximum allowed wire payload size (e.g. 225).
     * @return The wire payload, or null if the packet is too large even
     *         without remarks.
     */
    fun compressWithRemarksFallback(
        packet: TakPacketV2Data,
        maxWireBytes: Int,
    ): ByteArray? {
        val full = compress(packet)
        if (full.size <= maxWireBytes) return full

        // Strip remarks and retry
        if (packet.remarks.isEmpty()) return null
        val stripped = compress(packet.copy(remarks = ""))
        return if (stripped.size <= maxWireBytes) stripped else null
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
        val dictName: String
            get() = when (dictId) {
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
