# Simkl, history, library, up next, skip intro (Batch 2.4)

Spec: `SimklStore.swift`, `WatchHistory.swift`, `WatchLog.swift`, `LocalLibrary.swift`, `LibrarySource.swift` (`LibraryPrefs`, `LibrarySync`), `IntroClient.swift`,
`UpNext.swift` and `EpisodeSupport.swift` (`EpisodeItem`, `EpisodeLoader`).
Code: `shared/.../history/`, `simkl/`, `meta/IntroClient.kt`, `model/AppleDate.kt`, `shell/HistoryCheck.kt`.

## What is here
- **`WatchHistory`** (per profile, `watch.history[.<id>]`, archive in `watch.archive[.<id>]`): progress per title, the "watched through S·E" marker for shows, movie done flag,
  `checkpoint` (saves during playback without redrawing screens), `update` (on close), remove/archive, cap of 30 live entries (100 hard), `link` for tt…/tmdb: aliases,
  mark/unmark watched, `dismissUpNext`. Also `Fmt.clock` / `Fmt.remaining`.
- **`WatchLog`** (`watch.log[.<id>]`): seconds per day and hour, trimmed to about 14 months, average daily watch time.
- **`LocalLibrary`** (`library.local[.<id>]`): the profile's own watchlist and the three Library rows. **`LibraryPrefs`** (`library.prefs[.<id>]`): which library the profile uses,
  auto sync, last sync. **`LibrarySync`**: the two-way merge with Simkl (never deletes on either side; "watched" wins; a title removed on one side after the last sync isn't resurrected).
- **`SimklStore`**: device-flow sign-in (RFC 8628, `media:read media:write`) with the classic PIN as fallback, token refresh (within a day of expiry, and once after a 401),
  library sync through `/sync/activities` (unchanged = one request, changed = only what changed, a removal = one full download, 429 respected), `scrobble`, `add` (many titles
  in one request), `addToWatchlist`, account name and picture, disconnect with revoke.
- **`IntroClient`**: TheIntroDB intro/recap/credits/preview times by TMDB id, then IMDb id, kept for the run.
- **`UpNext`** + **`EpisodeLoader`**: the next aired episode (TMDB first, TheTVDB fills gaps), and the "best rated episode" flag.
- `AppData.switchProfile(id)` points history, log, library, library prefs and content settings at the active profile (the app calls it when the profile changes).

## Storage
All JSON uses the iOS key names and shapes, so a backup restores in both apps (Batch 2.5). Dates are Swift's: a number of seconds since 2001-01-01 (`model/AppleDate.kt`).
Not stored in settings: the Simkl token, refresh token, its expiry and client id (secret store), and the account name/picture and the library copy (`facts/simkl-*.json`,
the iOS app keeps those in the caches folder).

## Differences from the Swift app (on purpose)
1. **A damaged entry is skipped, not the whole list.** Swift drops the whole history if one entry can't be read.
2. **Time zone.** The watch log's day and hour come from the system time zone (`localParts`), as iOS does with the current calendar.
3. **Token expiry and client id** live in the secret store with the token (like the TVDB token date in 2.2), not in settings.
4. **Scrobble target.** `scrobble` takes `ScrobbleTarget(imdb, season, episode)`; the player's `PlayRequest` doesn't exist yet (Batch 4).
5. **`SimklStore` takes a coroutine scope** for the sign-in polling and background refresh (Swift uses `Task`). The sign-in page can't be opened by the store: the screen shows the
   code and opens `verificationUriComplete`.
6. **Same Simkl app id as iOS** (`SIMKL_BUILT_IN_CLIENT_ID`, public). If Simkl wants a separate registered app for desktop, change the constant; the settings screen (3.5) will also offer an own id.
7. **No history for Jellyfin/Plex** and no iOS-only pieces (Live Activities, widgets).
8. **Not here:** the Library tab, the Continue Watching row UI, the profile statistics page (3.1, 3.4) and the Simkl connect UI (3.5). The stores they need are all here.

## Check it (Settings -> Developer -> History & Simkl check)
1. **History:** press `Movie 50%`: "Continue Watching has it: true". `Movie finish`: watched, no longer in progress. `S1E3 40%`, `S1E3 finish`, `Mark through S1E5`, `Unmark S1E2`:
   the marker line follows. `Dismiss Up Next` hides the candidate; `S1E3 finish` again does not bring it back until a later episode is finished.
2. **Up Next (dev show):** after `S1E3 finish` it answers `S1E4`; after `Mark through S1E5` and a 10-episode season it moves on. `Real shows` uses TMDB for finished shows with real ids.
3. **Log / library:** `Log +30 s now` (total grows), `Save movie: plan` / `watched`, `Unsave movie` (history untouched).
4. Close and reopen the app: everything above is still there. Switch profile in Settings -> Developer -> Storage check: the lists change.
5. **Simkl:** `Connect` shows a code and `simkl.com/pin`; `Open page` opens it. Approve; within about 5 s it says Connected as <name>, and the library row counts appear.
   `Sync now` twice: the second is one small request. `Library: local/Simkl`, `Auto sync`, `Sync library (merge)` print "Received N · Sent M" or "Already in sync."
6. `Scrobble start` / `Scrobble stop 90%` with an IMDb id; the title appears in Simkl's history. `Disconnect` clears the tokens (and revokes the grant).
7. **Skip intro:** `tt0133093` with empty season/episode, or `tt0944947` season 1 episode 1: segments are listed with their times.
8. Before syncing a real Simkl account press `Clear dev data`: the dev titles use made-up ids, but the merge sends history titles whose ids look like IMDb ids.

## Not compiled
The authoring environment has no Gradle. `gradle :shared:build` is the real check; the likely trouble spots are the `@Serializable(with = AppleDate::class)` on the
nullable `lastSync` in `LibrarySync.kt` and the `override var ... private set` properties in `SimklStore.kt`.
