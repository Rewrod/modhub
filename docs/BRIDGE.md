# ModHub ⇄ Launcher bridge contract (v1)

ModHub (Kotlin, `com.modhub.app`) manages mods inside the SMAPI launcher's private
mods folder through a ContentProvider exported by the **ModHub Launcher** (our fork of
SMAPILoader, package `abc.smapi.gameloader`, in `launcher/`). The launcher's SMAPI fork
hardcodes `getExternalFilesDir(null)/Mods` as the mods path, so the bridge is the only
Android-13+-safe way in. Both APKs are signed with the same key
(`launcher/SMAPIGameLoader/debug.keystore`, alias `androiddebugkey`, password `android`,
the upstream project's public key), so a `signature`-level permission protects the bridge.

## Provider

- Authority: `abc.smapi.gameloader.modhub`
- Manifest: `<provider android:name=".ModHubBridgeProvider" android:authorities="abc.smapi.gameloader.modhub" android:exported="true" android:permission="abc.smapi.gameloader.permission.MODHUB_BRIDGE"/>`
- `<permission android:name="abc.smapi.gameloader.permission.MODHUB_BRIDGE" android:protectionLevel="signature"/>` declared by the launcher; ModHub declares `<uses-permission>` for it.
- Additionally the provider rejects callers whose package is not `com.modhub.app` (belt and braces).
- All paths are **relative to the launcher's external files dir** (`/storage/emulated/0/Android/data/abc.smapi.gameloader/files`) and MUST start with `Mods` (exactly `Mods` or `Mods/...`). Anything else → `SecurityException`. `..`, absolute paths and backslashes are rejected.

## `ContentResolver.call(authority, method, arg, extras)` → Bundle

| method   | arg            | extras                          | result Bundle                                                                 |
|----------|----------------|---------------------------------|-------------------------------------------------------------------------------|
| `info`   | –              | –                               | `bridgeVersion:int=1`, `launcherVersion:String`, `filesDir:String`, `modsPath:String` (absolute), `smapiInstalled:boolean`, `smapiVersion:String?`, `gameInstalled:boolean` |
| `exists` | path           | –                               | `exists:boolean`, `isDir:boolean`                                             |
| `list`   | dir path       | –                               | `names:String[]`, `dirs:boolean[]`, `sizes:long[]`, `mtimes:long[]` (ms)      |
| `mkdirs` | path           | –                               | `ok:boolean`                                                                  |
| `delete` | path           | –                               | `ok:boolean` (recursive; `true` if it did not exist)                          |
| `move`   | from path      | `to:String`, `overwrite:boolean`| `ok:boolean` (rename; falls back to copy+delete; `overwrite=false` and target exists → `ok=false`, `error:String`) |

Errors: result Bundle contains `error:String`; unknown method → `IllegalArgumentException`.

## Files

`openFile(uri, mode)` with `uri = content://abc.smapi.gameloader.modhub/<relative path>`:
- mode `r` → read; file must exist, else `FileNotFoundException`.
- mode `w` → create parent dirs, create/truncate, return writable `ParcelFileDescriptor`.
- Other modes rejected.

## Auto-start

`LauncherActivity` (exported) honours intent extra `modhub.autoStart` (boolean). When `true` it
starts the game immediately (same code path as the launcher's own "Play" button) without
showing the launcher UI; if SMAPI isn't installed yet it shows the launcher normally with a
toast explaining why. ModHub's "Play (modded)" sends this.

## Versioning

`info.bridgeVersion` starts at 1. ModHub refuses to use a bridge with a higher major than it
knows and shows "update ModHub"; a missing provider means "install ModHub Launcher".

## ModHub side

- Client: `app/.../storage/BridgeClient.kt` (`isAvailable`, cached `info`, `call`, `fileUri`) and
  `BridgeStorageBackend.kt` (`StorageBackend` id `bridge`, mounted at `StoragePath("/Mods")`).
- Manifest: `<uses-permission android:name="abc.smapi.gameloader.permission.MODHUB_BRIDGE"/>` and
  `<queries><provider android:authorities="abc.smapi.gameloader.modhub"/></queries>` (needed on Android 11+).
- `ContentResolver.call(Uri, ...)` is used (the `String authority` overload is API 29+; ModHub's minSdk is 26).
- The provider only accepts the caller package `com.modhub.app`, so ModHub's debug build has no
  `applicationIdSuffix`, and both build types are signed with `launcher/SMAPIGameLoader/debug.keystore`.
- Unverified on a device: whether Android grants the signature permission to ModHub when ModHub was
  installed *before* the launcher that defines it. If calls fail with a SecurityException in that order,
  reinstalling ModHub after the launcher fixes it; ModHub's "can't be used" card says so.
