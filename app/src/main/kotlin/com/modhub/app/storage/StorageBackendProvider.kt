package com.modhub.app.storage

import android.content.Context
import android.net.Uri
import com.modhub.app.data.settings.GameSettings
import com.modhub.app.data.settings.SettingsRepository
import com.modhub.app.data.settings.StorageMode
import com.modhub.app.platform.AndroidPackageInspector
import com.modhub.core.plugin.GameContext
import com.modhub.core.plugin.GamePlugin
import com.modhub.core.storage.StorageBackend
import com.modhub.core.storage.StoragePath
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** Why a particular backend was chosen; the UI should show this to the user. */
data class StorageChoice(val backend: StorageBackend, val reason: String, val usable: Boolean)

/**
 * Chooses a [StorageBackend] for a target path and builds [GameContext]s.
 * Rules are documented in docs/ARCHITECTURE.md ("Choosing a storage backend").
 */
@Singleton
class StorageBackendProvider @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val packages: AndroidPackageInspector,
) {
    val fileBackend = AndroidFileStorageBackend()
    val shizukuBackend = ShizukuStorageBackend()

    fun choose(target: StoragePath, mode: StorageMode, game: GameSettings): StorageChoice {
        val restricted = AndroidFileStorageBackend.isRestrictedPath(target)
        val saf = game.safTreeUri?.let { uri ->
            val mount = game.modsPathOverride ?: target
            runCatching { SafStorageBackend(context, Uri.parse(uri), mount) }.getOrNull()
        }
        return when (mode) {
            StorageMode.FILE -> StorageChoice(fileBackend, "File mode selected", !restricted && fileBackend.canWrite(target))
            StorageMode.SAF -> saf?.let { StorageChoice(it, "SAF mode selected", true) }
                ?: StorageChoice(fileBackend, "SAF mode selected but no folder granted", false)
            StorageMode.SHIZUKU -> StorageChoice(shizukuBackend, "Shizuku mode selected", shizukuBackend.isAvailable())
            StorageMode.AUTO -> when {
                restricted && shizukuBackend.isAvailable() ->
                    StorageChoice(shizukuBackend, "Android/data path: using Shizuku", true)
                restricted ->
                    StorageChoice(fileBackend, "Android/data path needs Shizuku (not available)", false)
                AndroidFileStorageBackend.hasAllFilesAccess() ->
                    StorageChoice(fileBackend, "Direct file access", true)
                saf != null ->
                    StorageChoice(saf, "Granted SAF folder", true)
                else ->
                    StorageChoice(fileBackend, "Needs All-files access or a SAF folder grant", false)
            }
        }
    }

    /** Builds the [GameContext] a plugin call needs, resolving overrides and storage mode. */
    suspend fun gameContext(plugin: GamePlugin): GameContext {
        val game = settings.currentGameSettings(plugin.id)
        val mode = settings.current().storageMode
        val ext = AndroidFileStorageBackend.externalStorageRoot
        val probe = GameContext(fileBackend, packages, game.modsPathOverride, ext)
        val target = game.modsPathOverride ?: plugin.defaultModsDirectories(probe).firstOrNull() ?: ext
        val choice = choose(target, mode, game)
        return GameContext(choice.backend, packages, game.modsPathOverride, ext)
    }
}
