package com.modhub.core.util

import com.modhub.core.storage.StorageBackend
import com.modhub.core.storage.StoragePath
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** Thrown when an archive entry would be written outside the destination ("zip slip"). */
class ZipSlipException(val entryName: String) :
    IOException("Archive entry escapes destination directory: '$entryName'")

/** Thrown when extraction exceeds [ZipExtractor.Limits]. */
class ArchiveTooLargeException(message: String) : IOException(message)

data class ArchiveEntry(
    /** Normalised, '/'-separated relative path (directories have no trailing slash). */
    val path: String,
    val isDirectory: Boolean,
    /** Uncompressed size from the header; -1 if unknown. */
    val size: Long,
)

data class ExtractResult(
    val destination: StoragePath,
    /** Paths written, relative to [destination]. */
    val files: List<String>,
    val directories: List<String>,
    val totalBytes: Long,
)

/**
 * Streaming ZIP extraction through a [StorageBackend], using only `java.util.zip`.
 *
 * Security: every entry name is normalised; absolute paths, drive letters and any
 * `..` that climbs above the destination cause a [ZipSlipException] *before* anything
 * is written for that entry. Size/count limits guard against zip bombs.
 */
object ZipExtractor {

    data class Limits(
        val maxEntries: Int = 20_000,
        val maxTotalBytes: Long = 2L * 1024 * 1024 * 1024, // 2 GiB
    )

    /**
     * Validates and normalises a raw entry name. Returns the safe relative path, or ""
     * for an entry that refers to the root itself. Throws [ZipSlipException] if unsafe.
     */
    fun safeEntryPath(rawName: String): String {
        val name = rawName.replace('\\', '/')
        if (name.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(name) || name.contains('\u0000')) {
            throw ZipSlipException(rawName)
        }
        return StoragePath.normaliseRelative(name) ?: throw ZipSlipException(rawName)
    }

    /** Lists entries (validating each name). Ignores OS junk such as `__MACOSX/`. */
    fun listEntries(input: InputStream, limits: Limits = Limits()): List<ArchiveEntry> {
        val out = mutableListOf<ArchiveEntry>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                val path = safeEntryPath(e.name)
                if (path.isNotEmpty() && !isJunk(path)) out += ArchiveEntry(path, e.isDirectory, e.size)
                if (out.size > limits.maxEntries) throw ArchiveTooLargeException("More than ${limits.maxEntries} entries")
            }
        }
        return out
    }

    /**
     * Extracts [input] into [destination].
     *
     * @param pathMapper maps each safe relative entry path to the relative path to write,
     *   or null to skip it. Plugins use this to strip wrapper folders or pick sub-mods.
     *   The mapped path is re-validated, so a mapper cannot introduce zip slip either.
     */
    suspend fun extract(
        input: InputStream,
        storage: StorageBackend,
        destination: StoragePath,
        limits: Limits = Limits(),
        pathMapper: (String) -> String? = { it },
    ): ExtractResult {
        val files = mutableListOf<String>()
        val dirs = mutableListOf<String>()
        var total = 0L
        var count = 0
        storage.mkdirs(destination)
        ZipInputStream(input).use { zip ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val e = zip.nextEntry ?: break
                if (++count > limits.maxEntries) throw ArchiveTooLargeException("More than ${limits.maxEntries} entries")
                val safe = safeEntryPath(e.name)
                if (safe.isEmpty() || isJunk(safe)) continue
                val mapped = pathMapper(safe) ?: continue
                val rel = safeEntryPath(mapped)
                if (rel.isEmpty()) continue
                val target = destination.resolve(rel)
                if (!target.isWithin(destination)) throw ZipSlipException(e.name) // belt and braces
                if (e.isDirectory) {
                    storage.mkdirs(target)
                    dirs += rel
                } else {
                    target.parent?.let { storage.mkdirs(it) }
                    storage.openWrite(target).use { out ->
                        while (true) {
                            val n = zip.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > limits.maxTotalBytes) {
                                throw ArchiveTooLargeException("Archive expands beyond ${limits.maxTotalBytes} bytes")
                            }
                            out.write(buf, 0, n)
                        }
                    }
                    files += rel
                }
            }
        }
        return ExtractResult(destination, files, dirs, total)
    }

    private fun isJunk(path: String): Boolean =
        path == "__MACOSX" || path.startsWith("__MACOSX/") ||
            path.substringAfterLast('/').let { it == ".DS_Store" || it == "Thumbs.db" }
}
