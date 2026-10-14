package com.modhub.core

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Builds an in-memory zip. Keys ending in '/' become directory entries. */
fun zipOf(vararg entries: Pair<String, String>): ByteArray {
    val bos = ByteArrayOutputStream()
    ZipOutputStream(bos).use { zip ->
        for ((name, content) in entries) {
            zip.putNextEntry(ZipEntry(name))
            if (!name.endsWith("/")) zip.write(content.toByteArray())
            zip.closeEntry()
        }
    }
    return bos.toByteArray()
}
