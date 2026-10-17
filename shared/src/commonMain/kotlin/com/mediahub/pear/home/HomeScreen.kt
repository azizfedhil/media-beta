package com.mediahub.pear.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.mediahub.pear.player.ui.PlayerSpinner
import com.mediahub.pear.shell.PearCapsuleButton
import com.mediahub.pear.theme.PearIcon
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.data.BuiltinRows
import com.mediahub.pear.data.PrefKeys
import com.mediahub.pear.data.isRowHidden
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.shell.LocalFeedCollapse
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.shell.rememberPref
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    onSelectTitle: (MetaPreview, Int?, Int?) -> Unit,
    onSeeAll: (CatalogRow) -> Unit,
    modifier: Modifier = Modifier,
) {
    val env = LocalAppEnv.current
    val isWide = LocalWideLayout.current
    val model = remember(env.data) { HomeModel(env.data) }
    val bottomInset = LocalBottomInset.current
    // The MDBList key and the lists chosen in Settings decide the MDBList rows (iOS `listsTaskID`).
    val mdbKey = rememberPref(env.data.prefs, PrefKeys.MDBLIST_KEY) { it.string(PrefKeys.MDBLIST_KEY, "") }
    val mdbLists = rememberPref(env.data.prefs, PrefKeys.MDBLIST_LISTS) { it.string(PrefKeys.MDBLIST_LISTS, "") }

    // Home is kept composed while Settings is open, so it never re-enters composition to reload on its own. Anything
    // that changes which rows Home should show therefore restarts the refresh here.
    LaunchedEffect(
        env.data.profiles.activeID,
        env.data.content.rules.addedRows,
        env.data.content.rules.hiddenRows,
        env.data.addons.pickerIds,
        env.data.addons.enrichmentAddon?.id,
        mdbKey,
        mdbLists,
    ) {
        model.refresh(force = false)
    }
    // Up Next follows the history: dismissing a card or finishing an episode resolves it again.
    val finished = env.data.history.finishedEntries
    LaunchedEffect(finished) {
        model.refreshUpNext()
    }

    if (isWide) {
        val fc = env.shell.feedCollapse
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        LaunchedEffect(scroll) {
            snapshotFlow { scroll.value }.collect { fc.vertical = (it / 260f).coerceIn(0f, 1f) }
        }
        val fold by animateFloatAsState(fc.progress, tween(140))
        CompositionLocalProvider(LocalFeedCollapse provides fc) {
            // One wash behind the hero and the feed together. It used to sit only on the feed column, so the hero's
            // faded right edge met a plain black page at a hard vertical line.
            Row(modifier.fillMaxSize().attachedAmbient(AmbientTuning.WIDE_REACH_DP)) {
                if (model.hero.isNotEmpty()) {
                    WideHeroPanel(
                        items = model.hero,
                        collapse = fold,
                        onSelectTitle = { onSelectTitle(it, null, null) },
                        scroll = scroll,
                    )
                }
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        Modifier.fillMaxSize()
                            .draggable(
                                rememberDraggableState { delta -> scope.launch { scroll.scrollBy(-delta) } },
                                Orientation.Vertical,
                            )
                            .verticalScroll(scroll),
                    ) {
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(start = 14.dp, top = 18.dp, bottom = bottomInset + 40.dp),
                            verticalArrangement = Arrangement.spacedBy(28.dp),
                        ) {
                            FeedRows(model, onSelectTitle, onSeeAll)
                        }
                    }
                }
            }
        }
    } else {
        val scroll = rememberScrollState()
        Box(modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(bottom = bottomInset + 32.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().attachedAmbient(),
                    verticalArrangement = Arrangement.spacedBy(26.dp),
                ) {
                    if (model.hero.isNotEmpty()) {
                        HeroCarousel(
                            items = model.hero,
                            onSelectTitle = { onSelectTitle(it, null, null) },
                            scroll = scroll,
                        )
                    } else {
                        Spacer(Modifier.height(40.dp))
                    }
                    FeedRows(model, onSelectTitle, onSeeAll)
                }
            }
        }
    }
}

@Composable
private fun FeedRows(
    model: HomeModel,
    onSelectTitle: (MetaPreview, Int?, Int?) -> Unit,
    onSeeAll: (CatalogRow) -> Unit,
) {
    val env = LocalAppEnv.current
    val rules = env.data.content.rules
    println("PEAR-CW feed continue=${env.data.history.continueEntries.size} upNext=${model.upNext.size}") // TEMP-CW

    // 0. Which add-ons are still loading, and which failed (with a button to try them again)
    AddonStatus(model)

    // The rows the user has not hidden (iOS `visible`). The add-on count is taken after hiding, as iOS does.
    val visibleSuggested = model.suggested.filter { !rules.isRowHidden(it.prefKey) }
    val visibleAddonRows = model.rows.filter { !rules.isRowHidden(it.prefKey) }
    // iOS `themesHidden`: with the built-in themes row hidden, no theme appears anywhere in the feed.
    val themeCount = if (rules.isRowHidden(BuiltinRows.THEMES)) 0 else model.themes.size
    val showContinue = env.data.history.continueEntries.isNotEmpty() || model.visibleUpNext.isNotEmpty()

    // The blocks of the feed, in iOS order (see feedOrder).
    feedOrder(showContinue, themeCount, visibleAddonRows.size).forEach { slot ->
        when (slot) {
            FeedSlot.Continue -> ContinueRow(
                entries = env.data.history.continueEntries,
                upNext = model.visibleUpNext,
                onSelectTitle = onSelectTitle,
            )
            is FeedSlot.Theme -> ThemeCarousel(model.themes[slot.index], { onSelectTitle(it, null, null) })
            FeedSlot.Suggested -> visibleSuggested.forEach { row ->
                CatalogRowView(
                    row = row,
                    onSelectTitle = { onSelectTitle(it, null, null) },
                    onSeeAll = { onSeeAll(row) },
                )
            }
            FeedSlot.Lists -> model.lists.forEach { row ->
                CatalogRowView(
                    row = row,
                    onSelectTitle = { onSelectTitle(it, null, null) },
                    onSeeAll = { onSeeAll(row) },
                )
            }
            is FeedSlot.Addon -> {
                val row = visibleAddonRows[slot.index]
                CatalogRowView(
                    row = row,
                    onSelectTitle = { onSelectTitle(it, null, null) },
                    onSeeAll = { onSeeAll(row) },
                )
            }
        }
    }
}

/**
 * What the add-ons are doing: a line while any are still loading, and one line per add-on that failed to provide data
 * with a Reload button. Shows nothing when every add-on has answered.
 */
@Composable
private fun AddonStatus(model: HomeModel) {
    val loading = model.loadingAddons
    val failed = model.failedAddons
    if (loading.isEmpty() && failed.isEmpty()) return
    val scope = rememberCoroutineScope()
    val c = PearTheme.colors

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loading.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlayerSpinner(diameter = 14.dp)
                PearText(loadingText(loading.map { it.manifest.name }), style = PearTheme.type.footnote, color = c.textSecondary)
            }
        }
        failed.forEach { addon ->
            Row(
                Modifier.fillMaxWidth().background(c.fill, RoundedCornerShape(14.dp)).padding(start = 14.dp, top = 6.dp, bottom = 6.dp, end = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PearIcon("exclamationmark.triangle.fill", size = 16.dp, tint = c.orange)
                PearText(
                    "${addon.manifest.name} failed to provide data. Want to reload it?",
                    style = PearTheme.type.footnote,
                    modifier = Modifier.weight(1f),
                )
                PearCapsuleButton(
                    label = "Reload",
                    symbol = "arrow.clockwise",
                    onClick = { scope.launch { model.retryFailed(addon) } },
                )
            }
        }
    }
}

internal fun loadingText(names: List<String>): String = when (names.size) {
    0 -> ""
    1 -> "Loading ${names[0]}…"
    2 -> "Loading ${names[0]} and ${names[1]}…"
    else -> "Loading ${names[0]} and ${names.size - 1} more add-ons…"
}
