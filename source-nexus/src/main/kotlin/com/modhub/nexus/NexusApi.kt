package com.modhub.nexus

import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable
import java.io.InputStream

/** Supplies the user's personal Nexus API key (from Settings). Null/blank = not configured. */
fun interface ApiKeyProvider {
    suspend fun apiKey(): String?
}

/** Which mod list endpoint to call. */
enum class NexusModList(val path: String) {
    LATEST_ADDED("latest_added"),
    LATEST_UPDATED("latest_updated"),
    TRENDING("trending"),
}

/** An open HTTP body being downloaded. [cancel] aborts it from another thread. */
interface NexusDownloadStream : Closeable {
    /** From Content-Length; null if the server did not send one. */
    val contentLength: Long?
    val input: InputStream
    fun cancel()
}

/**
 * The Nexus Mods v1 calls ModHub uses. [NexusApiClient] is the OkHttp implementation;
 * the interface keeps [NexusModSource] free of OkHttp (and testable with a fake).
 *
 * All calls throw [com.modhub.core.source.SourceAuthException] on 401/403 and on a
 * missing key, [RateLimitedException] on 429, and [NexusApiException] on other errors.
 */
interface NexusApi {
    /** Latest quota seen in any response; null until the first request completes. */
    val rateLimit: StateFlow<RateLimitInfo?>

    suspend fun validateUser(): NexusUserDto
    suspend fun game(domain: String): NexusGameDto
    suspend fun listMods(domain: String, list: NexusModList): List<NexusModDto>
    suspend fun mod(domain: String, modId: Long): NexusModDto
    suspend fun files(domain: String, modId: Long): NexusFilesResponseDto

    /**
     * Download URLs for one file. Pass [key]/[expires] from an `nxm://` link for
     * non-Premium accounts. A 403 here throws [NexusDownloadForbiddenException] instead of
     * the generic auth exception so the caller can explain the premium/nxm situation.
     */
    suspend fun downloadLinks(
        domain: String,
        modId: Long,
        fileId: Long,
        key: String? = null,
        expires: String? = null,
    ): List<NexusDownloadLinkDto>

    suspend fun md5Search(domain: String, md5: String): List<NexusMd5ResultDto>

    /** Opens a CDN URL returned by [downloadLinks]. No `apikey` header is sent to the CDN. */
    suspend fun openDownload(url: String): NexusDownloadStream
}
