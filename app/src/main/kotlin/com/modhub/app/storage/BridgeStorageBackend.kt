package com.modhub.app.storage

import android.content.Context
import android.os.Bundle
import com.modhub.core.storage.StorageBackend
import com.modhub.core.storage.StorageEntry
import com.modhub.core.storage.StorageException
import com.modhub.core.storage.StoragePath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream

/**
 * [StorageBackend] over the ModHub Launcher bridge (docs/BRIDGE.md). The launcher's mods
 * folder (`Android/data/abc.smapi.gameloader/files/Mods`) is mounted at [MODS_ROOT]:
 * `StoragePath("/Mods/Foo/manifest.json")` is bridge path `Mods/Foo/manifest.json`.
 * Paths outside [MODS_ROOT] don't exist for this backend (reads report "missing", writes throw).
 */
class BridgeStorageBackend(
    private val context: Context,
    private val client: BridgeClient,
    private val authority: String = BridgeClient.DEFAULT_AUTHORITY,
) : StorageBackend {

    override val id: String = "bridge"

    override suspend fun exists(path: StoragePath): Boolean {
        val rel = relativeOrNull(path) ?: return false
        return io { client.call(authority, "exists", rel).getBoolean("exists", false) }
    }

    override suspend fun isDirectory(path: StoragePath): Boolean {
        val rel = relativeOrNull(path) ?: return false
        return io {
            val r = client.call(authority, "exists", rel)
            r.getBoolean("exists", false) && r.getBoolean("isDir", false)
        }
    }

    override suspend fun list(path: StoragePath): List<StorageEntry> {
        val rel = relativeOrNull(path) ?: return emptyList()
        return io {
            val r = try {
                client.call(authority, "list", rel)
            } catch (e: StorageException) {
                // Contract: a missing path or a file is "Not a directory: ..."; the interface wants empty.
                if (e.message?.startsWith("Not a directory") == true) return@io emptyList()
                throw e
            }
            val names = r.getStringArray("names") ?: emptyArray()
            val dirs = r.getBooleanArray("dirs") ?: BooleanArray(0)
            val sizes = r.getLongArray("sizes") ?: LongArray(0)
            val mtimes = r.getLongArray("mtimes") ?: LongArray(0)
            names.mapIndexed { i, name ->
                StorageEntry(
                    path = path.resolve(name),
                    isDirectory = dirs.getOrElse(i) { false },
                    size = sizes.getOrElse(i) { 0L },
                    lastModified = mtimes.getOrElse(i) { 0L },
                )
            }
        }
    }

    override suspend fun openRead(path: StoragePath): InputStream {
        val uri = client.fileUri(authority, relative(path))
        return io {
            try {
                context.contentResolver.openInputStream(uri)
            } catch (e: FileNotFoundException) {
                throw StorageException("Not a file: $path (${e.message})", e)
            } catch (e: SecurityException) {
                throw StorageException("ModHub Launcher refused to open $path: ${e.message}", e)
            } ?: throw StorageException("ModHub Launcher could not open $path")
        }
    }

    override suspend fun openWrite(path: StoragePath): OutputStream {
        val uri = client.fileUri(authority, relative(path))
        return io {
            try {
                // Contract mode "w": the launcher creates parents and truncates.
                context.contentResolver.openOutputStream(uri, "w")
            } catch (e: FileNotFoundException) {
                throw StorageException("Cannot write $path: ${e.message}", e)
            } catch (e: SecurityException) {
                throw StorageException("ModHub Launcher refused to write $path: ${e.message}", e)
            } ?: throw StorageException("ModHub Launcher could not open $path for writing")
        }
    }

    override suspend fun mkdirs(path: StoragePath): Boolean {
        val rel = relative(path)
        return io { client.call(authority, "mkdirs", rel).getBoolean("ok", false) }
    }

    override suspend fun delete(path: StoragePath): Boolean {
        val rel = relative(path)
        if (path == MODS_ROOT) throw StorageException("Refusing to delete the launcher's Mods folder")
        return io {
            // The bridge reports ok=true for a missing path; the interface wants "was something deleted".
            if (!client.call(authority, "exists", rel).getBoolean("exists", false)) return@io false
            val r = client.call(authority, "delete", rel)
            if (!r.getBoolean("ok", false)) throw StorageException("Could not delete $path")
            true
        }
    }

    override suspend fun move(from: StoragePath, to: StoragePath, overwrite: Boolean) {
        val extras = Bundle().apply {
            putString("to", relative(to))
            putBoolean("overwrite", overwrite)
        }
        val rel = relative(from)
        io {
            val r = client.call(authority, "move", rel, extras)
            if (!r.getBoolean("ok", false)) throw StorageException("Could not move $from to $to")
        }
    }

    private fun relativeOrNull(path: StoragePath): String? =
        if (path.isWithin(MODS_ROOT)) path.value.removePrefix("/") else null

    private fun relative(path: StoragePath): String =
        relativeOrNull(path) ?: throw StorageException("$path is outside ModHub Launcher's Mods folder")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { block() }

    companion object {
        /** Where the launcher's `files/Mods` appears in [StoragePath] terms. */
        val MODS_ROOT: StoragePath = StoragePath.of("/Mods")
    }
}
