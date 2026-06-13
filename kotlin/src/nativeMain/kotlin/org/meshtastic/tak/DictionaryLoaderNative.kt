package org.meshtastic.tak

/**
 * Kotlin/Native [DictionaryLoader] actual, shared by all nine native targets.
 *
 * Kotlin/Native has no classpath resource loading, so the canonical dictionary
 * bytes are embedded into the binary by the [EmbeddedDictionaries] object that
 * the `generateEmbeddedDictionaries` Gradle task generates from
 * `kotlin/src/jvmMain/resources/dict_*.zstd` (the single source of truth) onto
 * the `nativeMain` source set.
 */
internal actual object DictionaryLoader {
    actual fun loadDictionary(name: String): ByteArray = when (name) {
        "dict_non_aircraft.zstd" -> EmbeddedDictionaries.nonAircraft()
        "dict_aircraft.zstd" -> EmbeddedDictionaries.aircraft()
        else -> throw IllegalStateException("Unknown dictionary resource: $name")
    }
}
