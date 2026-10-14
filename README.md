# ModHub

A multi-game mod manager for Android with a plugin architecture. Stardew Valley
(via SMAPI for Android) is the first supported game; Nexus Mods is the first mod source.

**Status: skeleton.** The shared contracts, installer and app shell exist; the Stardew
plugin, the Nexus client and the real screens are still placeholders.

## Build

Requirements: Android Studio Ladybug (2024.2) or newer, JDK 17+, Android SDK 35.

1. Open the `modhub/` folder in Android Studio and let Gradle sync. The wrapper
   downloads Gradle 8.11.1; AGP 8.7.3 and Kotlin 2.0.21 come from the version catalog
   (`gradle/libs.versions.toml`).
2. Run the `app` configuration, or from a terminal:
   ```
   ./gradlew :app:assembleDebug      # APK in app/build/outputs/apk/debug/
   ./gradlew :core:test              # JVM unit tests, no device needed
   ```

If you build from the command line, create `local.properties` with
`sdk.dir=/path/to/Android/sdk` (Android Studio does this for you).

`scripts/core-test-offline.sh` runs the `:core` tests with a bare Kotlin compiler and JUnit 4.
It is only for sandboxes that cannot reach Maven repositories.

## Modules

- `:core`: pure Kotlin. `GamePlugin`, `ModSource`, `StorageBackend`, models, `ZipExtractor`
  (with zip-slip and zip-bomb protection), `ModInstaller`, registries.
- `:game-stardew`: Stardew Valley plugin (placeholder).
- `:source-nexus`: Nexus Mods client (placeholder).
- `:app`: Compose UI, Hilt, settings, storage backends.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the interfaces and how to add a game.

## What works and what needs Shizuku

| Mods folder location | How ModHub reaches it |
|---|---|
| Shared storage, e.g. `/storage/emulated/0/StardewValley/Mods` (the SMAPI-for-Android default) | Direct file access once you grant **All files access** (Android 11+), or a folder picked through the system picker (SAF). Android 8-10 uses the legacy storage permission. |
| `Android/data/<package>/...` (e.g. the SMAPI launcher's `abc.smapi.gameloader` data dir) | **Needs Shizuku** on Android 11+. Android blocks every normal app from this folder, including through the folder picker and All files access. The Shizuku backend is not implemented yet. Until it is, use a PC (USB/MTP or `adb`) for these folders. |

Notes:

- All files access is restricted on Google Play, so ModHub is meant to be distributed
  through GitHub or F-Droid.
- Nexus Mods: paste your personal API key in Settings. Non-premium accounts download
  through the website's "Mod Manager Download" button; ModHub handles the `nxm://` link.
  Keys are never bundled with the app.
- SMAPI for Android is an unofficial community port. Install it separately; ModHub only
  manages the mods folder.
