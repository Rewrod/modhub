package com.modhub.app.storage

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import com.modhub.core.storage.FileStorageBackend
import com.modhub.core.storage.StoragePath
import java.io.File

/**
 * Plain java.io.File backend for real device paths (root = "/").
 *
 * Works for shared storage when the app holds All-files access (API 30+) or legacy
 * READ/WRITE_EXTERNAL_STORAGE (API 26-29). Never works for other apps' Android/data on 11+.
 */
class AndroidFileStorageBackend : FileStorageBackend(File("/")) {

    /** True if we can write to [path] (or its nearest existing ancestor) right now. */
    fun canWrite(path: StoragePath): Boolean {
        var p: StoragePath? = path
        while (p != null) {
            val f = toFile(p)
            if (f.exists()) return f.canWrite()
            p = p.parent
        }
        return false
    }

    companion object {
        val externalStorageRoot: StoragePath
            get() = StoragePath.of(Environment.getExternalStorageDirectory().absolutePath)

        /**
         * Broad shared-storage access: All files access on API 30+; on API 26-29 the legacy
         * WRITE_EXTERNAL_STORAGE runtime permission (checked only when [context] is given,
         * otherwise assumed granted).
         */
        fun hasAllFilesAccess(context: Context? = null): Boolean =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Environment.isExternalStorageManager()
            } else {
                context == null || context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                    PackageManager.PERMISSION_GRANTED
            }

        /** Paths that no File/SAF backend can reach on API 30+ (need Shizuku/root). */
        fun isRestrictedPath(path: StoragePath, externalRoot: StoragePath = externalStorageRoot): Boolean {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
            return path.isWithin(externalRoot / "Android/data") || path.isWithin(externalRoot / "Android/obb")
        }
    }
}
