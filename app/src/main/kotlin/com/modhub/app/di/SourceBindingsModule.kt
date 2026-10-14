package com.modhub.app.di

import com.modhub.app.BuildConfig
import com.modhub.app.data.settings.SettingsRepository
import com.modhub.app.domain.DependencyChecker
import com.modhub.app.domain.StardewDependencyChecker
import com.modhub.nexus.ApiKeyProvider
import com.modhub.nexus.di.NexusAppVersion
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import dagger.multibindings.Multibinds
import kotlinx.coroutines.flow.first

/** What `:source-nexus` needs from the app graph (see its NexusModule KDoc). */
@Module
@InstallIn(SingletonComponent::class)
object NexusAppBindings {
    /** Read on every request, so a key change in Settings applies immediately. */
    @Provides
    fun nexusApiKey(settings: SettingsRepository): ApiKeyProvider =
        ApiKeyProvider { settings.nexusApiKey.first() }

    /** Sent as the Nexus `Application-Version` header; fills NexusModule's `Optional<String>`. */
    @Provides
    @NexusAppVersion
    fun nexusAppVersion(): String = BuildConfig.VERSION_NAME
}

/** Per-game dependency checkers; a game without one just shows no dependency warnings. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DependencyCheckerBindings {
    @Multibinds
    abstract fun dependencyCheckers(): Set<DependencyChecker>

    @Binds
    @IntoSet
    abstract fun stardew(impl: StardewDependencyChecker): DependencyChecker
}
