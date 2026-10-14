# ModHub Launcher: changes from upstream

This directory is a modified copy of **SMAPILoader** by NRTnarathip:

- Upstream: https://github.com/NRTnarathip/SMAPILoader
- Base version: tag `1.1.7` (commit `70a2478`), imported as a git subtree
- License: GPL-3.0 (see `LICENSE`). The modified version is also distributed under GPL-3.0, and its full source is in this repository.

The ModHub project modified this copy in October 2026. The changes are listed below.

## Added
- `SMAPIGameLoader/Launcher/ModHubBridgeProvider.cs`: an exported `ContentProvider` with authority `abc.smapi.gameloader.modhub`. It lets the ModHub app (`com.modhub.app`) list, create, move, delete, read and write files under the launcher's `files/Mods` folder. It also reports launcher, SMAPI and game status. A signature-level permission and a caller-package check protect it. Paths outside `files/Mods` are rejected. The contract is in `docs/BRIDGE.md` in the ModHub repository.
- `.github/workflows/build-launcher.yml` (at the repository root): builds this APK in CI.

## Modified
- `SMAPIGameLoader/AndroidManifest.xml`:
  - Declares the `abc.smapi.gameloader.permission.MODHUB_BRIDGE` permission (`protectionLevel="signature"`).
  - The application label is now "ModHub Launcher".
- `SMAPIGameLoader/Launcher/LauncherActivity.cs`:
  - The activity label is now "ModHub Launcher", and the info text says this is a modified build.
  - Handles the `modhub.autoStart` intent extra, in `OnCreate` and in `OnNewIntent`. If SMAPI is installed, it starts the game through the same code path as the Play button. Otherwise it shows a toast. The extra is skipped when the activity is recreated.
- `SMAPIGameLoader/SMAPIGameLoader.csproj`:
  - `ApplicationId` is now `abc.smapi.gameloader`, which matches the manifest package and the upstream APK, so this build installs over it.
  - `ApplicationDisplayVersion` is now `1.1.7-modhub.1`.
  - The `dotnet tool restore` step has `IgnoreExitCode="true"`, as `docs/building.md` already says it should.
- `SMAPIGameLoader/Tool/LogParser.cs`: the shared log info shows the launcher's version string. Before, it showed the parsed `Version`, which comes out as `0.0` for a version name like `1.1.7-modhub.1`.

The package name and signing key (`debug.keystore`, the upstream public debug key) are unchanged.
