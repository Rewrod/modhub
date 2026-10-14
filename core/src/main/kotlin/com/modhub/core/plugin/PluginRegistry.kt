package com.modhub.core.plugin

import com.modhub.core.model.GameId

/** All installed game plugins, keyed by [GamePlugin.id]. Thread-safe for reads after construction. */
class PluginRegistry(plugins: Collection<GamePlugin> = emptyList()) {
    private val byId = LinkedHashMap<GameId, GamePlugin>()

    init {
        plugins.sortedBy { it.displayName.lowercase() }.forEach(::register)
    }

    @Synchronized
    fun register(plugin: GamePlugin) {
        require(plugin.id !in byId) { "Duplicate game plugin id: ${plugin.id}" }
        byId[plugin.id] = plugin
    }

    @Synchronized
    fun all(): List<GamePlugin> = byId.values.toList()

    @Synchronized
    fun get(id: GameId): GamePlugin? = byId[id]

    fun require(id: GameId): GamePlugin = get(id) ?: throw NoSuchElementException("No game plugin '$id'")

    /** Plugin owning an Android package name (e.g. to handle "open with" or detect installs). */
    @Synchronized
    fun findByPackage(packageName: String): GamePlugin? = byId.values.firstOrNull { packageName in it.packageNames }
}
