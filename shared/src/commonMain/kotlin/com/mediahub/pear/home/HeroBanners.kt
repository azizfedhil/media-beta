package com.mediahub.pear.home

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mediahub.pear.detail.RatingBadge
import com.mediahub.pear.meta.MdbRating
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.nav.LocalPageActive
import com.mediahub.pear.net.RemoteImage
import com.mediahub.pear.net.attempt
import com.mediahub.pear.net.rememberDominantColor
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.WideScale
import com.mediahub.pear.shell.mouseDragScroll
import com.mediahub.pear.shell.shiftWheelHorizontal
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min
import androidx.compose.animation.animateContentSize
import com.mediahub.pear.shell.LocalHttp

fun fallbackHeroAmbientColor(title: String): Color {
    val h = (abs(title.hashCode()) % 360).toFloat()
    return Color.hsv(h, 0.70f, 0.32f)
}

private val PhoneHeroHeight = 480.dp
private val WideHeroWidth = 460.dp * WideScale.HERO_WIDTH

private val heroTransition: AnimatedContentTransitionScope<Int>.() -> ContentTransform = {
    (fadeIn(tween(900)) + slideInHorizontally(tween(900)) { it / 10 }) togetherWith
        (fadeOut(tween(900)) + slideOutHorizontally(tween(900)) { -it / 10 })
}

private fun easedFade(start: Float, steps: Int = 10): Brush {
    val stops = ArrayList<Pair<Float, Color>>()
    stops += 0f to Color.Black
    for (i in 0..steps) {
        val u = i.toFloat() / steps
        val eased = u * u * (3 - 2 * u)
        stops += (start + (1f - start) * u) to Color.Black.copy(alpha = 1f - eased)
    }
    return Brush.verticalGradient(*stops.toTypedArray())
}

@Composable
private fun rememberHeroIndex(count: Int, intervalMillis: Long, paused: Boolean): MutableIntState {
    val index = remember { mutableIntStateOf(0) }
    // A page under another one (the player, a pushed page) is not on screen, so its hero rests.
    val pageActive = LocalPageActive.current
    LaunchedEffect(count, paused, pageActive) {
        if (paused || !pageActive || count < 2) return@LaunchedEffect
        while (true) {
            delay(intervalMillis)
            index.intValue = (index.intValue + 1) % count
        }
    }
    return index
}

@Composable
private fun HeroAmbient(url: String?) {
    val env = LocalAppEnv.current
    val http = LocalHttp.current
    LaunchedEffect(url) {
        val colour = url?.let { heroAmbientFor(http, it) } ?: return@LaunchedEffect
        env.shell.heroAmbientColor = colour
        env.shell.heroAmbientKnown = true
    }
}

@Composable
private fun rememberTitleRatings(item: MetaPreview): List<MdbRating> {
    val env = LocalAppEnv.current
    var loaded by remember { mutableStateOf<Pair<String, List<MdbRating>>?>(null) }
    LaunchedEffect(item.id) {
        val mdb = env.data.meta.mdblist
        val imdb = if (item.id.startsWith("tt")) item.id
        else item.id.removePrefix("tmdb:").toIntOrNull()?.let { attempt { env.data.meta.tmdb.imdbId(it, item.type) } }
        val ratings = if (imdb != null && mdb.hasKey) attempt { mdb.ratings(imdb, item.type) } ?: emptyList() else emptyList()
        loaded = item.id to ratings
    }
    return loaded?.takeIf { it.first == item.id }?.second.orEmpty()
}

@Composable
private fun HeroArt(
    url: String?,
    active: Boolean,
    scroll: ScrollState?,
    fadeSides: Boolean,
    scrimStart: Float = 0.45f,
    scrimEnd: Float = 0.6f,
    modifier: Modifier = Modifier,
) {
    val zoom = remember { Animatable(1f) }
    // The zoom holds still while this page is not on screen (see LocalPageActive).
    val pageActive = LocalPageActive.current
    LaunchedEffect(active, pageActive) {
        if (!pageActive) return@LaunchedEffect
        if (active) zoom.animateTo(1.1f, tween(durationMillis = 18_000, easing = LinearEasing))
        else zoom.animateTo(1f, tween(durationMillis = 900))
    }
    val bottomFade = remember { easedFade(start = 0.42f) }
    val sideFade = remember {
        Brush.horizontalGradient(
            0f to Color.Black,
            0.50f to Color.Black,
            1f to Color.Transparent,
        )
    }

    BoxWithConstraints(
        modifier
            .clipToBounds()
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawWithContent {
                drawContent()
                if (fadeSides) drawRect(sideFade, blendMode = BlendMode.DstIn)
                drawRect(bottomFade, blendMode = BlendMode.DstIn)
            },
    ) {
        val overscan = maxHeight * 0.125f
        Box(
            Modifier.fillMaxWidth().height(maxHeight * 1.25f).offset(y = -overscan).graphicsLayer {
                translationY = min((scroll?.value ?: 0) * 0.5f, overscan.toPx())
            },
        ) {
            RemoteImage(url, Modifier.fillMaxSize().graphicsLayer { scaleX = zoom.value; scaleY = zoom.value })
            Box(
                Modifier.fillMaxSize().background(
                    Brush.verticalGradient(scrimStart to Color.Transparent, 1f to Color.Black.copy(alpha = scrimEnd)),
                ),
            )
        }
    }
}

@Composable
private fun HeroInfo(
    modifier: Modifier,
    item: MetaPreview,
    ratings: List<MdbRating>,
    wide: Boolean,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    onDetails: () -> Unit,
) {
    var overflows by remember(item.id) { mutableStateOf(false) }
    // Each control gets its own interaction source, so it draws no hover or press overlay.
    val moreSource = remember { MutableInteractionSource() }
    // The ratings also scroll by mouse drag and Shift + wheel, as the Home rows do.
    val ratingsScroll = rememberScrollState()
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (wide) 16.dp else 10.dp)) {
        HeroTypePill(item, wide)
        TitleArt(
            item = item,
            textStyle = PearTheme.type.largeTitle.weight(FontWeight.Black).copy(fontSize = if (wide) 40.sp else 32.sp, letterSpacing = 1.sp),
            maxWidth = if (wide) 420.dp else 300.dp,
            maxHeight = if (wide) 96.dp else 72.dp,
        )
        if (ratings.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth()
                    .mouseDragScroll(ratingsScroll, Orientation.Horizontal)
                    .shiftWheelHorizontal { ratingsScroll.scrollBy(it) }
                    .horizontalScroll(ratingsScroll),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ratings.forEach { RatingBadge(it) }
            }
        }
        item.description?.takeIf { it.isNotBlank() }?.let { desc ->
            PearText(
                text = desc,
                style = PearTheme.type.subheadline.weight(FontWeight.Medium).copy(
                    fontSize = if (wide) 17.sp else 15.sp,
                    lineHeight = if (wide) 23.sp else 20.sp,
                ),
                color = Color.White.copy(alpha = 0.92f),
                maxLines = if (expanded) Int.MAX_VALUE else 3,
                modifier = Modifier.fillMaxWidth().animateContentSize(),
                onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
            )
            if (overflows || expanded) {
                PearText(
                    text = if (expanded) "Less" else "More",
                    style = PearTheme.type.subheadline.weight(FontWeight.SemiBold),
                    color = Color.White,
                    modifier = Modifier.clickable(interactionSource = moreSource, indication = null, onClick = onToggleExpanded),
                )
            }
        }
        HeroDetailsButton(onDetails)
    }
}

@Composable
private fun HeroTypePill(item: MetaPreview, wide: Boolean) {
    Row(
        Modifier
            .clip(RoundedCornerShape(PearRadii.pill))
            .background(Color.White.copy(alpha = 0.18f))
            .padding(horizontal = if (wide) 10.dp else 8.dp, vertical = if (wide) 5.dp else 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        PearIcon(if (item.type == "series") "tv" else "film", size = if (wide) 13.dp else 11.dp, tint = Color.White)
        PearText(
            item.typeLabel.uppercase(),
            style = PearTheme.type.caption2.weight(FontWeight.Bold).copy(fontSize = if (wide) 12.sp else 9.sp),
            color = Color.White,
        )
        item.year?.let { y ->
            PearText(
                y.toString(),
                style = PearTheme.type.caption2.weight(FontWeight.SemiBold).copy(fontSize = if (wide) 13.sp else 9.sp),
                color = Color.White.copy(alpha = 0.85f),
            )
        }
    }
}

@Composable
private fun HeroDetailsButton(onClick: () -> Unit) {
    val c = PearTheme.colors
    val source = remember { MutableInteractionSource() }
    Row(
        Modifier
            .clip(RoundedCornerShape(PearRadii.pill))
            .background(c.accent)
            .clickable(interactionSource = source, indication = null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        PearIcon("info.circle", size = 16.dp, tint = c.onAccent)
        PearText("Details", style = PearTheme.type.subheadline.weight(FontWeight.SemiBold), color = c.onAccent)
    }
}

@Composable
private fun HeroDots(count: Int, index: Int, wide: Boolean, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (i in 0 until count) {
            key(i) {
                val current = i == index
                val source = remember { MutableInteractionSource() }
                val width by animateDpAsState(
                    targetValue = if (current) (if (wide) 22.dp else 20.dp) else 6.dp,
                    animationSpec = tween(durationMillis = 400),
                )
                Box(
                    Modifier.height(5.dp).width(width)
                        .clip(RoundedCornerShape(PearRadii.pill))
                        .background(if (current) Color.White else Color.White.copy(alpha = 0.40f))
                        .clickable(interactionSource = source, indication = null) { onSelect(i) },
                )
            }
        }
    }
}

@Composable
fun HeroCarousel(
    items: List<MetaPreview>,
    onSelectTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
    scroll: ScrollState? = null,
) {
    if (items.isEmpty()) return
    var expandedId by remember { mutableStateOf<String?>(null) }
    val pagerState = rememberPagerState { items.size }
    val scope = rememberCoroutineScope()
    // True while a finger is on the carousel: the auto-advance waits for the swipe to finish.
    val dragged by pagerState.interactionSource.collectIsDraggedAsState()
    val paused = expandedId != null
    LaunchedEffect(pagerState.currentPage) { expandedId = null }
    // A carousel under another page does not advance either.
    val pageActive = LocalPageActive.current
    // Auto-advance every seven seconds, from the settled page. It keys on the settled page, which only changes once a slide
    // has finished, so the effect never restarts in the middle of one. The slide runs in [scope] for the same reason: a
    // change of paused or dragged while it runs must not cancel it.
    LaunchedEffect(pagerState.settledPage, paused, dragged, pageActive) {
        if (paused || dragged || !pageActive || items.size < 2) return@LaunchedEffect
        delay(7_000L)
        val next = (pagerState.settledPage + 1) % items.size
        scope.launch {
            if (next == 0) pagerState.scrollToPage(0) // rewind without streaking through every page
            else pagerState.animateScrollToPage(next, animationSpec = tween(900))
        }
    }
    val current = items.getOrElse(pagerState.currentPage) { items.first() }
    HeroAmbient(current.heroUrl(false) ?: current.backdropUrl ?: current.posterUrl)
    PrefetchHeroAmbient(items.map { it.heroUrl(false) ?: it.backdropUrl ?: it.posterUrl })

    Box(modifier.fillMaxWidth().height(PhoneHeroHeight)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            val item = items.getOrElse(page) { items.first() }
            val tapSource = remember { MutableInteractionSource() }
            Box(Modifier.fillMaxSize().clickable(interactionSource = tapSource, indication = null) { onSelectTitle(item) }) {
                HeroArt(
                    url = item.heroUrl(false) ?: item.backdropUrl ?: item.posterUrl,
                    active = page == pagerState.currentPage,
                    scroll = scroll,
                    fadeSides = false,
                    scrimEnd = if (expandedId == item.id) 0.85f else 0.6f,
                    modifier = Modifier.fillMaxSize(),
                )
                HeroInfo(
                    // While paging the text slides faster than the art and fades as it leaves (iOS HeroPage).
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 20.dp, end = 20.dp, bottom = 34.dp)
                        .graphicsLayer {
                            val phase = pagerState.getOffsetDistanceInPages(page).coerceIn(-1f, 1f)
                            translationX = phase * 70.dp.toPx()
                            alpha = 1f - minOf(abs(phase) * 1.6f, 1f)
                        },
                    item = item,
                    ratings = rememberTitleRatings(item),
                    wide = false,
                    expanded = expandedId == item.id,
                    onToggleExpanded = { expandedId = if (expandedId == item.id) null else item.id },
                    onDetails = { onSelectTitle(item) },
                )
            }
        }
        HeroDots(
            count = items.size,
            index = pagerState.currentPage,
            wide = false,
            onSelect = { page -> scope.launch { pagerState.animateScrollToPage(page) } },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp),
        )
    }
}

@Composable
fun WideHeroPanel(
    items: List<MetaPreview>,
    onSelectTitle: (MetaPreview) -> Unit,
    modifier: Modifier = Modifier,
    collapse: Float = 0f,
    scroll: ScrollState? = null,
    /** How far the sidebar covers the panel's left edge. The panel starts under it; its text and dots keep their places. */
    inset: Dp = 0.dp,
) {
    if (items.isEmpty()) return
    var expandedId by remember { mutableStateOf<String?>(null) }
    val index = rememberHeroIndex(items.size, intervalMillis = 8_000L, paused = expandedId != null)
    LaunchedEffect(index.intValue) { expandedId = null }
    val current = items.getOrElse(index.intValue) { items.first() }
    HeroAmbient(current.largePosterUrl() ?: current.backdropUrl)
    PrefetchHeroAmbient(items.map { it.largePosterUrl() ?: it.backdropUrl })

    val panelWidth = WideHeroWidth * (1f - collapse).coerceIn(0f, 1f)
    Box(modifier.fillMaxHeight().width(inset + panelWidth).clipToBounds()) {
        Box(
            Modifier.wrapContentWidth(Alignment.Start, unbounded = true).width(WideHeroWidth + inset).fillMaxHeight()
                .graphicsLayer { alpha = (1f - collapse * 1.3f).coerceIn(0f, 1f) },
        ) {
            AnimatedContent(
                targetState = index.intValue,
                transitionSpec = heroTransition,
                modifier = Modifier.fillMaxSize(),
                label = "hero-wide",
            ) { i ->
                val item = items.getOrElse(i) { items.first() }
                val tapSource = remember { MutableInteractionSource() }
                Box(Modifier.fillMaxSize().clickable(interactionSource = tapSource, indication = null) { onSelectTitle(item) }) {
                    HeroArt(
                        url = item.largePosterUrl() ?: item.backdropUrl,
                        active = i == index.intValue,
                        scroll = scroll,
                        fadeSides = true,
                        scrimStart = 0.5f,
                        scrimEnd = if (expandedId == item.id) 0.9f else 0.7f,
                        modifier = Modifier.fillMaxSize(),
                    )
                    HeroInfo(
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 30.dp + inset, end = 40.dp, bottom = 74.dp),
                        item = item,
                        ratings = rememberTitleRatings(item),
                        wide = true,
                        expanded = expandedId == item.id,
                        onToggleExpanded = { expandedId = if (expandedId == item.id) null else item.id },
                        onDetails = { onSelectTitle(item) },
                    )
                }
            }
            HeroDots(
                count = items.size,
                index = index.intValue,
                wide = true,
                onSelect = { index.intValue = it },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 26.dp).padding(start = inset),
            )
        }
    }
}

/** How many hero pictures are read for their colour at launch. */
private const val PREFETCH_LIMIT = 4

/** The launch intro runs about five seconds, then a 700 ms fade. The colours are read after it, so the intro stays smooth. */
private const val PREFETCH_AFTER_INTRO_MILLIS = 6_000L

/** A short pause between two reads, so one read never follows another straight away. */
private const val PREFETCH_GAP_MILLIS = 150L

@Composable
private fun PrefetchHeroAmbient(urls: List<String?>) {
    val http = LocalHttp.current
    LaunchedEffect(urls) {
        delay(PREFETCH_AFTER_INTRO_MILLIS)
        // One picture at a time, and no more than PREFETCH_LIMIT of them.
        for (url in urls.filterNotNull().take(PREFETCH_LIMIT)) {
            heroAmbientFor(http, url)
            delay(PREFETCH_GAP_MILLIS)
        }
    }
}