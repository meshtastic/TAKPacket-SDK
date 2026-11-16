package org.meshtastic.tak

import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic
import kotlin.random.Random

// --- byte layout shared by the fragmenter and reassembler (design doc §5.1, §5.2) ---

internal const val BYTE_MASK: Int = 0xFF
internal const val BYTE_BITS: Int = 8

/** Header offsets: `[marker][msg_id: 4 bytes LE][code:3 | K-1:5][esi]`. */
internal const val HDR_MSG_ID: Int = 1
internal const val MSG_ID_BYTES: Int = 4
internal const val HDR_SHAPE: Int = HDR_MSG_ID + MSG_ID_BYTES
internal const val HDR_ESI: Int = HDR_SHAPE + 1

/** `code` sits in the top 3 bits of the shape byte, `K - 1` in the low 5. */
internal const val CODE_SHIFT: Int = 5
internal const val K_MASK: Int = 0x1F

internal const val CRC_BYTES: Int = 4

/** CRC plus the 0x80 end delimiter, appended after the inner payload. */
internal const val BLOB_TRAILER_BYTES: Int = CRC_BYTES + 1

/**
 * Wire constants for fragmented `TAKPacketV2` messages on port 78. See
 * `docs/design/large-payload-chunking.md` §5 for the full specification.
 *
 * ```
 * [0xFE][msg_id: u32 LE][code:3 | K-1:5][esi][symbol data]
 * ```
 */
public object TakFragment {
    /** Flags-byte value that marks a fragment. Old receivers mask it to unknown dictionary 0x3E and reject it. */
    public const val MARKER: Int = 0xFE

    /** Flags-byte value reserved for repair requests (design doc §10). Not sent by this version. */
    public const val REPAIR_REQUEST_MARKER: Int = 0xFD

    /** Fragment header length in bytes. */
    public const val HEADER_SIZE: Int = 7

    /** `code` value for the systematic Cauchy GF(256) scheme with the plain 7-byte header. */
    public const val CODE_CAUCHY: Int = 0

    /** Largest number of source fragments the wire format can express (5-bit count). */
    public const val MAX_SOURCE_FRAGMENTS: Int = 32

    /** Protocol cap on source plus repair fragments in one burst. */
    public const val MAX_BURST: Int = 48

    /** What both Meshtastic clients cap a port-78 payload at (see WIRE_FORMAT.md). */
    public const val DEFAULT_MAX_FRAME_BYTES: Int = 225

    /**
     * The firmware's `Data.payload` ceiling. No frame can be larger on LoRa, so
     * receivers drop anything bigger (from MQTT, say) before buffering it, and
     * senders cannot be configured above it.
     */
    public const val MAX_FRAME_BYTES: Int = 233

    /** Decompression cap for a reassembled message (single frames keep `TakCompressor.MAX_DECOMPRESSED_SIZE`). */
    public const val MAX_REASSEMBLED_DECOMPRESSED_SIZE: Int = 32768

    /** Trailing delimiter after the CRC in a message blob; lets a rebuilt last slice be trimmed without a length field. */
    internal const val BLOB_END: Int = 0x80

    /** True if [frame] is a fragment rather than an ordinary single-frame wire payload. */
    @JvmStatic
    public fun isFragment(frame: ByteArray): Boolean = frame.isNotEmpty() && (frame[0].toInt() and BYTE_MASK) == MARKER
}

/**
 * Source of 32-bit message IDs for fragment sets. Receivers key partial messages
 * on the ID alone, not on the mesh sender, so IDs must be unlikely to repeat
 * across every sender on the mesh, not just within one (design doc §5.1).
 */
public fun interface MessageIdSource {
    /** The next message ID: any 32-bit value, carried in an `Int`. */
    public fun next(): Int

    public companion object {
        /** A fresh random 32-bit ID per message. */
        @JvmStatic
        public fun random(): MessageIdSource = MessageIdSource { Random.nextInt() }
    }
}

/**
 * Splits one wire payload (`TakCompressor.compress` output) into fragment frames
 * when it does not fit one frame.
 *
 * The result is a pure function of the payload, the message ID and this
 * configuration, so every binding produces byte-identical frames for the same
 * input. Not thread-safe: use one instance per sending thread, or synchronize.
 *
 * @param maxFrameBytes largest frame the transport accepts (225 for today's clients).
 * @param maxSourceFragments policy cap on source fragments, `1..32`; [split] returns
 *        null for a payload that would need more. The default of 8 is a sender policy:
 *        [TakReassembler] deliberately accepts any K up to 32, so senders can raise it.
 * @param repairPolicy number of repair fragments to add for `k` source fragments.
 *        The result is clamped so the whole set stays within [TakFragment.MAX_BURST].
 * @param messageIds where message IDs come from when [split] is called without one.
 */
public class TakFragmenter
    @JvmOverloads
    constructor(
        public val maxFrameBytes: Int = TakFragment.DEFAULT_MAX_FRAME_BYTES,
        public val maxSourceFragments: Int = 8,
        private val repairPolicy: (k: Int) -> Int = ::defaultRepairCount,
        private val messageIds: MessageIdSource = MessageIdSource.random(),
    ) {
        init {
            require(maxFrameBytes in TakFragment.HEADER_SIZE + 1..TakFragment.MAX_FRAME_BYTES) {
                "maxFrameBytes must be in ${TakFragment.HEADER_SIZE + 1}..${TakFragment.MAX_FRAME_BYTES}"
            }
            require(maxSourceFragments in 1..TakFragment.MAX_SOURCE_FRAGMENTS) {
                "maxSourceFragments must be in 1..${TakFragment.MAX_SOURCE_FRAGMENTS}"
            }
        }

        /** Bytes of message data carried by each fragment. */
        public val sliceSize: Int get() = maxFrameBytes - TakFragment.HEADER_SIZE

        /** Source fragments (K) needed for a wire payload of [innerSize] bytes, before any cap is applied. */
        public fun sourceFragmentsFor(innerSize: Int): Int = (innerSize + BLOB_TRAILER_BYTES + sliceSize - 1) / sliceSize

        /** [split] with the next ID from this fragmenter's [MessageIdSource]. Only draws an ID when it fragments. */
        public fun split(
            inner: ByteArray,
            protobufSize: Int,
        ): List<ByteArray>? =
            if (fitsOneFrame(inner, protobufSize)) {
                listOf(inner)
            } else {
                split(inner, protobufSize, messageIds.next())
            }

        /**
         * Frames for [inner], the wire payload of one packet whose serialized
         * protobuf is [protobufSize] bytes.
         *
         * - If [inner] fits one frame and the protobuf is within the single-frame
         *   decompression cap, returns `listOf(inner)` unchanged: today's format.
         * - Otherwise returns K source fragments followed by R repair fragments. K
         *   may be 1 when only the protobuf-size rule forces fragmenting (design doc
         *   §5.2).
         * - Returns null if K would exceed [maxSourceFragments], or if [protobufSize]
         *   exceeds [TakFragment.MAX_REASSEMBLED_DECOMPRESSED_SIZE], since every
         *   receiver would reject the reassembled message.
         */
        public fun split(
            inner: ByteArray,
            protobufSize: Int,
            msgId: Int,
        ): List<ByteArray>? {
            require(inner.isNotEmpty()) { "inner payload is empty" }
            return if (fitsOneFrame(inner, protobufSize)) listOf(inner) else fragment(inner, protobufSize, msgId)
        }

        private fun fitsOneFrame(
            inner: ByteArray,
            protobufSize: Int,
        ): Boolean = inner.size <= maxFrameBytes && protobufSize <= TakCompressor.MAX_DECOMPRESSED_SIZE

        private fun fragment(
            inner: ByteArray,
            protobufSize: Int,
            msgId: Int,
        ): List<ByteArray>? {
            val s = sliceSize
            val k = sourceFragmentsFor(inner.size)
            if (k > maxSourceFragments || protobufSize > TakFragment.MAX_REASSEMBLED_DECOMPRESSED_SIZE) return null
            val blob = buildBlob(inner)
            val r = repairPolicy(k).coerceIn(0, TakFragment.MAX_BURST - k)

            val shape = (TakFragment.CODE_CAUCHY shl CODE_SHIFT) or (k - 1)

            fun header(esi: Int): ByteArray {
                val h = ByteArray(TakFragment.HEADER_SIZE)
                h[0] = TakFragment.MARKER.toByte()
                for (b in 0 until MSG_ID_BYTES) h[HDR_MSG_ID + b] = (msgId ushr (BYTE_BITS * b)).toByte()
                h[HDR_SHAPE] = shape.toByte()
                h[HDR_ESI] = esi.toByte()
                return h
            }

            // Source fragments carry their slice as-is; the last one is sent unpadded.
            val slices = List(k) { i -> blob.copyOfRange(i * s, minOf((i + 1) * s, blob.size)) }
            val frames = ArrayList<ByteArray>(k + r)
            for (i in 0 until k) frames += header(i) + slices[i]
            if (r > 0) {
                // Repair symbols are computed over the slices zero-padded to the full slice size.
                val padded = slices.map { pad(it, s) }
                for (e in k until k + r) frames += header(e) + TakFec.encodeRepair(padded, e)
            }
            trace { "TakFragmenter: ${inner.size}B -> K=$k R=$r (msgId=$msgId, slice=$s)" }
            return frames
        }

        public companion object {
            private const val REPAIR_DIVISOR: Int = 4

            /** Default repair policy: max(1, ceil(k / 4)). */
            @JvmStatic
            public fun defaultRepairCount(k: Int): Int = maxOf(1, (k + REPAIR_DIVISOR - 1) / REPAIR_DIVISOR)

            /** `inner || CRC32(inner) LE || 0x80` (design doc §5.2). */
            internal fun buildBlob(inner: ByteArray): ByteArray {
                val crc = Crc32.compute(inner)
                val blob = ByteArray(inner.size + BLOB_TRAILER_BYTES)
                inner.copyInto(blob)
                for (b in 0 until CRC_BYTES) blob[inner.size + b] = (crc ushr (BYTE_BITS * b)).toByte()
                blob[inner.size + CRC_BYTES] = TakFragment.BLOB_END.toByte()
                return blob
            }

            internal fun pad(
                slice: ByteArray,
                s: Int,
            ): ByteArray = if (slice.size == s) slice else slice.copyOf(s)
        }
    }
