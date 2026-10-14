package com.modhub.core.model

import com.modhub.core.storage.StoragePath

/** A mod as listed by a remote [com.modhub.core.source.ModSource]. */
data class ModInfo(
    val id: ModId,
    val sourceId: String,
    val gameId: GameId,
    val name: String,
    val summary: String = "",
    val author: String = "",
    val version: String = "",
    val pictureUrl: String? = null,
    val pageUrl: String? = null,
    /** Epoch millis, or null if unknown. */
    val updatedAt: Long? = null,
    val downloads: Long? = null,
    val endorsements: Long? = null,
    val containsAdultContent: Boolean = false,
)

enum class ModFileCategory { MAIN, UPDATE, OPTIONAL, OLD_VERSION, MISCELLANEOUS, ARCHIVED, UNKNOWN }

/** A downloadable file belonging to a [ModInfo]. */
data class ModFile(
    /** Source-specific file id. */
    val id: String,
    val modId: ModId,
    val sourceId: String,
    val gameId: GameId,
    val name: String,
    val fileName: String,
    val version: String = "",
    val sizeBytes: Long? = null,
    /** Epoch millis, or null if unknown. */
    val uploadedAt: Long? = null,
    val category: ModFileCategory = ModFileCategory.UNKNOWN,
    val isPrimary: Boolean = false,
    val description: String = "",
)

/** A dependency declared by a mod's manifest. */
data class ModDependency(
    val id: String,
    val minimumVersion: String? = null,
    val required: Boolean = true,
)

/**
 * Game-agnostic view of a mod's own metadata file (for Stardew: SMAPI `manifest.json`).
 * Parsing is the plugin's job; core only carries the result.
 */
data class ModManifest(
    /** Unique id declared by the mod itself (SMAPI `UniqueID`). */
    val uniqueId: String,
    val name: String,
    val version: String,
    val author: String = "",
    val description: String = "",
    /** Minimum loader version (SMAPI `MinimumApiVersion`). */
    val minimumLoaderVersion: String? = null,
    val dependencies: List<ModDependency> = emptyList(),
    /** Update keys such as `"Nexus:2400"`. */
    val updateKeys: List<String> = emptyList(),
    /** Anything else the plugin wants to surface. */
    val extra: Map<String, String> = emptyMap(),
)

/** A mod that is present in a game's mods directory. */
data class InstalledMod(
    /**
     * Plugin-defined stable id. Prefer the manifest unique id; fall back to the folder name.
     */
    val id: String,
    val gameId: GameId,
    val name: String,
    val version: String?,
    /** The mod's folder (or file) inside the mods directory. */
    val location: StoragePath,
    val enabled: Boolean = true,
    val manifest: ModManifest? = null,
    val source: ModSourceRef? = null,
    /** Epoch millis, or null if unknown. */
    val installedAt: Long? = null,
    /** Position in the load order, if the game supports one. */
    val loadOrderIndex: Int? = null,
)
