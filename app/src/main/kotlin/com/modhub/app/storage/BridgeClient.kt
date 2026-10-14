package com.modhub.app.storage

import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import com.modhub.core.plugin.LoaderBridgeInfo
import com.modhub.core.storage.StorageException
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Result of the bridge's `info` call (docs/BRIDGE.md). */
data class BridgeInfo(
    val bridgeVersion: Int,
    val launcherVersion: String?,
    /** Absolute path of the launcher's external files dir (for display only). */
    val filesDir: String?,
    /** Absolute path of the launcher's Mods folder (for display only; all I/O uses bridge paths). */
    val modsPath: String?,
    val smapiInstalled: Boolean,
    val smapiVersion: String?,
    val gameInstalled: Boolean,
) {
    /** False when the launcher speaks a newer contract than this ModHub knows ("update ModHub"). */
    val supported: Boolean get() = bridgeVersion in 1..BridgeClient.SUPPORTED_BRIDGE_VERSION

    fun toLoaderBridgeInfo() = LoaderBridgeInfo(
        launcherVersion = launcherVersion,
        smapiInstalled = smapiInstalled,
        smapiVersion = smapiVersion,
        gameInstalled = gameInstalled,
    )

    companion object {
        fun fromBundle(b: Bundle): BridgeInfo = BridgeInfo(
            bridgeVersion = b.getInt("bridgeVersion", 0),
            launcherVersion = b.getString("launcherVersion"),
            filesDir = b.getString("filesDir"),
            modsPath = b.getString("modsPath"),
            smapiInstalled = b.getBoolean("smapiInstalled", false),
            smapiVersion = b.getString("smapiVersion"),
            gameInstalled = b.getBoolean("gameInstalled", false),
        )
    }
}

/**
 * Low-level client for a loader launcher's bridge ContentProvider (ModHub Launcher,
 * authority [DEFAULT_AUTHORITY]; contract in docs/BRIDGE.md).
 *
 * The provider is protected by a signature permission, so calls only succeed when ModHub and
 * the launcher are signed with the same key. Package visibility (API 30+) comes from the
 * `<queries><provider android:authorities=.../></queries>` entry in the app manifest.
 */
@Singleton
class BridgeClient @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private class Cached(val info: BridgeInfo?, val error: String?, val at: Long)

    private val cache = ConcurrentHashMap<String, Cached>()

    /** True if the provider is installed and visible (does not check that calls are allowed). */
    fun isAvailable(authority: String = DEFAULT_AUTHORITY): Boolean = runCatching {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.resolveContentProvider(authority, PackageManager.ComponentInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            pm.resolveContentProvider(authority, 0)
        }
        info != null
    }.getOrDefault(false)

    /**
     * The launcher's status, cached for [INFO_CACHE_MS] (detection runs often: every resume,
     * refresh and install). Null if the provider is missing or refused the call; see [lastError].
     */
    fun info(authority: String = DEFAULT_AUTHORITY, maxAgeMs: Long = INFO_CACHE_MS): BridgeInfo? {
        val now = System.currentTimeMillis()
        cache[authority]?.let { if (now - it.at in 0..maxAgeMs) return it.info }
        val fresh = try {
            Cached(BridgeInfo.fromBundle(call(authority, "info")), null, now)
        } catch (e: StorageException) {
            Cached(null, e.message, now)
        }
        cache[authority] = fresh
        return fresh.info
    }

    /** Why the last [info] call failed, if it did. */
    fun lastError(authority: String = DEFAULT_AUTHORITY): String? = cache[authority]?.error

    /** Drops cached [info], e.g. after the user (re)installed the launcher or set up SMAPI. */
    fun invalidate() = cache.clear()

    /**
     * `ContentResolver.call` with every failure mapped to [StorageException]: a missing provider,
     * a SecurityException (permission/path rejected), a null result or a result carrying `error`.
     */
    fun call(authority: String, method: String, arg: String? = null, extras: Bundle? = null): Bundle {
        val result = try {
            // The (String authority, ...) overload is API 29+; the Uri one works on every supported version.
            context.contentResolver.call(Uri.parse("content://$authority"), method, arg, extras)
        } catch (e: SecurityException) {
            throw StorageException("ModHub Launcher refused \"$method\": ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            throw StorageException("ModHub Launcher rejected \"$method\": ${e.message}", e)
        } catch (e: RuntimeException) {
            // e.g. the launcher process died mid-call (DeadObjectException wrapped by the framework)
            throw StorageException("ModHub Launcher call \"$method\" failed: ${e.message}", e)
        } ?: throw StorageException("ModHub Launcher is not installed or did not answer \"$method\"")
        result.getString(KEY_ERROR)?.let { throw StorageException(it) }
        return result
    }

    /** `content://<authority>/<relative path>`, each segment encoded. */
    fun fileUri(authority: String, relativePath: String): Uri {
        val builder = Uri.Builder().scheme("content").authority(authority)
        relativePath.split('/').filter { it.isNotEmpty() }.forEach { builder.appendPath(it) }
        return builder.build()
    }

    companion object {
        const val DEFAULT_AUTHORITY = "abc.smapi.gameloader.modhub"
        /** Highest `bridgeVersion` this build understands. */
        const val SUPPORTED_BRIDGE_VERSION = 1
        const val INFO_CACHE_MS = 3_000L
        const val KEY_ERROR = "error"
    }
}
