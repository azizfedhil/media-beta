# Blockers and questions

Each item says what is blocked, why, and what would unblock it. Nothing here is worked around silently.

## B1. commonMain uses java.awt (blocks the Android compile of :shared)

`shared/src/commonMain/.../data/DesktopClipboard.kt` imports `java.awt.Toolkit`, `java.awt.datatransfer.DataFlavor` and
`java.awt.datatransfer.StringSelection`. Android has no `java.awt`, so the Android target cannot compile commonMain. It is
used by two commonMain files: `settings/SettingsScreen.kt` (lines 293 and 301) and `simkl/SimklConnect.kt` (lines 21 and 111).

Fixing it needs a commonMain change, which is outside the allowed list (E1 to E10), so this batch does not make it. Proposed
change, the smallest one that keeps every call site as it is:

1. Move the current implementation to `shared/src/desktopMain/.../data/DesktopClipboard.desktop.kt` as
   `actual object DesktopClipboard`, with the same code.
2. Replace `commonMain/.../data/DesktopClipboard.kt` with
   `expect object DesktopClipboard { fun copy(text: String); fun paste(): String? }`.
3. Add `androidMain/.../data/DesktopClipboard.android.kt` with the same two functions on `ClipboardManager`.

Desktop behaviour does not change. Call sites are unchanged. Alternative considered: reflection, so the file stays in
commonMain. That would make copy and paste silently do nothing on Android, so it is not recommended.

**Decision needed:** approve the change above (B1), or name another approach.

## B2. Gradle needs an Android SDK for any :shared build (not verified)

The Android KMP library plugin is now applied to `:shared` (D1). I expect Gradle configuration of `:shared` to need an
Android SDK from now on, which would also affect desktop-only machines. This cannot be checked in this sandbox.

Options: (a) accept it, install the SDK on dev machines and in CI (plan T10.3); or (b) gate the Android target behind a
property so desktop-only builds never apply AGP. Option (b) needs one Gradle run to confirm the gate.

**Decision needed:** (a) or (b).

## Q3. Application id

`applicationId = "com.mediahub.pear"` (the iOS bundle id). Open question 1 in the plan: confirm before a release build.

## Not blocked, but unverified

- Every Compose, AndroidX and manifest file in this batch (`MainActivity`, `AndroidWindowControl`, `App.kt` E4 call,
  `SystemBackHandler.android.kt`, the Compose parts of `VideoSurface.android.kt`). They are not checked by the stubs.
- `:androidApp:assembleDebug` and every Gradle task: not run (no Maven, no SDK in the sandbox).
- Device checks listed in `PROGRESS.md`.
