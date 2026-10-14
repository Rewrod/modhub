package com.modhub.app.ui.screens.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.modhub.app.data.settings.GameSettings
import com.modhub.app.data.settings.SettingsRepository
import com.modhub.app.data.settings.StorageMode
import com.modhub.app.domain.ModRepository
import com.modhub.app.domain.describe
import com.modhub.app.storage.AndroidFileStorageBackend
import com.modhub.app.storage.StorageBackendProvider
import com.modhub.core.model.GameId
import com.modhub.core.source.SourceRegistry
import com.modhub.nexus.NexusModSource
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface KeyCheck {
    data object Idle : KeyCheck
    data object Checking : KeyCheck
    data class Valid(val userName: String, val premium: Boolean, val supporter: Boolean) : KeyCheck
    data class Invalid(val message: String) : KeyCheck
}

data class GameFolderRow(val gameId: GameId, val name: String, val settings: GameSettings)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val settings: SettingsRepository,
    private val repository: ModRepository,
    private val storageProvider: StorageBackendProvider,
    sources: SourceRegistry,
) : ViewModel() {

    private val nexus = sources.get(NexusModSource.SOURCE_ID) as? NexusModSource

    /** Text in the key field; seeded once from settings. */
    private val _keyDraft = MutableStateFlow("")
    val keyDraft: StateFlow<String> = _keyDraft.asStateFlow()

    private val _keyCheck = MutableStateFlow<KeyCheck>(KeyCheck.Idle)
    val keyCheck: StateFlow<KeyCheck> = _keyCheck.asStateFlow()

    val storageMode: StateFlow<StorageMode> = settings.storageMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StorageMode.AUTO)

    val savedKey: StateFlow<String?> = settings.nexusApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val gameFolders: StateFlow<List<GameFolderRow>> = run {
        val games = repository.games()
        if (games.isEmpty()) {
            flowOf(emptyList())
        } else {
            combine(games.map { p -> settings.gameSettings(p.id).map { GameFolderRow(p.id, p.displayName, it) } }) { it.toList() }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _allFilesAccess = MutableStateFlow(AndroidFileStorageBackend.hasAllFilesAccess(appContext))
    val allFilesAccess: StateFlow<Boolean> = _allFilesAccess.asStateFlow()

    val shizukuAvailable: Boolean get() = storageProvider.shizukuBackend.isAvailable()

    init {
        viewModelScope.launch { _keyDraft.value = settings.nexusApiKey.first().orEmpty() }
    }

    fun onResume() {
        _allFilesAccess.value = AndroidFileStorageBackend.hasAllFilesAccess(appContext)
    }

    fun setKeyDraft(value: String) {
        _keyDraft.value = value
        _keyCheck.value = KeyCheck.Idle
    }

    /** Saves the draft and validates it against Nexus. */
    fun saveAndValidateKey() {
        val key = _keyDraft.value.trim()
        viewModelScope.launch {
            settings.setNexusApiKey(key.ifEmpty { null })
            if (key.isEmpty()) {
                _keyCheck.value = KeyCheck.Idle
                return@launch
            }
            val source = nexus ?: run {
                _keyCheck.value = KeyCheck.Invalid("Nexus Mods source is not available in this build")
                return@launch
            }
            _keyCheck.value = KeyCheck.Checking
            _keyCheck.value = try {
                val user = source.validateKey(forceRefresh = true)
                KeyCheck.Valid(user.name ?: "(unknown user)", user.isPremium, user.isSupporter)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                KeyCheck.Invalid(describe(e))
            }
        }
    }

    fun clearKey() {
        _keyDraft.value = ""
        _keyCheck.value = KeyCheck.Idle
        viewModelScope.launch { settings.setNexusApiKey(null) }
    }

    fun setStorageMode(mode: StorageMode) {
        if (mode == StorageMode.SHIZUKU && !shizukuAvailable) return
        viewModelScope.launch { settings.setStorageMode(mode) }
    }

    fun clearGameFolder(gameId: GameId) {
        viewModelScope.launch { repository.clearModsFolder(gameId) }
    }
}
