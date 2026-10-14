package com.modhub.nexus

import com.modhub.core.model.GameId
import com.modhub.core.model.ModFileCategory
import com.modhub.core.model.ModId
import com.modhub.core.model.ModInfo
import com.modhub.core.source.ModListing
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NexusMappingTest {
    private val game = GameId("stardew")

    @Test
    fun mapsModDto() {
        val info = NexusMapping.toModInfo(NexusFixtures.contentPatcher(), game, "stardewvalley")
        assertEquals(ModId("1915"), info.id)
        assertEquals("nexus", info.sourceId)
        assertEquals(game, info.gameId)
        assertEquals("Content Patcher", info.name)
        assertEquals("Loads content packs.\nNo XNB needed.", info.summary)
        assertEquals("Pathoschild", info.author)
        assertEquals("2.4.0", info.version)
        assertEquals("https://staticdelivery.nexusmods.com/cp.png", info.pictureUrl)
        assertEquals("https://www.nexusmods.com/stardewvalley/mods/1915", info.pageUrl)
        assertEquals(1_700_000_100_000L, info.updatedAt)
        assertEquals(5_000_000L, info.downloads)
        assertEquals(90_000L, info.endorsements)
        assertFalse(info.containsAdultContent)
    }

    @Test
    fun modFallbacks() {
        val info = NexusMapping.toModInfo(
            NexusModDto(modId = 7, user = NexusModUserDto(name = "uploader"), createdTimestamp = 5),
            game,
            "stardewvalley",
        )
        assertEquals("Mod 7", info.name)
        assertEquals("uploader", info.author)
        assertEquals(5000L, info.updatedAt)
        assertNull(info.pictureUrl)
    }

    @Test
    fun categoryIds() {
        assertEquals(ModFileCategory.MAIN, NexusMapping.category(1))
        assertEquals(ModFileCategory.UPDATE, NexusMapping.category(2))
        assertEquals(ModFileCategory.OPTIONAL, NexusMapping.category(3))
        assertEquals(ModFileCategory.OLD_VERSION, NexusMapping.category(4))
        assertEquals(ModFileCategory.MISCELLANEOUS, NexusMapping.category(5))
        assertEquals(ModFileCategory.ARCHIVED, NexusMapping.category(6))
        assertEquals(ModFileCategory.UNKNOWN, NexusMapping.category(42))
        assertEquals(ModFileCategory.OPTIONAL, NexusMapping.category(null, "optional"))
        assertEquals(ModFileCategory.UNKNOWN, NexusMapping.category(null))
    }

    @Test
    fun mapsAndSortsFiles() {
        val files = NexusMapping.toModFiles(NexusJson.files(NexusFixtures.FILES_JSON), ModId("1915"), game)
        assertEquals(listOf("101", "103", "100"), files.map { it.id })
        val main = files.first()
        assertEquals(ModFileCategory.MAIN, main.category)
        assertEquals("Content Patcher", main.name)
        assertEquals("Content Patcher 2.4.0-1915-2-4-0-1700000000.zip", main.fileName)
        assertEquals("2.4.0", main.version)
        assertEquals(1_048_576L, main.sizeBytes)
        assertEquals(1_700_000_000_000L, main.uploadedAt)
        assertTrue(main.isPrimary)
        assertEquals(ModId("1915"), main.modId)
        assertEquals("nexus", main.sourceId)
        assertEquals(ModFileCategory.OPTIONAL, files[1].category)
        assertEquals(2048L, files[1].sizeBytes) // from size_kb
        assertEquals(ModFileCategory.OLD_VERSION, files[2].category)
    }

    @Test
    fun listingMapsToEndpoints() {
        assertEquals("latest_added", NexusMapping.toNexusList(ModListing.LATEST_ADDED).path)
        assertEquals("latest_updated", NexusMapping.toNexusList(ModListing.LATEST_UPDATED).path)
        assertEquals("trending", NexusMapping.toNexusList(ModListing.TRENDING).path)
    }

    @Test
    fun listableFiltersHiddenMods() {
        assertTrue(NexusMapping.isListable(NexusFixtures.contentPatcher()))
        assertFalse(NexusMapping.isListable(NexusModDto(modId = 1, name = null, available = false, status = "hidden")))
        assertFalse(NexusMapping.isListable(NexusModDto(modId = 1, name = "x", status = "removed")))
    }

    @Test
    fun clientSideMatchRequiresAllTerms() {
        val mod = ModInfo(ModId("1"), "nexus", game, "Content Patcher", summary = "Loads content packs", author = "Pathoschild")
        assertTrue(NexusMapping.matches(mod, "content"))
        assertTrue(NexusMapping.matches(mod, "  PACKS   patho "))
        assertTrue(NexusMapping.matches(mod, ""))
        assertFalse(NexusMapping.matches(mod, "content farm"))
    }

    @Test
    fun paging() {
        val items = (1..5).map { ModInfo(ModId("$it"), "nexus", game, "m$it") }
        NexusMapping.page(items, 0, 2).let { assertEquals(listOf("1", "2"), it.items.map { m -> m.id.value }); assertTrue(it.hasMore) }
        NexusMapping.page(items, 2, 2).let { assertEquals(listOf("5"), it.items.map { m -> m.id.value }); assertFalse(it.hasMore) }
        assertTrue(NexusMapping.page(items, 9, 2).items.isEmpty())
    }
}
