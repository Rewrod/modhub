package com.modhub.app.storage

import com.modhub.core.storage.StorageBackend
import com.modhub.core.storage.StorageEntry
import com.modhub.core.storage.StoragePath
import java.io.InputStream
import java.io.OutputStream

/**
 * Privileged backend via Shizuku (https://github.com/RikkaApps/Shizuku), needed for
 * `Android/data/<other app>` on Android 11+ without root.
 *
 * NOT IMPLEMENTED. Plan:
 *  1. Add `dev.rikka.shizuku:api` and `dev.rikka.shizuku:provider` (Maven Central; verify the
 *     current version), plus the `rikka.shizuku.ShizukuProvider` entry in the manifest.
 *  2. Check `Shizuku.pingBinder()` and request permission with `Shizuku.requestPermission()`.
 *  3. Bind a `UserService` (runs as shell uid) exposing an AIDL file API: exists/list/
 *     read/write (ParcelFileDescriptor pipes)/mkdirs/delete/rename.
 *  4. Implement each method below by calling that service.
 *
 * [isAvailable] lets the factory skip this backend until it's real.
 */
class ShizukuStorageBackend : StorageBackend {
    override val id: String = "shizuku"

    /** TODO: Shizuku.pingBinder() && permission granted. */
    fun isAvailable(): Boolean = false

    override suspend fun exists(path: StoragePath): Boolean = notYet()
    override suspend fun isDirectory(path: StoragePath): Boolean = notYet()
    override suspend fun list(path: StoragePath): List<StorageEntry> = notYet()
    override suspend fun openRead(path: StoragePath): InputStream = notYet()
    override suspend fun openWrite(path: StoragePath): OutputStream = notYet()
    override suspend fun mkdirs(path: StoragePath): Boolean = notYet()
    override suspend fun delete(path: StoragePath): Boolean = notYet()

    private fun notYet(): Nothing = throw UnsupportedOperationException("Shizuku backend not implemented yet")
}
