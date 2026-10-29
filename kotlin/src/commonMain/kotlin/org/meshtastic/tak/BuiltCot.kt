package org.meshtastic.tak

import kotlin.time.Instant

/**
 * What a decoder could establish about a packet's `start`, from [CotXmlBuilder.buildWithOrigin].
 *
 * Exists because [CotXmlBuilder.build] has to collapse three outcomes into one string: it writes
 * the decode instant whenever it has nothing better, so a caller reading `start` back out of the
 * XML cannot tell a recovered value from a fabricated one. That matters, because the fabricated one
 * reads as maximally fresh and will win any comparison it is entered into.
 */
public enum class StartRecovery {
    /** A trustworthy origin time was recovered, and [BuiltCot.originStart] carries it. */
    RECOVERED,

    /**
     * The sender did not populate `start_epoch_lo14`.
     *
     * A pre-upgrade peer, a producer that does not implement the field, or one whose own `start`
     * the sender gate declined to encode. It told us nothing, which is not the same as telling us
     * something false.
     */
    ABSENT,

    /**
     * The field was populated and the value was refused as out of range.
     *
     * Distinct from [ABSENT] on purpose, because it is a statement about the SENDER: it only
     * encodes a `start` within an hour of its own clock, so a value refused here means the two
     * clocks disagree by more than an hour, or transit exceeded an hour and a mesh does not
     * deliver that. Consumers that treat an unusable timestamp as grounds to discard the report
     * need this case and must not act on [ABSENT], which carries no such accusation.
     *
     * Note this is relative to the reference instant passed to
     * [CotXmlBuilder.buildWithOrigin], so decoding a stored capture refuses by construction
     * unless the original decode instant is supplied.
     */
    REFUSED,
}

/**
 * A rebuilt CoT together with what became of its origin time.
 *
 * Not a data class: this is published API, and a positional `componentN` surface is a liability
 * the SDK does not need here.
 */
public class BuiltCot internal constructor(
    /** The CoT XML, built identically to [CotXmlBuilder.build]. Always present. */
    public val xml: String,
    /** Which of the three cases applied. */
    public val recovery: StartRecovery,
    /**
     * The sender's own `start`, non-null exactly when [recovery] is [StartRecovery.RECOVERED].
     *
     * This is the value to order reports by. It is NOT necessarily the `start` in [xml], which
     * falls back to the decode instant in the other two cases.
     */
    public val originStart: Instant?,
)
