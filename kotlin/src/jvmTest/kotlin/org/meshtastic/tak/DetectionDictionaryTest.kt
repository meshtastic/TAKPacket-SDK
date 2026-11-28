package org.meshtastic.tak

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which dictionary a detection is compressed with. [DictionaryProvider.selectDictId] picks by CoT
 * type, which would send an `a-u-A-…` UAS marker with the 4 KB aircraft dictionary. The compressor
 * sends a detection that is not an aircraft track with the non-aircraft dictionary instead, and an
 * aircraft track keeps the aircraft one.
 */
class DetectionDictionaryTest {
    private val parser = CotXmlParser()
    private val compressor = TakCompressor()

    private fun fixture(name: String): TakPacketV2Data = parser.parse(TestFixtures.loadFixture("$name.xml"))

    private fun wireDictId(packet: TakPacketV2Data): Int = compressor.compress(packet)[0].toInt() and 0xFF

    @Test
    fun `a detection marker uses the non-aircraft dictionary whatever its type`() {
        val uas = fixture("detection_uas_remoteid")
        assertEquals(DictionaryProvider.DICT_ID_AIRCRAFT, DictionaryProvider.selectDictId(uas.cotTypeId, uas.cotTypeStr))
        assertEquals(DictionaryProvider.DICT_ID_NON_AIRCRAFT, wireDictId(uas))
    }

    @Test
    fun `the same marker without its detection keeps the aircraft dictionary`() {
        val uas = fixture("detection_uas_remoteid")
        assertEquals(DictionaryProvider.DICT_ID_AIRCRAFT, wireDictId(uas.copy(detection = null)))
    }

    @Test
    fun `an aircraft track keeps the aircraft dictionary with a detection`() {
        val detection = checkNotNull(fixture("detection_uas_remoteid").detection)
        val track = fixture("aircraft_adsb")
        assertTrue(track.payload is TakPacketV2Data.Payload.Aircraft)
        assertEquals(DictionaryProvider.DICT_ID_AIRCRAFT, wireDictId(track.copy(detection = detection)))
    }
}
