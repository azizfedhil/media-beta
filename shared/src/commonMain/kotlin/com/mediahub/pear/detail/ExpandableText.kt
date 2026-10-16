package com.mediahub.pear.detail

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediahub.pear.theme.PearText
import com.mediahub.pear.theme.PearTheme
import com.mediahub.pear.theme.weight

@Composable
fun ExpandableText(
    text: String,
    modifier: Modifier = Modifier,
    collapsedLines: Int = 3,
) {
    var expanded by remember { mutableStateOf(false) }

    Column(modifier.animateContentSize().clickable { expanded = !expanded }) {
        PearText(
            text = text,
            style = PearTheme.type.body,
            color = PearTheme.colors.textSecondary,
            maxLines = if (expanded) Int.MAX_VALUE else collapsedLines,
        )
        if (text.length > 140) {
            PearText(
                text = if (expanded) "less" else "more",
                style = PearTheme.type.caption.weight(FontWeight.SemiBold),
                color = PearTheme.colors.accent,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}