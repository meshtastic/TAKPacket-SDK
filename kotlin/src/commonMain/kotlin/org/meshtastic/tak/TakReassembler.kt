package org.meshtastic.tak

/**
 * Reassembles fragment sets ([TakFragment]) back into the wire payload they were
 * split from, which the caller then passes to `TakCompressor.decompress` with
 * [TakFragment.MAX_REASSEMBLED_DECOMPRESSED_SIZE].
 *
 * Follows `docs/design/large-payload-chunking.md` §6.2:
 * - fragments are keyed by (sender, msg_id) and may arrive in any order, with duplicates;
 * - a message completes once any K distinct fragments are held, and its CRC checks;
 * - a CRC mismatch keeps the partial open and retries without each fragment in turn;
 * - every partial has an idle deadline and an absolute ceiling, both from preset airtime;
 * - memory is bounded per partial, per sender and in total;
 * - exact redeliveries of a recently completed message are suppressed.
 *
 * Holds raw bytes only: no compression state is shared between messages, so
 * losing fragments of one message never affects another (§7). Malformed or
 * unexpected frames are dropped, never thrown. Not thread-safe.
 *
 * @param frameAirtimeMillis airtime of one full frame on the current modem preset.
 * @param hopFactor allowance for relaying, as a multiple of frame airtime.
 * @param toleratedConsecutiveLosses frames that may go missing in a row before the idle deadline passes.
 * @param slackMillis fixed extra time added to both deadlines.
 * @param maxPendingPerSender partial messages kept per sender; the oldest is evicted beyond this.
 * @param maxPendingTotal partial messages kept in total; the oldest is evicted beyond this.
 */
public class TakReassembler(
    public val frameAirtimeMillis: Long,
    public val hopFactor: Int = 3,
    public val toleratedConsecutiveLosses: Int = 4,
    public val slackMillis: Long = 30_000,
    public val maxPendingPerSender: Int = 4,
    public val maxPendingTotal: Int = 32,
) {
    init {
        require(frameAirtimeMillis > 0) { "frameAirtimeMillis must be positive" }
        require(hopFactor >= 1 && toleratedConsecutiveLosses >= 0 && slackMillis >= 0)
        require(maxPendingPerSender >= 1 && maxPendingTotal >= maxPendingPerSender)
    }

    private data class Key(
        val sender: Long,
        val msgId: Int,
    )

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

    private val partials = LinkedHashMap<Key, Partial>()

    /** Completed keys -> expiry time, oldest first. */
    private val completed = LinkedHashMap<Key, Long>()

    /** Per sender: CRCs of recently delivered messages, oldest first. */
    private val recentCrcs = HashMap<Long, ArrayDeque<Int>>()

    private val idleBudgetMillis: Long get() = (toleratedConsecutiveLosses + 1) * frameAirtimeMillis * hopFactor + slackMillis
    private val ceilingBudgetMillis: Long get() = TakFragment.MAX_BURST * frameAirtimeMillis * hopFactor + slackMillis

    /** Number of partial messages currently held. */
    public val pendingCount: Int get() = partials.size

    /**
     * Offer one frame received from [sender] (the Meshtastic node number) at
     * [nowMillis]. Returns the completed wire payload when this frame completes
     * a message, otherwise null: still waiting, a duplicate, not a fragment, or
     * dropped as malformed.
     */
    public fun accept(
        sender: Long,
        frame: ByteArray,
        nowMillis: Long,
    ): ByteArray? {
        evictExpired(nowMillis)
        val header = parseHeader(frame) ?: return null
        val key = Key(sender, header.msgId)
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
        val shape = frame[HDR_SHAPE].toInt() and BYTE_MASK
        val code = shape ushr CODE_SHIFT
        if (code != TakFragment.CODE_CAUCHY) return drop("unknown fragment code $code")
        return Header(
            msgId = (frame[HDR_MSG_ID_LO].toInt() and BYTE_MASK) or ((frame[HDR_MSG_ID_HI].toInt() and BYTE_MASK) shl BYTE_BITS),
            code = code,
            k = (shape and K_MASK) + 1,
            esi = frame[HDR_ESI].toInt() and BYTE_MASK,
            data = frame.copyOfRange(TakFragment.HEADER_SIZE, frame.size),
        )
    }

    /** The partial for [key], created (or restarted on a K/code change) as needed. Null if the caps evicted it at once. */
    private fun partialFor(
        key: Key,
        header: Header,
        nowMillis: Long,
    ): Partial? {
        val existing = partials[key]
        if (existing != null && existing.k == header.k && existing.code == header.code) return existing
        if (existing != null) trace { "TakReassembler: msg ${key.msgId} from ${key.sender} changed K/code, restarting" }
        val fresh = Partial(header.k, header.code, nowMillis)
        partials.remove(key)
        partials[key] = fresh
        enforceCaps(key.sender)
        return partials[key]
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
        key: Key,
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
            trace { "TakReassembler: msg ${key.msgId} from ${key.sender} failed CRC with ${p.symbols.size} fragments, waiting for more" }
        }
        return inner?.let { deliver(key, p, it) }
    }

    /** Records the completion and returns [inner], unless it is an exact redelivery of a recent message. */
    private fun deliver(
        key: Key,
        p: Partial,
        inner: ByteArray,
    ): ByteArray? {
        partials.remove(key)
        completed.remove(key)
        completed[key] = p.firstSeenAt + ceilingBudgetMillis
        while (completed.size > COMPLETED_CACHE_SIZE) completed.remove(completed.keys.first())

        val crc = Crc32.compute(inner)
        val recent = recentCrcs.getOrPut(key.sender) { ArrayDeque() }
        val duplicate = crc in recent
        if (duplicate) {
            trace { "TakReassembler: msg ${key.msgId} from ${key.sender} is an exact redelivery, dropped" }
        } else {
            recent.addLast(crc)
            while (recent.size > RECENT_CRCS_PER_SENDER) recent.removeFirst()
        }
        return if (duplicate) null else inner
    }

    /** Evict the oldest partials of [sender], then overall, until both caps hold. */
    private fun enforceCaps(sender: Long) {
        val mine = partials.entries.filter { it.key.sender == sender }
        if (mine.size > maxPendingPerSender) {
            mine.sortedBy { it.value.firstSeenAt }.take(mine.size - maxPendingPerSender).forEach { partials.remove(it.key) }
        }
        if (partials.size > maxPendingTotal) {
            partials.entries
                .sortedBy { it.value.firstSeenAt }
                .take(partials.size - maxPendingTotal)
                .map { it.key }
                .forEach { partials.remove(it) }
        }
    }

    private fun drop(reason: String): Header? {
        trace { "TakReassembler: dropped frame: $reason" }
        return null
    }

    private companion object {
        /** A partial keeps at most K + this many fragments: enough for the CRC leave-one-out retry. */
        const val EXTRA_FRAGMENTS_KEPT = 4

        /** Completed (sender, msg_id) pairs remembered, so late fragments don't reopen them. */
        const val COMPLETED_CACHE_SIZE = 256

        /** CRCs of recently delivered messages kept per sender, to drop exact redeliveries. */
        const val RECENT_CRCS_PER_SENDER = 64
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

    /** Any single bad fragment among the first K held is skipped by one of these subsets. */
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
