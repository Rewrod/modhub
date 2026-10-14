# ModHub

A multi-game mod manager for Android with a plugin architecture. Stardew Valley
(via SMAPI for Android) is the first supported game; Nexus Mods is the first mod source.

**Status: early, unverified on device.** The shared contracts, the Stardew Valley plugin, the
Nexus Mods client and the app screens (games, installed mods, browse/download, settings) are
written. The library modules have JVM unit tests; the app has not yet been built with Gradle or
run on a device (see "Verification" below).

## Build

Requirements: Android Studio Ladybug (2024.2) or newer, JDK 17+, Android SDK 35.

1. Open the `modhub/` folder in Android Studio and let Gradle sync. The wrapper
   downloads Gradle 8.11.1; AGP 8.7.3 and Kotlin 2.0.21 come from the version catalog
   (`gradle/libs.versions.toml`).
2. Run the `app` configuration, or from a terminal:
   ```
   ./gradlew :app:assembleDebug      # APK in app/build/outputs/apk/debug/
   ./gradlew :core:test              # JVM unit tests, no device needed
   ./gradlew :game-stardew:testDebugUnitTest :source-nexus:testDebugUnitTest
   ```

If you build from the command line, create `local.properties` with
`sdk.dir=/path/to/Android/sdk` (Android Studio does this for you).

`scripts/core-test-offline.sh`, `scripts/stardew-test-offline.sh` and `scripts/nexus-test-offline.sh`
run the `:core`, `:game-stardew` and `:source-nexus` tests with a bare Kotlin compiler and JUnit 4.
They are only for sandboxes that cannot reach Maven repositories.

## Modules

- `:core`: pure Kotlin. `GamePlugin`, `ModSource`, `StorageBackend`, models, `ZipExtractor`
  (with zip-slip and zip-bomb protection), `ModInstaller`, registries.
- `:game-stardew`: Stardew Valley plugin: detects the game and the SMAPI launcher, installs archives
  (including multi-mod bundles), enables/disables by SMAPI's leading-`.` rule, reports missing
  dependencies.
- `:source-nexus`: Nexus Mods v1 client: latest/trending lists, files, Premium and `nxm://`
  downloads, API key validation, rate-limit info; plus install-from-local-file.
- `:app`: Compose/Material3 UI, Hilt, settings, storage backends, `ModRepository` (use cases) and an
  in-app download manager. The app binds the Nexus `ApiKeyProvider` to the key saved in Settings.

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

## Using it

1. **Settings**: paste your Nexus Mods personal API key (nexusmods.com, Site preferences, API Keys)
   and tap *Save & validate*. On Android 11+, tap *Request All files access* if your mods folder is in
   shared storage.
2. **Games**: open Stardew Valley. The header shows whether the game, SMAPI and the mods folder were
   found. Use *Choose mods folder* to pick it with the system picker if needed.
3. **Add mod**: *Install from file* (a downloaded .zip) or *Browse Nexus*. Premium accounts can
   install straight from the files sheet; everyone else taps *Open on Nexus*, then
   *Mod Manager Download* on the website, and ModHub picks up the `nxm://` link.
4. Toggle mods on/off with the switch (SMAPI skips folders starting with `.`); uninstall from the
   overflow menu. A warning icon marks mods whose required dependencies are missing or disabled.

## Verification

The library modules (`:core`, `:game-stardew`, `:source-nexus`) are unit-tested. `:app` was written
without access to Gradle or the Android SDK: its non-UI code was type-checked with `kotlinc`
against stubbed Android APIs, and the Compose screens were checked only for syntax. The first
Gradle build may need small fixes, and nothing has run on a device yet.
