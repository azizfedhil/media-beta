# Packaging Pear for desktop

This page is the map of how a Pear desktop build becomes an installer, and what is done versus still open.

## Versions

- The version the app shows is `pearVersion`, a Gradle property. It defaults to `0.0.1-Alpha` (`gradle.properties` and
  `desktopApp/build.gradle.kts`). CI sets it from the tag: `desktop-v0.0.1-Alpha` builds `0.0.1-Alpha`.
- Installers need a numeric version. Windows' version resource and RPM accept only `MAJOR.MINOR.PATCH`, so the packages
  get `0.0.1`, and the `-Alpha` part stays in the app and in the installer's display text.

## Windows

Pipeline (`.github/workflows/desktop-ci.yml`, job `build` on `windows-latest`):

1. `:desktopApp:createDistributable` makes the app image, `desktopApp/build/compose/binaries/main/app/Pear/`. It holds
   `Pear.exe`, the bundled Java runtime, and `app/resources/p2p_core.dll`.
2. A check confirms that `p2p_core.dll` imports no Visual C++ runtime (`tools/inspect_binary.py --imports`). It is built
   with `-C target-feature=+crt-static` (`p2p-core/.cargo/config.toml`), so it runs without the VC++ redistributable.
3. The signing step signs the exe and DLLs, if a certificate is set (`docs/desktop/code-signing.md`).
4. Inno Setup (`choco install innosetup`) builds `PearSetup.exe` from `desktopApp/installer/pear.iss`.

The installer:

- is **per-user**: no administrator rights, no UAC prompt. It installs to `%LocalAppData%\Pear`;
- has one progress page, and opens Pear when it finishes;
- shows a Start menu entry, and an optional desktop shortcut;
- uses the same icon for the exe, the installer, the uninstaller, and Add/Remove Programs;
- uninstalls its files, removes libmpv and the caches, and asks before deleting settings and sign-ins (the default is to keep them);
- closes Pear first (`CloseApplications`), and restarts it after a silent update (`RestartApplications`).

**libmpv** is not in the app. It is fetched into `%LocalAppData%\Pear\native\libmpv-2.dll`, which is outside the app
folder, so an update that replaces the app leaves it in place. The build downloads the libmpv link in `gradle.properties` and records its SHA-256. The installer downloads the same link
(with the mirror as a fallback) with Windows PowerShell and checks the same hash. The app's
loader searches that folder (`MpvNativeLoader`, `%LOCALAPPDATA%\Pear\native`).

### Build locally

```text
:desktopApp:createDistributable            # the app image (needs the Rust engine built first, which Gradle does)
python tools/make_icons.py <AppIcon-1024.png>   # once: pear.ico and pear.png
ISCC.exe /DPearVersion=0.0.2-Alpha /DPearNumericVersion=0.0.2.0 /DLibmpvUrl=<link> /DLibmpvMirrorUrl= ^
    /DLibmpvSha256=<hash from shared\build\libmpv\sha256.txt> desktopApp\installer\pear.iss
```

## Linux

- `:desktopApp:packageRpm` builds an RPM. jpackage needs `rpmbuild`, which CI installs with `apt-get install rpm`.
- The launcher uses `pear.png` (the Linux icon) and the category `AudioVideo`.
- `p2p_core` (`libp2p_core.so`) is bundled. It is built on `ubuntu-22.04`, the oldest Ubuntu runner, so it runs on newer distributions.
- **libmpv is a dependency, not a bundled file.** The RPM should require `mpv-libs` (Fedora; the package is in RPM Fusion)
  or `libmpv2` (Debian and Ubuntu). **This is not wired yet.** Compose exposes no option for RPM `Requires`, so it needs
  a post-build step (`rpm` tools) or a direct jpackage call with `--linux-package-deps`. Check the result with `rpm -qpR`.
- **Testing needs RPM Fusion** on the Fedora VM, or `dnf` will fail on the missing dependency and say nothing about
  RPM Fusion. Consider making the failure message say so.
- The loader asks for `libmpv.so.2` first on Linux, because that is the only name a runtime package ships
  (`MpvNativeLoader.systemNames`).

Updates on Linux: no self-updater. The app shows "a new version is available" with the release link, and the user
installs the new RPM.

## Releases

Push a tag `desktop-v<version>`. CI builds both platforms, writes `latest.json` with `tools/make_latest_json.py`
(version, URLs, SHA-256 and size per installer), and publishes a GitHub Release marked *Latest*, with
`PearSetup.exe`, the RPM and `latest.json` attached.

`latest.json` is read by the app's update check. The hash shows that a download is the file the release published. It
does not show who published it: signing does that.

## Updates

**Windows.** The app reads `https://github.com/<owner>/<repo>/releases/latest/download/latest.json`. The release job marks
each release *Latest* and does not mark it as a pre-release, because GitHub skips pre-releases for that URL.
`tools/make_latest_json.py` writes the file. `decideUpdate` in `shared/.../update/PearUpdates.kt` compares it with the
running version. `checkForUpdate` asks for it and never throws: offline or a broken file means "no update", quietly.
Still to do: the background download of `PearSetup.exe`, the "Restart to update" prompt, and the rule that an update
never interrupts playback.

**Linux.** No self-updater. When the manifest has a newer version, the app shows the release link. The user installs the
new RPM.

## Status

| Item | State |
| --- | --- |
| Version scheme (display `0.0.1-Alpha`, packages `0.0.1`) | Done in Gradle. Check on the first CI run that jpackage accepts `0.0.1` on both platforms. |
| Icon generator | Done and tested (`tools/make_icons.py`). **The icon itself is missing**: send a 1024 px PNG, or the iOS AppIcon. |
| Window and title-bar icon | Done (`Main.kt`). Falls back to Java's icon when the PNG is absent. Not compiled yet. |
| libmpv search order on Windows (`%LOCALAPPDATA%\Pear\native`) | Done, with a JVM test. Not compiled or run yet. |
| libmpv search order on Linux (`libmpv.so.2` first) | Done, with a test. Unverified on Fedora. |
| libmpv fetch at launch, with the "Setting up Pear…" screen | Done and tested against a local HTTP server (`VerifiedDownloadTest`). Inactive in a build without a hash (offline or not Windows). Not compiled. |
| libmpv link and hash | Done in the build: no file to fill in. The first CI run prints the hash; compare it once with your own download (`python tools/inspect_binary.py`). |
| libmpv installer download | Done with Windows PowerShell, no plugin. Not compiled yet. |
| libmpv mirror | `libmpvMirrorUrl` in `gradle.properties`, empty until a GitHub Release asset exists. The app and the installer both try it after the Drive link. |
| Startup cost of the check | The launch check hashes libmpv (about 100 MB) each time. Measure it on a real machine. If it is noticeable, check the size first and hash only then. |
| p2p_core: static CRT | Config done. Not yet built and checked on Windows. |
| Inno Setup script | Written; not compiled. |
| CI (Windows installer, RPM, tagged release) | Written; not run. The first run will tell. |
| Code signing | Documented (`docs/desktop/code-signing.md`). Hook in place. No certificate. |
| Update check: decision and manifest | Done and tested. No UI yet. |
| Update: background download, "Restart to update" | **Not started.** |
| jlink module list (size) | **Not started.** `includeAllModules = true` is still on. |
| RPM `Requires: mpv-libs` | **Not started** (see Linux). |
| Test on a clean Fedora VM | **Not started.** |
| `SimklStore` user agent `MediaHub/0.1.0` | Left as it is. It is a shared default, not the desktop version. Decide whether Simkl should see `0.0.1-Alpha`. |

## Licence

The Windows libmpv is an LGPL build (the mpv-dev package). Shipping it in the installer means the LGPL terms apply: include
its licence text, and offer the source of the version you ship. The About screen already credits libmpv.
