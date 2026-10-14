package com.modhub.nexus

import com.modhub.core.model.GameId
import com.modhub.core.model.ModFile
import com.modhub.core.model.ModFileCategory
import com.modhub.core.model.ModId
import com.modhub.core.model.ModInfo
import com.modhub.core.source.ModListing
import com.modhub.core.source.ModPage

/** Pure DTO -> :core model mapping. No I/O, so it is tested without OkHttp. */
object NexusMapping {
    const val SOURCE_ID = "nexus"

    fun modPageUrl(domain: String, modId: Long): String = "https://www.nexusmods.com/$domain/mods/$modId"

    fun toModInfo(dto: NexusModDto, gameId: GameId, domain: String): ModInfo = ModInfo(
        id = ModId(dto.modId.toString()),
        sourceId = SOURCE_ID,
        gameId = gameId,
        name = dto.name?.takeIf { it.isNotBlank() } ?: "Mod ${dto.modId}",
        summary = dto.summary.orEmpty().let(::stripBbBreaks),
        author = dto.author?.takeIf { it.isNotBlank() }
            ?: dto.user?.name?.takeIf { it.isNotBlank() }
            ?: dto.uploadedBy.orEmpty(),
        version = dto.version.orEmpty(),
        pictureUrl = dto.pictureUrl?.takeIf { it.isNotBlank() },
        pageUrl = modPageUrl(dto.domainName?.takeIf { it.isNotBlank() } ?: domain, dto.modId),
        updatedAt = (dto.updatedTimestamp ?: dto.createdTimestamp)?.let { it * 1000 },
        downloads = dto.modDownloads,
        endorsements = dto.endorsementCount,
        containsAdultContent = dto.containsAdultContent,
    )

    /** Nexus `category_id`: 1 MAIN, 2 UPDATE, 3 OPTIONAL, 4 OLD_VERSION, 5 MISCELLANEOUS, 6 ARCHIVED. */
    fun category(categoryId: Int?, categoryName: String? = null): ModFileCategory = when (categoryId) {
        1 -> ModFileCategory.MAIN
        2 -> ModFileCategory.UPDATE
        3 -> ModFileCategory.OPTIONAL
        4 -> ModFileCategory.OLD_VERSION
        5 -> ModFileCategory.MISCELLANEOUS
        6, 7 -> ModFileCategory.ARCHIVED // 7 = "DELETED" in some API responses; not downloadable either
        else -> when (categoryName?.uppercase()) {
            "MAIN" -> ModFileCategory.MAIN
            "UPDATE" -> ModFileCategory.UPDATE
            "OPTIONAL" -> ModFileCategory.OPTIONAL
            "OLD_VERSION" -> ModFileCategory.OLD_VERSION
            "MISCELLANEOUS" -> ModFileCategory.MISCELLANEOUS
            "ARCHIVED" -> ModFileCategory.ARCHIVED
            else -> ModFileCategory.UNKNOWN
        }
    }

    fun toModFile(dto: NexusFileDto, modId: ModId, gameId: GameId): ModFile = ModFile(
        id = dto.fileId.toString(),
        modId = modId,
        sourceId = SOURCE_ID,
        gameId = gameId,
        name = dto.name?.takeIf { it.isNotBlank() } ?: dto.fileName ?: "File ${dto.fileId}",
        fileName = dto.fileName?.takeIf { it.isNotBlank() } ?: "${dto.fileId}.zip",
        version = dto.version ?: dto.modVersion.orEmpty(),
        sizeBytes = dto.sizeInBytes ?: dto.sizeKb?.let { it * 1024 },
        uploadedAt = dto.uploadedTimestamp?.let { it * 1000 },
        category = category(dto.categoryId, dto.categoryName),
        isPrimary = dto.isPrimary,
        description = dto.description.orEmpty().let(::stripBbBreaks),
    )

    /**
     * Files ordered for display: MAIN, UPDATE, OPTIONAL, MISCELLANEOUS, OLD_VERSION, ARCHIVED,
     * newest first within a category.
     */
    fun toModFiles(response: NexusFilesResponseDto, modId: ModId, gameId: GameId): List<ModFile> =
        response.files
            .map { toModFile(it, modId, gameId) }
            .sortedWith(compareBy<ModFile> { CATEGORY_ORDER.indexOf(it.category) }.thenByDescending { it.uploadedAt ?: 0 })

    private val CATEGORY_ORDER = listOf(
        ModFileCategory.MAIN,
        ModFileCategory.UPDATE,
        ModFileCategory.OPTIONAL,
        ModFileCategory.MISCELLANEOUS,
        ModFileCategory.UNKNOWN,
        ModFileCategory.OLD_VERSION,
        ModFileCategory.ARCHIVED,
    )

    fun toNexusList(listing: ModListing): NexusModList = when (listing) {
        ModListing.LATEST_ADDED -> NexusModList.LATEST_ADDED
        ModListing.LATEST_UPDATED -> NexusModList.LATEST_UPDATED
        ModListing.TRENDING -> NexusModList.TRENDING
    }

    /** Hidden/removed entries show up in lists with no name; they can't be used. */
    fun isListable(dto: NexusModDto): Boolean =
        dto.available && (dto.status == null || dto.status == "published") && !dto.name.isNullOrBlank()

    /**
     * Client-side "search" over already-fetched mods: every whitespace-separated term must
     * appear (case-insensitively) in the name, summary or author. Blank query matches all.
     */
    fun matches(mod: ModInfo, text: String): Boolean {
        val terms = text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return true
        val haystack = "${mod.name}\n${mod.summary}\n${mod.author}".lowercase()
        return terms.all { it in haystack }
    }

    fun page(items: List<ModInfo>, page: Int, pageSize: Int): ModPage {
        val size = pageSize.coerceAtLeast(1)
        val from = (page.coerceAtLeast(0) * size).coerceAtMost(items.size)
        val to = (from + size).coerceAtMost(items.size)
        return ModPage(items.subList(from, to), page, hasMore = to < items.size)
    }

    /** Nexus summaries sometimes carry `<br />` / `[br][/br]` markup. */
    private fun stripBbBreaks(s: String): String =
        s.replace(Regex("<br\\s*/?>|\\[br]\\[/br]|\\[br]", RegexOption.IGNORE_CASE), "\n").trim()
}
