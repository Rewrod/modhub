package com.modhub.nexus

import com.modhub.core.install.FileModArchive
import com.modhub.core.install.ModArchive
import com.modhub.core.model.DownloadProgress
import com.modhub.core.model.ModFile
import com.modhub.core.model.ModId
import com.modhub.core.model.ModInfo
import com.modhub.core.model.SourceGameRef
import com.modhub.core.source.DownloadAuth
import com.modhub.core.source.ModListing
import com.modhub.core.source.ModPage
import com.modhub.core.source.ModQuery
import com.modhub.core.source.ModSource
import com.modhub.core.source.SourceUnsupportedException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

/**
 * "Install from a file the user picked" (SAF `ACTION_OPEN_DOCUMENT`). It has no catalogue:
 * every [ModSource] call throws [SourceUnsupportedException]; the app calls
 * [archiveFromStream] with the picked document's stream and hands the archive to the
 * game plugin's installer. Pure JVM.
 */
class LocalFileSource : ModSource {
    override val id: String = SOURCE_ID
    override val displayName: String = "Local file"
    override val supportsSearch: Boolean = false

    override suspend fun isConfigured(): Boolean = true

    override suspend fun search(query: ModQuery): ModPage = unsupported("search")

    override suspend fun listLatest(game: SourceGameRef, listing: ModListing): List<ModInfo> =
        unsupported("list mods")

    override suspend fun getMod(game: SourceGameRef, modId: ModId): ModInfo = unsupported("look up mods")

    override suspend fun getFiles(game: SourceGameRef, modId: ModId): List<ModFile> = unsupported("list files")

    override fun download(game: SourceGameRef, file: ModFile, destination: File, auth: DownloadAuth): Flow<DownloadProgress> =
        flowOf(DownloadProgress.Failed(SourceUnsupportedException("Local files are not downloaded; use archiveFromStream()")))

    /**
     * Copies [input] (closed afterwards) into a fresh file under [cacheDir] and returns it as a
     * [FileModArchive] whose [ModArchive.fileName] is the sanitised [name]. Blocking: call
     * from an IO dispatcher. The caller owns (and should delete) the cached file.
     */
    fun archiveFromStream(name: String, input: InputStream, cacheDir: File): ModArchive {
        val safeName = sanitizeFileName(name)
        val dir = File(cacheDir, "local-import/${UUID.randomUUID()}")
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Cannot create $dir")
        val target = File(dir, safeName)
        try {
            input.use { inp -> target.outputStream().use { out -> inp.copyTo(out, 64 * 1024) } }
        } catch (e: IOException) {
            target.delete()
            dir.delete()
            throw e
        }
        return FileModArchive(target, safeName)
    }

    private fun unsupported(what: String): Nothing =
        throw SourceUnsupportedException("The local file source can't $what; pick a file instead.")

    companion object {
        const val SOURCE_ID = "local"

        /** Keeps only the last path component and strips characters that are illegal in file names. */
        fun sanitizeFileName(name: String): String {
            val base = name.substringAfterLast('/').substringAfterLast('\\')
                .replace(Regex("[\\u0000-\\u001f:*?\"<>|]"), "_")
                .trim()
                .trimStart('.')
            return base.ifEmpty { "mod.zip" }.take(200)
        }
    }
}
