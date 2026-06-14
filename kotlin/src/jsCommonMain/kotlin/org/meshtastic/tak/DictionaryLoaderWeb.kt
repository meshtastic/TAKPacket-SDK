package org.meshtastic.tak

/**
 * Web ([js] + [wasmJs]) [DictionaryLoader] actual, shared via `jsCommonMain`.
 *
 * The browser / Node environment has no classpath resources, so the canonical
 * dictionary bytes are read from the generated [EmbeddedDictionaries] object
 * (emitted by the `generateEmbeddedDictionaries` Gradle task from
 * `kotlin/src/jvmMain/resources/dict_*.zstd`, the single source of truth, onto
 * the `jsCommonMain` source set). Both the pure-Kotlin decompress path and the
 * @bokuweb compress path consume these bytes.
 */
internal actual object DictionaryLoader {
    actual fun loadDictionary(name: String): ByteArray = when (name) {
        "dict_non_aircraft.zstd" -> EmbeddedDictionaries.nonAircraft()
        "dict_aircraft.zstd" -> EmbeddedDictionaries.aircraft()
        else -> throw IllegalStateException("Unknown dictionary resource: $name")
    }
}
