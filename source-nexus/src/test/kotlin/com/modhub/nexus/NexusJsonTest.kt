package com.modhub.nexus

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NexusJsonTest {
    @Test
    fun decodesModAndIgnoresUnknownKeys() {
        val mod = NexusJson.mod(NexusFixtures.MOD_JSON)
        assertEquals(1915, mod.modId)
        assertEquals("Content Patcher", mod.name)
        assertEquals(5_000_000L, mod.modDownloads)
        assertEquals("Pathoschild", mod.user?.name)
    }

    @Test
    fun decodesModListWithHiddenEntry() {
        val list = NexusJson.modList(NexusFixtures.MOD_LIST_JSON)
        assertEquals(2, list.size)
        assertFalse(list[1].available)
        assertNull(list[1].name)
    }

    @Test
    fun decodesUser() {
        val u = NexusJson.user("""{"user_id":42,"key":"secret","name":"Ann","is_premium?":false,"is_supporter?":false,"is_premium":true,"is_supporter":true,"email":"a@b","profile_url":"x"}""")
        assertEquals(42L, u.userId)
        assertEquals("Ann", u.name)
        assertTrue(u.isPremium)
    }

    @Test
    fun nullBooleansCoerceToDefaults() {
        val u = NexusJson.user("""{"name":"Ann","is_premium":null}""")
        assertFalse(u.isPremium)
    }

    @Test
    fun decodesDownloadLinks() {
        val links = NexusJson.downloadLinks(NexusFixtures.DOWNLOAD_LINKS_JSON)
        assertEquals(listOf("Nexus CDN", "Amsterdam"), links.map { it.name })
        assertEquals("https://cf-files.nexusmods.com/cdn/1/a.zip?md5=x", links[0].uri)
    }

    @Test
    fun decodesMd5Search() {
        val results = NexusJson.md5Search(
            """[{"mod":${NexusFixtures.MOD_JSON},"file_details":{"file_id":101,"name":"Main","md5":"abc","category_id":1}}]""",
        )
        assertEquals(1915, results.single().mod.modId)
        assertEquals(101, results.single().fileDetails.fileId)
    }

    @Test
    fun decodesGame() {
        val g = NexusJson.game("""{"id":1303,"name":"Stardew Valley","domain_name":"stardewvalley","categories":[{"category_id":1}]}""")
        assertEquals("stardewvalley", g.domainName)
    }

    @Test
    fun errorMessage() {
        assertEquals("You don't have permission", NexusJson.errorMessage("""{"code":403,"message":"You don't have permission"}"""))
        assertNull(NexusJson.errorMessage("<html>nope</html>"))
        assertNull(NexusJson.errorMessage(null))
    }
}
