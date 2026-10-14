package com.modhub.app.ui.screens.games

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.modhub.app.domain.GameStatus
import com.modhub.app.domain.ModRepository
import com.modhub.app.platform.GameLauncher
import com.modhub.app.platform.LaunchTarget
import kotlinx.coroutines.Dispatchers
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class GameRow(
    val id: String,
    val name: String,
    val status: GameStatus?,
    /** What the row's play button starts; null hides it. */
    val launchTarget: LaunchTarget? = null,
)

data class GamesUiState(
    val loading: Boolean = true,
    val games: List<GameRow> = emptyList(),
)

@HiltViewModel
class GamesViewModel @Inject constructor(
    private val repository: ModRepository,
    private val launcher: GameLauncher,
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
                async {
                    GameRow(
                        id = p.id.value,
                        name = p.displayName,
                        status = runCatching { repository.status(p.id) }.getOrNull(),
                        launchTarget = withContext(Dispatchers.IO) { runCatching { launcher.resolve(p) }.getOrNull() },
                    )
                }
            }.awaitAll()
            _state.value = GamesUiState(loading = false, games = rows)
        }
    }

    /** Starts the row's game; if that fails (e.g. it was uninstalled), re-checks the list. */
    fun play(row: GameRow) {
        val target = row.launchTarget ?: return
        if (!launcher.launch(target)) refresh()
    }
}
