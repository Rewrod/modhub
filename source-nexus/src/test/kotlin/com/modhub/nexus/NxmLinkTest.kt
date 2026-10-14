package com.modhub.nexus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NxmLinkTest {
    @Test
    fun parsesFullLink() {
        val link = NxmLink.parse("nxm://stardewvalley/mods/2400/files/12345?key=abcDEF_123&expires=1760400000&user_id=987")
        assertEquals(NxmLink("stardewvalley", 2400, 12345, "abcDEF_123", "1760400000", "987"), link)
    }

    @Test
    fun parsesWithoutQuery() {
        val link = NxmLink.parse("nxm://stardewvalley/mods/1/files/2")!!
        assertEquals(1, link.modId)
        assertEquals(2, link.fileId)
        assertNull(link.key)
        assertTrue(link.toDownloadAuth().params.isEmpty())
    }

    @Test
    fun schemeAndDomainAreCaseInsensitiveAndDomainIsLowercased() {
        val link = NxmLink.parse("NXM://StardewValley/Mods/7/Files/8?key=k")!!
        assertEquals("stardewvalley", link.gameDomain)
        assertEquals("k", link.key)
    }

    @Test
    fun decodesPercentEncodedParamsAndIgnoresUnknownOnes() {
        val link = NxmLink.parse("nxm://skyrimspecialedition/mods/266/files/9?key=a%2Bb%3D&foo=bar&expires=5")!!
        assertEquals("a+b=", link.key)
        assertEquals("5", link.expires)
        assertNull(link.userId)
    }

    @Test
    fun toleratesTrailingSlashAndWhitespace() {
        assertEquals(3L, NxmLink.parse("  nxm://stardewvalley/mods/3/files/4/  ")?.modId)
    }

    @Test
    fun rejectsMalformedLinks() {
        listOf(
            "https://stardewvalley/mods/1/files/2",
            "nxm:///mods/1/files/2",
            "nxm://stardewvalley/mods/abc/files/2",
            "nxm://stardewvalley/mods/1/files/",
            "nxm://stardewvalley/mods/1",
            "nxm://stardewvalley/collections/xyz/revisions/3",
            "nxm://stardewvalley/mods/0/files/2",
            "nxm://stardewvalley/mods/1/files/2/extra",
            "not a uri at all %%",
            "",
        ).forEach { assertNull(NxmLink.parse(it), "should reject: $it") }
    }

    @Test
    fun toDownloadAuthCarriesKeyExpiresAndUser() {
        val auth = NxmLink("stardewvalley", 1, 2, key = "k", expires = "99", userId = "u").toDownloadAuth()
        assertEquals(mapOf("key" to "k", "expires" to "99", "user_id" to "u"), auth.params)
    }

    @Test
    fun roundTripsThroughToUri() {
        val link = NxmLink("stardewvalley", 10, 20, key = "k", expires = "30", userId = "40")
        assertEquals(link, NxmLink.parse(link.toUri()))
    }

    @Test
    fun expiry() {
        val link = NxmLink("d", 1, 2, expires = "1000")
        assertTrue(link.isExpired(nowEpochSeconds = 1001))
        assertFalse(link.isExpired(nowEpochSeconds = 999))
        assertFalse(NxmLink("d", 1, 2).isExpired())
    }
}
