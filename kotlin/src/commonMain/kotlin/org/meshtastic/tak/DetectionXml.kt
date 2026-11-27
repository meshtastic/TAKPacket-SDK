package org.meshtastic.tak

import org.meshtastic.tak.TakPacketV2Data.DetectionData
import kotlin.math.roundToInt

/**
 * The `<__detection>` CoT detail element that carries [DetectionData], shared by
 * [CotXmlParser] and [CotXmlBuilder].
 *
 * One self-closing element, a direct child of `<detail>`, with each attribute left out at
 * its default:
 *
 * ```
 * <__detection kind="uas" sources="remote_id rf" confidence="87" state="active" trend="closing"
 *     count="2" sensorAnchored="true" cep="12" rssi="-71" uasId="..." uasIdType="serial_number"
 *     uasStatus="airborne"/>
 * ```
 *
 * An enum attribute carries the value's name in lowercase snake_case (`UasOperator` is
 * `uas_operator`), and `sources` is a space-separated list of them. Reading ignores case,
 * takes an unknown token as unspecified and skips one in `sources`. `confidence` is clamped
 * to 0-100, `count` and `cep` to zero or more, and `uasId` is cut to 20 characters and kept
 * only when the kind is [DetectionData.Kind.Uas].
 *
 * `cep` is also the event's `<point ce>`: the builder writes it there, and the parser reads
 * `<point ce>` when the element has no `cep`. An event without the element keeps
 * `ce="9999999"`.
 *
 * None of these names is one [CotXmlParser] classifies an event by or [CotMeshSanitizer]
 * strips, so adding the annotation never changes an event's payload variant. Keep it that way.
 */
internal object DetectionXml {
    const val ELEMENT: String = "__detection"

    /** CoT's "unknown" circular error, written to `<point ce>` when there is no cep to carry. */
    const val UNKNOWN_CE: Int = 9_999_999

    private const val UAS_ID_MAX_CHARS = 20
    private const val CONFIDENCE_MAX = 100

    // A plain decimal: optional sign, digits with an optional fraction, optional exponent.
    // Narrower than toDoubleOrNull on purpose (no NaN, Infinity, hex or f/d suffix), so every
    // binding accepts exactly the same strings.
    private val DECIMAL = Regex("[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?")
    private val SPACES = Regex("\\s+")

    private val KINDS = tokenTable(DetectionData.Kind.entries)
    private val SOURCES = tokenTable(DetectionData.Source.entries)
    private val STATES = tokenTable(DetectionData.State.entries)
    private val TRENDS = tokenTable(DetectionData.Trend.entries)
    private val UAS_ID_TYPES = tokenTable(DetectionData.UasIdType.entries)
    private val UAS_STATUSES = tokenTable(DetectionData.UasStatus.entries)

    /** [value]'s XML token: its name in lowercase snake_case. */
    fun token(value: Enum<*>): String =
        buildString {
            value.name.forEachIndexed { i, c ->
                if (i > 0 && c.isUpperCase()) append('_')
                append(c.lowercaseChar())
            }
        }

    /**
     * The [DetectionData] for one element, reading its attributes through [attr] (null when
     * absent). [pointCe] is the event's `<point ce>`, used when the element has no `cep`.
     */
    fun parse(
        attr: (String) -> String?,
        pointCe: String?,
    ): DetectionData {
        val kind = lookup(attr("kind"), KINDS) ?: DetectionData.Kind.Unspecified
        return DetectionData(
            kind = kind,
            sources = attr("sources")?.let(::sourceBits) ?: 0,
            confidence = number(attr("confidence"))?.coerceIn(0, CONFIDENCE_MAX) ?: 0,
            state = lookup(attr("state"), STATES) ?: DetectionData.State.Unspecified,
            trend = lookup(attr("trend"), TRENDS) ?: DetectionData.Trend.Unspecified,
            count = number(attr("count"))?.coerceAtLeast(0) ?: 0,
            sensorAnchored = attr("sensorAnchored")?.trim().equals("true", ignoreCase = true),
            cepMeters = (number(attr("cep")) ?: number(pointCe)?.takeIf { it < UNKNOWN_CE })?.coerceAtLeast(0) ?: 0,
            rssiDbm = number(attr("rssi")) ?: 0,
            uasId = if (kind == DetectionData.Kind.Uas) attr("uasId").orEmpty().takeCodePoints(UAS_ID_MAX_CHARS) else "",
            uasIdType = lookup(attr("uasIdType"), UAS_ID_TYPES) ?: DetectionData.UasIdType.Unspecified,
            uasStatus = lookup(attr("uasStatus"), UAS_STATUSES) ?: DetectionData.UasStatus.Unspecified,
        )
    }

    /** The `ce` for the event's `<point>`: the detection's cep when it has one, else unknown. */
    fun pointCe(detection: DetectionData?): Int = detection?.cepMeters?.takeIf { it > 0 } ?: UNKNOWN_CE

    /** Appends the element as a `<detail>` child line, escaping `uasId` with [esc]. */
    fun append(
        sb: StringBuilder,
        detection: DetectionData,
        esc: (String) -> String,
    ) {
        sb.append("    <").append(ELEMENT)
        if (detection.kind != DetectionData.Kind.Unspecified) sb.attr("kind", token(detection.kind))
        val sources =
            DetectionData.Source.entries
                .filter { detection.hasSource(it) }
                .joinToString(" ") { token(it) }
        if (sources.isNotEmpty()) sb.attr("sources", sources)
        val confidence = detection.confidence.coerceIn(0, CONFIDENCE_MAX)
        if (confidence > 0) sb.attr("confidence", confidence.toString())
        if (detection.state != DetectionData.State.Unspecified) sb.attr("state", token(detection.state))
        if (detection.trend != DetectionData.Trend.Unspecified) sb.attr("trend", token(detection.trend))
        if (detection.count > 0) sb.attr("count", detection.count.toString())
        if (detection.sensorAnchored) sb.attr("sensorAnchored", "true")
        if (detection.cepMeters > 0) sb.attr("cep", detection.cepMeters.toString())
        if (detection.rssiDbm != 0) sb.attr("rssi", detection.rssiDbm.toString())
        val uasId = if (detection.kind == DetectionData.Kind.Uas) detection.uasId.takeCodePoints(UAS_ID_MAX_CHARS) else ""
        if (uasId.isNotEmpty()) sb.attr("uasId", esc(uasId))
        if (detection.uasIdType != DetectionData.UasIdType.Unspecified) sb.attr("uasIdType", token(detection.uasIdType))
        if (detection.uasStatus != DetectionData.UasStatus.Unspecified) sb.attr("uasStatus", token(detection.uasStatus))
        sb.append("/>\n")
    }

    private fun <E : Enum<E>> tokenTable(entries: List<E>): Map<String, E> = entries.associateBy { token(it) }

    private fun <E> lookup(
        raw: String?,
        table: Map<String, E>,
    ): E? = raw?.trim()?.lowercase()?.let { table[it] }

    private fun sourceBits(raw: String): Int = raw.trim().split(SPACES).fold(0) { bits, t -> bits or (SOURCES[t.lowercase()]?.value ?: 0) }

    /** A plain decimal rounded the way [CotXmlParser] rounds (half up), or null. */
    private fun number(raw: String?): Int? =
        raw
            ?.trim()
            ?.takeIf { DECIMAL.matches(it) }
            ?.toDouble()
            ?.takeIf { it.isFinite() }
            ?.roundToInt()

    private fun StringBuilder.attr(
        name: String,
        value: String,
    ) {
        append(' ')
            .append(name)
            .append("=\"")
            .append(value)
            .append('"')
    }

    /** The first [n] Unicode code points, never splitting a surrogate pair. */
    private fun String.takeCodePoints(n: Int): String {
        var end = 0
        var taken = 0
        while (end < length && taken < n) {
            end += if (this[end].isHighSurrogate() && end + 1 < length && this[end + 1].isLowSurrogate()) 2 else 1
            taken++
        }
        return substring(0, end)
    }
}
