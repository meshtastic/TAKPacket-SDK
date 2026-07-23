package org.meshtastic.tak

/**
 * Loads the shipped zstd dictionary bytes — ONE pure-Kotlin implementation on
 * every target.
 *
 * As of v0.6.0 the codec is pure-Kotlin on every binding, so the dictionaries
 * are embedded uniformly (no classpath resources, no per-target actual): the
 * bytes come from the generated [EmbeddedDictionaries] object, which the
 * `generateEmbeddedDictionaries` Gradle task emits onto `commonMain` from
 * `kotlin/src/jvmMain/resources/dict_*.zstd` (the single source of truth).
 * [DictionaryProvider] sits above this loader and maps dictionary IDs to the
 * canonical resource names.
 */
internal object DictionaryLoader {
    /**
     * Load the raw bytes of the dictionary resource named [name]
     * (e.g. `"dict_non_aircraft.zstd"`).
     */
    fun loadDictionary(name: String): ByteArray =
        when (name) {
            "dict_non_aircraft.zstd" -> EmbeddedDictionaries.nonAircraft()
            "dict_aircraft.zstd" -> EmbeddedDictionaries.aircraft()
            else -> throw IllegalStateException("Unknown dictionary resource: $name")
        }
}
