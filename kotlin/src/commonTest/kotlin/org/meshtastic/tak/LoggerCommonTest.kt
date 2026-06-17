package org.meshtastic.tak

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Multiplatform (`kotlin.test`) coverage of the diagnostics facility.
 *
 *  - [NoOpLogger] is the default and the inline [trace] short-circuit MUST NOT
 *    evaluate the message lambda when it is installed (the LoRa hot-path
 *    zero-allocation contract).
 *  - A custom [Logger] installed via [TakPacketSdk.logger] receives every traced
 *    message, and then the lambda IS evaluated.
 *
 * Runs on every target (no codec, no I/O) — it guards the Logger contract on
 * jvm + the 9 native targets + js + wasmJs + wasmWasi.
 */
class LoggerCommonTest {

    @AfterTest
    fun restoreDefault() {
        // Logger is a process-global; always leave it back at the no-op default
        // so test ordering can't leak a capturing logger into another suite.
        TakPacketSdk.logger = NoOpLogger
    }

    @Test
    fun noOpLoggerNeverEvaluatesTheTraceLambda() {
        TakPacketSdk.logger = NoOpLogger
        var evaluated = false
        trace {
            evaluated = true
            "expensive message that must never be built"
        }
        assertFalse(evaluated, "trace{} must not evaluate its lambda when NoOpLogger is installed")
    }

    @Test
    fun customLoggerCapturesEveryTracedMessage() {
        val captured = mutableListOf<String>()
        TakPacketSdk.logger = Logger { captured += it }

        var evaluated = false
        trace {
            evaluated = true
            "hello"
        }
        trace { "world" }

        assertTrue(evaluated, "trace{} must evaluate its lambda when a real logger is installed")
        assertEquals(listOf("hello", "world"), captured, "logger must receive every traced message in order")
    }

    @Test
    fun loggerDirectLogPassesMessageThrough() {
        val captured = mutableListOf<String>()
        val logger: Logger = Logger { captured += it }
        logger.log("direct")
        assertEquals(listOf("direct"), captured)
    }
}
