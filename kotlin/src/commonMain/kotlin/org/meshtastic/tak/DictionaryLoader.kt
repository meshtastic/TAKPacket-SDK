package org.meshtastic.tak

/**
 * Internal SPI for loading the shipped zstd dictionary bytes, satisfied by a
 * per-target `actual object`.
 *
 * On JVM the dictionaries are classpath resources; native/JS/Wasm targets will
 * embed the bytes (a later stage). [DictionaryProvider] sits above this SPI and
 * maps dictionary IDs to the canonical resource names.
 */
internal expect object DictionaryLoader {
    /**
     * Load the raw bytes of the dictionary resource named [name]
     * (e.g. `"dict_non_aircraft.zstd"`).
     */
    fun loadDictionary(name: String): ByteArray
}
