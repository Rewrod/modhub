package com.modhub.app.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.modhub.core.storage.StorageBackend
import com.modhub.core.storage.StorageEntry
import com.modhub.core.storage.StorageException
import com.modhub.core.storage.StoragePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.io.OutputStream

/**
 * Storage Access Framework backend over one persisted tree grant.
 *
 * [mountPoint] is the abstract path the tree root stands for (e.g.
 * `/storage/emulated/0/StardewValley/Mods` if the user picked that folder). Paths
 * outside the mount point throw [StorageException].
 *
 * Status: functional but slow (DocumentFile does a query per lookup). TODO:
 * replace lookups with DocumentsContract child queries + a small path→uri cache,
 * and implement a real [move] via DocumentsContract.renameDocument/moveDocument.
 */
class SafStorageBackend(
    private val context: Context,
    treeUri: Uri,
    private val mountPoint: StoragePath,
) : StorageBackend {

    override val id: String = "saf"

    private val root: DocumentFile = DocumentFile.fromTreeUri(context, treeUri)
        ?: throw StorageException("Invalid SAF tree uri: $treeUri")

    private fun segmentsOf(path: StoragePath): List<String> {
        if (!path.isWithin(mountPoint)) throw StorageException("$path is outside SAF mount $mountPoint")
        val rel = path.relativeTo(mountPoint)
        return if (rel.isEmpty()) emptyList() else rel.split('/')
    }

    private fun find(path: StoragePath): DocumentFile? {
        var cur: DocumentFile = root
        for (seg in segmentsOf(path)) cur = cur.findFile(seg) ?: return null
        return cur
    }

    private fun findOrCreateDir(path: StoragePath): DocumentFile {
        var cur: DocumentFile = root
        for (seg in segmentsOf(path)) {
            val next = cur.findFile(seg)
            cur = when {
                next == null -> cur.createDirectory(seg) ?: throw StorageException("Cannot create $seg in ${cur.uri}")
                next.isDirectory -> next
                else -> throw StorageException("Not a directory: $seg")
            }
        }
        return cur
    }

    override suspend fun exists(path: StoragePath): Boolean = io { find(path)?.exists() == true }

    override suspend fun isDirectory(path: StoragePath): Boolean = io { find(path)?.isDirectory == true }

    override suspend fun list(path: StoragePath): List<StorageEntry> = io {
        val dir = find(path)?.takeIf { it.isDirectory } ?: return@io emptyList()
        dir.listFiles().mapNotNull { doc ->
            val name = doc.name ?: return@mapNotNull null
            StorageEntry(path.resolve(name), doc.isDirectory, doc.length(), doc.lastModified())
        }.sortedBy { it.name }
    }

    override suspend fun openRead(path: StoragePath): InputStream = io {
        val doc = find(path)?.takeIf { it.isFile } ?: throw StorageException("Not a file: $path")
        context.contentResolver.openInputStream(doc.uri) ?: throw StorageException("Cannot open $path")
    }

    override suspend fun openWrite(path: StoragePath): OutputStream = io {
        val parent = path.parent ?: throw StorageException("No parent for $path")
        val dir = findOrCreateDir(parent)
        val existing = dir.findFile(path.name)
        val doc = when {
            existing == null -> dir.createFile("application/octet-stream", path.name)
                ?: throw StorageException("Cannot create $path")
            existing.isDirectory -> throw StorageException("Is a directory: $path")
            else -> existing
        }
        // "wt" = write + truncate. Some providers append a suffix when the mime is unknown;
        // createFile with application/octet-stream avoids that on the external storage provider.
        context.contentResolver.openOutputStream(doc.uri, "wt") ?: throw StorageException("Cannot write $path")
    }

    override suspend fun mkdirs(path: StoragePath): Boolean = io { findOrCreateDir(path).isDirectory }

    override suspend fun delete(path: StoragePath): Boolean = io {
        val doc = find(path) ?: return@io false
        if (path == mountPoint) throw StorageException("Refusing to delete the SAF mount root")
        doc.delete()
    }

    override suspend fun move(from: StoragePath, to: StoragePath, overwrite: Boolean) {
        // Fast path: same parent -> rename.
        if (from.parent == to.parent) {
            val renamed = io {
                val doc = find(from) ?: throw StorageException("Source does not exist: $from")
                val clash = find(to)
                if (clash != null) {
                    if (!overwrite) throw StorageException("Destination exists: $to")
                    clash.delete()
                }
                doc.renameTo(to.name)
            }
            if (renamed) return
        }
        super.move(from, to, overwrite)
    }

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }
}
