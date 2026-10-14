package com.modhub.stardew

import com.modhub.core.install.InstallOptions
import com.modhub.core.install.InstallProgress
import com.modhub.core.install.ModArchive
import com.modhub.core.install.ModInstaller
import com.modhub.core.model.GameDetection
import com.modhub.core.model.GameId
import com.modhub.core.model.InstalledMod
import com.modhub.core.model.LoaderRequirement
import com.modhub.core.model.LoaderStatus
import com.modhub.core.model.ModSourceRef
import com.modhub.core.plugin.GameContext
import com.modhub.core.plugin.GamePlugin
import com.modhub.core.plugin.LoaderBridgeInfo
import com.modhub.core.storage.StorageException
import com.modhub.core.storage.StoragePath
import com.modhub.core.util.ZipExtractor
import java.util.zip.ZipInputStream

/**
 * Stardew Valley on Android, modded through the community "SMAPI for Android" port
 * (see docs/RESEARCH.md section 1).
 *
 * Mods directory layout (same as desktop SMAPI):
 *  - each mod is a folder containing a `manifest.json`;
 *  - folders may be grouped (`Mods/SomeBundle/ModA/manifest.json`); SMAPI searches subfolders;
 *  - folders whose name starts with `.` are ignored by SMAPI, which is how mods are disabled.
 *
 * SMAPI orders mods by their dependencies, so there is no user load order; use
 * [dependencyReport] to surface missing dependencies instead.
 */
class StardewPlugin(
    private val installer: ModInstaller = ModInstaller(),
) : GamePlugin {

    override val id: GameId = GAME_ID
    override val displayName: String = "Stardew Valley"

    override val packageNames: List<String> = listOf(PACKAGE_GAME, PACKAGE_SMAPI_LAUNCHER)

    /** SMAPI Launcher first: launching it starts the game with mods loaded. */
    override val launchPackageNames: List<String> = listOf(PACKAGE_SMAPI_LAUNCHER, PACKAGE_GAME)

    override val loaderRequirements: List<LoaderRequirement> = listOf(SMAPI_REQUIREMENT)

    override val sourceGameKeys: Map<String, String> = mapOf("nexus" to NEXUS_DOMAIN)

    override val supportsLoadOrder: Boolean = false

    /** ModHub Launcher's bridge (docs/BRIDGE.md): the only way into the launcher's private Mods folder. */
    override val loaderBridgeAuthority: String = BRIDGE_AUTHORITY

    override fun defaultModsDirectories(context: GameContext): List<StoragePath> {
        if (context.loaderBridge != null) return listOf(BRIDGE_MODS_DIR)
        val ext = context.externalStorageRoot
        return listOf(
            ext.resolve("StardewValley/Mods"),
            ext.resolve("Android/data/$PACKAGE_SMAPI_LAUNCHER/files/Mods"),
            ext.resolve("Android/data/$PACKAGE_GAME/files/Mods"),
        )
    }

    // ---------------------------------------------------------------- detection

    override suspend fun detect(context: GameContext): GameDetection {
        val packages = context.packages
        val bridge = context.loaderBridge
        val gamePackageInstalled = runCatching { packages.isInstalled(PACKAGE_GAME) }.getOrDefault(false)
        // The launcher has its own package visibility; trust it if our lookup came up empty.
        val gameInstalled = gamePackageInstalled || bridge?.gameInstalled == true
        val gameVersion =
            if (gamePackageInstalled) runCatching { packages.versionName(PACKAGE_GAME) }.getOrNull() else null

        val notes = mutableListOf<String>()
        if (!gameInstalled) {
            notes += "Stardew Valley ($PACKAGE_GAME) is not installed. Install it from Google Play or the Galaxy Store."
        } else if (gameVersion != null && !SmapiVersion.isAtLeast(gameVersion, MIN_GAME_VERSION)) {
            notes += "SMAPI for Android needs Stardew Valley $MIN_GAME_VERSION or later; installed: $gameVersion."
        }

        return if (bridge != null) {
            detectWithBridge(context, bridge, gameInstalled, gameVersion, notes)
        } else {
            detectLegacy(context, gameInstalled, gameVersion, notes)
        }
    }

    /** ModHub Launcher is installed: SMAPI status comes from the launcher, mods live in its `files/Mods`. */
    private suspend fun detectWithBridge(
        context: GameContext,
        bridge: LoaderBridgeInfo,
        gameInstalled: Boolean,
        gameVersion: String?,
        notes: MutableList<String>,
    ): GameDetection {
        val modsDir = runCatching { modsDirectory(context) }.getOrDefault(BRIDGE_MODS_DIR)
        val storage = context.storage
        // The launcher creates Mods on first start; creating it early is harmless and lets installs work at once.
        val accessible = runCatching { storage.isDirectory(modsDir) || storage.mkdirs(modsDir) }.getOrDefault(false)

        val loaderStatus: LoaderStatus = if (bridge.smapiInstalled) {
            LoaderStatus.Installed(SMAPI_REQUIREMENT, bridge.smapiVersion)
        } else {
            LoaderStatus.Missing(SMAPI_REQUIREMENT, BRIDGE_SMAPI_MISSING_HINT)
        }

        notes += BRIDGE_MANAGED_NOTE + (bridge.launcherVersion?.let { " ($it)." } ?: ".")
        if (!accessible) notes += "ModHub Launcher did not let ModHub open its Mods folder. Open the launcher once, then refresh."

        return GameDetection(
            gameInstalled = gameInstalled,
            installedPackage = if (gameInstalled) PACKAGE_GAME else null,
            gameVersion = gameVersion,
            loaders = listOf(loaderStatus),
            modsDirectory = modsDir,
            modsDirectoryAccessible = accessible,
            notes = notes,
        )
    }

    /** No bridge: the official SMAPI Launcher (or nothing) is installed; scan the legacy shared paths. */
    private suspend fun detectLegacy(
        context: GameContext,
        gameInstalled: Boolean,
        gameVersion: String?,
        notes: MutableList<String>,
    ): GameDetection {
        val packages = context.packages
        val launcherInstalled = runCatching { packages.isInstalled(PACKAGE_SMAPI_LAUNCHER) }.getOrDefault(false)
        val launcherVersion =
            if (launcherInstalled) runCatching { packages.versionName(PACKAGE_SMAPI_LAUNCHER) }.getOrNull() else null

        val modsDir = runCatching { modsDirectory(context) }.getOrNull()
        val accessible = modsDir != null && runCatching { context.storage.isDirectory(modsDir) }.getOrDefault(false)

        val smapiInternal = context.externalStorageRoot.resolve("StardewValley/smapi-internal")
        val smapiFilesPresent = runCatching { context.storage.exists(smapiInternal) }.getOrDefault(false)
        val loaderStatus: LoaderStatus = when {
            // The official launcher without our bridge: SMAPI may work, but it never reads the folder ModHub can write.
            launcherInstalled -> LoaderStatus.Outdated(SMAPI_REQUIREMENT, launcherVersion, LAUNCHER_REPLACE_HINT)
            smapiFilesPresent -> LoaderStatus.Unknown(
                SMAPI_REQUIREMENT,
                "SMAPI files were found ($smapiInternal) but no SMAPI launcher app ($PACKAGE_SMAPI_LAUNCHER) " +
                    "is installed. If you use another SMAPI variant (e.g. Cinderbox or a patched APK) this may be fine.",
            )
            else -> LoaderStatus.Missing(SMAPI_REQUIREMENT, SMAPI_INSTALL_HINT)
        }

        notes += NO_BRIDGE_NOTE

        if (modsDir != null) {
            val restricted = isRestrictedAppDir(modsDir, context.externalStorageRoot)
            when {
                accessible -> Unit
                restricted -> notes += "The mods folder $modsDir is inside Android/data. On Android 11+ other apps " +
                    "cannot reach it with normal storage permissions or the folder picker. Install ModHub Launcher " +
                    "(or use Shizuku/root), or pick a different mods folder in Settings."
                else -> notes += "The mods folder $modsDir does not exist or is not accessible. Grant \"All files " +
                    "access\" (Android 11+) or pick the folder in Settings. ModHub creates it on the first install."
            }
        }

        return GameDetection(
            gameInstalled = gameInstalled,
            installedPackage = if (gameInstalled) PACKAGE_GAME else null,
            gameVersion = gameVersion,
            loaders = listOf(loaderStatus),
            modsDirectory = modsDir,
            modsDirectoryAccessible = accessible,
            notes = notes,
        )
    }

    // ---------------------------------------------------------------- install

    /**
     * Installs [archive] with core's [ModInstaller], then scans what was written for SMAPI
     * manifests. One archive can produce several [InstalledMod]s:
     *  - `MyMod/manifest.json` -> `Mods/MyMod` (one mod);
     *  - `Bundle/A/manifest.json`, `Bundle/B/manifest.json` -> `Mods/Bundle` (a grouped folder, two mods);
     *  - `A/manifest.json`, `B/manifest.json` (no wrapper) -> `Mods/<archive name>/A`, `.../B`;
     *  - `manifest.json` at the archive root -> `Mods/<manifest Name>` (falls back to the archive name).
     *
     * If the same folder exists in disabled form (`.MyMod`), it is removed so the update does not
     * leave a stale duplicate behind.
     */
    override suspend fun installMod(
        context: GameContext,
        archive: ModArchive,
        source: ModSourceRef?,
        progress: InstallProgress?,
    ): List<InstalledMod> {
        val modsDir = modsDirectory(context)
        val storage = context.storage

        // One full listing pass; the plan is reused by install() below.
        progress?.onPhase(InstallProgress.Phase.SCANNING)
        progress?.onProgress(0, 0, null)
        val plan = installer.plan(archive)
        progress?.onProgress(0, plan.fileCount, null)
        // Partial pass: stops at the root manifest.json entry.
        val folderOverride = if (plan.stripPrefix == null && plan.entries.any { it.path == SmapiManifest.FILE_NAME }) {
            rootManifestName(archive)
                ?.let { sanitizeFolderName(it) }
                ?.takeIf { it.isNotEmpty() }
        } else {
            null
        }
        val installed = installer.install(
            archive = archive,
            storage = storage,
            modsDirectory = modsDir,
            gameId = id,
            source = source,
            options = InstallOptions(folderName = folderOverride, replaceExisting = true),
            plan = plan,
            progress = progress,
        )
        progress?.onPhase(InstallProgress.Phase.FINALIZING)
        progress?.onProgress(plan.fileCount, plan.fileCount, null)

        val disabledTwin = modsDir.resolve(DISABLED_PREFIX + installed.location.name)
        if (storage.exists(disabledTwin)) storage.delete(disabledTwin)

        val found = findManifestDirs(context, installed.location, MAX_INSTALL_SCAN_DEPTH)
        if (found.isEmpty()) {
            // No manifest: likely not a SMAPI mod (e.g. an XNB replacement). Still report it so it can be removed.
            return listOf(installed.copy(name = installed.location.name, enabled = true))
        }
        return found.map { (dir, manifest) ->
            buildMod(dir, manifest, enabled = true, source = source, installedAt = installed.installedAt)
        }
    }

    // ---------------------------------------------------------------- listing

    /**
     * Lists `Mods/<folder>` and grouped `Mods/<group>/<folder>` mods. A folder without a
     * manifest and without manifest-bearing children is reported as a mod with `manifest = null`
     * so the user can see and remove it. ModHub's own staging folders are skipped.
     */
    override suspend fun listInstalled(context: GameContext): List<InstalledMod> {
        val modsDir = modsDirectory(context)
        val storage = context.storage
        if (!storage.isDirectory(modsDir)) return emptyList()

        val out = mutableListOf<InstalledMod>()
        for (entry in storage.list(modsDir)) {
            if (!entry.isDirectory || isInternalFolder(entry.name)) continue
            val topEnabled = !entry.name.startsWith(DISABLED_PREFIX)
            val manifest = readManifest(context, entry.path)
            if (manifest != null) {
                out += buildMod(entry.path, manifest, enabled = topEnabled)
                continue
            }
            val nested = storage.list(entry.path)
                .filter { it.isDirectory && !isInternalFolder(it.name) }
                .mapNotNull { child -> readManifest(context, child.path)?.let { child to it } }
            if (nested.isEmpty()) {
                out += InstalledMod(
                    id = entry.name.removePrefix(DISABLED_PREFIX),
                    gameId = id,
                    name = entry.name.removePrefix(DISABLED_PREFIX),
                    version = null,
                    location = entry.path,
                    enabled = topEnabled,
                )
            } else {
                for ((child, m) in nested) {
                    out += buildMod(child.path, m, enabled = topEnabled && !child.name.startsWith(DISABLED_PREFIX))
                }
            }
        }
        return out.sortedBy { it.name.lowercase() }
    }

    // ---------------------------------------------------------------- enable / uninstall

    /**
     * Renames the mod folder to add/remove SMAPI's leading `.`. For a mod inside a grouped
     * folder only the mod's own folder is renamed; a disabled group stays disabled.
     */
    override suspend fun setEnabled(context: GameContext, mod: InstalledMod, enabled: Boolean): InstalledMod {
        val current = mod.location
        val parent = current.parent ?: throw StorageException("Mod location has no parent: $current")
        val baseName = current.name.trimStart('.')
        if (baseName.isEmpty()) throw StorageException("Invalid mod folder name: ${current.name}")
        val target = parent.resolve(if (enabled) baseName else DISABLED_PREFIX + baseName)
        if (target == current) return mod.copy(enabled = enabled)
        if (context.storage.exists(target)) {
            throw StorageException("Cannot ${if (enabled) "enable" else "disable"} ${mod.name}: $target already exists")
        }
        context.storage.move(current, target, overwrite = false)
        return mod.copy(location = target, enabled = enabled)
    }

    override suspend fun uninstall(context: GameContext, mod: InstalledMod) {
        val modsDir = modsDirectory(context)
        if (!mod.location.isWithin(modsDir) || mod.location == modsDir) {
            throw StorageException("Refusing to delete ${mod.location}: not inside the mods folder $modsDir")
        }
        context.storage.delete(mod.location)
    }

    // ---------------------------------------------------------------- dependencies

    /** A required dependency (or content-pack target) that is missing, disabled or too old. */
    data class MissingDependency(
        val mod: InstalledMod,
        val missingUniqueId: String,
        val minimumVersion: String?,
        /** Version of the dependency that *is* installed and enabled, when it is just too old. */
        val installedVersion: String? = null,
        val kind: Kind = Kind.DEPENDENCY,
    ) {
        enum class Kind { DEPENDENCY, CONTENT_PACK_FOR }
        val isOutdated: Boolean get() = installedVersion != null
    }

    /**
     * Checks every enabled mod's required `Dependencies` and `ContentPackFor` against the other
     * enabled mods (SMAPI does not load disabled ones). Ids compare case-insensitively, as in SMAPI.
     * `SMAPI` itself is always treated as present.
     */
    suspend fun dependencyReport(context: GameContext): List<MissingDependency> =
        dependencyReport(listInstalled(context))

    /** Pure variant of [dependencyReport] over an already-listed set of mods. */
    fun dependencyReport(mods: List<InstalledMod>): List<MissingDependency> {
        val enabled = mods.filter { it.enabled }
        val available: Map<String, String?> = enabled
            .mapNotNull { m -> m.manifest?.let { it.uniqueId.lowercase() to it.version } }
            .toMap()

        fun check(mod: InstalledMod, depId: String, min: String?, kind: MissingDependency.Kind): MissingDependency? {
            if (depId.equals(SMAPI_UNIQUE_ID, ignoreCase = true)) return null
            val key = depId.lowercase()
            if (key !in available) return MissingDependency(mod, depId, min, null, kind)
            val have = available[key]
            return if (SmapiVersion.isAtLeast(have, min)) null else MissingDependency(mod, depId, min, have ?: "", kind)
        }

        val out = mutableListOf<MissingDependency>()
        for (mod in enabled) {
            val manifest = mod.manifest ?: continue
            for (dep in manifest.dependencies) {
                if (!dep.required) continue
                check(mod, dep.id, dep.minimumVersion, MissingDependency.Kind.DEPENDENCY)?.let { out += it }
            }
            manifest.extra[SmapiManifest.EXTRA_CONTENT_PACK_FOR]?.let { target ->
                val min = manifest.extra[SmapiManifest.EXTRA_CONTENT_PACK_FOR_MIN_VERSION]
                check(mod, target, min, MissingDependency.Kind.CONTENT_PACK_FOR)?.let { out += it }
            }
        }
        return out
    }

    // ---------------------------------------------------------------- helpers

    /** Reads and parses `<dir>/manifest.json`; null if absent or unparseable. */
    suspend fun readManifest(context: GameContext, dir: StoragePath): SmapiManifest? {
        val file = dir.resolve(SmapiManifest.FILE_NAME)
        return runCatching {
            if (!context.storage.exists(file)) return null
            SmapiManifest.parseOrNull(context.storage.readText(file))
        }.getOrNull()
    }

    /** Depth-first search for manifest folders; does not descend into a folder that has a manifest (like SMAPI). */
    private suspend fun findManifestDirs(
        context: GameContext,
        dir: StoragePath,
        depth: Int,
    ): List<Pair<StoragePath, SmapiManifest>> {
        readManifest(context, dir)?.let { return listOf(dir to it) }
        if (depth <= 0) return emptyList()
        return context.storage.list(dir)
            .filter { it.isDirectory && !it.name.startsWith(DISABLED_PREFIX) }
            .flatMap { findManifestDirs(context, it.path, depth - 1) }
    }

    private fun buildMod(
        dir: StoragePath,
        smapi: SmapiManifest,
        enabled: Boolean,
        source: ModSourceRef? = null,
        installedAt: Long? = null,
    ): InstalledMod {
        val manifest = smapi.toModManifest(fallbackId = dir.name.trimStart('.'))
        return InstalledMod(
            id = manifest.uniqueId,
            gameId = id,
            name = manifest.name,
            version = manifest.version.ifEmpty { null } ?: source?.version,
            location = dir,
            enabled = enabled,
            manifest = manifest,
            source = source,
            installedAt = installedAt,
        )
    }

    /** Reads the `Name` from a `manifest.json` at the archive root, without extracting. */
    private fun rootManifestName(archive: ModArchive): String? = runCatching {
        archive.open().use { raw ->
            ZipInputStream(raw).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    if (!e.isDirectory && ZipExtractor.safeEntryPath(e.name) == SmapiManifest.FILE_NAME) {
                        val bytes = zip.readNBytes(MAX_MANIFEST_BYTES)
                        return@runCatching SmapiManifest.parseOrNull(bytes.toString(Charsets.UTF_8))
                            ?.let { it.name ?: it.uniqueId }
                    }
                }
                null
            }
        }
    }.getOrNull()

    companion object {
        val GAME_ID = GameId("stardew")
        const val NEXUS_DOMAIN = "stardewvalley"
        const val PACKAGE_GAME = "com.chucklefish.stardewvalley"
        /** NRTnarathip's SMAPI Launcher (SMAPILoader) for Stardew 1.6. */
        const val PACKAGE_SMAPI_LAUNCHER = "abc.smapi.gameloader"
        const val SMAPI_UNIQUE_ID = "SMAPI"
        const val MIN_GAME_VERSION = "1.6.15.1"
        const val DISABLED_PREFIX = "."
        const val SMAPI_NEXUS_URL = "https://www.nexusmods.com/stardewvalley/mods/44436"
        const val SMAPI_LAUNCHER_GITHUB_URL = "https://github.com/NRTnarathip/SMAPILoader"

        // Same depth listInstalled() reports: a mod folder, or one grouped level below it.
        private const val MAX_INSTALL_SCAN_DEPTH = 1
        private const val MAX_MANIFEST_BYTES = 1024 * 1024
        private const val STAGING_PREFIX = ".modhub-staging-"

        /** ModHub Launcher's bridge provider (our fork of SMAPILoader, same package; docs/BRIDGE.md). */
        const val BRIDGE_AUTHORITY = "abc.smapi.gameloader.modhub"
        /** Bridge paths are relative to the launcher's files dir; "/Mods" is its `files/Mods`. */
        val BRIDGE_MODS_DIR: StoragePath = StoragePath.of("/Mods")

        val SMAPI_REQUIREMENT = LoaderRequirement(
            id = "smapi-android",
            name = "SMAPI for Android",
            description = "Unofficial community port of SMAPI (not supported by ConcernedApe; still experimental, " +
                "some mods do not work). Needs a 64-bit device and Stardew Valley $MIN_GAME_VERSION or later " +
                "from Google Play or the Galaxy Store. Install ModHub Launcher (ModHub's build of the SMAPI " +
                "Launcher, $SMAPI_LAUNCHER_GITHUB_URL), then use its \"Install SMAPI From Zip\" with the " +
                "SMAPI-Android zip.",
            url = SMAPI_NEXUS_URL,
            packageNames = listOf(PACKAGE_SMAPI_LAUNCHER),
        )

        const val SMAPI_INSTALL_HINT =
            "Install ModHub Launcher from ModHub's game screen, download the SMAPI-Android zip from " +
                "$SMAPI_NEXUS_URL, open the launcher and tap \"Install SMAPI From Zip\". Avoid APK mirror sites."

        /** Bridge present, SMAPI not set up inside the launcher yet. */
        const val BRIDGE_SMAPI_MISSING_HINT =
            "Install SMAPI inside ModHub Launcher: open the launcher and tap \"Install SMAPI From Zip\" " +
                "with the SMAPI-Android zip from $SMAPI_NEXUS_URL."

        /** The official SMAPI Launcher is installed, but without ModHub's bridge. */
        const val LAUNCHER_REPLACE_HINT = "Install ModHub Launcher (replaces SMAPI Launcher, keeps saves)"

        const val BRIDGE_MANAGED_NOTE = "Mods are managed inside ModHub Launcher"

        const val NO_BRIDGE_NOTE =
            "SMAPI Launcher only loads mods from its private folder; install ModHub Launcher so ModHub can manage them"

        private fun isInternalFolder(name: String): Boolean = name.startsWith(STAGING_PREFIX)

        internal fun isRestrictedAppDir(path: StoragePath, externalRoot: StoragePath): Boolean =
            path.isWithin(externalRoot.resolve("Android/data")) || path.isWithin(externalRoot.resolve("Android/obb"))

        /** Turns a manifest Name into a safe single folder name. */
        internal fun sanitizeFolderName(name: String): String =
            name.replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "_").trim().trimStart('.').trim()
    }
}
