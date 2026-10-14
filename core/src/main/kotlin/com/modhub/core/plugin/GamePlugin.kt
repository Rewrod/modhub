package com.modhub.core.plugin

import com.modhub.core.install.InstallProgress
import com.modhub.core.install.ModArchive
import com.modhub.core.model.GameDetection
import com.modhub.core.model.GameId
import com.modhub.core.model.InstalledMod
import com.modhub.core.model.LoaderRequirement
import com.modhub.core.model.ModSourceRef
import com.modhub.core.storage.StorageBackend
import com.modhub.core.storage.StoragePath

/**
 * Android-specific facts a plugin may need, supplied by the app. Kept as an interface
 * so `:core` stays free of Android (the app implements it with PackageManager).
 */
interface PackageInspector {
    fun isInstalled(packageName: String): Boolean
    /** `versionName` of an installed package, or null if not installed/unknown. */
    fun versionName(packageName: String): String?
}

/**
 * Everything a [GamePlugin] call needs from the outside world. Built by the app per
 * call (cheap), so settings changes (path override, storage mode) take effect at once.
 */
data class GameContext(
    /** Backend chosen for this game's mods directory (file / SAF / Shizuku). */
    val storage: StorageBackend,
    val packages: PackageInspector,
    /** User override from settings; when non-null plugins must use it as the mods dir. */
    val modsDirectoryOverride: StoragePath? = null,
    /** Shared external storage root, normally `/storage/emulated/0`. */
    val externalStorageRoot: StoragePath = StoragePath.of("/storage/emulated/0"),
)

/**
 * One supported game. Implementations live in `:game-*` modules and are contributed to
 * the app's [PluginRegistry] (Hilt `@IntoSet`).
 *
 * Plugins own all game-specific knowledge: package names, where mods live, how an
 * archive maps onto the mods dir, manifest parsing, enable/disable mechanics, load order.
 * They must do all file I/O through [GameContext.storage], never `java.io.File` directly.
 */
interface GamePlugin {
    val id: GameId
    val displayName: String

    /** Android package names of the game (store variants, launchers). First = preferred. */
    val packageNames: List<String>

    /**
     * Packages to try when the user taps "Play", in preference order: a mod loader's
     * launcher first (so mods load), then the plain game. The app launches the first one
     * that has a launch intent. A package listed in a [LoaderRequirement.packageNames] counts
     * as a "modded" launch. All of them must be in the plugin's manifest `<queries>`.
     */
    val launchPackageNames: List<String> get() = packageNames

    /** Loaders/prerequisites (e.g. SMAPI for Android). Empty if mods work without one. */
    val loaderRequirements: List<LoaderRequirement>

    /**
     * How each mod source names this game, keyed by [com.modhub.core.source.ModSource.id].
     * Example: `mapOf("nexus" to "stardewvalley")`. A source that is not listed does not
     * offer mods for this game.
     */
    val sourceGameKeys: Map<String, String>

    /** True if [getLoadOrder]/[setLoadOrder] are meaningful for this game. */
    val supportsLoadOrder: Boolean get() = false

    /**
     * Candidate mods directories in priority order (no I/O). The UI shows these as
     * suggestions when the user picks an override.
     */
    fun defaultModsDirectories(context: GameContext): List<StoragePath>

    /**
     * Mods directory to use: the override if set, otherwise the first existing candidate,
     * otherwise the first candidate.
     */
    suspend fun modsDirectory(context: GameContext): StoragePath {
        context.modsDirectoryOverride?.let { return it }
        val candidates = defaultModsDirectories(context)
        require(candidates.isNotEmpty()) { "$id declares no mods directory" }
        return candidates.firstOrNull { context.storage.exists(it) } ?: candidates.first()
    }

    /** Checks game install, loader status and mods dir reachability. Must not throw for "not found". */
    suspend fun detect(context: GameContext): GameDetection

    /**
     * Installs a downloaded archive into the mods directory and returns what was installed.
     * [progress], if given, receives file counts while the archive is extracted.
     */
    suspend fun installMod(
        context: GameContext,
        archive: ModArchive,
        source: ModSourceRef? = null,
        progress: InstallProgress? = null,
    ): List<InstalledMod>

    /** Scans the mods directory. */
    suspend fun listInstalled(context: GameContext): List<InstalledMod>

    /** Enables/disables without deleting (e.g. Stardew: rename folder with a leading '.'). Returns the updated mod. */
    suspend fun setEnabled(context: GameContext, mod: InstalledMod, enabled: Boolean): InstalledMod

    /** Removes the mod's files. */
    suspend fun uninstall(context: GameContext, mod: InstalledMod)

    /** Ordered installed mods. Default: alphabetical by name. */
    suspend fun getLoadOrder(context: GameContext): List<InstalledMod> =
        listInstalled(context).sortedBy { it.name.lowercase() }

    /** Persists a new order (ids of [InstalledMod]). Default: unsupported. */
    suspend fun setLoadOrder(context: GameContext, orderedModIds: List<String>) {
        throw UnsupportedOperationException("$displayName does not support load order")
    }
}
