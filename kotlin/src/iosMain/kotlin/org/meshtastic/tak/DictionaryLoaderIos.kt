package org.meshtastic.tak

/**
 * iOS stub for [DictionaryLoader].
 *
 * TODO: Implement using NSBundle resource loading or embedded byte arrays.
 */
actual object DictionaryLoader {
    actual fun loadDictionary(name: String): ByteArray {
        throw UnsupportedOperationException(
            "Dictionary loading is not yet implemented for iOS. " +
                "Requires NSBundle resource loading via kotlinx.cinterop."
        )
    }
}
