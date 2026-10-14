package com.modhub.core.model

/** Stable identifier of a supported game, e.g. `GameId("stardew")`. Owned by the [com.modhub.core.plugin.GamePlugin]. */
@JvmInline
value class GameId(val value: String) {
    init {
        require(value.isNotBlank()) { "GameId must not be blank" }
    }

    override fun toString(): String = value
}

/**
 * Identifier of a mod *within a mod source* (e.g. the Nexus numeric mod id as a string).
 * Always pair it with the source id ([ModSourceRef]) when persisting.
 */
@JvmInline
value class ModId(val value: String) {
    override fun toString(): String = value
}

/** Points back to where an installed mod came from, so updates can be checked later. */
data class ModSourceRef(
    /** [com.modhub.core.source.ModSource.id], e.g. "nexus". */
    val sourceId: String,
    val modId: ModId,
    /** Source-specific file id, if known. */
    val fileId: String? = null,
    val version: String? = null,
)

/** A game as a particular [com.modhub.core.source.ModSource] knows it (e.g. Nexus domain "stardewvalley"). */
data class SourceGameRef(
    val gameId: GameId,
    /** Source-specific key for the game. For Nexus this is the game domain name. */
    val key: String,
)
