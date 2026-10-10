# Settings, profiles and secrets (Batch 2.1)

Spec: `SettingsBackup.swift` (key list), `Keychain.swift`, `Profiles.swift`, `ContentPrefs.swift`.
Code: `shared/src/commonMain/.../data/` (logic) and `shared/src/desktopMain/.../data/` (files and keystore).

## Settings (`PrefStore`)
- A key to value map with the **iOS key names** (`tmdb.key`, `addon.manifestURLs`, `ui.accent`, ...; list in `PrefKeys`). Values are the
  property-list kinds (`PrefValue`: text, flag, whole, real, blob, text list, dict, moment), so Batch 2.5 can read an iPhone backup directly.
- Structured values (profiles, content rules, history, pins) are JSON **stored as bytes**, as on iOS (`setJsonText` / `jsonText`). The JSON field
  names are the Swift ones, so `profiles.list` and `content.prefs` are readable by both apps.
- Saved to `settings.json` in the config folder: Windows `%APPDATA%\Pear`, Linux `~/.config/pear` (or `$XDG_CONFIG_HOME/pear`).
  Writes are debounced (about 0.4 s), go through a temp file and a rename, and are flushed when the window closes and at JVM shutdown.
  A file that can't be parsed is kept as `settings.json.bad`.
- Call it from the UI thread. It is not reactive by itself: stores with Compose state (`ThemeStore`, `GlassStore`, `ProfileStore`,
  `ContentPrefs`) read from it at start and write through.
- Desktop-only keys (`desktop.glassMode`, `desktop.glassPerformance`, `desktop.appearance`) are not in `BackupRules`, so they stay out of backups.

## Profiles (`ProfileStore`, `ContentPrefs`)
- Same rules as Swift: up to 8, the last one can't be deleted, names cleaned to 20 characters, first profile keeps the legacy keys
  (`watch.history`), others use `watch.history.<id>`. Use `ProfileKeys.scoped(base, profileId)` for any per-profile data.
- Deleting a profile removes its `ProfileKeys.scopedBases` keys. A new per-profile store must add its base there (and to `BackupRules.dataPrefixes`).
- `App()` calls `content.load(activeID)` when the active profile changes.

## Secrets (`SecretStore`)
- Holds what the iOS app keeps in the keychain: `simkl.token`, `simkl.refresh`, `tvdb.token` (`SecretKeys`).
- **API keys (`tmdb.key`, `tvdb.key`, `mdblist.key`) stay in settings**, as on iOS, because they are part of the backup. The plan's wording
  "API keys in the OS keystore" would have broken backup parity; say if you want them moved.
- Order on desktop: Linux Secret Service through the `secret-tool` command (package `libsecret-tools`) -> Windows DPAPI (encrypted per user, kept in
  `secrets.json`) -> plain `secrets.json` with owner-only permissions. `set` returns true when the OS protection was used.
  Same fallback idea as the iOS app. Calls may block briefly (a keyring can ask to be unlocked), so call from a background coroutine, not a frame.

## Check it (Settings -> Developer -> Storage check)
1. Shared counter: add one, close the app, reopen: it is still there. `settings.json` is in the folder shown at the top.
2. Add a profile, select it, bump its counter and hide a category; select the first profile: its own numbers are unchanged.
3. Secrets: Store, then Read; the note says whether the OS store or the fallback file was used.

## Not done here
Backup export/import (2.5), Simkl token use (2.4), disk cache for HTTP (2.2; `AppDirs.cache` is ready).
