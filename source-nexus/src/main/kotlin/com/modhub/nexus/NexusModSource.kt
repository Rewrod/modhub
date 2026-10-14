package com.modhub.nexus

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
import com.modhub.core.source.SourceAuthException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException

/**
 * Nexus Mods v1 as a [ModSource].
 *
 * - **No text search** in API v1. [search] is a best-effort client-side filter over the
 *   latest-added, latest-updated and trending lists (about 30 mods); see [SEARCH_NOTE].
 * - **Downloads**: Premium accounts get CDN links directly. Everyone else must click
 *   "Mod Manager Download" on the website, which opens an `nxm://` link; parse it with
 *   [NxmLink.parse] and pass [NxmLink.toDownloadAuth] to [download]. Without it a non-Premium
 *   download fails with [NexusPremiumRequiredException].
 */
class NexusModSource(
    private val api: NexusApi,
    private val apiKeyProvider: ApiKeyProvider,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ModSource {

    override val id: String = SOURCE_ID
    override val displayName: String = "Nexus Mods"

    /** False: Nexus API v1 has no keyword search. [search] still works, see [SEARCH_NOTE]. */
    override val supportsSearch: Boolean = false

    /** Latest quota reported by Nexus (for a Settings/status line). */
    val rateLimit: StateFlow<RateLimitInfo?> get() = api.rateLimit

    private val userMutex = Mutex()
    private var cachedUser: Pair<String, NexusUserDto>? = null

    override suspend fun isConfigured(): Boolean = !apiKeyProvider.apiKey().isNullOrBlank()

    /**
     * Checks the key with `users/validate.json` (and caches the result for this key).
     * Throws [SourceAuthException] if the key is missing or rejected.
     */
    suspend fun validateKey(forceRefresh: Boolean = false): NexusUserDto = userMutex.withLock {
        val key = apiKeyProvider.apiKey()?.trim().orEmpty()
        val cached = cachedUser
        if (!forceRefresh && cached != null && cached.first == key) return cached.second
        val user = api.validateUser()
        cachedUser = key to user
        user
    }

    /**
     * Client-side filter (see [SEARCH_NOTE]); never throws [com.modhub.core.source.SourceUnsupportedException]
     * despite [supportsSearch] being false, so the UI may offer it as "filter recent mods".
     */
    override suspend fun search(query: ModQuery): ModPage {
        val all = coroutineScope {
            ModListing.entries
                .map { listing -> async { listLatest(query.game, listing) } }
                .flatMap { it.await() }
        }.distinctBy { it.id }
        val hits = all.filter { NexusMapping.matches(it, query.text) }
        return NexusMapping.page(hits, query.page, query.pageSize)
    }

    override suspend fun listLatest(game: SourceGameRef, listing: ModListing): List<ModInfo> =
        api.listMods(game.key, NexusMapping.toNexusList(listing))
            .filter(NexusMapping::isListable)
            .map { NexusMapping.toModInfo(it, game.gameId, game.key) }

    override suspend fun getMod(game: SourceGameRef, modId: ModId): ModInfo =
        NexusMapping.toModInfo(api.mod(game.key, modId.numeric()), game.gameId, game.key)

    override suspend fun getFiles(game: SourceGameRef, modId: ModId): List<ModFile> =
        NexusMapping.toModFiles(api.files(game.key, modId.numeric()), modId, game.gameId)

    /** Finds the [ModFile] an `nxm://` link points at (to then call [download] with [NxmLink.toDownloadAuth]). */
    suspend fun fileForNxm(game: SourceGameRef, link: NxmLink): ModFile {
        val modId = ModId(link.modId.toString())
        return getFiles(game, modId).firstOrNull { it.id == link.fileId.toString() }
            ?: throw NexusApiException(404, "File ${link.fileId} not found in mod ${link.modId}")
    }

    /** Looks up which mod/file an archive came from by its MD5 (lowercase hex). */
    suspend fun findByMd5(game: SourceGameRef, md5: String): List<Pair<ModInfo, ModFile>> =
        api.md5Search(game.key, md5).map { r ->
            val info = NexusMapping.toModInfo(r.mod, game.gameId, game.key)
            info to NexusMapping.toModFile(r.fileDetails, info.id, game.gameId)
        }

    override fun download(
        game: SourceGameRef,
        file: ModFile,
        destination: File,
        auth: DownloadAuth,
    ): Flow<DownloadProgress> = flow {
        // Exceptions thrown by the collector while we emit must propagate unchanged, not be
        // reported as DownloadProgress.Failed (flow exception transparency).
        suspend fun send(p: DownloadProgress) =
            try {
                emit(p)
            } catch (t: Throwable) {
                throw DownstreamFailure(t)
            }

        emit(DownloadProgress.Started)
        val partial = File(destination.path + ".part")
        try {
            val links = requestLinks(game, file, auth)
            destination.absoluteFile.parentFile?.mkdirs()
            val stream = openFirst(links)
            // Cancelling the collector must abort a read that is blocked on the socket, not just
            // the next loop turn: a child coroutine is cancelled with us and cancels the call.
            val cancelWatcher = CoroutineScope(currentCoroutineContext()).launch(start = CoroutineStart.UNDISPATCHED) {
                try {
                    awaitCancellation()
                } finally {
                    stream.cancel()
                }
            }
            try {
                stream.use { s ->
                    val total = s.contentLength ?: file.sizeBytes
                    send(DownloadProgress.Progress(0, total))
                    var read = 0L
                    var lastEmitted = 0L
                    partial.outputStream().use { out ->
                        val buf = ByteArray(BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = s.input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (read - lastEmitted >= PROGRESS_STEP_BYTES) {
                                lastEmitted = read
                                send(DownloadProgress.Progress(read, total))
                            }
                        }
                    }
                    if (s.contentLength != null && read != s.contentLength) {
                        throw IOException("Download truncated: got $read of ${s.contentLength} bytes")
                    }
                    if (read != lastEmitted) send(DownloadProgress.Progress(read, total))
                }
            } finally {
                cancelWatcher.cancel()
            }
            if (destination.exists() && !destination.delete()) throw IOException("Cannot replace $destination")
            if (!partial.renameTo(destination)) {
                partial.copyTo(destination, overwrite = true)
                partial.delete()
            }
            send(DownloadProgress.Completed(destination))
        } catch (e: DownstreamFailure) {
            partial.delete()
            throw e.cause
        } catch (e: CancellationException) {
            partial.delete()
            throw e
        } catch (e: Exception) {
            partial.delete()
            // A cancelled OkHttp call surfaces as IOException("Canceled"): rethrow as cancellation.
            currentCoroutineContext().ensureActive()
            emit(DownloadProgress.Failed(e))
        }
    }.flowOn(ioDispatcher)

    private suspend fun requestLinks(game: SourceGameRef, file: ModFile, auth: DownloadAuth): List<NexusDownloadLinkDto> {
        val key = auth.params[NxmLink.PARAM_KEY]
        val expires = auth.params[NxmLink.PARAM_EXPIRES]
        val links = try {
            api.downloadLinks(game.key, file.modId.numeric(), file.id.toLongOrNull()
                ?: throw IllegalArgumentException("Not a Nexus file id: ${file.id}"), key, expires)
        } catch (e: NexusDownloadForbiddenException) {
            throw explainForbidden(e)
        }
        if (links.isEmpty()) throw NexusApiException(200, "Nexus Mods returned no download servers for this file")
        return links
    }

    private suspend fun explainForbidden(e: NexusDownloadForbiddenException): Exception {
        if (e.usedNxmKey) {
            return NexusNxmLinkRejectedException(
                "Nexus Mods rejected the download link (${e.message}). It may have expired or belong to " +
                    "another account; tap \"Mod Manager Download\" on the website again.",
                e,
            )
        }
        val premium = runCatching { validateKey().isPremium }.getOrNull()
        return when (premium) {
            true -> SourceAuthException("Nexus Mods refused the download (HTTP 403): ${e.message}")
            // Not premium, or unknown (validation itself failed): the nxm flow is the way out either way.
            else -> NexusPremiumRequiredException(cause = e)
        }
    }

    private suspend fun openFirst(links: List<NexusDownloadLinkDto>): NexusDownloadStream {
        var last: Exception? = null
        for (link in links) {
            try {
                return api.openDownload(link.uri)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                last = e
            }
        }
        throw last ?: IOException("No download server available")
    }

    private class DownstreamFailure(override val cause: Throwable) : Exception(cause)

    private fun ModId.numeric(): Long =
        value.toLongOrNull() ?: throw IllegalArgumentException("Not a Nexus mod id: $value")

    companion object {
        const val SOURCE_ID = NexusMapping.SOURCE_ID
        private const val BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 256L * 1024

        const val SEARCH_NOTE =
            "Nexus Mods API v1 has no keyword search. Search here only filters the ~30 most recently " +
                "added, updated and trending mods by name, summary and author. To find anything else, " +
                "browse nexusmods.com and use \"Mod Manager Download\"."
    }
}
