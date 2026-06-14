package org.meshtastic.tak

/**
 * Kotlin/Wasm-WASI [DictionaryLoader] actual.
 *
 * WASI has no classpath resource loading, so the canonical dictionary bytes are
 * read from the generated [EmbeddedDictionaries] object (emitted by the
 * `generateEmbeddedDictionaries` Gradle task from
 * `kotlin/src/jvmMain/resources/dict_*.zstd`, the single source of truth, onto
 * the `wasmWasiMain` source set). The pure-Kotlin decoder consumes these bytes
 * to decompress wire frames on wasmWasi.
 */
internal actual object DictionaryLoader {
    actual fun loadDictionary(name: String): ByteArray = when (name) {
        "dict_non_aircraft.zstd" -> EmbeddedDictionaries.nonAircraft()
        "dict_aircraft.zstd" -> EmbeddedDictionaries.aircraft()
        else -> throw IllegalStateException("Unknown dictionary resource: $name")
    }
}
