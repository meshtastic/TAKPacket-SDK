package org.meshtastic.tak

/**
 * Loads and provides zstd compression dictionaries.
 *
 * The raw dictionary bytes are fetched through the internal [DictionaryLoader]
 * SPI (JVM = classpath resources; other targets embed the bytes in a later
 * stage). This provider owns the canonical resource names, the dictionary-ID
 * constants, and the aircraft-vs-non-aircraft selection rule.
 */
public object DictionaryProvider {

    public const val DICT_ID_NON_AIRCRAFT: Int = 0
    public const val DICT_ID_AIRCRAFT: Int = 1
    public const val DICT_ID_UNCOMPRESSED: Int = 0xFF

    public val nonAircraftDict: ByteArray by lazy {
        DictionaryLoader.loadDictionary("dict_non_aircraft.zstd")
    }

    public val aircraftDict: ByteArray by lazy {
        DictionaryLoader.loadDictionary("dict_aircraft.zstd")
    }

    /**
     * Get the dictionary bytes for a given dictionary ID.
     * Returns null for DICT_ID_UNCOMPRESSED or unknown IDs.
     */
    public fun getDictionary(dictId: Int): ByteArray? = when (dictId) {
        DICT_ID_NON_AIRCRAFT -> nonAircraftDict
        DICT_ID_AIRCRAFT -> aircraftDict
        else -> null
    }

    /**
     * Select the appropriate dictionary ID for a given CoT type.
     */
    public fun selectDictId(cotTypeId: Int, cotTypeStr: String?): Int {
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
