package org.meshtastic.tak

import kotlin.io.encoding.Base64

/**
 * iOS implementation of [DictionaryLoader] using embedded Base64-encoded dictionaries.
 *
 * Since Kotlin/Native has no classpath resource loading like JVM,
 * the dictionary data is embedded directly in the binary.
 */
internal actual object DictionaryLoader {
    actual fun loadDictionary(name: String): ByteArray = when (name) {
        "dict_aircraft.zstd" -> Base64.decode(EmbeddedDictionaries.AIRCRAFT_BASE64)
        "dict_non_aircraft.zstd" -> Base64.decode(EmbeddedDictionaries.NON_AIRCRAFT_BASE64)
        else -> throw IllegalStateException("Unknown dictionary resource: $name")
    }
}
