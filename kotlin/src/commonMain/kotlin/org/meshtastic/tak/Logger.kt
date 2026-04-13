package org.meshtastic.tak

import kotlin.concurrent.Volatile

/**
 * Single-method logging interface for SDK diagnostic output.
 *
 * Consumers wire this to their platform logger via SAM conversion:
 * ```kotlin
 * TakPacketSdk.logger = Logger { msg -> Log.d("TAKPacket", msg) }
 * ```
 *
 * The SDK emits trace-level messages only — there are no severity levels.
 * When [NoOpLogger] is installed (the default), **zero** string
 * concatenation or lambda allocation occurs because [trace] is `inline`
 * and checks the logger identity before evaluating its argument.
 *
 * @see TakPacketSdk
 * @see NoOpLogger
 */
public fun interface Logger {
    /** Emit a single diagnostic trace line. */
    public fun log(message: String)
}

/**
 * Default no-op logger that discards all messages.
 *
 * Used as the identity sentinel in [trace] — when this instance is
 * installed, the message lambda is never evaluated.
 */
public object NoOpLogger : Logger {
    override fun log(message: String) { /* intentionally empty */ }
}

/**
 * Global SDK configuration point.
 *
 * ```kotlin
 * // Enable diagnostic logging
 * TakPacketSdk.logger = Logger { println(it) }
 *
 * // Disable (default)
 * TakPacketSdk.logger = NoOpLogger
 * ```
 *
 * @see Logger
 * @see NoOpLogger
 */
public object TakPacketSdk {
    /**
     * The active [Logger] instance. Defaults to [NoOpLogger].
     *
     * Swapping loggers at runtime is safe — the field is `@Volatile` so
     * the new value is visible to all threads immediately.
     */
    @Volatile
    public var logger: Logger = NoOpLogger
}

/**
 * Emit a trace message if a real logger is installed.
 *
 * This function is `inline` so that when [TakPacketSdk.logger] is
 * [NoOpLogger] (the default), the [message] lambda is **never
 * evaluated** — no string concatenation or allocation occurs.
 *
 * @param message lazy message supplier, evaluated only when logging is active
 */
@PublishedApi
internal inline fun trace(message: () -> String) {
    val l = TakPacketSdk.logger
    if (l !== NoOpLogger) {
        l.log(message())
    }
}
