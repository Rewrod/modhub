# ModHub architecture

ModHub is a multi-game mod manager for Android. Game knowledge and mod catalogues
are plugins; the app is a shell that wires them together. See `RESEARCH.md` for the
platform constraints this design answers to.

## Modules

| Module | Type | Contents |
|---|---|---|
| `:core` | Pure Kotlin/JVM (`java-library` + `kotlin("jvm")`), no Android | Contracts (`GamePlugin`, `ModSource`, `StorageBackend`), models, `ZipExtractor`, `ModInstaller`, `PluginRegistry`, `SourceRegistry`, `FileStorageBackend`. Unit-tested on the JVM. |
| `:game-stardew` | Android library | Stardew Valley `GamePlugin` (SMAPI for Android). Placeholder for now. |
| `:source-nexus` | Android library | Nexus Mods v1 `ModSource` (OkHttp + kotlinx-serialization). Placeholder for now. |
| `:app` | Android application | Hilt, Compose/Material3 UI, Navigation, DataStore settings, Room provenance DB, Android storage backends (File / SAF / Shizuku), DI that collects plugins and sources. |

Dependency direction: `:app` -> `:game-*`, `:source-*` -> `:core`. Plugins and sources
never depend on each other or on `:app`. Anything Android-specific a plugin needs
(e.g. package lookups) reaches it through a `:core` interface (`PackageInspector`).

```
core/src/main/kotlin/com/modhub/core/
  model/     Ids.kt (GameId, ModId, ModSourceRef, SourceGameRef)
             Mods.kt (ModInfo, ModFile, ModFileCategory, ModManifest, ModDependency, InstalledMod)
             Status.kt (LoaderRequirement, LoaderStatus, GameDetection, DownloadProgress)
  plugin/    GamePlugin.kt (GamePlugin, GameContext, PackageInspector), PluginRegistry.kt
  source/    ModSource.kt (ModSource, ModQuery, ModPage, ModListing, DownloadAuth, exceptions), SourceRegistry.kt
  storage/   StorageBackend.kt (StorageBackend, StoragePath, StorageEntry, StorageException), FileStorageBackend.kt
  install/   ModArchive.kt (ModArchive, FileModArchive, ByteArrayModArchive), ModInstaller.kt
  util/      ZipExtractor.kt (ZipExtractor, ZipSlipException, ArchiveTooLargeException)
```

## The two plugin interfaces

### `GamePlugin` (one per game)

Owns everything game-specific: package names, loader prerequisites, where mods live,
how an archive maps onto the mods folder, how enabling/disabling works, load order.

Every call takes a `GameContext`, built fresh by the app
(`StorageBackendProvider.gameContext(plugin)`):

- `storage: StorageBackend`: the backend chosen for this game's mods dir. **All file I/O
  goes through it**, never `java.io.File`, so the plugin works unchanged under SAF/Shizuku.
- `packages: PackageInspector`: installed/versionName lookups.
- `modsDirectoryOverride: StoragePath?`: user override from Settings; must win.
- `externalStorageRoot`: normally `/storage/emulated/0`.

Key members: `id`, `displayName`, `packageNames`, `loaderRequirements`,
`sourceGameKeys` (e.g. `"nexus" to "stardewvalley"`), `supportsLoadOrder`,
`defaultModsDirectories()`, `modsDirectory()` (default impl: override, else first
existing candidate, else first candidate), `detect()`, `installMod()` (returns a *list*,
since one archive can hold several mods), `listInstalled()`, `setEnabled()`,
`uninstall()`, `getLoadOrder()`, `setLoadOrder()`.

`ModInstaller` is the shared helper for the common case (unzip into one folder, strip
a single wrapper folder, stage then move into place). Plugins with special layouts call
`ZipExtractor.extract(..., pathMapper = ...)` directly; the mapper's output is
re-validated, so it cannot reintroduce zip slip.

### `ModSource` (one per catalogue)

Remote listing + download: `id`, `displayName`, `supportsSearch`, `isConfigured()`,
`search()`, `listLatest()`, `getMod()`, `getFiles()`, `download()` (cold
`Flow<DownloadProgress>`: `Started`, `Progress*`, then `Completed` or `Failed`).

Games are identified to a source with `SourceGameRef(gameId, key)`, where `key` comes
from the plugin's `sourceGameKeys[source.id]` (`SourceRegistry.gameRef(plugin, source)`).
A source that a plugin does not list is hidden for that game.
`DownloadAuth` carries per-download parameters (Nexus non-premium: `key`/`expires`
parsed from the `nxm://` link that `MainActivity` receives).

## Adding a new game plugin

1. Create `:game-<name>` (copy `game-stardew/build.gradle.kts`; change `namespace`),
   add `include(":game-<name>")` to `settings.gradle.kts` and
   `implementation(project(":game-<name>"))` to `app/build.gradle.kts`.
2. Implement `GamePlugin`. Pick a unique `GameId`; fill `sourceGameKeys` for every
   source that carries the game.
3. In the module's `AndroidManifest.xml`, declare the game's packages so they are
   visible on API 30+:
   ```xml
   <queries><package android:name="com.example.game" /></queries>
   ```
4. Contribute it to Hilt (in the plugin module if it applies Hilt, or in `:app/di`):
   ```kotlin
   @Module @InstallIn(SingletonComponent::class)
   object MyGameBindings {
       @Provides @IntoSet fun plugin(): GamePlugin = MyGamePlugin()
   }
   ```
   `PluginRegistry` picks it up; no other app code changes. A new `ModSource` is added
   the same way with `@IntoSet ... : ModSource`.
5. Unit-test it against `FileStorageBackend(tempDir)`, as `:core` does.

## Storage backends

`StoragePath` is an absolute, normalised, '/'-separated path (it can't hold `..`).
Backends map it onto real storage:

| Backend | Module | Reaches | Needs |
|---|---|---|---|
| `FileStorageBackend` | `:core` | anything the process can read/write | nothing (tests use a temp root) |
| `AndroidFileStorageBackend` | `:app` | shared storage, e.g. `/sdcard/StardewValley/Mods` | All files access (API 30+) or legacy permissions (26-29). **Not** `Android/data`, `Android/obb` |
| `SafStorageBackend` | `:app` | one user-picked tree, mounted at a `StoragePath` | a persisted `ACTION_OPEN_DOCUMENT_TREE` grant. Can't pick `Android/data`, the storage root or `Download` on 11+ |
| `ShizukuStorageBackend` | `:app` | everything shell can reach, including `Android/data/<pkg>` | Shizuku running + permission. **Stub, not implemented** |

### Choosing a storage backend

`StorageBackendProvider.choose(target, mode, gameSettings)` takes the user's
`StorageMode` (Settings) and returns a `StorageChoice(backend, reason, usable)`, which
the UI should display. In `AUTO` mode:

1. Target under `Android/data` or `Android/obb` on API 30+ -> Shizuku if available,
   otherwise unusable ("needs Shizuku").
2. All files access granted (or API < 30) -> `AndroidFileStorageBackend`.
3. A SAF tree is granted for this game -> `SafStorageBackend` mounted at the override/target path.
4. Otherwise unusable: ask for All files access or a SAF folder.

`FILE`, `SAF`, `SHIZUKU` force that backend and report `usable=false` if it can't work.

## App wiring (`:app`)

- `ModHubApplication` (`@HiltAndroidApp`), `MainActivity` (`@AndroidEntryPoint`, Compose,
  forwards `nxm://` intents to the nav graph).
- `di/RegistryModule.kt`: `@Multibinds` sets of `GamePlugin` / `ModSource`, providers for
  `PluginRegistry`, `SourceRegistry`, `ModInstaller`. `di/AppModule.kt`: OkHttp (User-Agent,
  redacted `apikey` logging in debug), Room, `PackageInspector` binding.
- `data/settings/SettingsRepository`: DataStore keys for the Nexus API key, `StorageMode`, and
  per-game mods path override + SAF tree URI.
- `data/db`: Room table recording install provenance (source/mod/file/version) for update checks.
  The mods folder stays the source of truth for what is installed.
- `ui/navigation`: `Routes` (`games`, `game/{gameId}`, `browse/{gameId}`, `settings`) and
  `ModHubNavGraph`. `ui/screens/StubScreens.kt`: placeholders; keep their signatures when
  replacing them.
