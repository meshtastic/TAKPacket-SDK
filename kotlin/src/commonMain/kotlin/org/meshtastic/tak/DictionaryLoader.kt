package org.meshtastic.tak

/**
 * Platform-specific dictionary resource loader.
 *
 * JVM: loads from classpath resources.
 * iOS: loads from app bundle (not yet implemented).
 */
expect object DictionaryLoader {
    fun loadDictionary(name: String): ByteArray
}
