# Pear. desktop (Kotlin Multiplatform + Compose)

Batch notes: `theme.md` (1.2), `glass.md` (1.3), `shell.md` (1.4), `storage.md` (2.1), `metadata.md` (2.2), `addons.md` (2.3), `history.md` (2.4).

Plan: "Pear. desktop and Android port plan.md". Decisions: `phase0-decisions.md`.

- `shared/`: KMP module (commonMain UI and data, desktopMain). Android target is stubbed, enabled in Phase 7.
- `desktopApp/`: Compose Desktop entry point.
- `spikes/`: Phase 0 experiments, each its own Gradle build (not part of the root build).
- `p2p-core/`: Rust engine (iOS, and desktop via UniFFI), untouched by the Gradle build.

Run: `gradle :desktopApp:run` (Gradle 9.5.1, JDK 21). To use `./gradlew`, commit the wrapper files from the
`gradle-wrapper` artifact of the "Desktop CI" workflow. Phone-size window: resize to about 390 px wide.

## Build notes

- **P2P binding check** (`shared/build.gradle.kts`, `generateP2pBindings`). After UniFFI writes `p2p_core.kt`, the build checks that the names the desktop code uses are there. The check now ignores backticks, because UniFFI may quote an identifier (`` `defaultEngineConfig` ``); the Kotlin is the same either way. This is what the first Windows CI run tripped on, before any Kotlin compiled.
- **Commit `p2p-core/Cargo.lock`.** There is none in the repository, so CI resolves every crate to its newest compatible version on each run (the log shows "Locking 346 packages"). A lock file pins the versions the iOS and desktop builds use. Generate it once with `cargo generate-lockfile` (or a `cargo update` you have checked) and commit it.
- **Gradle deprecation warnings** on `compose.runtime`, `compose.animation`, `compose.foundation` and `compose.ui` (`shared/build.gradle.kts`, lines 20 to 23) are warnings, not errors. They can be moved to explicit coordinates when the Compose version is next changed.

## First compile of the desktop port (CI)

The first Windows CI build that got past the binding check failed in `compileKotlinDesktop`. The errors, and the fixes:

- `PlayRequest` used `P2PSource` without importing it (`com.mediahub.pear.p2p`).
- `PlayerOverlay` read `type`, `key` and the modifier flags on `KeyEvent` without importing their extension properties from `androidx.compose.ui.input.key`. `AdaptiveShell` already imports them; the overlay now does too.
- `PlayerSurfaces` imported `matchParentSize`, which is a member of `BoxScope` and can't be imported. Removed; the call already sits in a `BoxScope` lambda.
- `BackButton` (4.6c) imported `awaitPointerEvent` and `awaitPointerEventScope`, which are members of the pointer scopes, and used `PointerButtons.isBackPressed`, which doesn't exist here. Now uses `isPressed(PointerButton.Back)`.
- `P2PPlatformDesktop` passed UniFFI's `u64` values (`ULong`) where the app's stream and stats types take `Long`. Converted with `toLong()`.

Compose APIs were not checked against a compiled build here. If the next run reports more of this kind, the same check applies: a member of a receiver is used without an import, and an extension property is used without its import.

## Why the crates compile every run, and the cache

Each CI run starts from a clean checkout, so `p2p-core/target` and the cargo registry don't exist and cargo rebuilds all
of the crates (about 4 minutes on Windows). A retry after a later failure rebuilds them for the same reason. Gradle's build
cache does not cover this: `buildP2pCore` is an `Exec` task, and `Exec` outputs are not cached. The workflow now restores
the cargo cache with `Swatinem/rust-cache`. Committing `p2p-core/Cargo.lock` makes the cache key stable across runs.

- `BackButton` (4.6c) now reads the back button through AWT (`BackButton.desktop.kt`), the same route as the right-click
  check in `ClicksDesktop.kt`, instead of a Compose pointer API that doesn't exist in this version.
- `BackButton` (4.6c), second attempt: AWT's `InputEvent` has masks only for buttons 1 to 3, so the held state of button 4 can't be read from the modifiers. The back press is now the `MOUSE_PRESSED` event for button 4, one per click. The edge detector that tracked a held button was removed with it.
