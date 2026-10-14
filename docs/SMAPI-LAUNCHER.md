# SMAPI Launcher (`abc.smapi.gameloader`) — integration notes

Checked 2026-10-14 against tag `1.1.7` (commit `70a2478`, 2026-06-28) of `NRTnarathip/SMAPILoader`, the published `SMAPI-Launcher-v1.1.7.apk` (manifest decoded), and `NRTnarathip/SMAPI-Android-1.6` (`develop` and `stable` branches).
Tags: **[source]** = I read it in the code or APK; **[docs]** = from a wiki or README; **[unverified]** = inference.

## Repos
- The launcher is `github.com/NRTnarathip/SMAPILoader` (project `SMAPIGameLoader`). The SMAPI fork it runs is `github.com/NRTnarathip/SMAPI-Android-1.6`, which is also on Nexus as mod 44436 ("SMAPI for Android" 4.3.2). The repo names `SMAPI-Android-Installer` and `SMAPILauncher` don't exist publicly. **[source]**
- The APK's package is `abc.smapi.gameloader`, version 1.1.7, minSdk 28, targetSdk 35. The csproj's `ApplicationId` (`com.companyname...`) doesn't match, but the shipped APK uses `abc.smapi.gameloader`. **[source, APK]**

## 1. Where mods load from
- **Path:** `getExternalFilesDir(null)/Mods`, which on a device is `/storage/emulated/0/Android/data/abc.smapi.gameloader/files/Mods`. **[source]**
  - The SMAPI fork's `Program.Start` contains `#if SMAPI_FOR_ANDROID ... modsPath = Path.Combine(EarlyConstants.ExternalFilesDir, "Mods");`. This line runs after the `--mods-path` argument and the `SMAPI_MODS_PATH` variable are read, so it overrides both. The line is the same on `stable` and `develop`. **[source]**
  - The launcher's `ModTool.ModsDir` and `ModInstaller.ModDir` both use `Path.Combine(FileTool.ExternalFilesDir, "Mods")`, so it's the same folder. **[source]**
- **The launcher scans the folder at every start.** SMAPI scans `Mods/` itself. The Mod Manager just walks the folder recursively for `manifest.json` files. There's no private copy and no registry, so any mod folder placed there gets loaded. **[source]**
- **What "Install Mod" does:** it opens a zip picker (Xamarin.Essentials `FilePicker`), checks that the zip contains a `manifest.json`, and extracts it into `files/Mods`. "Open Folder Mods" fires `ACTION_VIEW` on the SAF URI `primary:Android/data/abc.smapi.gameloader/files/Mods`. **[source]**
- **Other folders:** SMAPI DLLs go in `files/Stardew Assemblies/`, saves in `files/Saves`, and logs in `files/ErrorLogs`. **[source]**
- **Not `/storage/emulated/0/StardewValley/Mods`.** The Stardew wiki tells users to unzip mods into "StardewValley/Mods". That's out of date or vague for 1.1.x, because nothing in the code reads that path. **[docs vs. source]**

## 2. Ways another app can hand it a mod
- **Exported components:** only `LauncherActivity` (the MAIN/LAUNCHER filter) and androidx's `ProfileInstallReceiver`, which is DUMP-protected. **[source, APK]**
  - `SMAPIActivity` and `ModManagerActivity` aren't exported, and all three providers have `exported=false`.
  - There's no `ACTION_VIEW` or `ACTION_SEND` handler for zip files, no deep link, no public ContentProvider, no broadcast receiver and no file watcher.
- **What's possible:** another app can start the launcher with its launch intent. It can't trigger an install or start the game directly. **[source]**

## 3. Config for a custom Mods path
- **There isn't one.** The path is hardcoded, and the Android branch ignores `--mods-path` and `SMAPI_MODS_PATH`. The launcher passes SMAPI an empty argument list. **[source]**
- **Other config:** SMAPI's normal `smapi-internal/config.json` lives under `files/Stardew Assemblies/smapi-internal/`. It can't change the mods path. **[unverified that nothing else reads it on Android; the ModsPath line is source-verified]**

## 4. License and APK URL
- **Launcher (`SMAPILoader`):** GPL-3.0. **SMAPI fork:** LGPL-3.0. Both licenses allow downloading and redistributing the APK and zip with attribution and a source link. **[source: LICENSE files]**
- **APK release URL:** `https://github.com/NRTnarathip/SMAPILoader/releases/download/<tag>/SMAPI-Launcher-v<tag>.apk`. Example: `.../download/1.1.7/SMAPI-Launcher-v1.1.7.apk`. Tags have no `v` prefix. **[source]**
- **Latest release:** use `api.github.com/repos/NRTnarathip/SMAPILoader/releases/latest`. **[unverified, standard GitHub API]**
- **SMAPI zip:** comes from `NRTnarathip/SMAPI-Android-1.6` releases or Nexus 44436. **[docs]**
- **APK signing:** release builds are signed with the Android debug keystore (password `android`, alias `androiddebugkey`). Updates from other builds may clash on signature. **[source, csproj]**

## 5. Shizuku or root on Android 13+
- **Short answer:** yes, for a silent and automatic install. Since Android 11, `Android/data/<other package>/` can't be written by other apps, and `MANAGE_EXTERNAL_STORAGE` doesn't change that. Since Android 13, SAF can't grant a tree on `Android/data` either; the old DocumentsUI workaround was closed. Without Shizuku or root, a third-party app can't write to `files/Mods`. **[platform behavior; unverified per OEM]**
- **Without Shizuku or root:**
  1. ModHub saves the mod zip to a public folder such as `Download/`.
  2. The user opens SMAPI Launcher, goes to Mod Manager, and taps "Install Mod".
  3. The user picks the zip, and the launcher extracts it itself. **[source]**
- **With Shizuku or root:** write or extract straight to `/storage/emulated/0/Android/data/abc.smapi.gameloader/files/Mods/<ModName>/`. SMAPI picks it up on the next game start. **[source for the path; Shizuku approach unverified]**

## Sources
- https://github.com/NRTnarathip/SMAPILoader (tag 1.1.7: `SMAPIGameLoader/Launcher/ModTool.cs`, `ModInstaller.cs`, `ModManagerActivity.cs`, `Tool/FileTool.cs`, `Game/SMAPIActivity.cs`, `Game/GameAssemblyManager.cs`, `AndroidManifest.xml`, `LICENSE`)
- https://github.com/NRTnarathip/SMAPILoader/releases/tag/1.1.7
- https://github.com/NRTnarathip/SMAPI-Android-1.6 (`src/SMAPI/Program.cs` line 257, `src/SMAPI/Constants.cs`, `LICENSE`)
- https://wiki.stardewvalley.net/Modding:Android
- https://github.com/NRTnarathip/SMAPILoader/wiki/How-To-Install-SMAPI
- https://www.nexusmods.com/stardewvalley/mods/44436
