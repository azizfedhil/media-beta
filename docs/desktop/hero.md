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
- **Blend and glow.** The artwork fades into the page along iOS's eased curve (smoothstep). The ambient glow behind the hero is the average colour of the hero's own image, tuned as iOS tunes it (`AmbientTone`). It is drawn inside the scrolling content, so it moves with the hero, and on the wide layout it reaches further right (`AmbientTuning`). See `shell.md`, "Home glow".
- **Detail page.** The wide detail page reads its glow from the same large image its poster draws, the same way Home does, so the colour matches the picture.
- **Synopsis.** Three lines on both layouts, with More and Less when the text is cut short. Opening one pauses the carousel, so the page does not move under the reader; moving to another page closes it (`expandedId` in `HeroCarousel` and `WideHeroPanel`).
- **Wide fade.** A short fade into black across the lower half of the panel keeps the title, ratings and synopsis readable.
- **Details button and type pill.** The background comes before the padding, so the fill covers the padding too. The Details button keeps the theme accent (decided). The type pill is iOS's white 18% capsule with no border.

## Not done

- A Play button. The hero's button is "Details", as on iOS; the iOS hero has no Play button either.
- Swipe paging and drag on the hero (iOS pages by swiping). The desktop hero pages on its timer and by its dots.
- The synopsis has More and Less on the phone too.

## Not compiled

Written without a Gradle or Kotlin toolchain. Run `gradle :shared:allTests` and `gradle :desktopApp:run` before relying on it. The zoom,
the parallax and the fade are estimates to check on screen; the numbers (18 s, 1.1, half speed, eighth overscan) are starting points.
