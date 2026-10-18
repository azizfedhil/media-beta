package com.mediahub.pear.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.glass.GlassTopBar
import com.mediahub.pear.shell.LocalBottomInset
import com.mediahub.pear.shell.LocalWideLayout

/**
 * The frame of a settings page: a scrolling column of cards under a floating top bar.
 *
 * The phone layout is the one every settings page always had. The wide layout centres the column and limits it to
 * [maxWidth], as the other wide pages do, and the cards may sit side by side through [SettingsColumns]. [compactSpacing]
 * is the gap between cards on the phone layout; the wide layout always uses the wide gap.
 */
@Composable
internal fun SettingsPage(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = 1000.dp,
    compactSpacing: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val bottomInset = LocalBottomInset.current
    val wide = LocalWideLayout.current
    Box(modifier.fillMaxSize()) {
        if (wide) {
            Column(
                Modifier.align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .widthIn(max = maxWidth)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                    .padding(horizontal = 32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                content = content,
            )
            GlassTopBar(title, Modifier.align(Alignment.TopCenter).padding(16.dp).widthIn(max = maxWidth), onBack = onBack)
        } else {
            Column(
                Modifier.fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(top = 92.dp, bottom = bottomInset + 32.dp)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(compactSpacing),
                content = content,
            )
            GlassTopBar(title = title, modifier = Modifier.align(Alignment.TopCenter).padding(16.dp), onBack = onBack)
        }
    }
}

/**
 * Two groups of cards side by side on the wide layout: [left] and [right], each a column of its own, top aligned, with
 * [leftWeight] and [rightWeight] sharing the width.
 *
 * On the phone layout the cards simply follow one another in the page's own column: [left] first, then [right]. So a
 * screen whose phone order differs from its wide grouping must not use this on the phone (it checks the layout itself).
 */
@Composable
internal fun ColumnScope.SettingsColumns(
    left: @Composable ColumnScope.() -> Unit,
    right: @Composable ColumnScope.() -> Unit,
    leftWeight: Float = 1f,
    rightWeight: Float = 1f,
) {
    if (LocalWideLayout.current) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(leftWeight), verticalArrangement = Arrangement.spacedBy(20.dp), content = left)
            Column(Modifier.weight(rightWeight), verticalArrangement = Arrangement.spacedBy(20.dp), content = right)
        }
    } else {
        left()
        right()
    }
}
