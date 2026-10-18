package com.mediahub.pear.shell

import androidx.compose.foundation.background
import com.mediahub.pear.party.forPartyRoom
import com.mediahub.pear.log.PearLog
import com.mediahub.pear.log.PearTrace
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.detail.DetailScreen
import com.mediahub.pear.downloads.DownloadsScreen
import com.mediahub.pear.explore.ExploreScreen
import com.mediahub.pear.glass.GlassScene
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.history.ContinueWatchingScreen
import com.mediahub.pear.history.RecentlyWatchedScreen
import com.mediahub.pear.home.CatalogGridScreen
import com.mediahub.pear.home.HomeScreen
import com.mediahub.pear.library.LibraryScreen
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.nav.NavController
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.preview.GlassGalleryScreen
import com.mediahub.pear.preview.ThemePreviewScreen
import com.mediahub.pear.profile.ProfileDetailScreen
import com.mediahub.pear.search.SearchScreen
import com.mediahub.pear.settings.AIOMetadataSettingsScreen
import com.mediahub.pear.settings.AboutScreen
import com.mediahub.pear.settings.AddonsSettingsScreen
import com.mediahub.pear.settings.ContentSettingsScreen
import com.mediahub.pear.settings.IntegrationsScreen
import com.mediahub.pear.settings.MediaServersScreen
import com.mediahub.pear.settings.PearPairScreen
import com.mediahub.pear.settings.PlayerControlsSettingsScreen
import com.mediahub.pear.settings.SettingsScreen
import com.mediahub.pear.settings.SubtitleSettingsScreen
import com.mediahub.pear.simkl.SimklLoginScreen
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

private val PageMaxWidth = 900.dp

/**
 * One route on the shell's pages. Under a glass sidebar the Home hero and a title's poster may draw behind the sidebar
 * (`bleed`): those pages start at the window's left edge, and are told how far the sidebar covers them through
 * [LocalSidebarInset]. Every other page starts beside the sidebar, as it always did.
 */
@Composable
internal fun RouteContent(route: Route, nav: NavController<Route>) {
    val side = LocalSidebarWidth.current
    val bleed = LocalSidebarGlass.current && (route is Route.Detail || (route is Route.Root && route.tab == AppTab.Home))
    Box(Modifier.fillMaxSize().padding(start = if (bleed) 0.dp else side)) {
        CompositionLocalProvider(LocalSidebarInset provides (if (bleed) side else 0.dp)) {
            RouteBody(route, nav)
        }
    }
}

@Composable
private fun RouteBody(route: Route, nav: NavController<Route>) {
    PearLog.context("route", route::class.simpleName)
    val env = LocalAppEnv.current
    when (route) {
        is Route.Root -> when (route.tab) {
            AppTab.Home -> HomeScreen(
                onSelectTitle = { item: MetaPreview, s: Int?, ep: Int? -> nav.push(Route.Detail(item, s, ep)) },
                onSeeAll = { row: CatalogRow -> nav.push(Route.Catalogue(row)) },
            )
            AppTab.Explore -> ExploreScreen(
                onSelectTitle = { item -> nav.push(Route.Detail(item)) },
            )
            AppTab.Search -> SearchScreen(
                onSelectTitle = { item -> nav.push(Route.Detail(item)) },
            )
            AppTab.Library -> LibraryScreen(
                onSelectTitle = { item -> nav.push(Route.Detail(item)) },
                onOpenDownloads = { nav.push(Route.Downloads) },
            )
            AppTab.Settings -> SettingsScreen(
                onNavigateIntegrations = { nav.push(Route.Integrations) },
                onNavigateAddons = { nav.push(Route.AddonsSettings) },
                onNavigateContent = { nav.push(Route.ContentSettings) },
                onNavigateSubtitles = { nav.push(Route.SubtitleSettings) },
                onNavigatePlayerControls = { nav.push(Route.PlayerControlsSettings) },
                onNavigateAbout = { nav.push(Route.About) },
                onNavigateDeveloperCheck = { target ->
                    when (target) {
                        "ThemePreview" -> nav.push(Route.ThemePreview)
                        "GlassGallery" -> nav.push(Route.GlassGallery)
                        "StorageCheck" -> nav.push(Route.StorageCheck)
                        "MetadataCheck" -> nav.push(Route.MetadataCheck)
                        "AddonsCheck" -> nav.push(Route.AddonsCheck)
                        "HistoryCheck" -> nav.push(Route.HistoryCheck)
                        "BackupCheck" -> nav.push(Route.BackupCheck)
                        "PlayerCheck" -> nav.push(Route.PlayerCheck)
                        "P2PCheck" -> nav.push(Route.P2PCheck)
                    }
                },
            )
        }
        is Route.Catalogue -> when (route.id) {
            // The sidebar's history pages have their own screens. Any other catalogue is the grid it always was.
            HistoryShortcuts.CONTINUE_WATCHING_ID -> ContinueWatchingScreen(
                onBack = { if (!nav.pop()) env.shell.back() },
                onSelectTitle = { item, season, episode -> nav.push(Route.Detail(item, season, episode)) },
            )
            HistoryShortcuts.RECENTLY_WATCHED_ID -> RecentlyWatchedScreen(
                onBack = { if (!nav.pop()) env.shell.back() },
                onSelectTitle = { item -> nav.push(Route.Detail(item)) },
            )
            else -> CatalogGridScreen(
                row = route.row,
                onBack = { if (!nav.pop()) env.shell.back() },
                onSelectTitle = { item -> nav.push(Route.Detail(item)) },
            )
        }
        is Route.Demo -> DemoPage(route, nav)
        is Route.Detail -> DetailScreen(
            item = route.item,
            startSeason = route.startSeason,
            startEpisode = route.startEpisode,
            onBack = { nav.pop() },
            onSelectTitle = { item -> nav.push(Route.Detail(item)) },
            onPlay = { request ->
                PearTrace.event("UI") { "player route pushed" }
                nav.push(Route.Player(request.forPartyRoom(env.data.party)), animate = false)
            },
        )
        is Route.Player -> PlayerScreen(request = route.request, onBack = { nav.pop() })
        Route.ProfileDetail -> ProfileDetailScreen(
            onBack = { nav.pop() },
            onNavigateIntegrations = { nav.push(Route.Integrations) },
            onNavigateDownloads = { nav.push(Route.Downloads) },
        )
        Route.ContentSettings -> ContentSettingsScreen(
            onBack = { nav.pop() },
            onNavigateAIOMetadata = { nav.push(Route.AIOMetadataSettings) },
        )
        Route.Downloads -> DownloadsScreen(
            onBack = { nav.pop() },
        )
        Route.Integrations -> IntegrationsScreen(
            onBack = { nav.pop() },
            onNavigateMediaServers = { nav.push(Route.MediaServers) },
            onNavigatePearPair = { nav.push(Route.PearPair) },
        )
        Route.MediaServers -> MediaServersScreen(
            onBack = { nav.pop() },
        )
        Route.PearPair -> PearPairScreen(
            onBack = { nav.pop() },
        )
        Route.AddonsSettings -> AddonsSettingsScreen(
            onBack = { nav.pop() },
        )
        Route.AIOMetadataSettings -> AIOMetadataSettingsScreen(
            onBack = { nav.pop() },
        )
        Route.SubtitleSettings -> SubtitleSettingsScreen(
            onBack = { nav.pop() },
        )
        Route.PlayerControlsSettings -> PlayerControlsSettingsScreen(
            onBack = { nav.pop() },
        )
        Route.About -> AboutScreen(
            onBack = { nav.pop() },
        )
        Route.SimklLogin -> SimklLoginScreen(
            onBack = { nav.pop() },
        )
        Route.ThemePreview -> ThemePreviewScreen(env.theme, env.glass, onBack = { nav.pop() })
        Route.GlassGallery -> GlassGalleryScreen(env.glass, onBack = { nav.pop() })
        Route.StorageCheck -> StorageCheckScreen(env.data, onBack = { nav.pop() })
        Route.MetadataCheck -> MetadataCheckScreen(env.data, onBack = { nav.pop() })
        Route.AddonsCheck -> AddonsCheckScreen(env.data, onBack = { nav.pop() })
        Route.HistoryCheck -> HistoryCheckScreen(env.data, onBack = { nav.pop() })
        Route.BackupCheck -> BackupCheckScreen(env.data, onBack = { nav.pop() })
        Route.PlayerCheck -> PlayerCheckScreen(onBack = { nav.pop() })
        Route.P2PCheck -> P2PCheckScreen(onBack = { nav.pop() })
    }
}

@Composable
internal fun PageScaffold(title: String, onBack: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val env = LocalAppEnv.current
    val bottom = LocalBottomInset.current
    GlassScene(env.glass, Modifier.fillMaxSize(), background = {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(top = 92.dp, bottom = bottom + 32.dp),
        ) {
            Column(Modifier.widthIn(max = PageMaxWidth), verticalArrangement = Arrangement.spacedBy(16.dp), content = content)
        }
    }) {
        GlassTopBar(title, Modifier.align(Alignment.TopCenter).padding(16.dp).widthIn(max = PageMaxWidth), onBack = onBack)
    }
}

@Composable
internal fun PageCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val c = PearTheme.colors
    val shape = RoundedCornerShape(PearRadii.card)
    val click = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)
    Column(
        modifier.fillMaxWidth().clip(shape).background(c.fillSubtle).then(click).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun DemoPage(route: Route.Demo, nav: NavController<Route>) {
    val c = PearTheme.colors
    PageScaffold("Page ${route.depth}", onBack = { nav.pop() }) {
        PageCard {
            PearText("Pushed page ${route.depth}", style = PearTheme.type.title3.weight(FontWeight.Bold))
            PearText("Stack depth: ${nav.depth}. The page below keeps its scroll position.", color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Push another", false) { nav.push(Route.Demo(route.depth + 1)) }
                DevChip("Back", false) { nav.pop() }
                DevChip("Back to root", false) { nav.popToRoot() }
            }
        }
        repeat(6) { i -> PageCard { PearText("Card ${i + 1}", color = c.textSecondary) } }
    }
}