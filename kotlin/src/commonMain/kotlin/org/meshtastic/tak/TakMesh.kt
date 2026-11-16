package org.meshtastic.tak

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
 * Result of `TakCompressor.compressForMesh`.
 *
 * @property outcome what the call produced.
 * @property frames the frames to send in order, one mesh packet each: a single
 *           frame for [Outcome.SINGLE_FRAME], K source then R repair fragments
 *           for [Outcome.FRAGMENTED], null otherwise.
 * @property remarksStripped true if this call dropped the remarks to make the packet fit.
 * @property truncated true if this call cut shape vertices or route links down to
 *           [CotXmlParser.Limits.SINGLE_FRAME] to make the packet fit.
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
 * compress, try one frame, otherwise fragment if allowed, with two fallbacks
 * that each run at most once and accumulate: drop the remarks, then cut the
 * geometry to single-frame limits.
 */
internal class MeshEncoder(
    private val compressor: TakCompressor,
    private val fragmenter: TakFragmenter,
    private val fragmentIfOversize: Boolean,
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

            fragmentIfOversize -> {
                fragmented(inner, protobufSize)
            }

            else -> {
                null
            }
        }
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
public class TakMeshReceiver(
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
                reassembler.accept(payload, nowMillis)?.let {
                    compressor.decompress(it, TakFragment.MAX_REASSEMBLED_DECOMPRESSED_SIZE)
                }
            }

            TakFragment.REPAIR_REQUEST_MARKER -> {
                null
            }

            else -> {
                compressor.decompress(payload)
            }
        }
    }

    /** Drop partial messages whose deadlines have passed. Call periodically, e.g. once a minute. */
    public fun evictExpired(nowMillis: Long) {
        reassembler.evictExpired(nowMillis)
    }
}
