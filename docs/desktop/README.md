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
