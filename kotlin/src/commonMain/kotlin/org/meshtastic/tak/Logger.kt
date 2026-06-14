package org.meshtastic.tak

import kotlin.concurrent.Volatile

/**
 * Sink for the SDK's optional diagnostic traces.
 *
 * The SDK never logs by default — see [NoOpLogger]. A consumer that wants to see
 * internal diagnostics (e.g. which dictionary a packet selected, or that the
 * skip-compress path fired) installs its own implementation via
 * [TakPacketSdk.logger]. This is a `fun interface`, so a lambda is enough:
 *
 * ```
 * TakPacketSdk.logger = Logger { message -> println(message) }
 * ```
 */
public fun interface Logger {
    /** Emit a single diagnostic [message]. */
    public fun log(message: String)
}

/**
 * The default [Logger]: discards every message. Used as a sentinel so [trace]
 * can short-circuit (and skip building the message string) when no real logger
 * is installed.
 */
public object NoOpLogger : Logger {
    override fun log(message: String) {}
}

/**
 * Global, mutable holder for the SDK's diagnostic [Logger].
 *
 * Defaults to [NoOpLogger] (zero output, zero allocation on the hot path).
 * Assign a real [Logger] to surface internal diagnostics. The field is
 * [Volatile] so a logger installed on one thread is visible to packet
 * processing on another.
 */
public object TakPacketSdk {
    @Volatile
    public var logger: Logger = NoOpLogger

    /**
     * Release the codec's cached native/dictionary resources.
     *
     * The [ZstdCodec] caches per-dictionary digested handles (on the JVM these
     * wrap native `ZstdDictCompress`/`ZstdDictDecompress` digest memory; native
     * targets cache cinterop CDict/DDict). They are freed automatically when the
     * process exits, so calling this is **optional**. A long-running consumer
     * that wants to drop the handles early (e.g. before going idle) can call it;
     * the caches simply rebuild lazily on the next compress/decompress.
     */
    public fun releaseCodecResources() {
        ZstdCodec.release()
    }
}

/**
 * Emit a diagnostic trace, evaluating [message] only when a non-default logger
 * is installed. The `inline` + lazy lambda means the no-op path costs nothing
 * (no string construction, no allocation) when logging is off — which is the
 * default and the case on the LoRa hot path.
 */
@PublishedApi
internal inline fun trace(message: () -> String) {
    val l = TakPacketSdk.logger
    if (l !== NoOpLogger) l.log(message())
}
