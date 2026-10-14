# ModHub architecture

ModHub is a multi-game mod manager for Android. Game knowledge and mod catalogues
are plugins; the app is a shell that wires them together. See `RESEARCH.md` for the
platform constraints this design answers to.

## Modules

| Module | Type | Contents |
|---|---|---|
| `:core` | Pure Kotlin/JVM (`java-library` + `kotlin("jvm")`), no Android | Contracts (`GamePlugin`, `ModSource`, `StorageBackend`), models, `ZipExtractor`, `ModInstaller`, `PluginRegistry`, `SourceRegistry`, `FileStorageBackend`. Unit-tested on the JVM. |
| `:game-stardew` | Android library | Stardew Valley `GamePlugin` (SMAPI for Android): detection, install (multi-mod archives), enable/disable via leading `.`, uninstall, `dependencyReport()` for missing/outdated SMAPI dependencies. Implemented and unit-tested. |
| `:source-nexus` | Android library | Nexus Mods v1 `ModSource` (OkHttp + kotlinx-serialization): latest/trending lists, client-side filter (`SEARCH_NOTE`), files, Premium downloads and `nxm://` (`NxmLink`) downloads, `validateKey()`, `rateLimit`. Also `LocalFileSource` (install from a picked file). Implemented and unit-tested. |
| `:app` | Android application | Hilt, Compose/Material3 UI, Navigation, DataStore settings, Room provenance DB, Android storage backends (File / SAF / Shizuku stub), the `ModRepository` use-case layer, `DownloadManager`, DI that collects plugins and sources. |

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

```
app/src/main/kotlin/com/modhub/app/
  MainActivity.kt, ModHubApplication.kt
  di/        AppModule (OkHttp, Room, PackageInspector), RegistryModule (plugin/source sets),
             SourceBindingsModule (ApiKeyProvider, @NexusAppVersion, Set<DependencyChecker>)
  data/      settings/SettingsRepository (DataStore), db/ (Room provenance table)
  storage/   AndroidFileStorageBackend, SafStorageBackend, ShizukuStorageBackend (stub),
             StorageBackendProvider (choose() + gameContext()/resolve())
  domain/    ModRepository, DownloadManager, DependencyChecker (+ StardewDependencyChecker), Errors.kt
  ui/        navigation/ (Routes, ModHubNavGraph, AppViewModel), components/Common.kt,
             screens/ (GamesScreen, GameDetailScreen, BrowseScreen, SettingsScreen + one ViewModel each)
```

- `ModHubApplication` (`@HiltAndroidApp`), `MainActivity` (`@AndroidEntryPoint`, Compose with dynamic
  colour on Android 12+ and the farm-green/wood scheme below that). `MainActivity` hands `nxm://` intents
  (launch, unless restored from saved state, and `onNewIntent`) to `ModHubNavGraph`.
- **DI the feature modules need from `:app`** (`di/SourceBindingsModule.kt`):
  - `ApiKeyProvider { settings.nexusApiKey.first() }`: read for every request, so a new key in
    Settings applies at once.
  - `@NexusAppVersion String = BuildConfig.VERSION_NAME`, which fills `NexusModule`'s `Optional<String>`.
  - `@Multibinds Set<DependencyChecker>` plus `StardewDependencyChecker` `@IntoSet`.
  - The shared `OkHttpClient` comes from `AppModule`.
- `data/settings/SettingsRepository`: DataStore keys for the Nexus API key (plain DataStore for now;
  TODO: Keystore-backed), `StorageMode`, and the per-game mods path override + SAF tree URI.
- `data/db`: Room table recording install provenance (source/mod/file/version) for later update checks.
  The mods folder stays the source of truth for what is installed; `ModRepository` merges provenance in.

### Use-case layer (`domain/`)

`ModRepository` is the only thing ViewModels talk to for game/mod work. It never exposes `GameContext`:

| Call | What it does |
|---|---|
| `status(gameId)` | `StorageBackendProvider.resolve()` + `plugin.detect()` -> `GameStatus` (detection, storage choice and its reason, Android/data flag, All-files flag, override). |
| `snapshot(gameId)` / `observe(gameId, refresh)` | Status + `listInstalled()` + provenance + `DependencyChecker` issues. `observe` re-runs on settings changes, after ModHub changes the folder, and on `refresh`; errors are emitted as `Result.failure` so the flow keeps running. |
| `setEnabled`, `uninstall` | Plugin calls through a fresh `GameContext`; they also update the provenance table. |
| `installFromUri(gameId, uri)` | SAF document -> `LocalFileSource.archiveFromStream` (cache) -> `installMod`. Runs as a `DownloadManager` job. |
| `downloadAndInstall(gameId, file, mod)` | `source.download()` into `cacheDir/downloads/<uuid>/` with progress -> `installMod` with a `ModSourceRef`. Cache folder is deleted afterwards. |
| `handleNxmLink(uri)` | `NxmLink.parse` -> game by `sourceGameKeys["nexus"]` -> `nexus.fileForNxm` -> download with `link.toDownloadAuth()` -> install. Returns the `GameId` so the UI can open that game. |
| `useTreeAsModsFolder`, `clearModsFolder` | Persist/release a SAF tree grant and set/clear the per-game override. `treeUriToPath` maps `primary:Foo/Mods` to `/storage/emulated/0/Foo/Mods`. |

Before installing, `ModRepository` refuses when `StorageChoice.usable` is false and explains why, so an
install never half-fails inside the plugin.

`DownloadManager` (singleton) runs jobs in an app-wide `SupervisorJob` scope, exposes
`StateFlow<List<ActiveDownload>>` (state, bytes, total, error, help URL) and a `SharedFlow<DownloadEvent>`,
and supports `cancel(id)`/`dismiss(id)`. Cancelling collection of `ModSource.download()` aborts the HTTP
read. Jobs survive navigation but not process death.

`DependencyChecker` keeps the UI game-agnostic: `check(mods) -> List<DependencyIssue>`. The Stardew
implementation adapts `StardewPlugin.dependencyReport(mods)`. A game with no checker shows no warnings.

### Screens

- **Games**: every registered plugin with chips for game installed / loader status / mods folder. Re-checks
  on resume.
- **Game detail**: detection header, storage reason, "Fix storage access" (All files access on 11+, legacy
  runtime permission on 8-10), "Choose mods folder" (SAF `OpenDocumentTree`, persisted grant + override),
  Android/data notice (explains why Shizuku is needed), installed mods with enable switch, version, author,
  dependency warnings, overflow -> uninstall (confirm dialog). FAB menu: Install from file
  (`OpenDocument`, `application/zip` + `*/*`) and Browse Nexus. Downloads bar with progress/cancel.
  Snackbars for results; a `NexusPremiumRequiredException` gets an "Open" action to the mod's Files tab.
- **Browse**: tabs Latest updated / Latest added / Trending (cached per tab), client-side filter with
  `SEARCH_NOTE` as helper text, cards with Coil images, files bottom sheet (category, version, size,
  Install), "Open on Nexus", rate-limit footer from `NexusModSource.rateLimit`. Without an API key it shows
  a prompt that links to Settings.
- **Settings**: API key (password field, Save & validate -> user name + Premium/Supporter), storage mode
  (Shizuku disabled "coming soon" while `ShizukuStorageBackend.isAvailable()` is false), All files access
  button + caveat, per-game folder overrides with Clear, About (SMAPI credit, no affiliation with
  ConcernedApe or Nexus Mods).

### nxm:// flow

`nxm` intent-filter on `MainActivity` (`singleTask`) -> `pendingNxmLink` -> `ModHubNavGraph` ->
activity-scoped `AppViewModel.onNxmLink` -> `ModRepository.handleNxmLink` (enqueues the job) -> navigate to
that game's detail screen, whose downloads bar shows progress. Unsupported game, malformed or expired
links show a dialog.

## Verification status

- `:core`, `:game-stardew`, `:source-nexus` have JVM unit tests; `scripts/*-test-offline.sh` run them with a
  bare `kotlinc` + JUnit 4 where Maven is unreachable. Normal runs: `./gradlew :core:test
  :game-stardew:testDebugUnitTest :source-nexus:testDebugUnitTest`.
- `:app` has **not been built with Gradle** (the authoring sandbox has no Android SDK or Maven access).
  Its non-Compose code (domain, ViewModels, DI modules, storage provider) was type-checked with `kotlinc`
  against small hand-written stubs of the Android/Hilt/lifecycle APIs it uses; the Compose screens were
  only checked for syntax and imports. Expect the first `./gradlew :app:assembleDebug` to need minor fixes
  (Compose API signatures, Hilt graph). Nothing has run on a device yet.
