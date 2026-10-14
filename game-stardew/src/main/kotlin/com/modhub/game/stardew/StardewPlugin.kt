package com.modhub.game.stardew

import com.modhub.core.model.GameId

/**
 * Placeholder for the Stardew Valley [com.modhub.core.plugin.GamePlugin].
 *
 * TODO(game-stardew agent): implement `class StardewPlugin : GamePlugin` per docs/ARCHITECTURE.md
 * and docs/RESEARCH.md section 1:
 *  - packageNames: Play / Galaxy / Amazon Stardew packages + SMAPI launcher `abc.smapi.gameloader`
 *  - defaultModsDirectories: `<ext>/StardewValley/Mods`, then `<ext>/Android/data/abc.smapi.gameloader/...`
 *  - loaderRequirements: "SMAPI for Android" (Nexus 44436, Stardew >= 1.6.15.1)
 *  - installMod: one InstalledMod per folder containing a SMAPI manifest.json
 *  - setEnabled: rename folder with/without a leading '.' (SMAPI skips dot-folders)
 * Then replace the empty `@Multibinds` set in :app's PluginModule with an `@IntoSet` binding.
 */
object StardewPluginPlaceholder {
    val GAME_ID = GameId("stardew")
    const val NEXUS_DOMAIN = "stardewvalley"
}
