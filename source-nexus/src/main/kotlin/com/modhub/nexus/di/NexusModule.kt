package com.modhub.nexus.di

import com.modhub.core.source.ModSource
import com.modhub.nexus.ApiKeyProvider
import com.modhub.nexus.LocalFileSource
import com.modhub.nexus.NexusApi
import com.modhub.nexus.NexusApiClient
import com.modhub.nexus.NexusModSource
import dagger.BindsOptionalOf
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import okhttp3.OkHttpClient
import java.util.Optional
import javax.inject.Qualifier
import javax.inject.Singleton

/** Qualifies the `String` sent as Nexus `Application-Version` (optional; the app may bind it). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class NexusAppVersion

/**
 * Contributes [NexusModSource] and [LocalFileSource] to the app's `Set<ModSource>`.
 *
 * Needs from the app graph (deliberately NOT provided here):
 *  - `OkHttpClient`: :app's `AppModule.okHttp()` (shared client, User-Agent, redacted logging).
 *  - [ApiKeyProvider] (**required**): bind it in :app from `SettingsRepository`, e.g.
 *    `@Provides fun nexusKey(s: SettingsRepository) = ApiKeyProvider { s.nexusApiKey.first() }`.
 *  - `@NexusAppVersion String` (optional): e.g. `BuildConfig.VERSION_NAME`. Defaults to "dev".
 */
@Module
@InstallIn(SingletonComponent::class)
object NexusModule {
    @Provides
    @Singleton
    fun nexusApi(
        client: OkHttpClient,
        keyProvider: ApiKeyProvider,
        @NexusAppVersion appVersion: Optional<String>,
    ): NexusApi = NexusApiClient(client, keyProvider, appVersion.orElse(DEFAULT_APP_VERSION))

    @Provides
    @Singleton
    fun nexusModSource(api: NexusApi, keyProvider: ApiKeyProvider): NexusModSource =
        NexusModSource(api, keyProvider)

    @Provides
    @Singleton
    fun localFileSource(): LocalFileSource = LocalFileSource()

    @Provides
    @IntoSet
    fun nexusIntoSet(source: NexusModSource): ModSource = source

    @Provides
    @IntoSet
    fun localIntoSet(source: LocalFileSource): ModSource = source

    const val DEFAULT_APP_VERSION = "dev"
}

@Module
@InstallIn(SingletonComponent::class)
abstract class NexusOptionalBindings {
    @BindsOptionalOf
    @NexusAppVersion
    abstract fun appVersion(): String
}
