package org.meshtastic.tak

/**
 * JVM [DictionaryLoader] actual: reads the shipped dictionaries from the
 * classpath (`kotlin/src/jvmMain/resources/dict_*.zstd`).
 */
internal actual object DictionaryLoader {
    actual fun loadDictionary(name: String): ByteArray {
        val stream = DictionaryLoader::class.java.classLoader?.getResourceAsStream(name)
        checkNotNull(stream) { "Dictionary resource not found: $name" }
        return stream.use { it.readBytes() }
    }
}
