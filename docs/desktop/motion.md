# Motion (desktop)

Code: `theme/PearTokens.kt` (`PearMotion`, the durations, curves and the press spring) and `theme/PearTransitions.kt` (enter and leave transitions for overlays, sheets and player panels). The reference is the Swift app: `.snappy` for selection and state changes, `.smooth` for content, `.easeOut` for fades, and a short spring for presses (`HomeView`'s button style).

## Rules

- **Every state change moves.** A hover, a press, a selection, a changed icon, an opened overlay: none snaps. Colours use `animateColorAsState(..., PearMotion.snappy(PearMotion.FAST))`; scale and lift use `animateFloatAsState(..., PearMotion.press())`.
- **Hover and press** scale cards by a spring (`press()`): posters 1.03 and 0.95, rows and episodes 1.02, the Play button 0.97. Glass buttons press to 0.94.
- **Overlays** (the profile panel, the source picker, the context menu) fade in and out. The sheets rise from the bottom in the phone layout (`sheetIn`/`sheetOut`) and grow in the wide one (`overlayIn`/`overlayOut`). An overlay keeps its last request while it leaves, so it can animate out after it is cleared.
- **Player panels** rise and fade in and out (`panelIn`/`panelOut`), and a switch from one panel to another crossfades.
- **Tabs** cross-fade on a switch (`KeepAlive` in `AdaptiveShell.kt`). A page keeps its room until its fade-out ends.
- **Symbols** cross-fade when they change (bookmark on and off, watched and not) in `GlassButton`.
- Durations come from `PearMotion`. Use `FAST` (150 ms) for feedback, `BASE` (250 ms) for fades, `PUSH` (350 ms) for pushes and sheets. Do not add new numbers.
