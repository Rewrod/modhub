package com.modhub.nexus

import com.modhub.core.source.SourceAuthException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InputStream
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OkHttp client for `https://api.nexusmods.com/v1`.
 *
 * Sends `apikey`, `Application-Name: ModHub` and `Application-Version` on every API call,
 * records the `x-rl-*` quota headers into [rateLimit], and maps HTTP errors
 * (401/403 -> [SourceAuthException], 429 -> [RateLimitedException]).
 *
 * @param httpClient the app's shared client (User-Agent etc. are added there).
 * @param baseUrl overridable for tests (MockWebServer).
 */
class NexusApiClient(
    private val httpClient: OkHttpClient,
    private val apiKeyProvider: ApiKeyProvider,
    private val applicationVersion: String,
    baseUrl: String = BASE_URL,
) : NexusApi {

    private val base: HttpUrl = baseUrl.trimEnd('/').plus("/").toHttpUrl()

    private val _rateLimit = MutableStateFlow<RateLimitInfo?>(null)
    override val rateLimit: StateFlow<RateLimitInfo?> = _rateLimit.asStateFlow()

    override suspend fun validateUser(): NexusUserDto =
        NexusJson.user(getJson(listOf("users", "validate.json")))

    override suspend fun game(domain: String): NexusGameDto =
        NexusJson.game(getJson(listOf("games", "$domain.json")))

    override suspend fun listMods(domain: String, list: NexusModList): List<NexusModDto> =
        NexusJson.modList(getJson(listOf("games", domain, "mods", "${list.path}.json")))

    override suspend fun mod(domain: String, modId: Long): NexusModDto =
        NexusJson.mod(getJson(listOf("games", domain, "mods", "$modId.json")))

    override suspend fun files(domain: String, modId: Long): NexusFilesResponseDto =
        NexusJson.files(getJson(listOf("games", domain, "mods", "$modId", "files.json")))

    override suspend fun downloadLinks(
        domain: String,
        modId: Long,
        fileId: Long,
        key: String?,
        expires: String?,
    ): List<NexusDownloadLinkDto> {
        val query = buildMap {
            if (!key.isNullOrBlank()) put("key", key)
            if (!expires.isNullOrBlank()) put("expires", expires)
        }
        val body = getJson(
            listOf("games", domain, "mods", "$modId", "files", "$fileId", "download_link.json"),
            query,
        ) { code, errorBody ->
            if (code == 403) {
                NexusDownloadForbiddenException(
                    usedNxmKey = !key.isNullOrBlank(),
                    message = NexusJson.errorMessage(errorBody) ?: "Nexus Mods refused the download link (HTTP 403)",
                )
            } else {
                null
            }
        }
        return NexusJson.downloadLinks(body)
    }

    override suspend fun md5Search(domain: String, md5: String): List<NexusMd5ResultDto> =
        NexusJson.md5Search(getJson(listOf("games", domain, "mods", "md5_search", "${md5.lowercase()}.json")))

    override suspend fun openDownload(url: String): NexusDownloadStream {
        val request = Request.Builder().url(url).get().build()
        val call = httpClient.newCall(request)
        val response = call.await()
        if (!response.isSuccessful) {
            val code = response.code
            response.close()
            throw NexusApiException(code, "Download failed: HTTP $code")
        }
        val body = response.body ?: run {
            response.close()
            throw IOException("Download response had no body")
        }
        return object : NexusDownloadStream {
            override val contentLength: Long? = body.contentLength().takeIf { it >= 0 }
            override val input: InputStream = body.byteStream()
            override fun cancel() = call.cancel()
            override fun close() = response.close()
        }
    }

    // ---- internals ----

    /**
     * GETs an API path and returns the body text. [customError] may turn a failure status
     * into a specific exception (return null to fall back to the default mapping).
     */
    private suspend fun getJson(
        segments: List<String>,
        query: Map<String, String> = emptyMap(),
        customError: ((code: Int, body: String?) -> Exception?)? = null,
    ): String {
        val key = apiKeyProvider.apiKey()?.trim()
        if (key.isNullOrEmpty()) {
            throw SourceAuthException("No Nexus Mods API key set. Add your personal API key in Settings.")
        }
        val url = base.newBuilder().apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()
        val request = Request.Builder()
            .url(url)
            .get()
            .header("apikey", key)
            .header("Application-Name", APPLICATION_NAME)
            .header("Application-Version", applicationVersion)
            .header("Accept", "application/json")
            .build()

        val response = httpClient.newCall(request).await()
        return response.use { r ->
            val rl = RateLimitInfo.fromHeaders { r.header(it) }
            if (rl != null) _rateLimit.value = rl
            val text = withContext(Dispatchers.IO) { r.body?.string() }
            if (!r.isSuccessful) {
                throw customError?.invoke(r.code, text) ?: nexusHttpError(r.code, text, rl)
            }
            text ?: throw IOException("Empty response from Nexus Mods")
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class) // resume(value, onCancellation)
    private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { runCatching { cancel() } }
        enqueue(object : Callback {
            override fun onResponse(call: Call, response: Response) {
                // If the coroutine was cancelled meanwhile, close the response instead of leaking it.
                cont.resume(response) { response.close() }
            }

            override fun onFailure(call: Call, e: IOException) {
                if (!cont.isCancelled) cont.resumeWithException(e)
            }
        })
    }

    companion object {
        const val BASE_URL = "https://api.nexusmods.com/v1/"
        const val APPLICATION_NAME = "ModHub"
    }
}
