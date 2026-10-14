package com.modhub.core.install

import java.io.File
import java.io.InputStream

/**
 * A mod archive that can be opened more than once (installers usually inspect the
 * entry list first, then extract).
 */
interface ModArchive {
    /** Original file name, e.g. "ContentPatcher 2.4.0-1915-2-4-0.zip". */
    val fileName: String
    fun open(): InputStream
}

class FileModArchive(val file: File, override val fileName: String = file.name) : ModArchive {
    override fun open(): InputStream = file.inputStream().buffered(256 * 1024)
}

class ByteArrayModArchive(override val fileName: String, private val bytes: ByteArray) : ModArchive {
    override fun open(): InputStream = bytes.inputStream()
}
