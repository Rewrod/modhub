package com.modhub.app.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.modhub.core.model.GameId
import com.modhub.core.storage.StoragePath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** How ModHub reaches a game's mods directory. See docs/ARCHITECTURE.md. */
enum class StorageMode {
    /** Pick automatically: FILE if the path is writable, else SAF if a tree is granted, else SHIZUKU. */
    AUTO,
    /** java.io.File; needs All-files access on 11+. Not for Android/data. */
    FILE,
    /** Storage Access Framework tree grant. Not for Android/data on 11+. */
    SAF,
    /** Privileged file ops through Shizuku. Works for Android/data. */
    SHIZUKU,
}

/** Per-game settings. */
data class GameSettings(
    val modsPathOverride: StoragePath? = null,
    /** Persisted SAF tree URI (string form) for this game's mods dir, if granted. */
    val safTreeUri: String? = null,
)

data class AppSettings(
    val nexusApiKey: String? = null,
    val storageMode: StorageMode = StorageMode.AUTO,
)

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(name = "modhub_settings")

/**
 * DataStore-backed settings. NOTE: the Nexus API key is stored in plain DataStore for now.
 * TODO: move it to EncryptedSharedPreferences / Keystore-wrapped storage before release.
 */
@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val store = context.settingsDataStore

    val settings: Flow<AppSettings> = store.data.map { p ->
        AppSettings(
            nexusApiKey = p[KEY_NEXUS_API_KEY]?.takeIf { it.isNotBlank() },
            storageMode = p[KEY_STORAGE_MODE]?.let { runCatching { StorageMode.valueOf(it) }.getOrNull() }
                ?: StorageMode.AUTO,
        )
    }

    val nexusApiKey: Flow<String?> = settings.map { it.nexusApiKey }
    val storageMode: Flow<StorageMode> = settings.map { it.storageMode }

    fun gameSettings(gameId: GameId): Flow<GameSettings> = store.data.map { p ->
        GameSettings(
            modsPathOverride = p[modsPathKey(gameId)]?.let { runCatching { StoragePath.of(it) }.getOrNull() },
            safTreeUri = p[safTreeKey(gameId)],
        )
    }

    suspend fun currentGameSettings(gameId: GameId): GameSettings = gameSettings(gameId).first()
    suspend fun current(): AppSettings = settings.first()

    suspend fun setNexusApiKey(key: String?) = store.edit { p ->
        if (key.isNullOrBlank()) p.remove(KEY_NEXUS_API_KEY) else p[KEY_NEXUS_API_KEY] = key.trim()
    }

    suspend fun setStorageMode(mode: StorageMode) = store.edit { it[KEY_STORAGE_MODE] = mode.name }

    suspend fun setModsPathOverride(gameId: GameId, path: StoragePath?) = store.edit { p ->
        if (path == null) p.remove(modsPathKey(gameId)) else p[modsPathKey(gameId)] = path.value
    }

    suspend fun setSafTreeUri(gameId: GameId, uri: String?) = store.edit { p ->
        if (uri == null) p.remove(safTreeKey(gameId)) else p[safTreeKey(gameId)] = uri
    }

    private companion object {
        val KEY_NEXUS_API_KEY = stringPreferencesKey("nexus_api_key")
        val KEY_STORAGE_MODE = stringPreferencesKey("storage_mode")
        fun modsPathKey(id: GameId) = stringPreferencesKey("game.${id.value}.mods_path")
        fun safTreeKey(id: GameId) = stringPreferencesKey("game.${id.value}.saf_tree")
    }
}
