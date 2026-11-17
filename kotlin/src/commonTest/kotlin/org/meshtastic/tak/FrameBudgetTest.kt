package org.meshtastic.tak

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * [TakFragment.maxFrameBytes] against the firmware's own arithmetic.
 *
 * These numbers are a contract with `Router.cpp`, not a property of this code, so they are pinned
 * literally. A frame one byte over the AEAD ceiling is refused as `TOO_LARGE` by the sending
 * firmware, which is invisible from the receiving end and easy to mistake for a radio problem.
 */
class FrameBudgetTest {

    @Test
    fun plain_channel_allows_232() {
        // 255 radio - 16 header - 5 Data framing - 2 bitfield
        assertEquals(232, TakFragment.maxFrameBytes())
    }

    @Test
    fun aead_channel_allows_220() {
        // ...minus the 12-byte AES-CCM tag.
        assertEquals(220, TakFragment.maxFrameBytes(aead = true))
    }

    @Test
    fun signing_costs_66_bytes_wherever_it_applies() {
        assertEquals(166, TakFragment.maxFrameBytes(signed = true))
        assertEquals(154, TakFragment.maxFrameBytes(aead = true, signed = true))
    }

    @Test
    fun a_caller_that_builds_Data_itself_keeps_the_bitfield_bytes() {
        // Transparent mode: the app composes and encrypts Data and the firmware sends it verbatim,
        // so no bitfield is added and two bytes stay available.
        assertEquals(222, TakFragment.maxFrameBytes(aead = true, firmwareSetsBitfield = false))
        // Plain and self-composed computes to 234, but Data.payload is capped at 233, so the
        // protobuf ceiling binds first. That clamp is the reason for the min().
        assertEquals(TakFragment.MAX_FRAME_BYTES, TakFragment.maxFrameBytes(firmwareSetsBitfield = false))
    }

    @Test
    fun never_exceeds_the_protobuf_payload_ceiling() {
        // Data.payload is capped at 233 in mesh.options whatever the radio could carry.
        assertTrue(TakFragment.maxFrameBytes(firmwareSetsBitfield = false) <= TakFragment.MAX_FRAME_BYTES)
    }

    @Test
    fun the_client_default_is_unsafe_on_an_aead_channel() {
        // Pins why maxFrameBytes exists at all: the convention both clients use is above the
        // ceiling an AEAD channel actually admits.
        assertTrue(TakFragment.DEFAULT_MAX_FRAME_BYTES > TakFragment.maxFrameBytes(aead = true))
    }
}
