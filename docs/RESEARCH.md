# ModHub research brief (as of 2026-10-14)

Confidence tags: [V] verified from a fetched page this session; [K] from prior knowledge or inference, not re-verified; [?] could not confirm.

## 1. Stardew Valley on Android: SMAPI

- **Not officially supported by ConcernedApe.** The wiki calls the Android SMAPI an unofficial community port, and still "experimental" with some mods not working. [V]
- **Lineage (all community forks of SMAPI):** MartyrPher (1.4.x) -> ZaneYork (1.5.x, "SMAPI-Android-Installer") -> NRTnarathip (1.6.x, current). [V]
  - Old approach (MartyrPher/ZaneYork): installer app pulls the game APK, injects SMAPI files, re-signs it, and installs a **patched APK** (app shows as "SMAPI Stardew Valley"). [V for ZaneYork README]
  - Current approach (NRTnarathip): a **standalone "SMAPI Launcher" APK** plus a separate `SMAPI-Android...zip`. The launcher runs an *unmodified* Stardew install with SMAPI loaded at launch (no re-signed APK). [V launcher/zip flow; "unmodified" wording is from the Geode launcher, so for SMAPI treat as [K]]
- **Projects/repos:** `NRTnarathip/SMAPI-Android-1.6` (SMAPI fork; GitHub releases page says downloads moved to Discord and GitHub issues are no longer supported), `NRTnarathip/SMAPILoader` (the Launcher APK, wiki "How-To-Install-SMAPI"), and a Nexus page "SMAPI for Android" (mod 44436). [V]
- **Version status:** Nexus "SMAPI for Android" is 4.3.2 (changelog 4.3.2.5), updated 3 April 2026. [V] Wiki says it needs **Stardew 1.6.15.1 or later** from Google Play or Galaxy Store. [V] A forum reply (Aug 2025) also shows Amazon-store installs, with mixed results. [V]
- **Requirements/caveats (forum):** 64-bit device; Chromebooks unsupported; do not clear app data; install mods via the launcher's built-in Mod Manager; Android 12+ for the "Cinderbox" variant (a different route, build 24356). [V]
- **Install flow:** install Launcher APK -> download SMAPI zip (name starts `SMAPI-Android`) -> Launcher "Install SMAPI From Zip" -> "Start Game". Updating = repeat. Uninstall = remove launcher/app and delete `smapi-internal`. [V]
- **Mod folder location (conflicting sources, must verify on a real device):**
  - Wiki: unzip mods into `StardewValley/Mods` (created by the installer), i.e. most likely `/storage/emulated/0/StardewValley/Mods`. [V for relative name; absolute root is [K]]
  - Forum (1.6 launcher): the Launcher's data folder is `abc.smapi.gameloader` in internal storage, may need ADB to reach, and mods should be installed via its Mod Manager. Likely under `Android/data/abc.smapi.gameloader/...`. [V for folder name; exact path [?]]
  - Cinderbox variant: `StardewValley` folder at root of main storage; config in `cinderbox-internal`. [V]
  - **Recommendation:** make the Mods path configurable, default to `/storage/emulated/0/StardewValley/Mods`, and auto-detect if `Android/data/abc.smapi.gameloader` exists.
- Beware many SEO/APK-mirror sites claiming "SMAPI 2026 updates"; treat as untrusted.

Sources:
- https://stardewvalleywiki.com/Modding:Installing_SMAPI_on_Android
- https://stardewvalleywiki.com/Modding:Android
- https://www.nexusmods.com/stardewvalley/mods/44436
- https://github.com/NRTnarathip/SMAPI-Android-1.6
- https://github.com/NRTnarathip/SMAPI-Android-1.6/releases
- https://github.com/NRTnarathip/SMAPILoader/wiki/How-To-Install-SMAPI
- https://github.com/zaneyork/martyrpher-smapi-android-installer
- https://forums.stardewvalley.net/threads/how-to-mod-on-android.54287/latest
- https://forums.stardewvalley.net/threads/installing-smapi-on-android.22478/latest

## 2. Android scoped storage and `Android/data`, `Android/obb`

- **Android 11+ (API 30):** apps cannot access another app's dir in `Android/data/` or `Android/obb/` via file paths. SAF `ACTION_OPEN_DOCUMENT_TREE` also cannot select those dirs for apps targeting 11+. Internal storage root and `Download` are also blocked as tree grants. [V]
- **Android 11-12:** a loophole existed: file managers could get a tree grant to `Android/data` (and `Android/obb` in some cases) through the system picker. [V partly]
- **Android 13, 14, 15, 16:** third-party SAF access to `Android/data` (and `Android/obb`) is blocked. 14/15/16 not separately documented in sources found; assume unchanged or tighter. [V for 13; K for 14-16]
- **MANAGE_EXTERNAL_STORAGE ("All files access"):** grants broad shared-storage read/write, but explicitly **excludes `Android/data` and `Android/obb`** and other apps' private dirs. Grants *do* cover normal shared paths like `/sdcard/StardewValley/Mods`. Play policy: only for core-function uses (file managers, backup, antivirus, etc.) and requires a Play declaration; a mod manager is borderline. Fine for sideloaded/F-Droid builds. [V]
- **Shizuku:** open-source (Apache 2.0) service started by root or by wireless ADB (user re-starts after reboot). It hands apps a binder to call system APIs with shell/root privileges. Apps integrate the Shizuku API, request its permission, and run file operations via a privileged process (UserService). This can reach `Android/data` on 13+ without root. Needs one-time user setup; Android 11+ wireless debugging pairing. [V for definition; UserService detail K]
- **Root:** full access, small audience. [V]
- Other: MTP/USB from a PC and `adb` can reach these dirs. [V]
- **Recommendation for ModHub:**
  1. Abstract storage behind a `ModTarget` interface with backends: (a) plain shared-storage path with `MANAGE_EXTERNAL_STORAGE` (default; works if SMAPI Mods dir is `/sdcard/StardewValley/Mods`); (b) Shizuku backend for `Android/data/...` targets; (c) root backend optional; (d) fallback "export zip + manual instructions", or SAF to a user-chosen non-restricted folder.
  2. Detect the actual SMAPI folder at runtime and show the user what is in use.
  3. Avoid Play-store reliance on All files access; distribute via GitHub/F-Droid, or use Shizuku-only for restricted paths.
  4. Never write to the game's own private dirs; only modded loaders' dirs.

Sources:
- https://developer.android.com/about/versions/11/privacy/storage
- https://developer.android.com/training/data-storage/manage-all-files
- https://anexplorer.io/solve/storage-permissions-android
- https://esper.io/blog/android-dessert-bites-28-file-manager-loophole-closed-73891524
- https://github.com/RikkaApps/Shizuku

## 3. Other Android modding ecosystems

- **Minecraft Bedrock:** real path. Packs go in `behavior_packs`/`resource_packs` (and `development_*` variants) inside `com.mojang`: `/storage/emulated/0/Android/data/com.mojang.minecraftpe/files/games/com.mojang` (or internal, root-only, depending on File Storage Location). `.mcpack`/`.mcaddon` files open directly in the game, which imports them (the preferred route; no storage access needed). Packs sit in `Android/data`, so direct writes need Shizuku/root on 13+. [V path; K import route]
- **Terraria / tModLoader:** no official tModLoader for Android; the 1.4.4+ tModLoader is PC-only. Search results for "tModLoader APK" were spam. Treat as no supported path. [K; search inconclusive]
- **Balatro mobile:** no official mod loader on Android. Lovely injector docs cover only Windows/Mac/Linux/Steam Deck. Community "balatro-mobile-maker" style tooling repackages a patched APK with Lovely/Steamodded and mobile-compat patches (kasimeka mod index); not a simple drop-in folder. [V partial; K for repack]
- **Geometry Dash:** Geode has a dedicated Android launcher (`geode-sdk/android-launcher`, v1.8.0 seen) that launches vanilla GD with Geode loaded; mods are managed in an in-game Geode menu. Exact mod dir not found. [V; path ?]
- Priority for plugins after Stardew: Minecraft Bedrock (easy, pack files), Geometry Dash (launcher-managed, install via Geode's own UI), others low.

Sources:
- https://minecraft.wiki/w/Com.mojang
- https://github.com/ethangreen-dev/lovely-injector
- https://smallgit.dasguney.com/skyline/balatro-mod-index/src/branch/main/mods/kasimeka@mobile-essentials/description.md
- https://github.com/geode-sdk/android-launcher
- https://geode-sdk.org/install

## 4. Nexus Mods API (v1 REST)

- **Base URL:** `https://api.nexusmods.com/v1/`. Auth header: `apikey: <personal key>`. Also send a descriptive `User-Agent` (name/version of app). [V from client library]
- **Stardew game domain:** `stardewvalley` (e.g. `nexusmods.com/stardewvalley/mods/44436`). [V]
- **Endpoints (relative):** [V]
  - `games.json`, `games/{domain}.json` (game info)
  - `users/validate.json` (check key, shows premium flag)
  - `games/{domain}/mods/latest_added.json`, `latest_updated.json`, `trending.json`, `updated.json?period=1d|1w|1m`
  - `games/{domain}/mods/{id}.json`; `md5_search/{md5}.json`
  - `games/{domain}/mods/{id}/files.json`; `.../files/{file_id}.json`; `.../changelogs.json`
  - `games/{domain}/mods/{id}/files/{file_id}/download_link.json` (optional `?key=...&expires=...`)
  - `user/tracked_mods.json`, `user/endorsements.json`, endorse/abstain endpoints
  - No keyword search in v1 (no `search` endpoint); text search needs the GraphQL v2 API or site scraping, and v2 details not verified here. [K]
- **Downloads:** Premium users: `download_link.json` returns CDN URLs directly. Non-premium users: must come through the website's "Mod Manager Download" button, which fires an `nxm://stardewvalley/mods/{id}/files/{file_id}?key=...&expires=...` link; the app registers as the nxm handler, parses `key`/`expires`, and passes them to `download_link.json`. Without them the call is refused (403). [K, consistent with client-lib parameter docs V]
- **SSO:** `wss://sso.nexusmods.com` flow, needs application approval from Nexus; otherwise users paste a Personal API Key (no approval needed). [V]
- **Rate limits:** 20,000 requests/day (resets 00:00 GMT), then 500/hour after that; counts sent in response headers; limits are per key. Older docs said 2,500/day + 100/hour, so read headers instead of hardcoding. [V help article; headers names K: `x-rl-daily-remaining`, `x-rl-hourly-remaining`]
- **ToS points for a third-party client:** register/identify your app (User-Agent); keys are personal and must not be shared/shipped in the app; do not bypass the premium/nxm download flow or scrape; do not re-host mod files; respect rate limits and cache responses; SSO requires approval; app can be disabled by Nexus. Official acceptable-use page could not be fetched; verify at `nexusmods.com/about/api-acceptable-use-policy` before release. [K/?]
- **Design implications:** non-premium users must use the browser -> nxm handoff (register intent filter for `nxm://` on Android), premium can one-tap install; cache lists; Nexus has no official Android Vortex, so there is no policy for mobile either way.

Sources:
- https://pynxm.readthedocs.io/en/latest/_modules/pynxm.html
- https://pynxm.readthedocs.io/en/latest/
- https://www.nexusmods.com/dispatch/news/13921
- https://help.nexusmods.com/article/105-i-have-reached-a-daily-or-hourly-limit-api-requests-have-been-consumed-rate-limit-exceeded-what-does-this-mean
- https://app.nexusmods.com/api-docs (blocked to fetcher; the canonical reference)
