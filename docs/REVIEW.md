# Pre-sync review (2026-10-14)

Integration review of all four modules before the first Gradle sync. No Gradle or Maven
access was available, so this was done with `kotlinc` 2.0.21 plus hand-written stubs.

## What was checked and how

- **Kotlin type check, whole project in one compilation unit.** This covered `:core`, `:game-stardew`,
  `:source-nexus` and every non-Compose file in `:app`: domain, data, storage, DI modules, all
  ViewModels and `Routes`. It ran against minimal stubs of Android, Dagger/Hilt, Room, DataStore,
  DocumentFile, lifecycle and OkHttp. Result: **0 errors, 0 warnings**. So every call from `:app` into
  `:core`, `:game-stardew` and `:source-nexus` matches its definition: names, parameters, types,
  `suspend`-ness and nullability.
- **Hilt graph, checked by hand:**
  - `OkHttpClient` is provided once (`AppModule.okHttp`).
  - `ApiKeyProvider` is provided once (`NexusAppBindings`).
  - `@NexusAppVersion String` has one `@Provides` in `:app` plus one `@BindsOptionalOf` in `:source-nexus`. That pairing is valid: the optional resolves to present.
  - `PackageInspector` is bound once.
  - `Set<GamePlugin>`, `Set<ModSource>` and `Set<DependencyChecker>` each have `@Multibinds` plus `@IntoSet` contributions.
  - `LocalFileSource` and `NexusModSource` are `@Singleton` `@Provides`, so `ModRepository` and `SettingsViewModel` get the same instances the registry holds.
  - Every `@Inject` constructor dependency has a binding.
  - Everything is installed in `SingletonComponent`.
  - Hilt and KSP plugins are applied in all three modules that declare a `@Module`.
  - No `kapt` anywhere.
- **Gradle:**
  - `settings.gradle.kts` includes all four modules.
  - A script checked every `libs.*` reference in every build file against `libs.versions.toml`; all exist, plugin aliases included.
  - jvmTarget/compileOptions are 17 everywhere (`:core` uses `JvmTarget.JVM_17` with Java 17).
  - Namespaces are unique (`com.modhub.app`, `.stardew`, `.nexus`).
  - `buildConfig = true` is set in `:app`, the only module that uses `BuildConfig`.
  - All manifests and resource XML parse. `<queries>` sits directly under `<manifest>` in `:game-stardew` and is merged into the app.
- **Compose, reviewed by hand against BOM 2024.12.01 (material3 1.3.x, foundation 1.7.x):**
  - Every composable, icon and modifier used is imported.
  - Each `TopAppBar` / `ModalBottomSheet` user has `@OptIn(ExperimentalMaterial3Api::class)`; each `FlowRow` user has `@OptIn(ExperimentalLayoutApi::class)`.
  - All icons are in material-icons-core: `Add`, `Build`, `CheckCircle`, `Clear`, `Close`, `Info`, `Lock`, `MoreVert`, `Refresh`, `Search`, `Settings`, `Warning`, `AutoMirrored.Filled.ArrowBack`.
  - `collectAsStateWithLifecycle` and `LifecycleEventEffect` come from `lifecycle-runtime-compose`, which is in the catalog and in the `:app` deps.
  - Coil 2.x import is `coil.compose.AsyncImage` (correct for 2.7.0).
- **Unit tests:** `scripts/core-test-offline.sh` (19), `stardew-test-offline.sh` (27) and `nexus-test-offline.sh` (47) all pass after the change below.

## What was fixed

1. `ui/components/Common.kt`: `AssistChip(border = null)` changed to `border = null as BorderStroke?`, with the
   `BorderStroke` import added. Material3 1.2 and 1.3 keep a deprecated `ChipBorder?` overload of `AssistChip`, and
   a bare `null` can make overload resolution ambiguous. The typed null selects the current overload.

No other integration bug turned up. The parallel agents' cross-module contracts line up.

## Risks to expect on first sync/build (checklist)

- [ ] **Versions are from mid-2024/early-2025 and have never been resolved.** Confirm they all exist together:
      AGP 8.7.3, Gradle 8.11.1 (wrapper), Kotlin 2.0.21, KSP `2.0.21-1.0.28`, Hilt 2.53.1, Room 2.6.1, Compose BOM
      2024.12.01, navigation-compose 2.8.5, lifecycle 2.8.7, activity-compose 1.9.3, hilt-navigation-compose 1.2.0,
      coil 2.7.0, OkHttp 4.12.0, kotlinx-serialization 1.7.3, coroutines 1.9.0. The KSP version must match Kotlin
      exactly. If Studio offers to upgrade Kotlin, upgrade KSP to the matching release at the same time.
- [ ] **Room 2.6.1 with KSP on Kotlin 2.0.** Supported, but if KSP reports errors on `@Upsert` / `Flow` DAO methods,
      bump Room to 2.7.x.
- [ ] **Hilt + `java.util.Optional` for `@BindsOptionalOf`.** Needs API 24+, and `minSdk` is 26. The qualified
      `@NexusAppVersion Optional<String>` / `@BindsOptionalOf @NexusAppVersion String` pairing is the documented
      pattern. If Dagger still reports a missing binding, the fallback is to drop the optional and `@Provides` the
      `String` directly.
- [ ] **`cont.resume(response) { response.close() }`** in `NexusApiClient` uses the
      `resume(value, onCancellation: (Throwable) -> Unit)` overload. It still exists in coroutines 1.9, but the
      offline check only saw the 1.6.4 copy bundled with kotlinc. Expect at most a deprecation warning.
- [ ] **Compose was not compiled.** Signatures to watch:
  - [ ] `LinearProgressIndicator(progress = { ... })`, the lambda form from material3 1.2+.
  - [ ] `TabRow`: newer material3 versions steer toward `PrimaryTabRow`. At most a deprecation warning.
  - [ ] `RadioButton(onClick = null)`.
  - [ ] `ListItem(leadingContent = null)`.
  - [ ] `AssistChipDefaults.IconSize`.
  - [ ] `rememberModalBottomSheetState(skipPartiallyExpanded = ...)`.
- [ ] **`kotlin.plugin.serialization` is applied to `:app` but `:app` has no `@Serializable` classes.** This is harmless and can be removed.
- [ ] **`testInstrumentationRunner = androidx.test.runner.AndroidJUnitRunner`**, but `androidx.test:runner` is not a
      dependency. This is fine until an `androidTest` is added.
- [ ] **Release build:** `isMinifyEnabled = true`. Hilt/Room/Compose ship their own consumer rules. Check a release
      build with R8 at least once, because the Nexus DTO keep rules have not been exercised.
- [ ] **Manifest lint:** `MANAGE_EXTERNAL_STORAGE` and `requestLegacyExternalStorage` produce lint warnings; they are
      intended. `tools:targetApi="35"` is set on `<application>`.
- [ ] **Shizuku is not wired in.** No dependency or manifest provider yet; `ShizukuStorageBackend.isAvailable()` is `false`.
- [ ] **Runtime-only (not a build risk):**
  - [ ] The SAF backend's performance with `DocumentFile`.
  - [ ] Delivery of `nxm://` links while the activity is `singleTask`.
  - [ ] Package visibility of `abc.smapi.gameloader` on API 30+, which relies on the merged `<queries>`.
