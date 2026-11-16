package org.meshtastic.tak

/** A partial keeps at most K + this many fragments: enough for the CRC leave-one-out retry. */
private const val EXTRA_FRAGMENTS_KEPT: Int = 4

/** Completed msg_ids remembered, so late fragments don't reopen them. */
private const val COMPLETED_CACHE_SIZE: Int = 256

/** Digests of recently delivered messages kept, to drop exact redeliveries. */
private const val RECENT_DIGESTS: Int = 256

private const val FNV64_OFFSET: Long = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
private const val FNV64_PRIME: Long = 0x100000001b3L

/** 64-bit FNV-1a. Only used to recognise exact redeliveries, so it needs no cryptographic strength. */
private fun fnv1a64(data: ByteArray): Long {
    var h = FNV64_OFFSET
    for (b in data) h = (h xor (b.toLong() and BYTE_MASK.toLong())) * FNV64_PRIME
    return h
}

/**
 * Reassembles fragment sets ([TakFragment]) back into the wire payload they were
 * split from, which the caller then passes to `TakCompressor.decompress` with
 * [TakFragment.MAX_REASSEMBLED_DECOMPRESSED_SIZE].
 *
 * Follows `docs/design/large-payload-chunking.md` §6.2:
 * - fragments are keyed by their 32-bit msg_id alone (not by the mesh sender, which
 *   receivers do not rely on) and may arrive in any order, with duplicates;
 * - a message completes once any K distinct fragments are held, and its CRC checks;
 * - a CRC mismatch keeps the partial open and retries without each fragment in turn;
 * - every partial has an idle deadline and an absolute ceiling, both from preset airtime;
 * - memory is bounded per partial and in total;
 * - exact redeliveries of a recently completed message are suppressed.
 *
 * Holds raw bytes only: no compression state is shared between messages, so
 * losing fragments of one message never affects another (§7). Malformed or
 * unexpected frames are dropped, never thrown. Not thread-safe.
 *
 * Receivers accept any K the wire format allows (up to [TakFragment.MAX_SOURCE_FRAGMENTS], 32),
 * even though [TakFragmenter] defaults to sending at most 8. This is deliberate: the sender's
 * cap is a policy each app can raise, and a receiver must handle every sender's policy.
 *
 * @param frameAirtimeMillis airtime of one full frame on the current modem preset.
 * @param hopFactor allowance for relaying, as a multiple of frame airtime.
 * @param toleratedConsecutiveLosses frames that may go missing in a row before the idle deadline passes.
 * @param slackMillis fixed extra time added to both deadlines.
 * @param maxPending partial messages kept at once; the oldest is evicted beyond this.
 */
public class TakReassembler(
    public val frameAirtimeMillis: Long,
    public val hopFactor: Int = 3,
    public val toleratedConsecutiveLosses: Int = 4,
    public val slackMillis: Long = 30_000,
    public val maxPending: Int = 32,
) {
    init {
        require(frameAirtimeMillis > 0) { "frameAirtimeMillis must be positive" }
        require(hopFactor >= 1 && toleratedConsecutiveLosses >= 0 && slackMillis >= 0)
        require(maxPending >= 1) { "maxPending must be at least 1" }
    }

    private class Header(
        val msgId: Int,
        val code: Int,
        val k: Int,
        val esi: Int,
        val data: ByteArray,
    )

    private class Partial(
        val k: Int,
        val code: Int,
        val firstSeenAt: Long,
    ) {
        /** ESI -> symbol data as received (source slices unpadded). */
        val symbols = LinkedHashMap<Int, ByteArray>()

        /** Slice size, once a repair fragment or a non-last source fragment has shown it; -1 until then. */
        var s: Int = -1
        var lastNewEsiAt: Long = firstSeenAt
    }

    /** msg_id -> partial message, oldest first. */
    private val partials = LinkedHashMap<Int, Partial>()

    /** Completed msg_ids -> expiry time, oldest first. */
    private val completed = LinkedHashMap<Int, Long>()

    /** Digests (size + 64-bit FNV-1a) of recently delivered messages, oldest first. */
    private val recentDigests = ArrayDeque<Pair<Int, Long>>()

    private val idleBudgetMillis: Long get() = (toleratedConsecutiveLosses + 1) * frameAirtimeMillis * hopFactor + slackMillis
    private val ceilingBudgetMillis: Long get() = TakFragment.MAX_BURST * frameAirtimeMillis * hopFactor + slackMillis

    /** Number of partial messages currently held. */
    public val pendingCount: Int get() = partials.size

    /**
     * Offer one port-78 frame received at [nowMillis]. Returns the completed wire
     * payload when this frame completes a message, otherwise null: still
     * waiting, a duplicate, not a fragment, or dropped as malformed.
     */
    public fun accept(
        frame: ByteArray,
        nowMillis: Long,
    ): ByteArray? {
        evictExpired(nowMillis)
        val header = parseHeader(frame) ?: return null
        val key = header.msgId
        val partial = if (completed.containsKey(key)) null else partialFor(key, header, nowMillis)
        return if (partial != null && store(partial, header, nowMillis)) tryComplete(key, partial) else null
    }

    /** Remove partial messages and completed-message entries whose time has passed. */
    public fun evictExpired(nowMillis: Long) {
        val idle = idleBudgetMillis
        val ceiling = ceilingBudgetMillis
        partials.entries.removeAll { (_, p) -> nowMillis > p.lastNewEsiAt + idle || nowMillis > p.firstSeenAt + ceiling }
        completed.entries.removeAll { (_, expiry) -> nowMillis > expiry }
    }

    /** Parses and sanity-checks a fragment header, or returns null for anything that isn't a usable fragment. */
    @Suppress("ReturnCount") // a run of guard clauses reads more clearly than nested conditionals
    private fun parseHeader(frame: ByteArray): Header? {
        if (!TakFragment.isFragment(frame)) return null
        if (frame.size <= TakFragment.HEADER_SIZE) return drop("frame shorter than header + 1 byte")
        if (frame.size > TakFragment.MAX_FRAME_BYTES) return drop("frame larger than ${TakFragment.MAX_FRAME_BYTES} bytes")
        val shape = frame[HDR_SHAPE].toInt() and BYTE_MASK
        val code = shape ushr CODE_SHIFT
        if (code != TakFragment.CODE_CAUCHY) return drop("unknown fragment code $code")
        return Header(
            msgId = (0 until MSG_ID_BYTES).fold(0) { id, b -> id or ((frame[HDR_MSG_ID + b].toInt() and BYTE_MASK) shl (BYTE_BITS * b)) },
            code = code,
            k = (shape and K_MASK) + 1,
            esi = frame[HDR_ESI].toInt() and BYTE_MASK,
            data = frame.copyOfRange(TakFragment.HEADER_SIZE, frame.size),
        )
    }

    /**
     * The partial for [key], created if new. Null if the frame disagrees with the partial's K or
     * code, or if the cap evicted a new partial at once.
     *
     * A frame whose K or code disagrees with an existing partial is dropped and the partial kept.
     * With random 32-bit message IDs a genuine clash between two messages is about one in four
     * billion, so a mismatch is far more likely a bad frame than a new message, and dropping it
     * keeps the progress already made. If it really is a new message, it waits until the old
     * partial expires.
     */
    private fun partialFor(
        key: Int,
        header: Header,
        nowMillis: Long,
    ): Partial? {
        val existing = partials[key]
        return when {
            existing == null -> {
                partials[key] = Partial(header.k, header.code, nowMillis)
                enforceCap()
                partials[key]
            }

            existing.k == header.k && existing.code == header.code -> {
                existing
            }

            else -> {
                trace { "TakReassembler: msg $key frame disagrees on K/code, dropped" }
                null
            }
        }
    }

    /** Adds the fragment to [p] if it is new, there is room, and its length fits the set. True if stored. */
    private fun store(
        p: Partial,
        header: Header,
        nowMillis: Long,
    ): Boolean {
        val isNew = !p.symbols.containsKey(header.esi) && p.symbols.size < p.k + EXTRA_FRAGMENTS_KEPT
        val fits = isNew && acceptLength(p, header.esi, header.data)
        if (isNew && !fits) drop("fragment length inconsistent with its set")
        if (fits) {
            p.symbols[header.esi] = header.data
            p.lastNewEsiAt = nowMillis
        }
        return fits
    }

    /**
     * Checks [data]'s length against what the set has shown so far, learning the
     * slice size when this fragment reveals it. Non-last source slices and repair
     * symbols are exactly one slice long; the last source slice is 1..slice bytes.
     */
    private fun acceptLength(
        p: Partial,
        esi: Int,
        data: ByteArray,
    ): Boolean {
        val revealsS = esi >= p.k || esi < p.k - 1
        return when {
            p.s >= 0 -> {
                if (revealsS) data.size == p.s else data.size <= p.s
            }

            revealsS -> {
                p.s = data.size
                // Re-check anything held before the slice size was known.
                p.symbols.entries.removeAll { (e, d) -> if (e >= p.k || e < p.k - 1) d.size != p.s else d.size > p.s }
                true
            }

            else -> {
                true
            } // the last source slice alone doesn't reveal the slice size
        }
    }

    private fun tryComplete(
        key: Int,
        p: Partial,
    ): ByteArray? {
        // With K == 1 and only the source fragment held, its own length is the slice size.
        val s =
            if (p.s >= 0 || p.k != 1) {
                p.s
            } else {
                p.symbols.values
                    .first()
                    .size
            }
        val inner = if (p.symbols.size >= p.k && s >= 0) FragmentSetDecoder.decode(p.k, s, p.symbols) else null
        if (inner == null && p.symbols.size >= p.k) {
            trace { "TakReassembler: msg $key failed CRC with ${p.symbols.size} fragments, waiting for more" }
        }
        return inner?.let { deliver(key, p, it) }
    }

    /** Records the completion and returns [inner], unless it is an exact redelivery of a recent message. */
    private fun deliver(
        key: Int,
        p: Partial,
        inner: ByteArray,
    ): ByteArray? {
        partials.remove(key)
        completed.remove(key)
        completed[key] = p.firstSeenAt + ceilingBudgetMillis
        while (completed.size > COMPLETED_CACHE_SIZE) completed.remove(completed.keys.first())

        // A CRC match alone isn't proof of a redelivery, so compare size plus a 64-bit digest.
        val digest = inner.size to fnv1a64(inner)
        val duplicate = digest in recentDigests
        if (duplicate) {
            trace { "TakReassembler: msg $key is an exact redelivery, dropped" }
        } else {
            recentDigests.addLast(digest)
            while (recentDigests.size > RECENT_DIGESTS) recentDigests.removeFirst()
        }
        return if (duplicate) null else inner
    }

    /** Evict the oldest partials until [maxPending] holds. */
    private fun enforceCap() {
        if (partials.size > maxPending) {
            partials.entries
                .sortedBy { it.value.firstSeenAt }
                .take(partials.size - maxPending)
                .map { it.key }
                .forEach { partials.remove(it) }
        }
    }

    private fun drop(reason: String): Header? {
        trace { "TakReassembler: dropped frame: $reason" }
        return null
    }
}

/**
 * Decodes a held fragment set into the inner payload: picks K fragments, runs
 * the erasure decoder, then unwraps and CRC-checks the blob (design doc §5.2,
 * §6.2). Stateless, so it lives outside [TakReassembler].
 */
internal object FragmentSetDecoder {
    /** First the K cheapest held fragments (sources before repair); on a CRC failure, leave each one out in turn. */
    fun decode(
        k: Int,
        s: Int,
        symbols: Map<Int, ByteArray>,
    ): ByteArray? {
        val held =
            symbols.entries
                .sortedBy { it.key }
                .map { it.key to it.value }
        return decodeSubset(k, s, held.take(k)) ?: leaveOneOut(k, s, held)
    }

    /**
     * Any single bad fragment among the first K held is skipped by one of these subsets.
     *
     * Cost: while a partial keeps failing its CRC, every new fragment re-runs up to K decodes of
     * at most 32 x 32. That is microseconds on a phone; ports to slower targets should keep it in
     * mind (the partial holds at most K + 4 fragments, which bounds the retries).
     */
    private fun leaveOneOut(
        k: Int,
        s: Int,
        held: List<Pair<Int, ByteArray>>,
    ): ByteArray? =
        if (held.size <= k) {
            null
        } else {
            (0 until k).asSequence().mapNotNull { skip -> decodeSubset(k, s, held.filterIndexed { i, _ -> i != skip }.take(k)) }.firstOrNull()
        }

    private fun decodeSubset(
        k: Int,
        s: Int,
        subset: List<Pair<Int, ByteArray>>,
    ): ByteArray? {
        val padded = subset.map { (e, d) -> e to TakFragmenter.pad(d, s) }
        val source = TakFec.decode(k, s, padded) ?: return null
        val blob = ByteArray(k * s)
        source.forEachIndexed { i, sym -> sym.copyInto(blob, i * s) }
        return unwrapBlob(blob)
    }

    /** Strips trailing zero padding and the 0x80 delimiter, then checks the CRC. Returns the inner payload or null. */
    private fun unwrapBlob(blob: ByteArray): ByteArray? {
        var end = blob.size
        while (end > 0 && blob[end - 1].toInt() == 0) end--
        val delimited = end > BLOB_TRAILER_BYTES && (blob[end - 1].toInt() and BYTE_MASK) == TakFragment.BLOB_END
        if (!delimited) return null
        val innerLen = end - BLOB_TRAILER_BYTES
        var crc = 0
        for (b in 0 until CRC_BYTES) crc = crc or ((blob[innerLen + b].toInt() and BYTE_MASK) shl (BYTE_BITS * b))
        return if (Crc32.compute(blob, 0, innerLen) == crc) blob.copyOf(innerLen) else null
    }
}
