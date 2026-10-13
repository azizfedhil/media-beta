package com.mediahub.pear.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mediahub.pear.theme.icons.FALLBACK_ICON
import com.mediahub.pear.theme.icons.LucideShape
import com.mediahub.pear.theme.icons.lucideIcons
import com.mediahub.pear.theme.icons.sfToLucide

/** Lucide draws every icon with this stroke width on its 24-unit grid (its SVGs say `stroke-width="2"`). */
private const val LUCIDE_STROKE = 2f

/** One drawn part of an icon: its outline, and whether it is filled (solid dots) or stroked. */
internal class IconPart(val path: Path, val filled: Boolean)

/** Looks up icons by the SF Symbol names the Swift app uses. The icons are Lucide's (see tools/gen_icons.py). */
object PearIcons {
    /** Every SF Symbol name that has an icon. */
    val symbols: List<String> get() = sfToLucide.keys.sorted()

    /** True if [symbol] has its own icon (otherwise [PearIcon] shows a question mark). */
    fun has(symbol: String): Boolean = symbol in sfToLucide

    /** The drawing data of [symbol]'s icon, or the fallback's. */
    internal fun shapes(symbol: String): List<LucideShape> {
        val lucide = sfToLucide[symbol] ?: FALLBACK_ICON
        return lucideIcons[lucide] ?: lucideIcons.getValue(FALLBACK_ICON)
    }

    private val parsed = HashMap<String, List<IconPart>>()

    internal fun parts(symbol: String): List<IconPart> = parsed.getOrPut(symbol) {
        shapes(symbol).map { IconPart(PathParser().parsePathString(it.d).toPath(), it.filled) }
    }
}

/** An icon by SF Symbol name, drawn at [size] and coloured with [tint]. Outline parts are stroked, dots are filled. */
@Composable
fun PearIcon(
    symbol: String,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    tint: Color = PearTheme.colors.textPrimary,
    contentDescription: String? = null,
) {
    val parts = remember(symbol) { PearIcons.parts(symbol) }
    val semantic = if (contentDescription == null) Modifier else Modifier.semantics { this.contentDescription = contentDescription }
    Canvas(modifier.size(size).then(semantic)) {
        val factor = this.size.width / 24f
        val stroke = Stroke(width = LUCIDE_STROKE, cap = StrokeCap.Round, join = StrokeJoin.Round)
        // The stroke is in the icon's own units, so the scale also scales its width.
        scale(factor, Offset.Zero) {
            for (part in parts) {
                if (part.filled) drawPath(part.path, tint) else drawPath(part.path, tint, style = stroke)
            }
        }
    }
}
