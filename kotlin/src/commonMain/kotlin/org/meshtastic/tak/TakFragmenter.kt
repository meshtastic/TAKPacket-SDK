package org.meshtastic.tak

import kotlin.random.Random

// --- byte layout shared by the fragmenter and reassembler (design doc §5.1, §5.2) ---

internal const val BYTE_MASK: Int = 0xFF
internal const val BYTE_BITS: Int = 8

/** Header offsets: `[marker][msg_id lo][msg_id hi][code:3 | K-1:5][esi]`. */
internal const val HDR_MSG_ID_LO: Int = 1
internal const val HDR_MSG_ID_HI: Int = 2
internal const val HDR_SHAPE: Int = 3
internal const val HDR_ESI: Int = 4

/** `code` sits in the top 3 bits of the shape byte, `K - 1` in the low 5. */
internal const val CODE_SHIFT: Int = 5
internal const val K_MASK: Int = 0x1F

internal const val MSG_ID_LIMIT: Int = 0x10000
internal const val CRC_BYTES: Int = 4

/** CRC plus the 0x80 end delimiter, appended after the inner payload. */
internal const val BLOB_TRAILER_BYTES: Int = CRC_BYTES + 1

/**
 * Wire constants for fragmented `TAKPacketV2` messages on port 78. See
 * `docs/design/large-payload-chunking.md` §5 for the full specification.
 *
 * ```
 * [0xFE][msg_id: u16 LE][code:3 | K-1:5][esi][symbol data]
 * ```
 */
public object TakFragment {
    /** Flags-byte value that marks a fragment. Old receivers mask it to unknown dictionary 0x3E and reject it. */
    public const val MARKER: Int = 0xFE

    /** Flags-byte value reserved for repair requests (design doc §10). Not sent by this version. */
    public const val REPAIR_REQUEST_MARKER: Int = 0xFD

    /** Fragment header length in bytes. */
    public const val HEADER_SIZE: Int = 5

    /** `code` value for the systematic Cauchy GF(256) scheme with the plain 5-byte header. */
    public const val CODE_CAUCHY: Int = 0

    /** Largest number of source fragments the wire format can express (5-bit count). */
    public const val MAX_SOURCE_FRAGMENTS: Int = 32

    /** Protocol cap on source plus repair fragments in one burst. */
    public const val MAX_BURST: Int = 48

    /** What both Meshtastic clients cap a port-78 payload at (see WIRE_FORMAT.md). */
    public const val DEFAULT_MAX_FRAME_BYTES: Int = 225

    /** Decompression cap for a reassembled message (single frames keep `TakCompressor.MAX_DECOMPRESSED_SIZE`). */
    public const val MAX_REASSEMBLED_DECOMPRESSED_SIZE: Int = 32768

    /** Trailing delimiter after the CRC in a message blob; lets a rebuilt last slice be trimmed without a length field. */
    internal const val BLOB_END: Int = 0x80

    /** True if [frame] is a fragment rather than an ordinary single-frame wire payload. */
    public fun isFragment(frame: ByteArray): Boolean = frame.isNotEmpty() && (frame[0].toInt() and BYTE_MASK) == MARKER
}

/**
 * Source of 16-bit message IDs for fragment sets. A sender must not reuse an ID
 * while receivers may still be reassembling it (design doc §5.1).
 */
public fun interface MessageIdSource {
    /** The next message ID, in `0..0xFFFF`. */
    public fun next(): Int

    public companion object {
        /** A rolling counter that starts at a random value, so a restart is unlikely to reuse an active ID. */
        public fun random(): MessageIdSource {
            var counter = Random.nextInt(MSG_ID_LIMIT)
            return MessageIdSource {
                val id = counter
                counter = (counter + 1) % MSG_ID_LIMIT
                id
            }
        }
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
 *        null for a payload that would need more.
 * @param repairPolicy number of repair fragments to add for `k` source fragments.
 *        The result is clamped so the whole set stays within [TakFragment.MAX_BURST].
 * @param messageIds where message IDs come from when [split] is called without one.
 */
public class TakFragmenter(
    public val maxFrameBytes: Int = TakFragment.DEFAULT_MAX_FRAME_BYTES,
    public val maxSourceFragments: Int = 8,
    private val repairPolicy: (k: Int) -> Int = ::defaultRepairCount,
    private val messageIds: MessageIdSource = MessageIdSource.random(),
) {
    init {
        require(maxFrameBytes > TakFragment.HEADER_SIZE) { "maxFrameBytes must exceed the ${TakFragment.HEADER_SIZE}-byte header" }
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
        require(msgId in 0 until MSG_ID_LIMIT) { "msgId must fit 16 bits" }
        return if (fitsOneFrame(inner, protobufSize)) listOf(inner) else fragment(inner, protobufSize, msgId)
    }

    /**
     * Fragments [inner] even when it would fit one frame, drawing an ID from this
     * fragmenter's [MessageIdSource]. Used by `compressForMesh` for an
     * uncompressed packet whose geometry exceeds the firmware's single-frame
     * pools (design doc §8). Returns null on the same caps as [split].
     */
    internal fun fragmentAlways(
        inner: ByteArray,
        protobufSize: Int,
    ): List<ByteArray>? {
        require(inner.isNotEmpty()) { "inner payload is empty" }
        return fragment(inner, protobufSize, messageIds.next())
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
            h[HDR_MSG_ID_LO] = msgId.toByte()
            h[HDR_MSG_ID_HI] = (msgId ushr BYTE_BITS).toByte()
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
