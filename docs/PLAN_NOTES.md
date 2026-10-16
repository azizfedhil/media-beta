# Pear. fix plan: notes

Written after applying the plan. Nothing has been compiled or run. Checks so far were by reading the code and a brace-balance pass over every touched file.

## Build result (first CI run)

- `:shared:compileAndroidMain` passed. That compile covers commonMain, jvmCommonMain and androidMain, so every shared change compiles for Android, and OkHttp's `okhttp3` types are visible from `AndroidNetwork.kt`.
- `:shared:compileKotlinDesktop` failed on four redeclarations that were already in the uploaded repo: `FilePrefsPersistence`, `FileFactStore`, `FileResponseCache` and `PlistCodec`. Each had a copy in `desktopMain` and an identical copy in `jvmCommonMain`. The four `desktopMain` copies were removed. The `jvmCommonMain` copies are used by Android and by the desktop tests.
- Second CI run: `:shared:compileKotlinDesktop`, `:desktopApp:compileKotlin` and `:desktopApp:createDistributable` all passed. The desktop tests ran: 716 tests, one failed. `AddonClientMetaTest.aFailureIsNotRememberedButAnUnreadableAnswerIs` failed because the Task 9 retry also covered meta lookups. A meta lookup that fails is supposed to be asked again on the next call, not retried. The retry now applies only to stream lookups, and meta lookups behave as before. The fix has not been rerun yet.

## Task 1. Crash briefing and logging: done, with corrections

- The logger is split in two. `log/PearLog.kt` is in commonMain (a facade that writes to a `LogSink`), because commonMain code calls it. `log/PearLogFile.kt` is in jvmCommonMain (file, rotation, session marker, crash handler). The plan had all of it in jvmCommonMain, which commonMain cannot call.
- `AppDirs.detect().config.resolve("logs")` does not exist on `java.io.File`. The code uses `File(config, "logs")`.
- `java.awt.Desktop` (the "Open logs folder" action) is in `desktopApp/Main.kt`, not in jvmCommonMain. Android has no `java.awt`, and `ManagementFactory` is not used for the same reason.
- `-XX:ErrorFile` is not set. `${user.home}` is not expanded inside a JVM flag, so the path would be wrong. Native crashes are still detected through the `session.lock` marker, and the `hs_err_pid*.log` files the JVM writes in the working directory are listed in the "unclean exit" report.
- The URL logging keeps only the host and redacts long token-like path segments (`safeLogUrl`).

## Task 2. Catalogues box: done

The lower sidebar is one scrolling column. The icon-only sidebar is unchanged.

## Task 3. Pinning: done

Pins are stored per profile under `sidebar.pins` (the one new pref key). The key is in the profile and backup lists. The store is `shell/SidebarPins.kt`.

## Task 4. Detail page landing episode: done, with one skipped step

- Reuses the existing `UpNext.resolve` instead of a second copy of its logic.
- When nothing further has aired, the page opens on the last episode watched, not S1 E1. This is the case reported in the notes.
- Step 4, scrolling the episode list to the selected episode: done for the one-row layout only. The two-row layout is left alone; its row has a different structure and was not changed.
  - `DragLazyRow` takes an optional `state` parameter. It sits before `content`, not after it, because every call site passes a trailing lambda, and a parameter after `content` would have broken them. Named callers and the 11 existing call sites are unchanged.
  - The detail page creates the state and passes it to the one-row episode row only.
  - The scroll fires once per arrival of a season's episodes, without animation. It is keyed on `item.id` and the episode list, not on `selectedSeason` with `episodes.isNotEmpty()` as the plan had it. With `selectedSeason` in the key, a season change scrolls the previous season's list, which has not been cleared yet. With only an emptiness check, a season change made while a list is already showing never scrolls.
  - The guard is the `userChose` flag. A season or episode the person picks sets it, so picking a season does not move the row.

## Task 5. Simkl progress sync

- 5a, sending: done. Deviation: the outbox is a file (`simkl-outbox.json`, through `FactStore`), not a second pref key, because the plan's rule 8 allows one new key. Failed scrobbles are saved and replayed oldest first. Starts and pauses older than 10 minutes are dropped. A `pause` scrobble is sent. On close, an `AppExit` hook waits up to 2.5 s for scrobbles in flight, using the existing `blockingUntil`. A stop that is still in flight when the wait gives up is lost.
- 5b, reading progress back: written and wired (`simkl/SimklHistorySync.kt`, `AppData.simklHistory`, `App.kt`). It runs on connect, on profile switch, and every five minutes. The Simkl endpoints (`/sync/activities`, `/sync/playback/`, `/sync/all-items/shows/`, `/sync/all-items/movies/`) and reply shapes come from the plan and were not checked against the live API. Parsing is defensive. **Check with a real account.** The log lines are tagged `SimklSync`.
- The duration of a paused title uses the cached TMDB runtime when present, otherwise 45 min for a show and 120 min for a film. The position is therefore an estimate until the real duration is known.
- 5c, pushing manual "mark as watched" to Simkl: skipped (optional in the plan).

## Task 6. Windows key in full screen restarts the player: done as a hypothesis fix

While a film is on top, the layout branch is frozen to the last non-player layout, and a zero-width window is ignored. The cause is still a hypothesis. Check `pear.log` for the `Window` and `Player` lines to confirm.

## Task 7. Maximized window restores after full screen: done as a hypothesis fix

The control remembers the state before full screen (`modeBeforeFullscreen`, a default member of `WindowControl`, so Android is unchanged). Leaving full screen to maximized re-checks at 80, 220 and 500 ms. The re-check stops if the person has changed the window since.

## Task 8. Source pills: done

`dragHorizontalScroll` (in `shell/DragScroll.kt`) is applied to the wide source filter row and to the phone chip row. Skipped: forwarding the vertical wheel to the pill row (optional in the plan).

## Task 9. Add-on failures: done

- Desktop and Android: OkHttp pool tuned and emptiable, through the `preconfigured` engine option. This needs `okhttp3` types at compile time. They come through `ktor-client-okhttp`, which is not verified. If the build cannot see them, add the okhttp dependency. The plan forbids new Gradle dependencies, so that is a decision for you.
- Stream deadline 8 s to 15 s. One retry on timeout, network error, 429 or 5xx, after 600 ms and a connection reset (at most one reset per 5 s). A 404 and other 4xx are not retried.
- Reload in the picker drops the pooled connections before asking again.

## Logging added (Task 1f)

Add-on failures and retries, the player's engine created and released, load errors, P2P playback failures, Simkl sync failures, window mode changes and placement, and the current route (`PearLog.context`).

## Left unchanged on purpose

The `// TEMP-CW` debug prints. Storage formats. `AppExit`. The Simkl library sync (`LibrarySync`, `SimklStore.doSync`) and its `usesSimkl` gate.

## Things to check when you build

1. Simkl pull (Task 5b): a real account, with progress made in another app. Look for `SimklSync` lines.
2. Full-screen restart (Task 6) and restore (Task 7): reproduce, then read the `Window` and `Player` lines in `pear.log`.
3. Desktop compile (see Build result above). The okhttp3 visibility question is answered by the Android compile.
4. Existing tests were not run. Two were read to confirm they are unaffected: the scrobble-count assertions in `PlaybackProgressTest` (none call `pausedOrBackgrounded`), and the request-count assertions in `AddonClientTest` (the unknown routes return 404, which is not retried).
