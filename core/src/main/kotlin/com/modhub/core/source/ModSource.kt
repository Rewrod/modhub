package com.modhub.core.source

import com.modhub.core.model.DownloadProgress
import com.modhub.core.model.GameId
import com.modhub.core.model.ModFile
import com.modhub.core.model.ModId
import com.modhub.core.model.ModInfo
import com.modhub.core.model.SourceGameRef
import kotlinx.coroutines.flow.Flow
import java.io.File

/** Which built-in listing to fetch. Sources map these to their own endpoints. */
enum class ModListing { LATEST_ADDED, LATEST_UPDATED, TRENDING }

data class ModQuery(
    val game: SourceGameRef,
    val text: String,
    val page: Int = 0,
    val pageSize: Int = 20,
)

data class ModPage(
    val items: List<ModInfo>,
    val page: Int,
    val hasMore: Boolean,
)

/**
 * Source-specific authorisation for one download. For Nexus non-premium users this
 * carries the `key`/`expires` from an `nxm://` link; empty otherwise.
 */
data class DownloadAuth(val params: Map<String, String> = emptyMap()) {
    companion object {
        val NONE = DownloadAuth()
    }
}

/** Thrown when an operation needs credentials the user has not provided. */
class SourceAuthException(message: String) : Exception(message)

/** Thrown when this source cannot do something (e.g. Nexus v1 has no text search). */
class SourceUnsupportedException(message: String) : UnsupportedOperationException(message)

/**
 * A remote catalogue of mods (Nexus Mods, GitHub releases, ...). Implementations live
 * in `:source-*` modules and are contributed to [SourceRegistry].
 *
 * Game identity is passed as a [SourceGameRef] built from
 * [com.modhub.core.plugin.GamePlugin.sourceGameKeys]; see [SourceRegistry.gameRef].
 */
interface ModSource {
    /** Stable id, also the key in `GamePlugin.sourceGameKeys`, e.g. "nexus". */
    val id: String
    val displayName: String

    /** True if text [search] is implemented. */
    val supportsSearch: Boolean

    /** True if the source has what it needs to work (e.g. an API key). */
    suspend fun isConfigured(): Boolean

    /** Text search. Throws [SourceUnsupportedException] if [supportsSearch] is false. */
    suspend fun search(query: ModQuery): ModPage

    suspend fun listLatest(game: SourceGameRef, listing: ModListing = ModListing.LATEST_UPDATED): List<ModInfo>

    suspend fun getMod(game: SourceGameRef, modId: ModId): ModInfo

    suspend fun getFiles(game: SourceGameRef, modId: ModId): List<ModFile>

    /**
     * Downloads [file] into [destination] (a local cache file owned by the app),
     * emitting [DownloadProgress.Started], any number of [DownloadProgress.Progress], then
     * exactly one [DownloadProgress.Completed] or [DownloadProgress.Failed]. Cold flow:
     * nothing happens until collected; cancelling collection cancels the download.
     */
    fun download(
        game: SourceGameRef,
        file: ModFile,
        destination: File,
        auth: DownloadAuth = DownloadAuth.NONE,
    ): Flow<DownloadProgress>

    /** Optional: map a GameId to this source's key without a plugin (default: none). */
    fun defaultGameKey(gameId: GameId): String? = null
}
