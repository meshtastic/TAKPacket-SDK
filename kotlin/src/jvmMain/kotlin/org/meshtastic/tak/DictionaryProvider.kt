package org.meshtastic.tak

/**
 * Loads and provides zstd compression dictionaries embedded as classpath resources.
 */
object DictionaryProvider {

    const val DICT_ID_NON_AIRCRAFT = 0
    const val DICT_ID_AIRCRAFT = 1
    const val DICT_ID_UNCOMPRESSED = 0xFF

    val nonAircraftDict: ByteArray by lazy {
        loadResource("dict_non_aircraft.zstd")
    }

    val aircraftDict: ByteArray by lazy {
        loadResource("dict_aircraft.zstd")
    }

    /**
     * Get the dictionary bytes for a given dictionary ID.
     * Returns null for DICT_ID_UNCOMPRESSED or unknown IDs.
     */
    fun getDictionary(dictId: Int): ByteArray? = when (dictId) {
        DICT_ID_NON_AIRCRAFT -> nonAircraftDict
        DICT_ID_AIRCRAFT -> aircraftDict
        else -> null
    }

    /**
     * Select the appropriate dictionary ID for a given CoT type.
     */
    fun selectDictId(cotTypeId: Int, cotTypeStr: String?): Int {
        // Check enum-based classification first
        if (cotTypeId != CotTypeMapper.COTTYPE_OTHER) {
            return if (CotTypeMapper.isAircraft(cotTypeId)) DICT_ID_AIRCRAFT else DICT_ID_NON_AIRCRAFT
        }
        // Fall back to string-based classification
        if (cotTypeStr != null && CotTypeMapper.isAircraftString(cotTypeStr)) {
            return DICT_ID_AIRCRAFT
        }
        return DICT_ID_NON_AIRCRAFT
    }

    private fun loadResource(name: String): ByteArray {
        val stream = DictionaryProvider::class.java.classLoader?.getResourceAsStream(name)
            ?: throw IllegalStateException("Dictionary resource not found: $name")
        return stream.use { it.readBytes() }
    }
}
