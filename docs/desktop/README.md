# Pear. desktop (Kotlin Multiplatform + Compose)

Batch notes: `theme.md` (1.2), `glass.md` (1.3), `shell.md` (1.4), `storage.md` (2.1).

Plan: "Pear. desktop and Android port plan.md". Decisions: `phase0-decisions.md`.

- `shared/`: KMP module (commonMain UI and data, desktopMain). Android target is stubbed, enabled in Phase 7.
- `desktopApp/`: Compose Desktop entry point.
- `spikes/`: Phase 0 experiments, each its own Gradle build (not part of the root build).
- `p2p-core/`: Rust engine (iOS, and desktop via UniFFI), untouched by the Gradle build.

Run: `gradle :desktopApp:run` (Gradle 9.5.1, JDK 21). To use `./gradlew`, commit the wrapper files from the
`gradle-wrapper` artifact of the "Desktop CI" workflow. Phone-size window: resize to about 390 px wide.
