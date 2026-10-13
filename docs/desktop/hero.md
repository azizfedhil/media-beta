# Home hero (desktop)

Code: `shared/.../home/HeroBanners.kt` (both heroes, the text, the animation), `home/TitleArt.kt` (the logo or its text),
`home/HomeScreen.kt` (passes each layout's scroll state in), `theme/PearText.kt` (`onTextLayout` tells the synopsis when it is cut).

## What the hero does now

- **Logos on both layouts.** `TitleArt` shows the title's logo (the add-on's, else TMDB's) and falls back to the title as text until the logo
  has loaded. Settings' "Logos instead of title text" applies to the hero as it does to the theme cards.
- **Every MDBList rating.** The phone hero shows the same ratings row as the wide one (IMDb, Rotten Tomatoes, RT Audience, Metacritic and
  the rest MDBList returns). A row that does not fit scrolls. The old placeholder score ("83%") is gone.
- **Parallax.** The artwork is drawn taller than its window and drifts at half the scroll speed: the phone hero follows the Home column, the
  wide panel follows the feed. The overscan is an eighth at each end, so no edge shows.
- **Zoom.** While a page shows, its artwork zooms slowly from 1.0 to 1.1 over 18 seconds, linear, as iOS's Ken Burns zoom does. It returns to
  1.0 quickly when the page leaves.
- **Animation.** Pages change with a fade and a 10% slide. The dots widen smoothly for the current page and can be clicked.
- **Blend and glow.** The artwork fades into the page along iOS's eased curve (smoothstep), so the ambient glow behind the hero shows through
  the bottom. The hard colour edge is gone.
- **Wide text.** The text uses the full width of the panel (it was capped at 340 dp). The synopsis shows six lines, with More and Less when it
  is longer. The synopsis is 17 sp Medium in the wide layout and 15 sp Medium on the phone (it was 15 and 12 sp Regular).
- **Wide fade.** A short fade into black across the lower half of the panel keeps the title, ratings and synopsis readable.
- **Details button.** The padding now comes before the background. Before, the accent only covered the text, so the pill had no visible
  background. The type pills had the same fault and are fixed too.

## Not done

- A Play button. The hero's button is "Details", as on iOS; the iOS hero has no Play button either.
- Swipe paging and drag on the hero (iOS pages by swiping). The desktop hero pages on its timer and by its dots.
- The phone synopsis has no More toggle: three lines, as before.

## Not compiled

Written without a Gradle or Kotlin toolchain. Run `gradle :shared:allTests` and `gradle :desktopApp:run` before relying on it. The zoom,
the parallax and the fade are estimates to check on screen; the numbers (18 s, 1.1, half speed, eighth overscan) are starting points.
