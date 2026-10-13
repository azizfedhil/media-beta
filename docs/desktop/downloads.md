# Downloads and source menus (desktop)

Code: `shared/.../downloads/` (the engine and its interfaces), `shared/src/desktopMain/.../downloads/DesktopDownloads.kt` (the folder and the
streamed transfer), `shared/.../detail/SourceMenus.kt` (the menus and the download button), `shared/.../Downloads/DownloadsScreen.kt` (the list).

## What the user gets

- **Source menu.** Right click a source (or long press it on the phone sheet) for its menu, as the long press on the iOS source row. It offers
  Download, or what the listed download can do (pause, resume, retry, cancel, delete), then Pin or Unpin. Torrents say they can't be downloaded,
  as on iOS.
- **Download button.** A small round button on each direct-link source: start, pause, resume, retry, and a check once the file is on disk.
- **Size.** Each source shows its file size, from the add-on's `behaviorHints.videoSize` when it gives one, otherwise from the "💾 2.3 GB" line
  in its description or title. Sizes use decimal units, as iOS's `ByteCountFormatter` `.file` does.
- **Episode menu.** The "..." button and a right click on an episode card: download (the source is chosen from the sources list, as on iOS),
  and the watched marks (mark as watched, mark episodes until here, unmark).
- **Title menu on theme cards.** A right click on a Home theme card opens the same title menu as a poster.
- **Downloads list.** Every download, newest first, with its state, a progress bar while it comes, and its controls. Pause, resume, retry and
  delete are there; playing a download offline is not built yet.
- **Wide source picker.** The episode's still fills the top two thirds of the pane. Its bottom fades into the dark where the logo, the episode's
  name, the ratings, the synopsis and the details (runtime, air date) sit.

## How the engine works

- `DownloadManager` keeps the list in the `downloads.records` preference as JSON, so it survives a restart. Every change runs on one
  single-threaded scope, so the list changes in one order. The UI reads `records` and calls the actions from anywhere.
- Each download is one file in the downloads folder (`config/downloads`), appended to as its bytes arrive. Nothing is held in memory whole.
- A download is identified by its title, or its episode (`imdb` or `imdb:season:episode`). A second download of the same one is ignored.
- Resuming sends an HTTP `Range` request from the bytes on disk. A server that ignores the range sends the whole file, and the bytes already
  on disk are skipped. A server that answers 416 (the file is already whole) completes the download.
- Pause and cancel stop within one read. A chunk that was read is not written after a cancel, so no orphaned partial file is left behind.
- Nothing runs across a restart: anything that was queued or downloading is listed as paused and resumes from its bytes when asked.
- Progress is written to the list at most twice a second, so the screens stay light while a file streams.

## Where things are

- Files: `config/downloads` under the app's config folder. On Windows that is `%APPDATA%\Pear\downloads`, which is a roaming folder. Large
  files there may sync with the profile. Move them to the local app data folder if that matters.
- Index: the `downloads.records` preference (per device, not per profile).
- Transport: `HttpURLConnection` from the JDK, so no Ktor streaming API is used. It follows redirects and sends the source's request headers.

## Not done

- Playing a finished download from the list (iOS plays them offline).
- A limit on how many downloads run at once: every download starts when it is queued.
- Android (the engine is in `commonMain`, but there is no Android transport yet).
- Checking the file against the source's size after it is complete.

## Tests

`commonTest/.../downloads/DownloadManagerTest.kt` covers a full download, resuming from the bytes on disk after a restart, a failure and its
retry, delete, the duplicate rule, and the saved index. `commonTest/.../model/StreamSizeTest.kt` covers reading and formatting sizes.

## Not compiled

This was written without a Gradle or Kotlin toolchain available. Run `gradle :shared:allTests` and `gradle :desktopApp:run` before relying on it.
