package com.modhub.app.di

import com.modhub.core.install.ModInstaller
import com.modhub.core.plugin.GamePlugin
import com.modhub.core.plugin.PluginRegistry
import com.modhub.core.source.ModSource
import com.modhub.core.source.SourceRegistry
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.Multibinds
import javax.inject.Singleton

/**
 * Plugins and sources are Hilt multibindings. To add one, contribute it from any module:
 *
 * ```
 * @Module @InstallIn(SingletonComponent::class)
 * abstract class StardewBindings {
 *     @Binds @IntoSet abstract fun bind(p: StardewPlugin): GamePlugin
 * }
 * ```
 * (or `@Provides @IntoSet fun provide(): GamePlugin = StardewPlugin()`).
 * The `@Multibinds` declarations below make the sets valid even while empty.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class MultibindingDeclarations {
    @Multibinds
    abstract fun gamePlugins(): Set<GamePlugin>

    @Multibinds
    abstract fun modSources(): Set<ModSource>
}

@Module
@InstallIn(SingletonComponent::class)
object RegistryModule {
    @Provides
    @Singleton
    fun pluginRegistry(plugins: Set<@JvmSuppressWildcards GamePlugin>): PluginRegistry = PluginRegistry(plugins)

    @Provides
    @Singleton
    fun sourceRegistry(sources: Set<@JvmSuppressWildcards ModSource>): SourceRegistry = SourceRegistry(sources)

    @Provides
    @Singleton
    fun modInstaller(): ModInstaller = ModInstaller()
}
