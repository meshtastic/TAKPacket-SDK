package org.meshtastic.tak

/**
 * Compresses TAKPacketV2 protobuf bytes using zstd with pre-trained dictionaries,
 * and decompresses received wire payloads back to protobuf bytes.
 *
 * Wire format: [1 byte flags][zstd-compressed protobuf bytes]
 * Flags byte bits 0-5 = dictionary ID, bits 6-7 = reserved.
 * Special value 0xFF = uncompressed raw protobuf.
 *
 * Multiplatform: the zstd codec itself is reached through the internal
 * [ZstdCodec], a single pure-Kotlin implementation used on every target. The
 * wire framing — the 4-byte magic strip/re-prepend, the `0xFF` skip-compress
 * path, the dict-ID flags masking, and the [MAX_DECOMPRESSED_SIZE] guard — all
 * live HERE, above the codec, so every target shares one framing implementation.
 */
public class TakCompressor(
    // NOTE: kzstd's pure-Kotlin encoder treats this as a documented no-op —
    // a single fixed strategy regardless of value (see Zstd.DEFAULT_LEVEL in
    // org.meshtastic:kzstd). Accepted for call-site familiarity and forward
    // compatibility; the libzstd-based Swift/Python/TS/C# bindings do honor it.
    private val compressionLevel: Int = 19,
) {
    public companion object {
        /** Maximum allowed decompressed payload size (bytes). Prevents decompression bombs. */
        public const val MAX_DECOMPRESSED_SIZE: Int = 4096

        /**
         * The 4-byte zstd frame magic number (little-endian 0xFD2FB528).
         *
         * We strip this on compress and prepend it on decompress: the SDK is
         * both ends of the link and identifies the frame from its own 1-byte
         * flags prefix, so the magic is pure overhead (4 B/packet). Done in
         * application code rather than via a binding's native "magicless"
         * format flag because TypeScript's zstd-napi cannot set that
         * experimental parameter — manual strip keeps the wire bytes
         * byte-identical across all 5 language bindings. The magic is a fixed
         * constant, so this stays fully stateless: every frame is independently
         * reconstructable.
         */
        private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

        /** Uppercase two-hex-digit rendering of a byte value (0..255); common-safe replacement for `"%02X".format`. */
        private fun hex2Upper(value: Int): String {
            val digits = "0123456789ABCDEF"
            val v = value and 0xFF
            return "${digits[v ushr 4]}${digits[v and 0xF]}"
        }
    }

    /**
     * Compress a TakPacketV2Data into a wire payload:
     * [flags byte][zstd-compressed protobuf]
     *
     * @throws ZstdException if the underlying zstd codec fails to compress.
     */
    @Throws(ZstdException::class)
    public fun compress(packet: TakPacketV2Data): ByteArray {
        val protobufBytes = TakPacketV2Serializer.serialize(packet)
        val dictId = DictionaryProvider.selectDictId(packet.cotTypeId, packet.cotTypeStr)

        // Compress exactly one independent frame per packet through the codec
        // SPI's one-shot API (NEVER a streaming API) so every packet decodes in
        // isolation — LoRa is lossy and a dropped packet must never affect any
        // other. The codec emits a full standard frame with dictID / content-
        // size / checksum all OFF (set inside the actual); we strip its magic
        // here. The dict ID already travels in our flags byte, so content-size /
        // checksum are dead weight on these tiny dict-compressed payloads.
        val framed = ZstdCodec.compressWithDict(protobufBytes, dictId, compressionLevel)
        // Strip the 4-byte zstd magic (see ZSTD_MAGIC doc). Defensive check: the
        // frame must start with the known magic, or our strip/prepend contract
        // is broken and we'd ship undecodable bytes.
        require(
            framed.size >= ZSTD_MAGIC.size &&
                framed[0] == ZSTD_MAGIC[0] && framed[1] == ZSTD_MAGIC[1] &&
                framed[2] == ZSTD_MAGIC[2] && framed[3] == ZSTD_MAGIC[3],
        ) { "Unexpected zstd frame header (magic mismatch)" }
        val body = framed.copyOfRange(ZSTD_MAGIC.size, framed.size)

        // Skip compression when it doesn't pay. For tiny payloads the frame +
        // dict-reference overhead can exceed the entropy saved, so the
        // "compressed" form is actually larger than raw. The 0xFF uncompressed
        // path is already understood by every decoder. Both wire forms carry
        // the same 1-byte flags prefix, so compare payload sizes directly and
        // emit whichever is smaller (ties → raw: cheaper to decode, no zstd pass).
        return if (protobufBytes.size <= body.size) {
            trace { "TakCompressor: skip-compress (raw ${protobufBytes.size}B <= body ${body.size}B), emitting 0xFF" }
            ByteArray(1 + protobufBytes.size).also {
                it[0] = DictionaryProvider.DICT_ID_UNCOMPRESSED.toByte()
                protobufBytes.copyInto(it, destinationOffset = 1)
            }
        } else {
            trace { "TakCompressor: compressed dictId=$dictId ${protobufBytes.size}B -> ${body.size}B" }
            ByteArray(1 + body.size).also {
                it[0] = (dictId and 0x3F).toByte()
                body.copyInto(it, destinationOffset = 1)
            }
        }
    }

    /**
     * Decompress a wire payload back to a TakPacketV2Data.
     * Handles both compressed (dict-based) and uncompressed (0xFF) payloads.
     *
     * @throws IllegalArgumentException for input the spec says to reject
     *         (payload < 2 bytes, unknown dictionary ID, uncompressed bytes >
     *         MAX_DECOMPRESSED_SIZE).
     * @throws ZstdException directly (unwrapped) when the underlying zstd codec
     *         rejects the frame — e.g. a decompression bomb exceeding
     *         MAX_DECOMPRESSED_SIZE — so callers can catch the typed exception.
     *         Other zstd / protobuf failures are wrapped in [RuntimeException]
     *         (the original cause is preserved via `cause`).
     */
    @Throws(ZstdException::class, IllegalArgumentException::class)
    public fun decompress(wirePayload: ByteArray): TakPacketV2Data {
        require(wirePayload.size >= 2) { "Wire payload too short: ${wirePayload.size} bytes" }

        val flagsByte = wirePayload[0].toInt() and 0xFF
        val compressedBytes = wirePayload.copyOfRange(1, wirePayload.size)

        val protobufBytes =
            if (flagsByte == DictionaryProvider.DICT_ID_UNCOMPRESSED) {
                // Uncompressed raw protobuf (e.g. from TAK_TRACKER firmware). Enforce
                // the decompressed-size cap here because there's no zstd pass to do it.
                if (compressedBytes.size > MAX_DECOMPRESSED_SIZE) {
                    throw IllegalArgumentException(
                        "Uncompressed payload size ${compressedBytes.size} exceeds limit $MAX_DECOMPRESSED_SIZE",
                    )
                }
                compressedBytes
            } else {
                val dictId = flagsByte and 0x3F
                // Reject unknown dictionary IDs up front with IllegalArgumentException
                // (the codec would only learn this once it tried to load a dict).
                if (DictionaryProvider.getDictionary(dictId) == null) {
                    throw IllegalArgumentException("Unknown dictionary ID: $dictId")
                }

                try {
                    // Re-attach the 4-byte magic stripped on compress (see ZSTD_MAGIC),
                    // yielding a standard frame the stock decoder accepts. The supplied
                    // dict (not a frame-embedded dict ID) selects the dictionary.
                    val restored = ByteArray(ZSTD_MAGIC.size + compressedBytes.size)
                    ZSTD_MAGIC.copyInto(restored, destinationOffset = 0)
                    compressedBytes.copyInto(restored, destinationOffset = ZSTD_MAGIC.size)
                    // The codec's size-limited decompress guards the 4096B cap — a bomb
                    // that expands past the limit throws inside the zstd library.
                    ZstdCodec.decompressWithDict(restored, dictId, MAX_DECOMPRESSED_SIZE)
                } catch (e: ZstdException) {
                    // Preserve the typed codec exception so callers can catch it
                    // (re-wrapping in RuntimeException would defeat the contract).
                    throw e
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
                    "(flagsByte=0x${hex2Upper(flagsByte)}, protobufSize=${protobufBytes.size}): " +
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
     * @throws ZstdException if the underlying zstd codec fails to compress.
     */
    @Throws(ZstdException::class)
    public fun compressWithRemarksFallback(
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
     *
     * @throws ZstdException if the underlying zstd codec fails to compress.
     */
    @Throws(ZstdException::class)
    public fun compressWithRemarksFallbackDetailed(
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
    public data class RemarksFallbackResult(
        val wirePayload: ByteArray?,
        val remarksStripped: Boolean,
    ) {
        /** Convenience: did this call produce a sendable wire payload? */
        public val fits: Boolean get() = wirePayload != null

        override fun equals(other: Any?): Boolean =
            other is RemarksFallbackResult &&
                remarksStripped == other.remarksStripped &&
                (wirePayload?.contentEquals(other.wirePayload) ?: (other.wirePayload == null))

        override fun hashCode(): Int = 31 * (wirePayload?.contentHashCode() ?: 0) + remarksStripped.hashCode()
    }

    /**
     * Compress and return both the wire payload and intermediate sizes for reporting.
     *
     * @throws ZstdException if the underlying zstd codec fails to compress.
     */
    @Throws(ZstdException::class)
    public fun compressWithStats(packet: TakPacketV2Data): CompressionResult {
        val protobufBytes = TakPacketV2Serializer.serialize(packet)
        val wirePayload = compress(packet)
        // Report the ACTUAL emitted mode from the flags byte, not the intended
        // dict — the skip-compress path may have emitted 0xFF (uncompressed)
        // when compression didn't pay.
        val flags = wirePayload[0].toInt() and 0xFF
        val dictId = if (flags == DictionaryProvider.DICT_ID_UNCOMPRESSED) flags else (flags and 0x3F)

        return CompressionResult(
            protobufSize = protobufBytes.size,
            compressedSize = wirePayload.size,
            dictId = dictId,
            wirePayload = wirePayload,
        )
    }

    public data class CompressionResult(
        val protobufSize: Int,
        val compressedSize: Int,
        val dictId: Int,
        val wirePayload: ByteArray,
    ) {
        public val dictName: String get() =
            when (dictId) {
                DictionaryProvider.DICT_ID_NON_AIRCRAFT -> "non-aircraft"
                DictionaryProvider.DICT_ID_AIRCRAFT -> "aircraft"
                DictionaryProvider.DICT_ID_UNCOMPRESSED -> "uncompressed"
                else -> "unknown"
            }

        override fun equals(other: Any?): Boolean =
            other is CompressionResult && protobufSize == other.protobufSize &&
                compressedSize == other.compressedSize && dictId == other.dictId &&
                wirePayload.contentEquals(other.wirePayload)

        override fun hashCode(): Int = wirePayload.contentHashCode()
    }
}
