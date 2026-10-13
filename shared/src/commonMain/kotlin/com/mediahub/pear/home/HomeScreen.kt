package com.mediahub.pear.home

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.BuiltinRows
import com.mediahub.pear.data.isRowHidden
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.shell.LocalFeedCollapse
import com.mediahub.pear.shell.LocalWideLayout
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.fillMaxWidth

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

    LaunchedEffect(env.data.profiles.activeID) {
        model.refresh(force = false)
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
            Row(modifier.fillMaxSize().attachedAmbient()) {
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
                            .verticalScroll(scroll)
                            .padding(top = 18.dp, bottom = bottomInset + 40.dp),
                    ) {
                        Column(
                            Modifier.fillMaxWidth().padding(start = 14.dp),
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
        Box(modifier.fillMaxSize().attachedAmbient()) {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(scroll)
                    .padding(bottom = bottomInset + 32.dp),
            ) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(26.dp)) {
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

    // 1. Continue Watching
    if (env.data.history.continueEntries.isNotEmpty() || model.upNext.isNotEmpty()) {
        ContinueRow(
            entries = env.data.history.continueEntries,
            upNext = model.upNext,
            onSelectTitle = onSelectTitle,
        )
    }

    // 2. Theme Row 0
    if (model.themes.isNotEmpty()) {
        ThemeCarousel(model.themes[0], { onSelectTitle(it, null, null) })
    }

    // 3. Suggestions
    val visibleSuggested = model.suggested.filter { !rules.isRowHidden(it.prefKey) }
    visibleSuggested.forEach { row ->
        CatalogRowView(
            row = row,
            onSelectTitle = { onSelectTitle(it, null, null) },
            onSeeAll = { onSeeAll(row) },
        )
    }

    // 4. Remaining Theme rows & Addon catalogues
    val visibleAddonRows = model.rows.filter { !rules.isRowHidden(it.prefKey) }
    visibleAddonRows.forEachIndexed { index, row ->
        CatalogRowView(
            row = row,
            onSelectTitle = { onSelectTitle(it, null, null) },
            onSeeAll = { onSeeAll(row) },
        )
        val themeIdx = 1 + index / 2
        if (index % 2 == 1 && themeIdx < model.themes.size) {
            ThemeCarousel(model.themes[themeIdx], { onSelectTitle(it, null, null) })
        }
    }
}