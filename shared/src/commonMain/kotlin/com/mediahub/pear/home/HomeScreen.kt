package com.mediahub.pear.home

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.mediahub.pear.data.BuiltinRows
import com.mediahub.pear.data.isRowHidden
import com.mediahub.pear.model.CatalogRow
import com.mediahub.pear.model.MetaPreview
import com.mediahub.pear.shell.LocalAppEnv
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.shell.LocalWideLayout
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import kotlinx.coroutines.launch

/**
 * Main Home feed supporting both compact phone layout and wide desktop/iPad split layout.
 */
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
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        model.refresh(force = false)
    }

    if (isWide) {
        // Desktop / iPad split layout
        Row(modifier.fillMaxSize()) {
            if (model.hero.isNotEmpty()) {
                WideHeroPanel(
                    items = model.hero,
                    onSelectTitle = { onSelectTitle(it, null, null) },
                )
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                Column(
                    Modifier.fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(top = 24.dp, bottom = bottomInset + 40.dp),
                    verticalArrangement = Arrangement.spacedBy(28.dp),
                ) {
                    FeedRows(model, onSelectTitle, onSeeAll)
                }
            }
        }
    } else {
        // Phone / compact layout with top hero and ambient gradient
        Box(modifier.fillMaxSize()) {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(bottom = bottomInset + 32.dp),
                verticalArrangement = Arrangement.spacedBy(26.dp),
            ) {
                if (model.hero.isNotEmpty()) {
                    HeroCarousel(
                        items = model.hero,
                        onSelectTitle = { onSelectTitle(it, null, null) },
                    )
                } else {
                    Spacer(Modifier.height(40.dp))
                }
                FeedRows(model, onSelectTitle, onSeeAll)
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

    // Continue Watching Row (no play buttons)
    if (env.data.history.continueEntries.isNotEmpty() || model.upNext.isNotEmpty()) {
        ContinueRow(
            entries = env.data.history.continueEntries,
            upNext = model.upNext,
            onSelectTitle = onSelectTitle,
        )
    }

    // Theme Row 0
    if (model.themes.isNotEmpty()) {
        ThemeCarousel(model.themes[0], { onSelectTitle(it, null, null) })
    }

    // Suggested Rows (Trending Movies, Series, Because you watched)
    val visibleSuggested = model.suggested.filter { !rules.isRowHidden(it.prefKey) }
    visibleSuggested.forEach { row ->
        CatalogRowView(
            row = row,
            onSelectTitle = { onSelectTitle(it, null, null) },
            onSeeAll = { onSeeAll(row) },
        )
    }

    // Theme Row 1
    if (model.themes.size > 1) {
        ThemeCarousel(model.themes[1], { onSelectTitle(it, null, null) })
    }

    // Addon Rows
    val visibleAddonRows = model.rows.filter { !rules.isRowHidden(it.prefKey) }
    visibleAddonRows.forEachIndexed { index, row ->
        CatalogRowView(
            row = row,
            onSelectTitle = { onSelectTitle(it, null, null) },
            onSeeAll = { onSeeAll(row) },
        )
        // Interleave remaining theme rows after every 2nd addon row
        val themeIdx = 2 + index / 2
        if (index % 2 == 1 && themeIdx < model.themes.size) {
            ThemeCarousel(model.themes[themeIdx], { onSelectTitle(it, null, null) })
        }
    }
}