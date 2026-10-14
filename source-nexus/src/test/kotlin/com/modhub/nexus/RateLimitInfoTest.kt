package com.modhub.nexus

import com.modhub.core.source.SourceAuthException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RateLimitInfoTest {
    @Test
    fun parsesHeadersCaseInsensitively() {
        val headers = mapOf(
            "x-rl-daily-limit" to "20000",
            "x-rl-daily-remaining" to " 19876 ",
            "x-rl-daily-reset" to "2026-10-15 00:00:00 +0000",
            "x-rl-hourly-limit" to "500",
            "x-rl-hourly-remaining" to "499",
            "x-rl-hourly-reset" to "2026-10-14T14:00:00+00:00",
        )
        val info = RateLimitInfo.fromHeaders { name -> headers.entries.firstOrNull { it.key.equals(name, true) }?.value }!!
        assertEquals(20000, info.dailyLimit)
        assertEquals(19876, info.dailyRemaining)
        assertEquals(499, info.hourlyRemaining)
        assertEquals(1_792_022_400_000L, info.dailyResetEpochMillis)
        assertEquals(1_791_986_400_000L, info.hourlyResetEpochMillis)
    }

    @Test
    fun noHeadersGivesNull() {
        assertNull(RateLimitInfo.fromHeaders { null })
    }

    @Test
    fun exhausted() {
        assertTrue(RateLimitInfo(dailyRemaining = 0, hourlyRemaining = 0).exhausted)
        assertEquals(false, RateLimitInfo(dailyRemaining = 0, hourlyRemaining = 3).exhausted)
    }

    @Test
    fun httpErrorMapping() {
        assertIs<SourceAuthException>(nexusHttpError(401, """{"message":"Please provide a valid API Key"}""", null))
        assertIs<SourceAuthException>(nexusHttpError(403, null, null))
        val rl = RateLimitInfo(hourlyRemaining = 0)
        val limited = assertIs<RateLimitedException>(nexusHttpError(429, null, rl))
        assertEquals(rl, limited.info)
        assertEquals(404, assertIs<NexusApiException>(nexusHttpError(404, null, null)).statusCode)
    }
}
