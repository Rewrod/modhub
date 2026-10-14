package com.modhub.core.storage

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * [StorageBackend] on top of `java.io.File`.
 *
 * @param root real directory that [StoragePath.ROOT] maps to. Defaults to the
 *   filesystem root, so `StoragePath.of("/storage/emulated/0/X")` means exactly that file.
 *   Tests pass a temp dir to sandbox everything.
 */
open class FileStorageBackend(private val root: File = File("/")) : StorageBackend {

    override val id: String = "file"

    fun toFile(path: StoragePath): File =
        if (path == StoragePath.ROOT) root else File(root, path.value.removePrefix("/"))

    override suspend fun exists(path: StoragePath): Boolean = io { toFile(path).exists() }

    override suspend fun isDirectory(path: StoragePath): Boolean = io { toFile(path).isDirectory }

    override suspend fun list(path: StoragePath): List<StorageEntry> = io {
        toFile(path).listFiles().orEmpty()
            .sortedBy { it.name }
            .map { f ->
                StorageEntry(
                    path = path.resolve(f.name),
                    isDirectory = f.isDirectory,
                    size = if (f.isFile) f.length() else 0L,
                    lastModified = f.lastModified(),
                )
            }
    }

    override suspend fun openRead(path: StoragePath): InputStream = io {
        val f = toFile(path)
        if (!f.isFile) throw StorageException("Not a file: $path")
        FileInputStream(f)
    }

    override suspend fun openWrite(path: StoragePath): OutputStream = io {
        val f = toFile(path)
        f.parentFile?.let { if (!it.isDirectory && !it.mkdirs()) throw StorageException("Cannot create ${path.parent}") }
        BufferedOutputStream(FileOutputStream(f), WRITE_BUFFER_SIZE)
    }

    override suspend fun mkdirs(path: StoragePath): Boolean = io {
        val f = toFile(path)
        f.isDirectory || f.mkdirs()
    }

    override suspend fun delete(path: StoragePath): Boolean = io {
        val f = toFile(path)
        f.exists() && f.deleteRecursively()
    }

    override suspend fun move(from: StoragePath, to: StoragePath, overwrite: Boolean) {
        val src = toFile(from)
        val dst = toFile(to)
        if (!src.exists()) throw StorageException("Source does not exist: $from")
        if (dst.exists()) {
            if (!overwrite) throw StorageException("Destination exists: $to")
            io { dst.deleteRecursively() }
        }
        val renamed = io {
            dst.parentFile?.mkdirs()
            src.renameTo(dst)
        }
        if (!renamed) super.move(from, to, overwrite)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** Each write() through FUSE (/storage/emulated/0) is a syscall round trip; batch them. */
        const val WRITE_BUFFER_SIZE = 256 * 1024
    }
}
