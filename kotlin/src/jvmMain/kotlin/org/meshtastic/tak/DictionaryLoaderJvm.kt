package org.meshtastic.tak

/**
 * JVM implementation of [DictionaryLoader] using classpath resources.
 */
internal actual object DictionaryLoader {
    actual fun loadDictionary(name: String): ByteArray {
        val stream = checkNotNull(DictionaryLoader::class.java.classLoader?.getResourceAsStream(name)) {
            "Dictionary resource not found: $name"
        }
        return stream.use { it.readBytes() }
    }
}
