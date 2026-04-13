package org.meshtastic.tak

/**
 * Platform-specific dictionary resource loader.
 *
 * JVM: loads from classpath resources.
 * iOS: loads embedded Base64-encoded dictionaries.
 */
internal expect object DictionaryLoader {
    fun loadDictionary(name: String): ByteArray
}
