package com.modhub.stardew.di

import com.modhub.core.plugin.GamePlugin
import com.modhub.stardew.StardewPlugin
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet

/** Contributes [StardewPlugin] to the app's `Set<GamePlugin>` (see :app RegistryModule). */
@Module
@InstallIn(SingletonComponent::class)
object StardewModule {
    @Provides
    @IntoSet
    fun plugin(): GamePlugin = StardewPlugin()
}
