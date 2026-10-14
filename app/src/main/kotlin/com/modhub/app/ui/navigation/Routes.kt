package com.modhub.app.ui.navigation

import android.net.Uri

/**
 * Route strings for the nav graph. Screens receive typed callbacks, never raw routes;
 * use the builder functions here when navigating.
 */
object Routes {
    const val ARG_GAME_ID = "gameId"

    /** Start destination: list of supported games + detection status. */
    const val GAMES = "games"

    /** Installed mods for one game (enable/disable, uninstall, load order). */
    const val GAME_DETAIL = "game/{$ARG_GAME_ID}"
    fun gameDetail(gameId: String) = "game/${Uri.encode(gameId)}"

    /** Browse/download mods from sources for one game. */
    const val BROWSE = "browse/{$ARG_GAME_ID}"
    fun browse(gameId: String) = "browse/${Uri.encode(gameId)}"

    /** App settings: API keys, storage mode, per-game mods path. */
    const val SETTINGS = "settings"
}
