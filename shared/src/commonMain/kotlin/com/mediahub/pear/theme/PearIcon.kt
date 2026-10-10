package com.mediahub.pear.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.theme.icons.FALLBACK_ICON
import com.mediahub.pear.theme.icons.mdiPaths
import com.mediahub.pear.theme.icons.sfToMdi

/** Looks up icons by the SF Symbol names the Swift app uses. */
object PearIcons {
    /** Every SF Symbol name that has an icon. */
    val symbols: List<String> get() = sfToMdi.keys.sorted()

    /** True if [symbol] has its own icon (otherwise [PearIcon] shows a question mark). */
    fun has(symbol: String): Boolean = symbol in sfToMdi

    /** The SVG path data (24x24 grid) for [symbol], or the fallback icon's. */
    fun pathData(symbol: String): String {
        val mdi = sfToMdi[symbol] ?: FALLBACK_ICON
        return mdiPaths[mdi] ?: mdiPaths.getValue(FALLBACK_ICON)
    }

    private val parsed = HashMap<String, Path>()

    internal fun path(symbol: String): Path = parsed.getOrPut(symbol) { PathParser().parsePathString(pathData(symbol)).toPath() }
}

/** An icon by SF Symbol name, drawn at [size] and coloured with [tint]. */
@Composable
fun PearIcon(
    symbol: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = PearTheme.colors.textPrimary,
    contentDescription: String? = null,
) {
    val path = remember(symbol) { PearIcons.path(symbol) }
    val semantic = if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription }
    Canvas(modifier.size(size).then(semantic)) {
        val factor = this.size.width / 24f
        scale(factor, Offset.Zero) { drawPath(path, tint) }
    }
}
