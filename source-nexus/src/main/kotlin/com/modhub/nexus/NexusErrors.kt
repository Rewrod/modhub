package com.modhub.nexus

import com.modhub.core.source.SourceAuthException
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Request quota from the `x-rl-*` response headers. Limits are per API key; Nexus has
 * changed the numbers before, so always read them rather than hardcoding.
 */
data class RateLimitInfo(
    val dailyLimit: Int? = null,
    val dailyRemaining: Int? = null,
    /** Raw `x-rl-daily-reset` value, e.g. "2026-10-15 00:00:00 +0000". */
    val dailyReset: String? = null,
    val hourlyLimit: Int? = null,
    val hourlyRemaining: Int? = null,
    val hourlyReset: String? = null,
) {
    val dailyResetEpochMillis: Long? get() = parseResetTime(dailyReset)
    val hourlyResetEpochMillis: Long? get() = parseResetTime(hourlyReset)

    /** True once both the daily and the hourly allowance are used up. */
    val exhausted: Boolean
        get() = (dailyRemaining ?: 1) <= 0 && (hourlyRemaining ?: 1) <= 0

    companion object {
        /**
         * Reads the rate-limit headers through [header] (case-insensitive lookup supplied by
         * the HTTP layer). Returns null if the response carried none of them.
         */
        fun fromHeaders(header: (String) -> String?): RateLimitInfo? {
            val info = RateLimitInfo(
                dailyLimit = header("x-rl-daily-limit")?.trim()?.toIntOrNull(),
                dailyRemaining = header("x-rl-daily-remaining")?.trim()?.toIntOrNull(),
                dailyReset = header("x-rl-daily-reset")?.trim()?.takeIf { it.isNotEmpty() },
                hourlyLimit = header("x-rl-hourly-limit")?.trim()?.toIntOrNull(),
                hourlyRemaining = header("x-rl-hourly-remaining")?.trim()?.toIntOrNull(),
                hourlyReset = header("x-rl-hourly-reset")?.trim()?.takeIf { it.isNotEmpty() },
            )
            return if (info == RateLimitInfo()) null else info
        }

        private val NEXUS_RESET_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z")

        /** Parses "2026-10-15 00:00:00 +0000" or ISO-8601 offset date-times. */
        fun parseResetTime(value: String?): Long? {
            if (value.isNullOrBlank()) return null
            val v = value.trim()
            return runCatching { OffsetDateTime.parse(v, NEXUS_RESET_FORMAT) }
                .recoverCatching { OffsetDateTime.parse(v) }
                .getOrNull()
                ?.toInstant()
                ?.toEpochMilli()
        }
    }
}

/** HTTP 429: quota used up. [info] holds the headers of that response, if any. */
class RateLimitedException(
    val info: RateLimitInfo?,
    message: String = "Nexus Mods API rate limit reached" +
        (info?.hourlyReset?.let { "; hourly quota resets at $it" } ?: ""),
) : Exception(message)

/** Any other non-2xx response (404, 5xx, ...). */
class NexusApiException(val statusCode: Int, message: String) : Exception(message)

/**
 * The account behind the API key is not Premium, so Nexus refuses a direct
 * `download_link.json` request. The user has to start the download on the website.
 */
class NexusPremiumRequiredException(
    message: String = PREMIUM_REQUIRED_MESSAGE,
    cause: Throwable? = null,
) : Exception(message, cause) {
    companion object {
        const val PREMIUM_REQUIRED_MESSAGE =
            "Direct downloads from Nexus Mods need a Premium account. Open the mod's Files tab on " +
                "nexusmods.com and tap \"Mod Manager Download\" (\"Download with manager\"). The site will " +
                "hand ModHub an nxm:// link with a one-time key, and the download will start from there."
    }
}

/** An nxm:// link was used but Nexus rejected its key (expired, other user, already used). */
class NexusNxmLinkRejectedException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Maps an HTTP failure status to the exception the rest of the app understands. */
internal fun nexusHttpError(code: Int, body: String?, rateLimit: RateLimitInfo?): Exception {
    val msg = NexusJson.errorMessage(body)
    return when (code) {
        401 -> SourceAuthException("Nexus Mods rejected the API key (HTTP 401)" + (msg?.let { ": $it" } ?: ""))
        403 -> SourceAuthException("Nexus Mods refused the request (HTTP 403)" + (msg?.let { ": $it" } ?: ""))
        429 -> RateLimitedException(rateLimit, msg ?: "Nexus Mods API rate limit reached (HTTP 429)")
        else -> NexusApiException(code, "Nexus Mods API error HTTP $code" + (msg?.let { ": $it" } ?: ""))
    }
}

/**
 * `download_link.json` answered 403. Raised by [NexusApi.downloadLinks]; [NexusModSource]
 * turns it into [NexusPremiumRequiredException], [NexusNxmLinkRejectedException] or a
 * [SourceAuthException] depending on the account and on whether an nxm key was sent.
 */
class NexusDownloadForbiddenException(
    val usedNxmKey: Boolean,
    message: String,
) : Exception(message)
