package com.modhub.app.ui.screens.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.modhub.app.domain.GameStatus
import com.modhub.app.domain.ModRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class GameRow(val id: String, val name: String, val status: GameStatus?)

data class GamesUiState(
    val loading: Boolean = true,
    val games: List<GameRow> = emptyList(),
)

@HiltViewModel
class GamesViewModel @Inject constructor(
    private val repository: ModRepository,
) : ViewModel() {

    private val _state = MutableStateFlow(GamesUiState())
    val state: StateFlow<GamesUiState> = _state.asStateFlow()

    /** Re-runs detection (also called on resume: the user may have installed the game or granted access). */
    fun refresh() {
        viewModelScope.launch {
            val plugins = repository.games()
            _state.update { s ->
                s.copy(
                    loading = true,
                    games = s.games.ifEmpty { plugins.map { GameRow(it.id.value, it.displayName, null) } },
                )
            }
            val rows = plugins.map { p ->
                async { GameRow(p.id.value, p.displayName, runCatching { repository.status(p.id) }.getOrNull()) }
            }.awaitAll()
            _state.value = GamesUiState(loading = false, games = rows)
        }
    }
}
