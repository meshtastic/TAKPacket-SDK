package org.meshtastic.tak

/**
 * Loads and provides zstd compression dictionaries.
 *
 * Dictionary bytes are loaded via the platform-specific [DictionaryLoader].
 */
public object DictionaryProvider {

    internal const val DICT_ID_NON_AIRCRAFT = 0
    internal const val DICT_ID_AIRCRAFT = 1
    internal const val DICT_ID_UNCOMPRESSED = 0xFF

    internal val nonAircraftDict: ByteArray by lazy {
        DictionaryLoader.loadDictionary("dict_non_aircraft.zstd")
    }

    internal val aircraftDict: ByteArray by lazy {
        DictionaryLoader.loadDictionary("dict_aircraft.zstd")
    }

    /**
     * Get the dictionary bytes for a given dictionary ID.
     *
     * @throws IllegalArgumentException for unknown dictionary IDs
     */
    internal fun getDictionary(dictId: Int): ByteArray = when (dictId) {
        DICT_ID_NON_AIRCRAFT -> nonAircraftDict
        DICT_ID_AIRCRAFT -> aircraftDict
        else -> throw IllegalArgumentException("Unknown dictionary ID: $dictId")
    }

    /**
     * Select the appropriate dictionary ID for a given CoT type.
     */
    internal fun selectDictId(cotTypeId: Int, cotTypeStr: String?): Int {
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
}
