package com.modhub.core.storage

import java.io.InputStream
import java.io.OutputStream

/**
 * Abstract, backend-neutral path. Always absolute, '/'-separated, normalised
 * (no `.`/`..`, no trailing slash, no empty segments).
 *
 * What the root means is up to the backend: for file-based backends it is the
 * filesystem root (`/storage/emulated/0/StardewValley/Mods`); for SAF it is
 * resolved against a granted tree.
 */
@JvmInline
value class StoragePath private constructor(val value: String) {

    val name: String get() = value.substringAfterLast('/')

    val parent: StoragePath?
        get() = if (value == "/") null else of(value.substringBeforeLast('/').ifEmpty { "/" })

    val segments: List<String> get() = if (value == "/") emptyList() else value.removePrefix("/").split('/')

    /** Resolves a relative child path. Rejects `..` that would escape this path. */
    fun resolve(relative: String): StoragePath {
        val rel = normaliseRelative(relative)
            ?: throw IllegalArgumentException("Path escapes its parent: '$relative'")
        if (rel.isEmpty()) return this
        return StoragePath(if (value == "/") "/$rel" else "$value/$rel")
    }

    operator fun div(relative: String): StoragePath = resolve(relative)

    fun isWithin(other: StoragePath): Boolean =
        other.value == "/" || value == other.value || value.startsWith(other.value + "/")

    /** Path of this relative to [base], e.g. `/a/b/c` relative to `/a` is `b/c`. */
    fun relativeTo(base: StoragePath): String {
        require(isWithin(base)) { "$this is not within $base" }
        return if (value == base.value) "" else value.removePrefix(if (base.value == "/") "/" else base.value + "/")
    }

    override fun toString(): String = value

    companion object {
        val ROOT = StoragePath("/")

        fun of(path: String): StoragePath {
            val p = path.replace('\\', '/')
            require(p.startsWith("/")) { "StoragePath must be absolute: '$path'" }
            val rel = normaliseRelative(p) ?: throw IllegalArgumentException("Path escapes root: '$path'")
            return StoragePath("/$rel")
        }

        /**
         * Normalises a relative path. Returns null if `..` would climb above the start.
         * Leading slashes are ignored (treated as relative).
         */
        fun normaliseRelative(path: String): String? {
            val out = ArrayDeque<String>()
            for (seg in path.replace('\\', '/').split('/')) {
                when (seg) {
                    "", "." -> Unit
                    ".." -> if (out.isEmpty()) return null else out.removeLast()
                    else -> out.addLast(seg)
                }
            }
            return out.joinToString("/")
        }
    }
}

data class StorageEntry(
    val path: StoragePath,
    val isDirectory: Boolean,
    val size: Long = 0L,
    /** Epoch millis, 0 if unknown. */
    val lastModified: Long = 0L,
) {
    val name: String get() = path.name
}

open class StorageException(message: String, cause: Throwable? = null) : java.io.IOException(message, cause)

/**
 * Everything ModHub does to a game's files goes through this interface, so the
 * same plugin code works with plain files, SAF trees, or a Shizuku/root helper.
 *
 * All calls may block on I/O; implementations should switch to an I/O dispatcher.
 * Streams returned by [openRead]/[openWrite] must be closed by the caller.
 */
interface StorageBackend {
    /** Stable id, e.g. "file", "saf", "shizuku". */
    val id: String

    suspend fun exists(path: StoragePath): Boolean
    suspend fun isDirectory(path: StoragePath): Boolean

    /** Direct children of [path]. Empty if [path] is missing or not a directory. */
    suspend fun list(path: StoragePath): List<StorageEntry>

    suspend fun openRead(path: StoragePath): InputStream

    /** Opens for writing, truncating any existing file. Creates parent directories. */
    suspend fun openWrite(path: StoragePath): OutputStream

    /** Creates the directory and any missing parents. Returns true if it exists afterwards. */
    suspend fun mkdirs(path: StoragePath): Boolean

    /** Deletes a file, or a directory recursively. Returns true if something was deleted. */
    suspend fun delete(path: StoragePath): Boolean

    suspend fun readBytes(path: StoragePath): ByteArray = openRead(path).use { it.readBytes() }

    suspend fun writeBytes(path: StoragePath, bytes: ByteArray) {
        openWrite(path).use { it.write(bytes) }
    }

    suspend fun readText(path: StoragePath): String = readBytes(path).toString(Charsets.UTF_8)
    suspend fun writeText(path: StoragePath, text: String) = writeBytes(path, text.toByteArray(Charsets.UTF_8))

    /**
     * Copies a file or directory tree. If [overwrite] is false and [to] exists, throws.
     * Default implementation streams through [openRead]/[openWrite].
     */
    suspend fun copy(from: StoragePath, to: StoragePath, overwrite: Boolean = false) {
        if (!exists(from)) throw StorageException("Source does not exist: $from")
        if (exists(to)) {
            if (!overwrite) throw StorageException("Destination exists: $to")
            delete(to)
        }
        copyTree(from, to)
    }

    /** Moves/renames. Default is copy + delete; backends should override with a real rename. */
    suspend fun move(from: StoragePath, to: StoragePath, overwrite: Boolean = false) {
        copy(from, to, overwrite)
        delete(from)
    }
}

private suspend fun StorageBackend.copyTree(from: StoragePath, to: StoragePath) {
    if (isDirectory(from)) {
        mkdirs(to)
        for (child in list(from)) copyTree(child.path, to.resolve(child.name))
    } else {
        openRead(from).use { input -> openWrite(to).use { output -> input.copyTo(output) } }
    }
}
