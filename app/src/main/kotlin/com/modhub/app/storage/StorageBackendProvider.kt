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
    private val bridgeClient: BridgeClient,
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
                AndroidFileStorageBackend.hasAllFilesAccess(context) ->
                    StorageChoice(fileBackend, "Direct file access", true)
                saf != null ->
                    StorageChoice(saf, "Granted SAF folder", true)
                else ->
                    StorageChoice(fileBackend, "Needs All-files access or a SAF folder grant", false)
            }
        }
    }

    /** Builds the [GameContext] a plugin call needs, resolving overrides and storage mode. */
    suspend fun gameContext(plugin: GamePlugin): GameContext = resolve(plugin).context

    /**
     * Like [gameContext], but also returns the [StorageChoice] (backend + human-readable reason)
     * and the target path it was chosen for, so the UI can explain storage problems.
     *
     * If the plugin has a loader bridge ([GamePlugin.loaderBridgeAuthority]) and it answers,
     * mods are managed through it ([BridgeStorageBackend], mods dir [BridgeStorageBackend.MODS_ROOT]);
     * the user's path override is ignored then. Otherwise this is [resolveLegacy].
     */
    suspend fun resolve(plugin: GamePlugin): ResolvedStorage {
        val authority = plugin.loaderBridgeAuthority ?: return resolveLegacy(plugin)
        if (!bridgeClient.isAvailable(authority)) return resolveLegacy(plugin).copy(bridgeAuthority = authority)
        val info = bridgeClient.info(authority)
        val problem = when {
            info == null -> "ModHub Launcher is installed but refused ModHub's requests" +
                (bridgeClient.lastError(authority)?.let { " ($it)" } ?: "") +
                ". Both apps must come from the same ModHub release (same signing key). " +
                "If you installed the launcher after ModHub, reinstall ModHub."
            !info.supported -> "ModHub Launcher uses a newer bridge (v${info.bridgeVersion}) than this ModHub " +
                "understands. Update ModHub."
            else -> null
        }
        if (info == null || problem != null) {
            return resolveLegacy(plugin).copy(bridgeAuthority = authority, bridgeProblem = problem)
        }
        val backend = BridgeStorageBackend(context, bridgeClient, authority)
        val ext = AndroidFileStorageBackend.externalStorageRoot
        val modsDir = BridgeStorageBackend.MODS_ROOT
        return ResolvedStorage(
            context = GameContext(backend, packages, modsDir, ext, info.toLoaderBridgeInfo()),
            choice = StorageChoice(backend, BRIDGE_REASON, true),
            target = modsDir,
            bridge = info,
            bridgeAuthority = authority,
        )
    }

    /**
     * Path-based resolution (no bridge): the user's override, else the plugin's default folder,
     * with a backend picked by [choose]. Also used to read the old folder when migrating to the bridge.
     */
    suspend fun resolveLegacy(plugin: GamePlugin): ResolvedStorage {
        val game = settings.currentGameSettings(plugin.id)
        val mode = settings.current().storageMode
        val ext = AndroidFileStorageBackend.externalStorageRoot
        val probe = GameContext(fileBackend, packages, game.modsPathOverride, ext)
        val target = game.modsPathOverride
            ?: runCatching { plugin.modsDirectory(probe) }.getOrNull()
            ?: plugin.defaultModsDirectories(probe).firstOrNull()
            ?: ext
        val choice = choose(target, mode, game)
        return ResolvedStorage(GameContext(choice.backend, packages, game.modsPathOverride, ext), choice, target)
    }

    companion object {
        const val BRIDGE_REASON = "ModHub Launcher bridge"
    }
}

/** Result of [StorageBackendProvider.resolve]. */
data class ResolvedStorage(
    val context: GameContext,
    val choice: StorageChoice,
    val target: StoragePath,
    /** Set when mods are managed through the loader bridge. */
    val bridge: BridgeInfo? = null,
    /** The plugin's bridge authority, if it has one (whether or not the bridge is installed). */
    val bridgeAuthority: String? = null,
    /** Why an installed bridge can't be used (signature mismatch, newer contract). */
    val bridgeProblem: String? = null,
) {
    val bridgeActive: Boolean get() = bridge != null
}
