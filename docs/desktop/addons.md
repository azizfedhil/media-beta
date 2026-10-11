# Add-ons, sources and subtitles (Batch 2.3)

Spec: `Models.swift` (`AddonManifest`, `Addon`, `StreamItem`, `CatalogRow`), `AddonClient.swift`, `AddonStore.swift`, `PinnedSources.swift`,
`OpenSubtitlesClient.swift`, `SubLanguages` (from `SubtitleSupport.swift`), `TitleClassifier.swift`, and the add-on parts of `HomeView.swift` / `WideLayout.swift`.
Code: `shared/.../addons/`, `model/AddonModels.kt`, `model/Subtitles.kt`, `meta/TitleClassifier.kt`, `shell/AddonsCheck.kt`.

## Finished from 2.2 first
- **Compile fix:** `PrefStore.moment` read `.value`; the field is `epochMillis`.
- **`TitleClassifier`** (the content-rule filter for lists) was missing. It decides from genres/origin a title already carries, asks TMDB only for the rest (6 at a time),
  remembers answers in `facts/title-traits.json` (written 5 s after the last lookup and on exit), and doesn't retry a failed lookup in the same run. `AppData.classifier`.

## What is here
- **Models** (`AddonModels.kt`): `AddonManifest` (tolerant: wrong-typed fields are "missing", bad catalogue/resource entries are skipped; only `id` and `name` are required),
  `Addon` (address, `provides`, `normalize`, `isAIOMetadata`, `homeCatalogs`, `configureUrl`), `StreamItem` (torrent detection, trackers, request headers, file extension),
  `CatalogRow`, `CatalogSource`.
- **`AddonClient`**: manifest (retries twice, 1.5 s then 3 s), catalogue (`skip`, `search`), `metaExtras`, and stream lookups. `streamUpdates` is a `Flow` that emits each add-on
  the moment it answers; only add-ons that declare `stream` for that type and id are asked; each has a hard 8 s deadline; failures are skipped. `ordered` restores the user's order.
- **`AddonStore`**: installed add-ons as Compose state, saved under the iOS keys `addon.manifestURLs`, `addon.disabled`, `addon.catalogPicker`. Launch restore fills the list in as
  manifests arrive; unreachable add-ons stay saved and `retryUnreachable()` brings them back. Add, remove, reorder, switch on/off, reload one/all, catalogue-picker flag,
  `enrichmentAddon`, `reloadFromPrefs()` (for the 2.5 import). First launch installs Cinemeta.
- **`PinnedSources`**: one pinned source per show (`pinned.sources`, same JSON as iOS).
- **`HomeCatalogs`**: which catalogues get a Home row (`jobs`: hidden rows, picker add-ons, 12-row cap) and `loadRows` (fetch, filter, build `CatalogRow`s, progress callback, failure count).
  The sidebar's "Catalogues" list now comes from it instead of the dev stand-ins.
- **`OpenSubtitlesClient`** (public Stremio endpoint, `subs.online`, `subs.baseURL`) with `choices`, and `SubLanguages` / `OnlineSubtitle`.

## Caches
| What | Where | How long |
| --- | --- | --- |
| Manifests | disk cache (`http/`) | 6 h; a reload skips it but stores the new answer |
| Catalogue pages | disk cache | 30 min per page; an older copy is served if the add-on fails (5xx/429) or can't be reached |
| Streams | never | 8 s deadline per add-on |
| `meta` extras | memory | for the run (failures and timeouts are not kept) |
| Online subtitles | memory | for the run (empty answers too) |
Add-on addresses can hold API keys, so the cache key is a hash of the address, never the address.

## Differences from the Swift app (on purpose)
1. **No Jellyfin/Plex.** Swift's client and `enabledAddons` consult `MediaServerRegistry`; media servers are not in the port plan. `AddonStore.enabledAddons` is installed add-ons only.
2. **No P2P gate yet.** `AddonClient(streamFilter = ...)` is where Swift's `P2PGate.apply` plugs in (Batch 4.5). `StreamItem.isPlayable(p2pReady)` takes the engine state as a parameter.
3. **`likelyUnsupported` is dropped.** It lists containers AVPlayer can't play; mpv plays them. `fileExtension` is kept.
4. **Tolerant manifests.** Swift fails the whole manifest on one malformed catalogue or `types` field; here that entry/field is skipped (same idea as 2.2's `decodeMetas`).
5. **Time-based disk cache** instead of `URLCache` header handling (as in 2.2). `clearCache()` hides older answers for the current run only (it doesn't empty TMDB's answers); after a restart
   they can reappear until their time is up.
6. **Stable id for link-less streams.** Swift's `StreamItem.id` made a new UUID on every read; here it is fixed when the item is made.
7. **Changes are `suspend`** (add, remove, move, enable, picker...). They run one at a time on a single-threaded dispatcher so the launch restore can't race them. Call from a coroutine.
8. **Ids in addresses are percent-encoded** as a path segment (`tt1:2:3` keeps its colons). Swift only encoded stream ids.
9. **Language names** outside the 28 known languages show the upper-case code (Swift asks the system's locale tables).
10. **`TitleClassifier` file** is in the config folder's `facts/` (Swift: the caches folder), capped at 4000 titles.

## Check it (Settings -> Developer -> Add-ons check)
1. Opens with "restoring..." then "1 installed" (Cinemeta, the default). Only hosts are shown, never full addresses.
2. Paste an add-on address (also `stremio://...`) and **Add**. A bad address says "Not a usable address"; an unreachable one fails after about 5 s (3 tries).
3. **Switch off / on**, **Up / Down**, **Reload**, **Remove** on an add-on card. Close and reopen the app: the order and switches are kept.
4. **Home rows**: one line per row (`Popular Movies [Cinemeta]: 100 of 100 titles`). Hide a category in the (3.4) content settings later and rows shrink. Add-ons without a catalogue are skipped.
5. Enter `tt0133093` (movie) and press **Streams**: add-ons answer one by one with their time, "asked N of M" counts only add-ons that serve streams. Cinemeta serves none.
   For a series: id `tt0944947:1:1`, type `series`.
6. **Meta extras**, **Subtitles** (languages found, and the list the player will show for your `sub.lang`), **Pin first source** / **Show pin** / **Remove pin**.
7. **Search catalogues** (add-ons whose catalogues take `search`) and **Page 2 (skip 100)**.
8. Turn the network off and press **Home rows** again: rows served from the cache still load.

## Not done here
- Home/Explore screens that show the rows (3.1, 3.3), the Add-ons settings page (3.5), `ArtworkPool`.
- The AIOMetadata catalogue picker UI (3.4/3.5): the store and `ContentRules.addedRows` already support it.
- Not compiled by the author (no Gradle in the authoring environment): `gradle :shared:build` is the real check.
