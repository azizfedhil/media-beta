# Phase 0 decisions (draft, gate pending)

Repo path: `docs/desktop/phase0-decisions.md`

## Gate status: PENDING (no 0.2 / 0.3 numbers yet)

Fill this in from the spike's "Print summary" output (2 s samples, averages):

| OS | Source | Render | hwdec | Bar | video-fps | drawn-fps | draw ms | CPU % |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Windows | 1080p60 | window | off | hidden | | | | |
| Windows | 1080p60 | window | off | frosted blur | | | | |
| Windows | 1080p60 | window | off | glass regular | | | | |
| Windows | 4K | max 1080p | auto-copy | frosted blur | | | | |
| Linux | 1080p60 | window | off | hidden | | | | |
| Linux | 1080p60 | window | off | frosted blur | | | | |
| Linux | 1080p60 | window | off | glass regular | | | | |
| Linux | 4K | max 1080p | auto-copy | frosted blur | | | | |

**Pass rule.** On both OSes, 1080p60 content with the default bar visible holds drawn-fps >= 58 and no steady frame drops, windowed and fullscreen. 4K is not part of the gate: it only has to play without audio glitches in performance mode (render capped at 1080p).

**If it fails, find the stage first** (spike overlay shows each one):

| Symptom | Cause | Fix |
| --- | --- | --- |
| mpv-render > ~8 ms | software scaling or decode | hwdec auto-copy, lower render size |
| copy + image > ~6 ms | three full-frame copies per frame | render straight into Skia pixel memory (Batch 4.2), downscale before the copy |
| drawn-fps drops only when the bar is on | blur/glass cost | tint-only bar while playing (or blur in performance mode); full glass only when paused |
| everything slow at 1080p even with the bar hidden | software path too slow | escalate to the OpenGL render API surface (`compose-gl`) before Phase 1 |

## Decisions

### 1. Video surface: software render into a Compose canvas (default)
- Keeps video inside the Compose scene, so glass can blur it. A native video window (mpv `wid`) cannot be drawn over or blurred by Compose (airspace problem), which defeats the goal.
- Known cost: render -> native buffer -> heap -> Skia = three copies. Batch 4.2 removes at least one by letting mpv write directly into Skia-owned memory, and adds the 4K performance mode (downscale before the copy).
- Escalation if the gate fails: OpenGL render API into a GL-backed surface (more work, no copies).

### 2. libmpv licensing
- mpv and libmpv are GPL by default. Building with `-Dgpl=false` gives an LGPL libmpv, but FFmpeg and every other linked library must also be LGPL-only builds.
- Spike: GPL Windows build (shinchiro) bundled in the artifact; Linux uses the system libmpv, so nothing is redistributed.
- Proposed default for releases: ship an LGPL build on Windows (build it in CI from the mpv build scripts with GPL off). Accept GPL only if you are happy to publish the desktop app's source under a GPL-compatible licence. Verify the exact flags when we reach Batch 6.1.
- Linux: AppImage must bundle libmpv (same LGPL rule). A deb or Flatpak can depend on the distro's libmpv.
- About screen (3.5) lists libmpv, FFmpeg, Haze, Skia, Inter, Material Symbols and their licences.

### 3. Fonts and icons (same choice as the Android plan)
- Inter (OFL) bundled as a Compose resource. Material Symbols Rounded (Apache 2.0) for icons, via the SF Symbol -> Material name map in Batch 1.2.

### 4. Default bar style (provisional)
- Frosted blur with noise (Haze blur), matching your Android decision, so both platforms look the same. Haze glass (regular) stays available as an option if the numbers allow it.
- Fallback order when too slow: blur in performance mode -> flat translucent tint while playing.

### 5. Version pins
| Item | Pin | Note |
| --- | --- | --- |
| Kotlin | 2.4.20 | required by Haze 2.0.x |
| Compose Multiplatform | 1.12.1 | required by Haze 2.0.1 |
| Haze | 2.0.1 (`haze`, `haze-blur`, `haze-glass`) | stable; glass module is `@ExperimentalHazeApi` |
| Gradle | 9.5.1 | |
| JDK | 21 (Temurin) | CI and jpackage runtime |
| JNA | 5.14.0 | UniFFI Kotlin bindings and libmpv |
| UniFFI | 0.29 | matches `p2p-core` |
| libmpv | latest 0.3x client API | version logged at startup by the spike |

### 6. Risk table updates
- "Haze 2 is alpha" is obsolete: 2.0.1 is stable; only glass is experimental. Pin versions anyway.
- New: Kotlin 2.4.20 and CMP 1.12 are required together with Haze, so the whole project tracks them.
- New: the Windows spike downloads shinchiro's latest libmpv build; pin a specific release before Phase 6.

## Pinned libmpv (Windows) — recorded for Phase 6

- Release: `2026-10-11-d94d095814` (zhongfly/mpv-winbuild), LGPL build, x86_64 (not v3, so it runs on any 64-bit PC).
- Archive: `mpv-dev-lgpl-x86_64-20261011-git-d94d095814.7z`, SHA-256 `72bc1e3fe1c8aaf17b5243003b110a522d109caf6d871cbc8e05996238b93d06`.
- Contains `libmpv-2.dll`, SHA-256 `e3ae7ade8ccf49e5e0a9c4e95ab464a639e80fe156429cad16a15d083901acfc`, 101,194,240 bytes.
- Hard dependency: `vulkan-1.dll`, which comes with GPU drivers and is not part of Windows. Verify on a machine without it.
- Not in the repository. CI fetches it and checks the hash; the installer carries it. Update this record whenever the pinned release changes.
