package com.modhub.app.domain

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import com.modhub.app.data.db.InstalledModDao
import com.modhub.app.data.db.InstalledModEntity
import com.modhub.app.data.settings.SettingsRepository
import com.modhub.app.storage.AndroidFileStorageBackend
import com.modhub.app.storage.BridgeInfo
import com.modhub.app.storage.StorageBackendProvider
import com.modhub.app.storage.StorageChoice
import com.modhub.core.install.FileModArchive
import com.modhub.core.install.InstallProgress
import com.modhub.core.install.ModArchive
import com.modhub.core.model.DownloadProgress
import com.modhub.core.model.GameDetection
import com.modhub.core.model.GameId
import com.modhub.core.model.InstalledMod
import com.modhub.core.model.ModFile
import com.modhub.core.model.ModInfo
import com.modhub.core.model.ModSourceRef
import com.modhub.core.plugin.GameContext
import com.modhub.core.plugin.GamePlugin
import com.modhub.core.plugin.PluginRegistry
import com.modhub.core.source.DownloadAuth
import com.modhub.core.source.ModSource
import com.modhub.core.source.SourceRegistry
import com.modhub.core.storage.StorageBackend
import com.modhub.core.storage.StoragePath
import com.modhub.nexus.LocalFileSource
import com.modhub.nexus.NexusModSource
import com.modhub.nexus.NxmLink
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** Detection plus how storage was resolved, for one game. */
data class GameStatus(
    val plugin: GamePlugin,
    val detection: GameDetection,
    val storage: StorageChoice,
    /** Mods folder the storage choice was made for. */
    val target: StoragePath,
    /** True if [target] is under Android/data or Android/obb on Android 11+. */
    val restrictedPath: Boolean,
    val hasAllFilesAccess: Boolean,
    /** The user's folder override, when it is in use (never set while the bridge is active). */
    val modsPathOverride: StoragePath?,
    /** Loader bridge status when mods are managed through it (ModHub Launcher), else null. */
    val bridge: BridgeInfo? = null,
    /** The plugin's bridge authority if it supports one, whether or not it is installed. */
    val bridgeAuthority: String? = null,
    /** Why an installed bridge can't be used (signing key mismatch, newer contract). */
    val bridgeProblem: String? = null,
) {
    val bridgeActive: Boolean get() = bridge != null

    /** The game supports a loader bridge but it isn't usable: offer to install ModHub Launcher. */
    val launcherNeeded: Boolean get() = bridgeAuthority != null && bridge == null
}

/** Mod folders left in the old shared-storage folder after switching to the bridge. */
data class LegacyMods(
    /** The old folder, e.g. `/storage/emulated/0/StardewValley/Mods`. */
    val folder: StoragePath,
    /** Top-level mod folders there that the launcher's Mods folder doesn't have yet. */
    val folderNames: List<String>,
) {
    val count: Int get() = folderNames.size
}

/** Everything the game detail screen shows. */
data class GameSnapshot(
    val status: GameStatus,
    val mods: List<InstalledMod>,
    val issues: List<DependencyIssue>,
    /** Set when listing failed (e.g. no storage access); [mods] is then empty. */
    val listError: Throwable? = null,
    /** Mods in the old folder that can be moved into the launcher (bridge active only). */
    val legacyMods: LegacyMods? = null,
) {
    fun issuesFor(mod: InstalledMod): List<DependencyIssue> = issues.filter { it.modLocation == mod.location }
}

/** Thrown for user-facing problems that have a plain explanation. */
class UserFacingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The app's use-case layer: wraps [GamePlugin]s, [ModSource]s and storage so ViewModels
 * never build [com.modhub.core.plugin.GameContext]s or touch files themselves.
 */
@Singleton
class ModRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val plugins: PluginRegistry,
    private val sources: SourceRegistry,
    private val storage: StorageBackendProvider,
    private val settings: SettingsRepository,
    private val dao: InstalledModDao,
    private val downloads: DownloadManager,
    private val localFiles: LocalFileSource,
    private val dependencyCheckers: Set<@JvmSuppressWildcards DependencyChecker>,
) {
    /** Emits a game id whenever that game's mods folder was changed by ModHub. */
    private val invalidations = MutableSharedFlow<GameId>(extraBufferCapacity = 16)

    fun games(): List<GamePlugin> = plugins.all()

    fun plugin(gameId: GameId): GamePlugin? = plugins.get(gameId)

    private fun requirePlugin(gameId: GameId): GamePlugin =
        plugins.get(gameId) ?: throw UserFacingException("Unknown game '$gameId'")

    /** Remote catalogues for a game (excludes the local-file pseudo source). */
    fun remoteSourcesFor(gameId: GameId): List<ModSource> {
        val plugin = plugins.get(gameId) ?: return emptyList()
        return sources.sourcesFor(plugin).filter { it.id != LocalFileSource.SOURCE_ID }
    }

    fun gameRef(gameId: GameId, source: ModSource) = plugins.get(gameId)?.let { sources.gameRef(it, source) }

    // ------------------------------------------------------------------ status

    suspend fun status(gameId: GameId): GameStatus = withContext(Dispatchers.IO) {
        val plugin = requirePlugin(gameId)
        val resolved = storage.resolve(plugin)
        val detection = runCatching { plugin.detect(resolved.context) }.getOrElse { e ->
            GameDetection(gameInstalled = false, notes = listOf("Detection failed: ${describe(e)}"))
        }
        GameStatus(
            plugin = plugin,
            detection = detection,
            storage = resolved.choice,
            target = detection.modsDirectory ?: resolved.target,
            restrictedPath = AndroidFileStorageBackend.isRestrictedPath(detection.modsDirectory ?: resolved.target),
            hasAllFilesAccess = AndroidFileStorageBackend.hasAllFilesAccess(context),
            modsPathOverride = if (resolved.bridgeActive) null else resolved.context.modsDirectoryOverride,
            bridge = resolved.bridge,
            bridgeAuthority = resolved.bridgeAuthority,
            bridgeProblem = resolved.bridgeProblem,
        )
    }

    suspend fun snapshot(gameId: GameId): GameSnapshot = withContext(Dispatchers.IO) {
        val status = status(gameId)
        val plugin = status.plugin
        val ctx = storage.gameContext(plugin)
        val listed = runCatching { plugin.listInstalled(ctx) }
        val legacy = if (ctx.loaderBridge != null) {
            runCatching { findLegacyMods(plugin, ctx)?.mods }.getOrNull()
        } else {
            null
        }
        val provenance = runCatching { dao.observe(gameId.value).first() }.getOrDefault(emptyList())
            .associateBy { it.location }
        val mods = listed.getOrDefault(emptyList()).map { mod ->
            if (mod.source != null) return@map mod
            val p = provenance[mod.location.value] ?: return@map mod
            val sourceId = p.sourceId ?: return@map mod
            val sourceModId = p.sourceModId ?: return@map mod
            mod.copy(
                source = ModSourceRef(sourceId, com.modhub.core.model.ModId(sourceModId), p.sourceFileId, p.version),
                installedAt = mod.installedAt ?: p.installedAt,
            )
        }
        val issues = dependencyCheckers.filter { it.gameId == gameId }.flatMap { runCatching { it.check(mods) }.getOrDefault(emptyList()) }
        GameSnapshot(status, mods, issues, listed.exceptionOrNull(), legacy)
    }

    /**
     * Snapshot that reloads when ModHub changes the game's mods, when settings change,
     * or when [refresh] emits. Failures are emitted as values so the flow keeps running.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(gameId: GameId, refresh: Flow<Unit> = flowOf()): Flow<Result<GameSnapshot>> =
        merge(
            // DataStore flows emit their current value on collection, which triggers the first load.
            invalidations.filter { it == gameId }.map { },
            settings.settings.distinctUntilChanged().map { },
            settings.gameSettings(gameId).distinctUntilChanged().map { },
            refresh,
        ).mapLatest {
            try {
                Result.success(snapshot(gameId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    fun invalidate(gameId: GameId) {
        invalidations.tryEmit(gameId)
    }

    // ------------------------------------------------------------------ mod operations

    suspend fun setEnabled(mod: InstalledMod, enabled: Boolean): InstalledMod = withContext(Dispatchers.IO) {
        val plugin = requirePlugin(mod.gameId)
        val updated = plugin.setEnabled(storage.gameContext(plugin), mod, enabled)
        dao.get(mod.gameId.value, mod.id)?.let { dao.upsert(it.copy(location = updated.location.value)) }
        invalidate(mod.gameId)
        updated
    }

    suspend fun uninstall(mod: InstalledMod) = withContext(Dispatchers.IO) {
        val plugin = requirePlugin(mod.gameId)
        plugin.uninstall(storage.gameContext(plugin), mod)
        runCatching { dao.delete(mod.gameId.value, mod.id) }
        invalidate(mod.gameId)
    }

    private suspend fun installArchive(
        gameId: GameId,
        archive: ModArchive,
        source: ModSourceRef?,
        reporter: DownloadReporter? = null,
    ): List<InstalledMod> {
        val plugin = requirePlugin(gameId)
        val resolved = storage.resolve(plugin)
        if (!resolved.choice.usable) {
            throw UserFacingException("Can't write to the mods folder: ${resolved.choice.reason}. Open the game and use \"Fix storage access\".")
        }
        val progress = reporter?.let(::throttledInstallProgress)
        val installed = plugin.installMod(resolved.context, archive, source, progress)
        val now = System.currentTimeMillis()
        for (mod in installed) {
            runCatching {
                dao.upsert(
                    InstalledModEntity(
                        gameId = gameId.value,
                        modId = mod.id,
                        location = mod.location.value,
                        name = mod.name,
                        version = mod.version,
                        sourceId = source?.sourceId,
                        sourceModId = source?.modId?.value,
                        sourceFileId = source?.fileId,
                        installedAt = mod.installedAt ?: now,
                    ),
                )
            }
        }
        invalidate(gameId)
        return installed
    }

    /**
     * Forwards install file counts to [reporter], at most every [INSTALL_PROGRESS_STEP] files
     * (plus whenever the total changes and on the last file) so a 10k-file mod doesn't flood
     * the UI state flow.
     */
    private fun throttledInstallProgress(reporter: DownloadReporter): InstallProgress {
        var lastDone = -1
        var lastTotal = -1
        val lock = Any()
        return InstallProgress { done, total, _ ->
            synchronized(lock) {
                val due = total != lastTotal || done == total || done - lastDone >= INSTALL_PROGRESS_STEP
                if (due) {
                    lastDone = done
                    lastTotal = total
                    reporter.installing(done, total)
                }
            }
        }
    }

    /** Copies a user-picked document into the cache and installs it. Runs as a download job. */
    fun installFromUri(gameId: GameId, uri: Uri): String {
        val name = displayName(uri) ?: uri.lastPathSegment ?: "mod.zip"
        return downloads.enqueue(gameId, title = name) { reporter ->
            reporter.installing()
            val archive = context.contentResolver.openInputStream(uri)?.let { input ->
                localFiles.archiveFromStream(name, input, File(context.cacheDir, "imports"))
            } ?: throw UserFacingException("Could not open $name")
            try {
                installArchive(gameId, archive, null, reporter)
            } finally {
                (archive as? FileModArchive)?.file?.let { f -> f.delete(); f.parentFile?.delete() }
            }
        }
    }

    /** Downloads [file] from its source with progress, then installs it. Returns the download id. */
    fun downloadAndInstall(gameId: GameId, file: ModFile, mod: ModInfo? = null, auth: DownloadAuth = DownloadAuth.NONE): String {
        val title = mod?.name?.let { "$it: ${file.name}" } ?: file.name
        return downloads.enqueue(gameId, title = title, helpUrl = mod?.pageUrl?.let(::filesTabUrl)) { reporter ->
            runDownload(gameId, file, auth, reporter)
        }
    }

    private suspend fun runDownload(gameId: GameId, file: ModFile, auth: DownloadAuth, reporter: DownloadReporter): List<InstalledMod> {
        val plugin = requirePlugin(gameId)
        val source = sources.get(file.sourceId) ?: throw UserFacingException("Unknown mod source '${file.sourceId}'")
        val ref = sources.gameRef(plugin, source)
            ?: throw UserFacingException("${source.displayName} does not offer mods for ${plugin.displayName}")
        val dir = File(context.cacheDir, "downloads/${UUID.randomUUID()}")
        val dest = File(dir, LocalFileSource.sanitizeFileName(file.fileName.ifBlank { "${file.id}.zip" }))
        try {
            var completed: File? = null
            source.download(ref, file, dest, auth).collect { p ->
                when (p) {
                    DownloadProgress.Started -> reporter.downloading(0, file.sizeBytes)
                    is DownloadProgress.Progress -> reporter.downloading(p.bytesRead, p.totalBytes)
                    is DownloadProgress.Completed -> completed = p.file
                    is DownloadProgress.Failed -> throw p.error
                }
            }
            val downloaded = completed ?: throw UserFacingException("Download ended without a file")
            reporter.installing()
            val sourceRef = ModSourceRef(file.sourceId, file.modId, file.id, file.version.ifBlank { null })
            return installArchive(gameId, FileModArchive(downloaded, dest.name), sourceRef, reporter)
        } finally {
            dir.deleteRecursively()
        }
    }

    /**
     * Handles an `nxm://` link: finds the game by its Nexus domain, then looks up the file,
     * downloads it with the link's one-time key and installs it, all as a download job.
     * Returns the game the link belongs to, so the UI can show it.
     */
    fun handleNxmLink(uri: String): GameId {
        val link = NxmLink.parse(uri) ?: throw UserFacingException("Not a valid Nexus Mods download link:\n$uri")
        val plugin = plugins.all().firstOrNull {
            it.sourceGameKeys[NexusModSource.SOURCE_ID].equals(link.gameDomain, ignoreCase = true)
        } ?: throw UserFacingException("ModHub does not support the game \"${link.gameDomain}\" yet.")
        if (link.isExpired()) {
            throw UserFacingException("This download link has expired. Tap \"Mod Manager Download\" on the website again.")
        }
        val nexus = sources.get(NexusModSource.SOURCE_ID) as? NexusModSource
            ?: throw UserFacingException("The Nexus Mods source is not available")
        val ref = sources.gameRef(plugin, nexus) ?: throw UserFacingException("Nexus Mods is not set up for ${plugin.displayName}")
        val pageUrl = "https://www.nexusmods.com/${link.gameDomain}/mods/${link.modId}?tab=files"
        downloads.enqueue(plugin.id, title = "Nexus mod ${link.modId}, file ${link.fileId}", helpUrl = pageUrl) { reporter ->
            reporter.downloading(0, null)
            val file = nexus.fileForNxm(ref, link)
            reporter.retitle(file.name.ifBlank { file.fileName })
            runDownload(plugin.id, file, link.toDownloadAuth(), reporter)
        }
        return plugin.id
    }

    // ------------------------------------------------------------------ legacy folder -> bridge

    private class LegacySource(val mods: LegacyMods, val backend: StorageBackend)

    /**
     * Looks for mod folders in the old shared-storage folders (the user's override first, then the
     * plugin's non-bridge defaults, e.g. `StardewValley/Mods`) that [bridgeCtx]'s mods folder lacks.
     * Folders are matched by name, ignoring case and the disabled `.` prefix, so a folder that was
     * already copied is not offered again. Android/data paths are skipped (unreadable, and the
     * launcher's own folder lives there).
     */
    private suspend fun findLegacyMods(plugin: GamePlugin, bridgeCtx: GameContext): LegacySource? {
        val legacy = storage.resolveLegacy(plugin)
        val backend = legacy.context.storage
        val override = settings.currentGameSettings(plugin.id).modsPathOverride
        val candidates = (listOfNotNull(override) + plugin.defaultModsDirectories(legacy.context))
            .distinct()
            .filterNot { AndroidFileStorageBackend.isRestrictedPath(it) }
        val bridgeDir = plugin.modsDirectory(bridgeCtx)
        val present = bridgeCtx.storage.list(bridgeDir).mapTo(HashSet()) { migrationKey(it.name) }
        for (dir in candidates) {
            val entries = runCatching { backend.list(dir) }.getOrNull() ?: continue
            val pending = entries
                .filter { it.isDirectory && !it.name.startsWith(INTERNAL_PREFIX) && migrationKey(it.name) !in present }
                .map { it.name }
            if (pending.isNotEmpty()) return LegacySource(LegacyMods(dir, pending), backend)
        }
        return null
    }

    /**
     * Copies the mod folders from the old shared-storage folder into the launcher through the bridge
     * (read via the legacy backend, write via the bridge; staged, then renamed into place), and
     * optionally deletes each original once its copy is complete. Runs as a [DownloadManager] job
     * reporting file counts. Folders the launcher already has are skipped. Returns the job id.
     */
    fun migrateLegacyMods(gameId: GameId, deleteOriginals: Boolean): String {
        val plugin = requirePlugin(gameId)
        return downloads.enqueue(gameId, title = MIGRATION_TITLE) { reporter ->
            reporter.installing()
            val resolved = storage.resolve(plugin)
            if (!resolved.bridgeActive) {
                throw UserFacingException("ModHub Launcher isn't available. Install it, open it once, then try again.")
            }
            val bridgeCtx = resolved.context
            val source = findLegacyMods(plugin, bridgeCtx) ?: return@enqueue emptyList()
            val src = source.backend
            val dst = bridgeCtx.storage
            val dstDir = plugin.modsDirectory(bridgeCtx)
            dst.mkdirs(dstDir)

            val trees = source.mods.folderNames.associateWith { walk(src, source.mods.folder.resolve(it)) }
            val total = trees.values.sumOf { entries -> entries.count { !it.second } }
            var done = 0
            reporter.installing(0, total)
            val moved = mutableListOf<StoragePath>()
            for (name in source.mods.folderNames) {
                val from = source.mods.folder.resolve(name)
                val target = dstDir.resolve(name)
                val staging = dstDir.resolve("$MIGRATION_STAGING_PREFIX$name-${System.currentTimeMillis()}")
                try {
                    dst.mkdirs(staging)
                    for ((rel, isDir) in trees.getValue(name)) {
                        currentCoroutineContext().ensureActive()
                        if (isDir) {
                            dst.mkdirs(staging.resolve(rel))
                            continue
                        }
                        src.openRead(from.resolve(rel)).use { input ->
                            dst.openWrite(staging.resolve(rel)).use { output -> input.copyTo(output) }
                        }
                        done++
                        if (done == total || done % INSTALL_PROGRESS_STEP == 0) reporter.installing(done, total, name)
                    }
                    dst.move(staging, target, overwrite = false)
                } catch (e: Exception) {
                    runCatching { dst.delete(staging) }
                    if (e is CancellationException) throw e
                    invalidate(gameId)
                    throw UserFacingException(
                        "Moved ${moved.size} of ${source.mods.count} mod folders; \"$name\" failed: ${describe(e)}",
                        e,
                    )
                }
                moved += target
                // Best effort: an original that can't be deleted stays behind but isn't offered again
                // (the launcher now has a folder with its name).
                if (deleteOriginals) runCatching { src.delete(from) }
            }
            invalidate(gameId)
            plugin.listInstalled(bridgeCtx).filter { mod -> moved.any { mod.location.isWithin(it) } }
        }
    }

    /** Every entry under [root] as (relative path, isDirectory), parents before children. */
    private suspend fun walk(backend: StorageBackend, root: StoragePath, rel: String = ""): List<Pair<String, Boolean>> {
        val out = mutableListOf<Pair<String, Boolean>>()
        val dir = if (rel.isEmpty()) root else root.resolve(rel)
        for (entry in backend.list(dir)) {
            val childRel = if (rel.isEmpty()) entry.name else "$rel/${entry.name}"
            out += childRel to entry.isDirectory
            if (entry.isDirectory) out += walk(backend, root, childRel)
        }
        return out
    }

    // ------------------------------------------------------------------ mods folder (SAF)

    /**
     * Persists a tree grant from `ACTION_OPEN_DOCUMENT_TREE` and makes the picked folder this
     * game's mods folder (path override + SAF tree). Returns the path it maps to.
     */
    suspend fun useTreeAsModsFolder(gameId: GameId, treeUri: Uri): StoragePath {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(treeUri, flags)
        val path = treeUriToPath(treeUri)
            ?: throw UserFacingException("That folder isn't on local storage. Pick a folder on the device's internal storage or SD card.")
        val old = settings.currentGameSettings(gameId).safTreeUri
        settings.setSafTreeUri(gameId, treeUri.toString())
        settings.setModsPathOverride(gameId, path)
        if (old != null && old != treeUri.toString()) releaseTree(old)
        invalidate(gameId)
        return path
    }

    /** Removes the override and any SAF grant for [gameId]; detection falls back to the defaults. */
    suspend fun clearModsFolder(gameId: GameId) {
        val old = settings.currentGameSettings(gameId).safTreeUri
        settings.setModsPathOverride(gameId, null)
        settings.setSafTreeUri(gameId, null)
        old?.let(::releaseTree)
        invalidate(gameId)
    }

    private fun releaseTree(uri: String) {
        runCatching {
            context.contentResolver.releasePersistableUriPermission(
                Uri.parse(uri),
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    companion object {
        /** ModHub's own temporary folders inside a mods folder (plugins skip them). */
        const val INTERNAL_PREFIX = ".modhub-"
        const val MIGRATION_TITLE = "Moving mods into ModHub Launcher"
        const val MIGRATION_STAGING_PREFIX = ".modhub-staging-migrate-"

        /** Folder identity for migration: shared storage is case-insensitive, `.Foo` is disabled `Foo`. */
        fun migrationKey(folderName: String): String = folderName.trimStart('.').lowercase()

        /** Report install progress at most every this many files. */
        const val INSTALL_PROGRESS_STEP = 25
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"

        /**
         * Maps an ExternalStorageProvider tree URI (`primary:StardewValley/Mods`,
         * `1234-ABCD:Mods`) to the file path it stands for. Null for other providers.
         */
        fun treeUriToPath(treeUri: Uri): StoragePath? {
            if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
            val docId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull() ?: return null
            return docIdToPath(docId, Environment.getExternalStorageDirectory().absolutePath)
        }

        /** Pure part of [treeUriToPath]. */
        fun docIdToPath(docId: String, primaryRoot: String): StoragePath? {
            val volume = docId.substringBefore(':', missingDelimiterValue = "")
            if (volume.isEmpty()) return null
            val rel = docId.substringAfter(':')
            val root = if (volume.equals("primary", ignoreCase = true)) primaryRoot else "/storage/$volume"
            return runCatching { StoragePath.of(root).resolve(rel) }.getOrNull()
        }

        /** Initial location hint for the folder picker (works on API 26+, ignored if unknown). */
        fun initialTreeUri(path: StoragePath): Uri? {
            val primary = runCatching { StoragePath.of(Environment.getExternalStorageDirectory().absolutePath) }.getOrNull()
                ?: return null
            if (!path.isWithin(primary)) return null
            return DocumentsContract.buildDocumentUri(EXTERNAL_STORAGE_AUTHORITY, "primary:" + path.relativeTo(primary))
        }

        /** Nexus mod page -> its Files tab (where "Mod Manager Download" lives). */
        fun filesTabUrl(pageUrl: String): String =
            if (pageUrl.contains("nexusmods.com") && !pageUrl.contains("tab=")) {
                pageUrl + (if ('?' in pageUrl) "&" else "?") + "tab=files"
            } else {
                pageUrl
            }
    }
}
