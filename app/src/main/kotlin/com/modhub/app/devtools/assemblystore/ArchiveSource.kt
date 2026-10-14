package com.modhub.app.devtools.assemblystore

import java.io.Closeable
import java.io.File
import java.util.zip.ZipFile

/** Read-only view of an APK/zip: entry names in archive order and their contents. */
interface ArchiveSource : Closeable {
    val entryNames: List<String>
    fun read(name: String): ByteArray

    override fun close() {}
}

class ZipArchiveSource(file: File) : ArchiveSource {
    private val zip = ZipFile(file)
    override val entryNames: List<String> = zip.entries().asSequence().filter { !it.isDirectory }.map { it.name }.toList()

    override fun read(name: String): ByteArray {
        val entry = zip.getEntry(name) ?: throw java.io.FileNotFoundException(name)
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    override fun close() = zip.close()
}

/** In-memory archive (tests). */
class MapArchiveSource(private val entries: Map<String, ByteArray>) : ArchiveSource {
    override val entryNames: List<String> = entries.keys.toList()
    override fun read(name: String): ByteArray = entries[name] ?: throw java.io.FileNotFoundException(name)
}
