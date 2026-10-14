package com.modhub.core.install

import com.modhub.core.model.GameId
import com.modhub.core.model.InstalledMod
import com.modhub.core.model.ModSourceRef
import com.modhub.core.storage.StorageBackend
import com.modhub.core.storage.StorageException
import com.modhub.core.storage.StoragePath
import com.modhub.core.util.ArchiveEntry
import com.modhub.core.util.ZipExtractor

/** How to place an archive into the mods directory. */
data class InstallOptions(
    /** Force the target folder name. Default: derived from the archive layout. */
    val folderName: String? = null,
    /** If the target folder exists: true = replace it, false = fail. */
    val replaceExisting: Boolean = true,
    val limits: ZipExtractor.Limits = ZipExtractor.Limits(),
)

class ModAlreadyInstalledException(val path: StoragePath) : StorageException("Mod folder already exists: $path")

/**
 * Generic "unzip a mod into a folder" installer shared by plugins.
 *
 * Layout rule:
 *  - if every entry lives under one top-level folder (`MyMod/...`), that folder is
 *    installed as `<modsDir>/MyMod`;
 *  - otherwise (files at the archive root, or several top-level folders) everything is
 *    extracted into `<modsDir>/<archive name without extension>`.
 *
 * Games with smarter rules (e.g. Stardew archives that bundle several SMAPI mods, each
 * with its own manifest.json) should call [ZipExtractor] directly or call [install] once
 * per sub-folder using [InstallOptions.folderName] and a custom plan.
 *
 * Writes go to a staging folder first and are moved into place, so a failed
 * extraction never leaves a half-installed mod behind.
 */
class ModInstaller(
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /**
     * Decides the target folder name and the entry → path mapping without writing anything.
     * This is the one full pass over the archive's entry list; pass the result to [install]
     * to avoid reading the archive again.
     */
    fun plan(archive: ModArchive, options: InstallOptions = InstallOptions()): InstallPlan {
        val entries = archive.open().use { ZipExtractor.listEntries(it, options.limits) }
        if (entries.none { !it.isDirectory }) throw StorageException("Archive '${archive.fileName}' contains no files")
        val singleRoot = singleTopLevelFolder(entries)
        val folder = options.folderName
            ?: singleRoot
            ?: archive.fileName.substringBeforeLast('.').ifBlank { "mod" }
        return InstallPlan(folderName = sanitizeFolderName(folder), stripPrefix = singleRoot, entries = entries)
    }

    /**
     * Extracts [archive] into `<modsDirectory>/<folder>`.
     *
     * @param plan result of [plan] for this archive; when given the archive is not listed again.
     *   [InstallOptions.folderName], if set, still overrides the plan's folder name.
     * @param progress receives (files written, total files, path) during extraction.
     */
    suspend fun install(
        archive: ModArchive,
        storage: StorageBackend,
        modsDirectory: StoragePath,
        gameId: GameId,
        source: ModSourceRef? = null,
        options: InstallOptions = InstallOptions(),
        plan: InstallPlan? = null,
        progress: InstallProgress? = null,
    ): InstalledMod {
        val effectivePlan = when {
            plan == null -> {
                progress?.onPhase(InstallProgress.Phase.SCANNING)
                progress?.onProgress(0, 0, null)
                plan(archive, options)
            }
            options.folderName != null -> plan.copy(folderName = sanitizeFolderName(options.folderName))
            else -> plan
        }
        val target = modsDirectory.resolve(effectivePlan.folderName)
        if (storage.exists(target) && !options.replaceExisting) throw ModAlreadyInstalledException(target)

        val totalFiles = effectivePlan.fileCount
        progress?.onPhase(InstallProgress.Phase.EXTRACTING)
        progress?.onProgress(0, totalFiles, null)

        storage.mkdirs(modsDirectory)
        val staging = modsDirectory.resolve(".modhub-staging-${effectivePlan.folderName}-${clock()}")
        try {
            archive.open().use { input ->
                ZipExtractor.extract(
                    input,
                    storage,
                    staging,
                    options.limits,
                    totalEntries = totalFiles,
                    progress = progress?.let { p -> { done, total, path -> p.onProgress(done, total, path) } },
                ) { path ->
                    val prefix = effectivePlan.stripPrefix
                    when {
                        prefix == null -> path
                        path == prefix -> null
                        else -> path.removePrefix("$prefix/")
                    }
                }
            }
            if (storage.exists(target)) storage.delete(target)
            storage.move(staging, target, overwrite = true)
        } catch (t: Throwable) {
            runCatching { storage.delete(staging) }
            throw t
        }

        return InstalledMod(
            id = effectivePlan.folderName,
            gameId = gameId,
            name = effectivePlan.folderName,
            version = source?.version,
            location = target,
            enabled = true,
            manifest = null,
            source = source,
            installedAt = clock(),
        )
    }

    private fun sanitizeFolderName(folder: String): String {
        val sanitized = ZipExtractor.safeEntryPath(folder)
        require(sanitized.isNotEmpty() && !sanitized.contains('/')) { "Invalid mod folder name: '$folder'" }
        return sanitized
    }

    data class InstallPlan(
        val folderName: String,
        /** Top-level folder stripped from entry paths, or null if none. */
        val stripPrefix: String?,
        val entries: List<ArchiveEntry>,
    ) {
        /** Number of files (not directories) extraction will write. */
        val fileCount: Int get() = entries.count { !it.isDirectory }
    }

    companion object {
        /** Returns the single top-level folder name if every entry is inside it, else null. */
        fun singleTopLevelFolder(entries: List<ArchiveEntry>): String? {
            val tops = entries.map { it.path.substringBefore('/') }.toSet()
            if (tops.size != 1) return null
            val top = tops.single()
            // A lone file at the root (e.g. "mod.dll") is not a folder.
            val isFolder = entries.any { it.path.startsWith("$top/") || (it.path == top && it.isDirectory) }
            return if (isFolder) top else null
        }
    }
}
