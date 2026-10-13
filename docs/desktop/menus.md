# Context menus (desktop)

A right click opens a menu (a long press, on the phone sheet). The menus are built on `shell/ContextMenu.kt`: `clickOrMenu` for the
click and the right click, `menuAnchor` for where the menu opens, and `openContextMenu` to show it.

## Where the desktop has menus

- Posters (`PosterCard`), and the theme cards on Home: the title menu (`titleMenuItems`).
- Seasons and episodes: the season menu; an episode's "..." button and its right click open the episode menu (download, watched marks).
- Sources, in the wide picker and on the phone sheet: download, the state of a download, pin or unpin.
- Continue Watching and Up Next cards: "Remove from Continue Watching", then the title menu.
- Home rows: right click the row title to hide the row, or take an added row off Home.
- Media servers: show on Home or hide from Home, and remove.
- Add-ons: turn on or off, configure (when the add-on has a setup page), and reload.
- Downloads: the same menu as the iOS download row (pause, resume, retry, cancel or delete). Play is missing: the desktop has no offline
  playback yet.

## Still missing from iOS

- The movie Play button on the details page (`DetailView.swift`, line 285) has a menu with the download options. The desktop's movie page
  has no equivalent yet.
- The menus are right click only. A touch device long press is not wired on the desktop shell.

## Not compiled

Written without a Gradle or Kotlin toolchain. Run `gradle :shared:allTests` and `gradle :desktopApp:run` before relying on it.
