package org.meshtastic.tak

/**
 * Loads and provides zstd compression dictionaries.
 *
 * The raw dictionary bytes are fetched through the internal [DictionaryLoader]
 * (a single commonMain object backed by the generated `EmbeddedDictionaries`,
 * uniform across every target as of v0.6.0). This provider owns the canonical
 * resource names, the dictionary-ID constants, and the aircraft-vs-non-aircraft
 * selection rule.
 */
public object DictionaryProvider {
    /**
     * Dictionary ID for the 512 KB non-aircraft, proto-trained dictionary —
     * the default for PLI, chat, ground units, shapes, markers, routes, etc.
     * Encoded in flags-byte bits 0–5 of the wire payload.
     */
    public const val DICT_ID_NON_AIRCRAFT: Int = 0

    /**
     * Dictionary ID for the 4 KB aircraft dictionary, used for Air-domain CoT
     * types (3rd type atom = `A`). Encoded in flags-byte bits 0–5.
     */
    public const val DICT_ID_AIRCRAFT: Int = 1

    /**
     * Reserved flags-byte value (`0xFF`) meaning the payload is raw,
     * uncompressed `TAKPacketV2` protobuf — no zstd, no dictionary. Emitted by
     * the encoder's skip-compress path and by TAK_TRACKER firmware.
     */
    public const val DICT_ID_UNCOMPRESSED: Int = 0xFF

    /** Lazily-loaded bytes of the non-aircraft dictionary ([DICT_ID_NON_AIRCRAFT]). */
    public val nonAircraftDict: ByteArray by lazy {
        DictionaryLoader.loadDictionary("dict_non_aircraft.zstd")
    }

    /** Lazily-loaded bytes of the aircraft dictionary ([DICT_ID_AIRCRAFT]). */
    public val aircraftDict: ByteArray by lazy {
        DictionaryLoader.loadDictionary("dict_aircraft.zstd")
    }

    /**
     * Return the dictionary bytes for a dictionary ID.
     *
     * @param dictId [DICT_ID_NON_AIRCRAFT] or [DICT_ID_AIRCRAFT].
     * @return the dictionary bytes, or `null` for [DICT_ID_UNCOMPRESSED] and any
     *         unknown ID (the decoder treats a `null` here as "unknown
     *         dictionary" and rejects the frame).
     */
    public fun getDictionary(dictId: Int): ByteArray? =
        when (dictId) {
            DICT_ID_NON_AIRCRAFT -> nonAircraftDict
            DICT_ID_AIRCRAFT -> aircraftDict
            else -> null
        }

    /**
     * Pick the dictionary ID to compress a packet with, based on its CoT type.
     *
     * Prefers the enum classification ([CotTypeMapper.isAircraft]); when the
     * type is [CotTypeMapper.COTTYPE_OTHER] it falls back to the raw type string
     * ([CotTypeMapper.isAircraftString]). Air-domain types map to
     * [DICT_ID_AIRCRAFT], everything else to [DICT_ID_NON_AIRCRAFT].
     *
     * @param cotTypeId the packet's `cot_type_id` enum value.
     * @param cotTypeStr the raw CoT type string, used only when [cotTypeId] is
     *        [CotTypeMapper.COTTYPE_OTHER]; may be `null`.
     * @return [DICT_ID_AIRCRAFT] or [DICT_ID_NON_AIRCRAFT].
     */
    public fun selectDictId(
        cotTypeId: Int,
        cotTypeStr: String?,
    ): Int {
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
