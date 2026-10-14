package com.modhub.core.source

import com.modhub.core.model.SourceGameRef
import com.modhub.core.plugin.GamePlugin

/** All mod sources, keyed by [ModSource.id]. */
class SourceRegistry(sources: Collection<ModSource> = emptyList()) {
    private val byId = LinkedHashMap<String, ModSource>()

    init {
        sources.sortedBy { it.displayName.lowercase() }.forEach(::register)
    }

    @Synchronized
    fun register(source: ModSource) {
        require(source.id !in byId) { "Duplicate mod source id: ${source.id}" }
        byId[source.id] = source
    }

    @Synchronized
    fun all(): List<ModSource> = byId.values.toList()

    @Synchronized
    fun get(id: String): ModSource? = byId[id]

    fun require(id: String): ModSource = get(id) ?: throw NoSuchElementException("No mod source '$id'")

    /** Sources that offer mods for [plugin]'s game. */
    fun sourcesFor(plugin: GamePlugin): List<ModSource> = all().filter { gameRef(plugin, it) != null }

    /** How [source] identifies [plugin]'s game, or null if it doesn't carry it. */
    fun gameRef(plugin: GamePlugin, source: ModSource): SourceGameRef? {
        val key = plugin.sourceGameKeys[source.id] ?: source.defaultGameKey(plugin.id) ?: return null
        return SourceGameRef(plugin.id, key)
    }
}
