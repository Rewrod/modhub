package com.modhub.app.ui.navigation

import androidx.lifecycle.ViewModel
import com.modhub.app.domain.ModRepository
import com.modhub.app.domain.describe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import javax.inject.Inject

/** Activity-scoped: turns incoming `nxm://` links into download jobs and navigation. */
@HiltViewModel
class AppViewModel @Inject constructor(
    private val repository: ModRepository,
) : ViewModel() {

    private val _openGame = Channel<String>(Channel.BUFFERED)
    /** Game ids to navigate to (the game whose mod is being downloaded). */
    val openGame: Flow<String> = _openGame.receiveAsFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun onNxmLink(uri: String) {
        runCatching { repository.handleNxmLink(uri) }
            .onSuccess { gameId -> _openGame.trySend(gameId.value) }
            .onFailure { _error.value = describe(it) }
    }

    fun dismissError() {
        _error.value = null
    }
}
