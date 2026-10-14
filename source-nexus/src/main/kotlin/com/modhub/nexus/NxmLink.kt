package com.modhub.nexus

import com.modhub.core.source.DownloadAuth
import java.net.URI
import java.net.URLDecoder

/**
 * A Nexus "Mod Manager Download" link:
 * `nxm://{domain}/mods/{modId}/files/{fileId}?key=...&expires=...&user_id=...`.
 *
 * Non-Premium users get these from the website; [key] and [expires] authorise exactly one
 * `download_link.json` call for that file.
 */
data class NxmLink(
    val gameDomain: String,
    val modId: Long,
    val fileId: Long,
    val key: String? = null,
    val expires: String? = null,
    val userId: String? = null,
) {
    /** Params for [com.modhub.core.source.ModSource.download] (consumed by [NexusModSource]). */
    fun toDownloadAuth(): DownloadAuth = DownloadAuth(
        buildMap {
            key?.let { put(PARAM_KEY, it) }
            expires?.let { put(PARAM_EXPIRES, it) }
            userId?.let { put(PARAM_USER_ID, it) }
        },
    )

    /** True if [expires] (epoch seconds) is in the past relative to [nowEpochSeconds]. */
    fun isExpired(nowEpochSeconds: Long = System.currentTimeMillis() / 1000): Boolean =
        expires?.toLongOrNull()?.let { it < nowEpochSeconds } ?: false

    fun toUri(): String = buildString {
        append(SCHEME).append("://").append(gameDomain).append("/mods/").append(modId)
            .append("/files/").append(fileId)
        val q = listOfNotNull(
            key?.let { "key=$it" },
            expires?.let { "expires=$it" },
            userId?.let { "user_id=$it" },
        )
        if (q.isNotEmpty()) append('?').append(q.joinToString("&"))
    }

    companion object {
        const val SCHEME = "nxm"
        const val PARAM_KEY = "key"
        const val PARAM_EXPIRES = "expires"
        const val PARAM_USER_ID = "user_id"

        /** Parses [uri]; returns null if it is not a well-formed nxm mod-file link. */
        fun parse(uri: String): NxmLink? {
            val parsed = runCatching { URI(uri.trim()) }.getOrNull() ?: return null
            if (!parsed.scheme.equals(SCHEME, ignoreCase = true)) return null
            val domain = parsed.host?.takeIf { it.isNotBlank() }
                ?: parsed.rawAuthority?.takeIf { it.isNotBlank() }
                ?: return null
            val segments = (parsed.rawPath ?: "").split('/').filter { it.isNotEmpty() }
            if (segments.size != 4 ||
                !segments[0].equals("mods", ignoreCase = true) ||
                !segments[2].equals("files", ignoreCase = true)
            ) {
                return null
            }
            val modId = segments[1].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val fileId = segments[3].toLongOrNull()?.takeIf { it > 0 } ?: return null
            val query = parseQuery(parsed.rawQuery)
            return NxmLink(
                gameDomain = domain.lowercase(),
                modId = modId,
                fileId = fileId,
                key = query[PARAM_KEY]?.takeIf { it.isNotEmpty() },
                expires = query[PARAM_EXPIRES]?.takeIf { it.isNotEmpty() },
                userId = query[PARAM_USER_ID]?.takeIf { it.isNotEmpty() },
            )
        }

        private fun parseQuery(raw: String?): Map<String, String> {
            if (raw.isNullOrEmpty()) return emptyMap()
            return raw.split('&').mapNotNull { pair ->
                if (pair.isEmpty()) return@mapNotNull null
                val i = pair.indexOf('=')
                val k = if (i < 0) pair else pair.substring(0, i)
                val v = if (i < 0) "" else pair.substring(i + 1)
                decode(k) to decode(v)
            }.toMap()
        }

        private fun decode(s: String): String = URLDecoder.decode(s, Charsets.UTF_8.name())
    }
}
