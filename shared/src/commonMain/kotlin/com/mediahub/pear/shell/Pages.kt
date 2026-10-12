package com.mediahub.pear.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.detail.DetailScreen
import com.mediahub.pear.explore.ExploreScreen
import com.mediahub.pear.glass.GlassScene
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.home.CatalogGridScreen
import com.mediahub.pear.home.HomeScreen
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.nav.NavController
import com.mediahub.pear.preview.DevChip
import com.mediahub.pear.preview.GlassGalleryScreen
import com.mediahub.pear.preview.ThemePreviewScreen
import com.mediahub.pear.search.SearchScreen
import com.mediahub.pear.theme.PearRadii
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

private val PageMaxWidth = 900.dp

@Composable
internal fun RouteContent(route: Route, nav: NavController<Route>) {
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
            AppTab.Settings -> SettingsPlaceholder(nav)
        }
        is Route.Catalogue -> CatalogGridScreen(
            row = route.row,
            onBack = { nav.pop() },
            onSelectTitle = { item -> nav.push(Route.Detail(item)) },
        )
        is Route.Detail -> DetailScreen(
            item = route.item,
            startSeason = route.startSeason,
            startEpisode = route.startEpisode,
            onBack = { nav.pop() },
            onSelectTitle = { item -> nav.push(Route.Detail(item)) },
            onPlay = { _ -> },
        )
        Route.ProfileDetail -> ProfileDetailScreen(
            onBack = { nav.pop() },
            onNavigateIntegrations = { /* Batch 3.5 integrations */ },
            onNavigateDownloads = { nav.push(Route.Downloads) },
        )
        Route.ContentSettings -> ContentSettingsScreen(
            onBack = { nav.pop() },
            onNavigateAIOMetadata = { /* Batch 3.5 AIOMetadata */ },
        )
        Route.Downloads -> DownloadsScreen(
            onBack = { nav.pop() },
        )
        Route.ThemePreview -> ThemePreviewScreen(env.theme, env.glass, onBack = { nav.pop() })
        Route.GlassGallery -> GlassGalleryScreen(env.glass, onBack = { nav.pop() })
        Route.StorageCheck -> StorageCheckScreen(env.data, onBack = { nav.pop() })
        Route.MetadataCheck -> MetadataCheckScreen(env.data, onBack = { nav.pop() })
        Route.AddonsCheck -> AddonsCheckScreen(env.data, onBack = { nav.pop() })
        Route.HistoryCheck -> HistoryCheckScreen(env.data, onBack = { nav.pop() })
        Route.BackupCheck -> BackupCheckScreen(env.data, onBack = { nav.pop() })
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
        modifier.fillMaxWidth().clip(shape).background(c.fillSubtle).border(1.dp, c.outline, shape).then(click).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
private fun layoutSummary(): String = if (LocalWideLayout.current) "Wide layout (sidebar)" else "Phone layout (tab bar)"

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsPlaceholder(nav: NavController<Route>) {
    val env = LocalAppEnv.current
    val c = PearTheme.colors
    val windowControl = LocalWindowControl.current
    PageScaffold("Settings", onBack = null) {
        PageCard {
            PearText("Settings", style = PearTheme.type.title3.weight(FontWeight.Bold))
            PearText("Placeholder. SettingsView.swift is ported in Batch 3.5.", color = c.textSecondary)
        }
        if (env.devTools) {
            PageCard {
                PearText("Developer: component gallery", style = PearTheme.type.headline)
                PearText("Screens for comparing against the iPad and iPhone apps.", style = PearTheme.type.footnote, color = c.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DevChip("Theme preview", false) { nav.push(Route.ThemePreview) }
                    DevChip("Glass gallery", false) { nav.push(Route.GlassGallery) }
                    DevChip("Storage check", false) { nav.push(Route.StorageCheck) }
                    DevChip("Metadata check", false) { nav.push(Route.MetadataCheck) }
                    DevChip("Add-ons check", false) { nav.push(Route.AddonsCheck) }
                    DevChip("History & Simkl check", false) { nav.push(Route.HistoryCheck) }
                    DevChip("Backup check", false) { nav.push(Route.BackupCheck) }
                    DevChip("Push a page", false) { nav.push(Route.Demo(1)) }
                }
            }
            PageCard {
                PearText("Developer: layout", style = PearTheme.type.headline)
                PearText(layoutSummary(), style = PearTheme.type.footnote, color = c.textSecondary)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LayoutOverride.entries.forEach { o ->
                        DevChip(o.label, env.shell.layoutOverride == o) { env.shell.layoutOverride = o }
                    }
                    if (windowControl != null) {
                        val saved = env.shell.savedWindowSize
                        DevChip(if (saved != null) "Phone-sized window: on" else "Phone-sized window", saved != null) {
                            if (saved == null) {
                                env.shell.savedWindowSize = windowControl.size
                                windowControl.resize(390.dp, 844.dp)
                            } else {
                                windowControl.resize(saved.width, saved.height)
                                env.shell.savedWindowSize = null
                            }
                        }
                    }
                }
                PearText("Resize the window across 640 dp and the layout switches by itself (Auto).", style = PearTheme.type.footnote, color = c.textSecondary)
            }
        }
    }
}

@Composable
private fun Placeholder(title: String, swiftFile: String, batch: String, nav: NavController<Route>, onBack: (() -> Unit)? = null) {
    val c = PearTheme.colors
    PageScaffold(title, onBack = onBack) {
        PageCard {
            PearText(title, style = PearTheme.type.title3.weight(FontWeight.Bold))
            PearText("Placeholder. $swiftFile is ported in $batch.", color = c.textSecondary)
            PearText(layoutSummary(), style = PearTheme.type.footnote, color = c.textSecondary)
        }
        PageCard(onClick = { nav.push(Route.Demo(1)) }) {
            PearText("Click to push a page", color = c.textSecondary)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DemoPage(route: Route.Demo, nav: NavController<Route>) {
    val c = PearTheme.colors
    PageScaffold("Page ${route.depth}", onBack = { nav.pop() }) {
        PageCard {
            PearText("Pushed page ${route.depth}", style = PearTheme.type.title3.weight(FontWeight.Bold))
            PearText("Stack depth: ${nav.depth}. The page below keeps its scroll position.", color = c.textSecondary)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DevChip("Push another", false) { nav.push(Route.Demo(route.depth + 1)) }
                DevChip("Back", false) { nav.pop() }
                DevChip("Back to root", false) { nav.popToRoot() }
            }
        }
        repeat(6) { i -> PageCard { PearText("Card ${i + 1}", color = c.textSecondary) } }
    }
}