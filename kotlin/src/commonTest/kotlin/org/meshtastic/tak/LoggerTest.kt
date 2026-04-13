package org.meshtastic.tak

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Tests for the internal diagnostic logging system.
 * Verifies that [Logger], [NoOpLogger], and [TakPacketSdk] work correctly,
 * and that [trace] calls in SDK components emit messages when a real logger
 * is installed.
 */
class LoggerTest {

    @AfterTest
    fun resetLogger() {
        TakPacketSdk.logger = NoOpLogger
    }

    @Test
    fun defaultLoggerIsNoOp() {
        assertIs<NoOpLogger>(TakPacketSdk.logger)
    }

    @Test
    fun noOpLoggerDoesNotCapture() {
        // With NoOpLogger installed, trace should not evaluate the lambda
        var evaluated = false
        trace { evaluated = true; "should not appear" }
        assertEquals(false, evaluated, "trace lambda should not be evaluated with NoOpLogger")
    }

    @Test
    fun customLoggerCapturesMessages() {
        val captured = mutableListOf<String>()
        TakPacketSdk.logger = Logger { captured.add(it) }

        trace { "hello from trace" }

        assertEquals(1, captured.size)
        assertEquals("hello from trace", captured[0])
    }

    @Test
    fun parserEmitsTraceMessages() {
        val captured = mutableListOf<String>()
        TakPacketSdk.logger = Logger { captured.add(it) }

        val parser = CotXmlParser()
        parser.parse(InlinedFixtures.PLI_BASIC)

        // Should have at least 2 messages: input length + resolved payload type
        assertTrue(captured.size >= 2, "Expected at least 2 trace messages, got ${captured.size}")
        assertTrue(
            captured.any { it.contains("CotXmlParser.parse") && it.contains("input") },
            "Expected a CotXmlParser.parse input trace message",
        )
        assertTrue(
            captured.any { it.contains("CotXmlParser.parse") && it.contains("payload") },
            "Expected a CotXmlParser.parse payload trace message",
        )
    }

    @Test
    fun serializerEmitsTraceMessages() {
        val captured = mutableListOf<String>()
        TakPacketSdk.logger = Logger { captured.add(it) }

        val data = TakPacketV2Data(
            cotTypeId = CotTypeMapper.COTTYPE_A_F_G_U_C,
            how = CotTypeMapper.COTHOW_M_G,
            callsign = "TEST",
            uid = "test-uid",
            latitudeI = 377749000,
            longitudeI = -1224194000,
            payload = TakPacketV2Data.Payload.Pli(true),
        )

        val bytes = TakPacketV2Serializer.serialize(data)
        assertTrue(
            captured.any { it.contains("TakPacketV2Serializer.serialize") },
            "Expected a serialize trace message",
        )

        captured.clear()
        TakPacketV2Serializer.deserialize(bytes)
        assertTrue(
            captured.any { it.contains("TakPacketV2Serializer.deserialize") && it.contains("input") },
            "Expected a deserialize input trace message",
        )
        assertTrue(
            captured.any { it.contains("TakPacketV2Serializer.deserialize") && it.contains("payload") },
            "Expected a deserialize payload trace message",
        )
    }

    @Test
    fun builderEmitsTraceMessages() {
        val captured = mutableListOf<String>()
        TakPacketSdk.logger = Logger { captured.add(it) }

        val parser = CotXmlParser()
        val data = parser.parse(InlinedFixtures.PLI_FULL)

        captured.clear()
        val builder = CotXmlBuilder()
        builder.build(data)

        assertTrue(
            captured.any { it.contains("CotXmlBuilder.build") && it.contains("uid=") },
            "Expected a CotXmlBuilder.build input trace message",
        )
        assertTrue(
            captured.any { it.contains("CotXmlBuilder.build") && it.contains("output") },
            "Expected a CotXmlBuilder.build output trace message",
        )
    }

    @Test
    fun loggerCanBeSwappedAtRuntime() {
        val captured1 = mutableListOf<String>()
        val captured2 = mutableListOf<String>()

        TakPacketSdk.logger = Logger { captured1.add(it) }
        trace { "first" }

        TakPacketSdk.logger = Logger { captured2.add(it) }
        trace { "second" }

        assertEquals(listOf("first"), captured1)
        assertEquals(listOf("second"), captured2)
    }

    @Test
    fun resetToNoOpStopsCapturing() {
        val captured = mutableListOf<String>()
        TakPacketSdk.logger = Logger { captured.add(it) }
        trace { "captured" }
        assertEquals(1, captured.size)

        TakPacketSdk.logger = NoOpLogger
        trace { "not captured" }
        assertEquals(1, captured.size, "No new messages after resetting to NoOpLogger")
    }
}
