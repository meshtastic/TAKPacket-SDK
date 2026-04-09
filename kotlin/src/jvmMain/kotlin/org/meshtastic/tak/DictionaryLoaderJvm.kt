package org.meshtastic.tak

/**
 * JVM implementation of [DictionaryLoader] using classpath resources.
 */
actual object DictionaryLoader {
    actual fun loadDictionary(name: String): ByteArray {
        val stream = DictionaryLoader::class.java.classLoader?.getResourceAsStream(name)
            ?: throw IllegalStateException("Dictionary resource not found: $name")
        return stream.use { it.readBytes() }
    }
}
