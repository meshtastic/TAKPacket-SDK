package org.meshtastic.tak

import kotlin.jvm.JvmOverloads
import kotlin.jvm.JvmStatic

/** One hour, the window over which firmware tracks transmit airtime (`air_util_tx`). */
private const val DUTY_CYCLE_WINDOW_MILLIS: Long = 3_600_000L
private const val PERCENT: Double = 100.0

/**
 * Transmit airtime a sender may still spend this hour, for the pre-burst check
 * in `compressForMesh` (design doc §6.1). Pass `null` instead of a budget when
 * the region has no duty-cycle limit or the radio overrides it.
 *
 * @property frameAirtimeMillis airtime of one full frame on the current modem preset.
 * @property remainingMillis airtime left in the current hour.
 */
public data class AirtimeBudget(
    val frameAirtimeMillis: Long,
    val remainingMillis: Long,
) {
    init {
        require(frameAirtimeMillis > 0) { "frameAirtimeMillis must be positive" }
    }

    /** Whether [frames] full frames fit in what is left of the hour. */
    public fun allows(frames: Int): Boolean = frames.toLong() * frameAirtimeMillis <= remainingMillis

    public companion object {
        /**
         * Budget from what the radio reports: the region's duty-cycle limit,
         * minus `air_util_tx` from its device metrics, minus frames already
         * handed to the radio but not yet sent (`QueueStatus.maxlen - free`),
         * each counted at one full frame of airtime. Never negative.
         *
         * @param dutyCyclePercent the region's hourly limit, e.g. 10.0 for EU_868.
         * @param airUtilTxPercent `DeviceMetrics.air_util_tx`: percent of the last hour spent transmitting.
         * @param queuedFrames frames waiting in the radio's outgoing queue.
         */
        @JvmStatic
        public fun fromDeviceMetrics(
            dutyCyclePercent: Double,
            airUtilTxPercent: Double,
            queuedFrames: Int,
            frameAirtimeMillis: Long,
        ): AirtimeBudget {
            require(queuedFrames >= 0) { "queuedFrames must not be negative" }
            val hourLeft = (dutyCyclePercent - airUtilTxPercent) / PERCENT * DUTY_CYCLE_WINDOW_MILLIS
            val remaining = hourLeft.toLong() - queuedFrames.toLong() * frameAirtimeMillis
            return AirtimeBudget(frameAirtimeMillis, remaining.coerceAtLeast(0))
        }
    }
}

/**
 * What `TakCompressor.compressForMesh` may give up, and in which order, when a
 * CoT does not fit one frame. The caller picks it per CoT; it is never carried
 * on the wire.
 *
 * A CoT that fits one frame goes out as that frame, untouched, under every
 * policy. They differ only once it does not fit. A fragment set in the table
 * is conditional twice over: the fragmenter's caps have to allow it
 * (`maxSourceFragments`, and a protobuf within
 * [TakFragment.MAX_REASSEMBLED_DECOMPRESSED_SIZE]), and so does the
 * [AirtimeBudget] when one is passed. What happens when either refuses comes
 * after the table.
 *
 * | Policy           | Misses one frame only because of its remarks | Too big even without its remarks                   |
 * |------------------|----------------------------------------------|----------------------------------------------------|
 * | [NEVER]          | one frame, remarks dropped                   | one frame if cutting the geometry is enough        |
 * | [STRIP_FIRST]    | one frame, remarks dropped                   | a fragment set, without the remarks                |
 * | [FRAGMENT_FIRST] | a fragment set, remarks kept                 | a fragment set, remarks kept while the caps allow  |
 *
 * The same two fallbacks apply under every policy, each at most once and in
 * this order, whenever a packet neither fits one frame nor may be fragmented,
 * whether the policy is holding fragmenting back or the fragmenter's caps
 * refuse the set: drop the remarks, then cut the geometry to
 * [CotXmlParser.Limits.SINGLE_FRAME]. A packet that still fits nowhere is
 * [MeshEncodeResult.Outcome.TOO_LARGE]. A long chat message over the caps
 * ends there under every policy, since it has no remarks to drop and no
 * geometry to cut.
 *
 * A fragment set that the caps allow but the airtime budget does not cover is
 * [MeshEncodeResult.Outcome.INSUFFICIENT_AIRTIME]. That ends the call with no
 * frames; it does not fall back to a smaller packet. The budget is only
 * consulted for a fragment set, never for a single frame.
 */
public enum class FragmentPolicy {
    /**
     * One frame or nothing; never a fragment. Today's behaviour, and the right
     * choice for PLI and anything else sent periodically.
     */
    NEVER,

    /**
     * Drop the remarks before considering a fragment set. A CoT that misses one
     * frame only because of its remarks still goes out as one frame, and a
     * burst is spent only on what dropping them cannot fix: geometry, or text
     * that is the message itself.
     */
    STRIP_FIRST,

    /**
     * Fragment before giving anything up, so the CoT arrives whole whenever the
     * fragmenter's caps allow. Costs a burst even when dropping the remarks
     * alone would have fitted one frame.
     */
    FRAGMENT_FIRST,
}

/**
 * The policy a `fragmentIfOversize` flag stands for: `false` is
 * [FragmentPolicy.NEVER], `true` is [FragmentPolicy.FRAGMENT_FIRST].
 */
internal fun fragmentPolicyFor(fragmentIfOversize: Boolean): FragmentPolicy = if (fragmentIfOversize) FragmentPolicy.FRAGMENT_FIRST else FragmentPolicy.NEVER

/**
 * Result of `TakCompressor.compressForMesh`.
 *
 * @property outcome what the call produced.
 * @property frames the frames to send in order, one mesh packet each: a single
 *           frame for [Outcome.SINGLE_FRAME], K source then R repair fragments
 *           for [Outcome.FRAGMENTED], null otherwise.
 * @property remarksStripped true if this call dropped the remarks to make the packet fit.
 * @property truncated true if the geometry fallback ran: the encoder cut shape
 *           vertices or route links down to [CotXmlParser.Limits.SINGLE_FRAME]
 *           to make the packet fit. It does not report a cut made at parse.
 *           Under [FragmentPolicy.NEVER] the XML overload parses with those
 *           limits, so longer geometry is already cut before the encoder sees
 *           it and this stays false. The payload's own `truncated` flag is set
 *           in both cases.
 */
public class MeshEncodeResult(
    public val outcome: Outcome,
    public val frames: List<ByteArray>?,
    public val remarksStripped: Boolean,
    public val truncated: Boolean,
) {
    public enum class Outcome {
        /** One frame in today's format. */
        SINGLE_FRAME,

        /** A fragment set. */
        FRAGMENTED,

        /** Still over the limits after every fallback: drop it. */
        TOO_LARGE,

        /** It fits, but not in this hour's duty-cycle budget: retry later. */
        INSUFFICIENT_AIRTIME,
    }

    override fun toString(): String =
        "MeshEncodeResult(outcome=$outcome, frames=${frames?.size}, " +
            "remarksStripped=$remarksStripped, truncated=$truncated)"
}

/**
 * The send path behind `TakCompressor.compressForMesh` (design doc §6.1):
 * compress, try one frame, otherwise fragment if the [policy] allows it yet,
 * with two fallbacks that each run at most once and accumulate: drop the
 * remarks, then cut the geometry to single-frame limits.
 */
internal class MeshEncoder(
    private val compressor: TakCompressor,
    private val fragmenter: TakFragmenter,
    private val policy: FragmentPolicy,
    private val airtime: AirtimeBudget?,
) {
    private var remarksStripped = false
    private var truncated = false

    fun encode(packet: TakPacketV2Data): MeshEncodeResult {
        var current = packet
        while (true) {
            attempt(current)?.let { return it }
            current =
                when {
                    !remarksStripped && current.remarks.isNotEmpty() -> {
                        remarksStripped = true
                        current.copy(remarks = "")
                    }

                    !truncated && exceedsSingleFrameLimits(current) -> {
                        truncated = true
                        truncateToSingleFrame(current)
                    }

                    else -> {
                        return result(MeshEncodeResult.Outcome.TOO_LARGE, null)
                    }
                }
        }
    }

    /** A final result for [packet], or null if the next fallback should run. */
    private fun attempt(packet: TakPacketV2Data): MeshEncodeResult? {
        val protobufSize = TakPacketV2Serializer.serialize(packet).size
        val inner = compressor.compress(packet)
        return when {
            singleFrameAllowed(inner, protobufSize) -> {
                result(MeshEncodeResult.Outcome.SINGLE_FRAME, listOf(inner))
            }

            mayFragment(packet) -> {
                fragmented(inner, protobufSize)
            }

            else -> {
                null
            }
        }
    }

    /**
     * Whether [packet] may go out as a fragment set at this point.
     * [FragmentPolicy.STRIP_FIRST] holds fragmenting back while there are
     * remarks left to drop, so the remarks fallback in [encode] runs first and
     * a burst is only spent on what dropping them could not fix.
     */
    private fun mayFragment(packet: TakPacketV2Data): Boolean =
        when (policy) {
            FragmentPolicy.NEVER -> false
            FragmentPolicy.STRIP_FIRST -> packet.remarks.isEmpty()
            FragmentPolicy.FRAGMENT_FIRST -> true
        }

    /** The fragment set for [inner], or null if the fragmenter's caps refuse it. */
    private fun fragmented(
        inner: ByteArray,
        protobufSize: Int,
    ): MeshEncodeResult? {
        val frames = fragmenter.split(inner, protobufSize)
        return when {
            frames == null -> {
                null
            }

            airtime != null && !airtime.allows(frames.size) -> {
                result(MeshEncodeResult.Outcome.INSUFFICIENT_AIRTIME, null)
            }

            else -> {
                result(MeshEncodeResult.Outcome.FRAGMENTED, frames)
            }
        }
    }

    /**
     * Today's single-frame rule plus the receivers' 4,096-byte single-frame
     * protobuf cap. The same rule as `TakFragmenter.split`'s unchanged path.
     */
    private fun singleFrameAllowed(
        inner: ByteArray,
        protobufSize: Int,
    ): Boolean = inner.size <= fragmenter.maxFrameBytes && protobufSize <= TakCompressor.MAX_DECOMPRESSED_SIZE

    private fun result(
        outcome: MeshEncodeResult.Outcome,
        frames: List<ByteArray>?,
    ) = MeshEncodeResult(outcome, frames, remarksStripped, truncated)

    companion object {
        private val SINGLE = CotXmlParser.Limits.SINGLE_FRAME

        fun exceedsSingleFrameLimits(packet: TakPacketV2Data): Boolean =
            when (val p = packet.payload) {
                is TakPacketV2Data.Payload.DrawnShape -> p.vertices.size > SINGLE.maxVertices
                is TakPacketV2Data.Payload.Route -> p.links.size > SINGLE.maxRouteLinks
                else -> false
            }

        /**
         * Same result as re-parsing the XML with [CotXmlParser.Limits.SINGLE_FRAME]:
         * the parser keeps the first N vertices or links in document order and
         * sets `truncated` when it drops any.
         */
        fun truncateToSingleFrame(packet: TakPacketV2Data): TakPacketV2Data =
            when (val p = packet.payload) {
                is TakPacketV2Data.Payload.DrawnShape -> {
                    packet.copy(payload = p.copy(vertices = p.vertices.take(SINGLE.maxVertices), truncated = true))
                }

                is TakPacketV2Data.Payload.Route -> {
                    packet.copy(payload = p.copy(links = p.links.take(SINGLE.maxRouteLinks), truncated = true))
                }

                else -> {
                    packet
                }
            }
    }
}

/**
 * One-call receive path for port-78 payloads (design doc §6.2). Fragments go to
 * the [reassembler] and are decoded with the larger cap once complete; every
 * other payload is decoded as a single frame, exactly as `decompress` does today.
 *
 * Not thread-safe: feed it from one thread, or synchronize.
 *
 * @param reassembler holds partial fragment sets; build it with the current preset's frame airtime.
 * @param compressor decodes single frames and reassembled payloads.
 */
public class TakMeshReceiver
    @JvmOverloads
    constructor(
        public val reassembler: TakReassembler,
        private val compressor: TakCompressor = TakCompressor(),
    ) {
        /**
         * Handle one port-78 payload. The mesh packet's sender is deliberately not
         * used: fragments are matched on their own 32-bit message ID, and the
         * decoded packet carries its own `uid` and callsign.
         *
         * @return the decoded packet; or null when [payload] is a fragment and its
         *         message is not complete yet (or the fragment was dropped), or a
         *         repair request, which this version ignores.
         * @throws IllegalArgumentException, ZstdException or RuntimeException for a
         *         payload that cannot be decoded, as `TakCompressor.decompress` does.
         */
        @Throws(ZstdException::class, IllegalArgumentException::class)
        public fun onPayload(
            payload: ByteArray,
            nowMillis: Long,
        ): TakPacketV2Data? {
            val flags = if (payload.isEmpty()) -1 else payload[0].toInt() and BYTE_MASK
            return when (flags) {
                TakFragment.MARKER -> {
                    // accept() evicts expired partials itself.
                    reassembler.accept(payload, nowMillis)?.let {
                        compressor.decompress(it, TakFragment.MAX_REASSEMBLED_DECOMPRESSED_SIZE)
                    }
                }

                TakFragment.REPAIR_REQUEST_MARKER -> {
                    reassembler.evictExpired(nowMillis)
                    null
                }

                else -> {
                    reassembler.evictExpired(nowMillis)
                    compressor.decompress(payload)
                }
            }
        }

        /**
         * Drop partial messages whose deadlines have passed. [onPayload] already does this on every
         * payload, fragment or not, and before decoding, so a payload that fails to decode still
         * counts. Calling it is optional: it only frees memory sooner on a quiet channel. This class
         * is not thread-safe, so call it on the same thread as [onPayload] (or under the same lock),
         * never from an independent timer thread.
         */
        public fun evictExpired(nowMillis: Long) {
            reassembler.evictExpired(nowMillis)
        }
    }
