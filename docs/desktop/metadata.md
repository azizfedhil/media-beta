# Metadata clients (Batch 2.2)

Spec: `TMDBClient.swift`, `TVDBClient.swift`, `MDBListClient.swift`, the metadata parts of `Models.swift` (`MetaPreview`) and `ContentRules.swift`.
Code: `shared/.../meta/` (clients), `model/` (shapes), `net/` (HTTP and caches), `data/ContentFilter.kt` (filter decisions),
`shared/src/desktopMain/.../net/` (OkHttp, files).

## What is here
- **`Http` seam** (`net/Http.kt`). Every client talks to a small interface, not to Ktor. `KtorHttp` implements it on a Ktor client; the desktop one
  uses OkHttp (system proxy, HTTP/2, gzip). Tests use a fake. Errors the clients throw on purpose derive from `MetaException`
  (`NoKeyException`, `BadStatusException`, `BadUrlException`, `UnresolvableException`, `NetworkException`).
- **`TmdbClient`**: every public call of the Swift actor: `trending`, `search`, `recommendations`, `details`, `basicDetails`, `cachedDetails`,
  `traits`, `episodes`, `videos`, `network`, `logo`, `genres`, `discover`, `themed`, `imdbId`, `tmdbIdentifier`. Same rules:
  own key (`tmdb.key`) goes straight to TMDB, otherwise the Supabase proxy with the public key in two headers; ids that can never resolve
  (`kitsu:`, `mal:`...) cost no request; concurrent asks for one IMDb id share one `/find`; misses are remembered for the run, network failures are not.
- **`TvdbClient`**: login (token kept as a secret, renewed after 25 days or on a 401), episodes with thumbnails, clear logos. Ids may be numbers or strings.
- **`MdbListClient`**: ratings (IMDb, RT, RT Audience, Metacritic, Letterboxd, Trakt), the user's lists, list items in both answer shapes.
- **`MetaPreview`** reads and writes the Swift JSON (`imdbRating`, `genres`/`genre` in; `tmdbGenres`, `lang`, `countries` out), so saved titles are
  readable by both apps. **`ContentFilter.kt`** has the decision logic 2.1 left out (`outcome`, `excludedGenreQuery`, `hidesLanguage`, region tables).
- **`MetaServices`** (in `AppData.meta`) wires the three clients to the settings: keys are read live on every request.

## Caches
| What | Where | How long |
| --- | --- | --- |
| TMDB / TVDB / MDBList-ratings answers | `%LOCALAPPDATA%\Pear\Cache\http` / `~/.cache/pear/http` (50 MB, oldest used first) | trending 30 min, search 10 min, discover 30 min, genres 7 days, everything else 6 h |
| IMDb id <-> TMDB id, keyword ids, network icons | `facts/tmdb-facts.json` in the config folder | permanent (capped, oldest dropped) |
| Seasons, genres, videos, details, ratings, lists | memory | for the run (lists 30 min) |

An older cached answer is used when the server can't be reached or answers 5xx/429 (never for 401/403/404). Only an answer that parses is cached.
API keys are never part of a cache key. User-specific MDBList calls (`/lists/...`) are not kept on disk.
Facts are written 8 s after the last change and when the window closes (`AppData.flush`).

## Differences from the Swift app (on purpose)
1. **Disk cache** is time-based (above) instead of `URLCache`'s header heuristics, and serves stale answers on failure.
2. **Permanent facts** live in their own file, not in the settings file, so settings aren't rewritten every time a poster resolves.
3. **TVDB token date** is stored next to the token in the secret store (`SecretKeys.TVDB_TOKEN_DATE`), so nothing writes `PrefStore` off the UI thread.
   `PrefKeys.TVDB_TOKEN_DATE` stays for iOS parity and is unused.
4. **`PrefStore.map` is `@Volatile`** (one-line change to a 2.1 file): the clients read API keys from background coroutines.
5. **Timeouts** are total per request (TMDB 10 s, others 30 s). Swift's `timeoutIntervalForRequest` is an idle timeout.
6. **`MetaPreview.decodeMetas`** skips an item that can't be read (no id/type/name) instead of failing the whole catalogue.
7. **`budget` and `revenue` are `Long`** (a blockbuster's revenue is above 2^31).
8. **"Newest" uses the local date**; ratings use `%.1f`-style rounding, half up.
9. TVDB and MDBList ratings are cached on disk too (MDBList's free tier has daily limits).
10. Kotlin names: `posterUrl`, `backdropUrl`, `heroUrl`, `withRating`, `withLogo`, `imdbId(tmdb, type)`. Types: `TmdbDetails`, `TvdbEpisode`, `MdbRating`, `MdbUserList`.
11. Kept on purpose: the IMDb-to-TMDB id cache is keyed by IMDb id only (as in Swift), so a title that is both a movie and a show would share one entry.

## Check it (Settings -> Developer -> Metadata check)
1. **Trending movies**, then **Trending series**: a list of titles appears, each line ends with `tmdb:<id>`. The header says "the built-in proxy". The time in the log is the first
   (network) ask. Press **Trending movies** again: it should be near-instant (served from the cache).
2. **Search** (default text "dune"): movies and shows, no people. Pick a result by running Trending/Search again: the first title of the latest list becomes the selected title.
3. **Selected title -> Details**: tagline, runtime, genres, cast, budget/revenue. **Season 1** (select a series first), **More like this**, **Logo, network, trailers**, **IMDb id**.
4. **Discover** chips, **K-drama theme** (an add-on-free Home row), **Genres**.
5. Own key: paste a TMDB key under "Keys" and Save. The header changes to "your own key". Repeat step 1. Remove it to go back to the proxy.
6. TVDB (key and PIN) and MDBList (key): the chips for them say "No ... key set" until you save one. With a key: TVDB episodes S1 and logo, MDBList ratings and lists.
7. **Caches -> Show** lists how many answers are on disk. **Clear answers**, then repeat a call: slow again. Close and reopen the app, repeat a call: still fast (disk cache).
8. Turn the network off (or block the proxy) and repeat a call you made before: it still answers from the cache.

## Not done here
- `ArtworkPool` / image loading (Phase 3). The client already hands posters and backdrops from the logo request to `TmdbClient.artwork` (an `ArtworkSink`).
- Add-on clients and models: done in Batch 2.3 (`addons.md`), together with `TitleClassifier`, which 2.2 had left out.
- Not compiled by the author (no Gradle in the authoring environment): the first `gradle :shared:build` is the real check. Tests: `shared/src/commonTest/.../{meta,model,net}`,
  `data/ContentFilterTest.kt`, `shared/src/desktopTest/.../net`.
