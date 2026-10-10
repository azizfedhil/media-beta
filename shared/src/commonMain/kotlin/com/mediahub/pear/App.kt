package com.mediahub.pear

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

/** Root of the shared UI. Empty shell for Batch 1.1; PearTheme, glass and navigation arrive in 1.2 to 1.4. */
@Composable
fun App() {
    Box(Modifier.fillMaxSize().background(Color(0xFF0B0B0D)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            BasicText("Pear.", style = TextStyle(color = Color.White, fontSize = 40.sp))
            BasicText(platformName, style = TextStyle(color = Color(0x99FFFFFF), fontSize = 14.sp))
        }
    }
}
