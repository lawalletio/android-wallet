package io.lawallet.wallet.ui.theme

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect

/** The isotype's three lights — the "prism" behind every glass surface. */
object Prism {
    val Mint = Color(0xFF55B68C)
    val Sun = Color(0xFFFDC800)
    val Coral = Color(0xFFE95052)
}

enum class GlassLevel { Thin, Regular, Strong }

/** Translucent panels over the prism light; the edge is brighter on top, like a lit bevel. */
object Glass {
    fun fill(level: GlassLevel) = Color.White.copy(alpha = when (level) { GlassLevel.Thin -> 0.04f; GlassLevel.Regular -> 0.065f; GlassLevel.Strong -> 0.11f })
    fun edge(level: GlassLevel) = Brush.verticalGradient(
        when (level) {
            GlassLevel.Thin -> listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.03f))
            GlassLevel.Regular -> listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))
            GlassLevel.Strong -> listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.07f))
        },
    )
    val Sheen = Brush.verticalGradient(0f to Color.White.copy(alpha = 0.05f), 0.45f to Color.Transparent)
    /** Opaque-ish surface for dialogs/menus, which sit in their own window (no backdrop to frost). */
    val Dialog = Color(0xFF16181B)
    val Shape = RoundedCornerShape(20.dp)
}

/** In-flow glass panel: translucent fill + sheen + gradient edge. No blur (the prism behind is already soft). */
fun Modifier.glass(shape: Shape = Glass.Shape, level: GlassLevel = GlassLevel.Regular, tint: Color? = null): Modifier =
    clip(shape)
        .background(Glass.fill(level), shape)
        .then(if (tint != null) Modifier.background(tint, shape) else Modifier)
        .drawBehind { drawRect(Glass.Sheen) }
        .border(1.dp, Glass.edge(level), shape)

/** Haze source for overlays that float over scrolling content (tab bar, notices). */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/** Real backdrop-blur glass for floating overlays; falls back to a dense tint where blur is unavailable. */
fun Modifier.frosted(state: HazeState?, shape: Shape): Modifier =
    clip(shape)
        .then(
            if (state != null) Modifier.hazeEffect(
                state,
                HazeStyle(
                    backgroundColor = Lw.Background,
                    tint = HazeTint(Color(0xFF0E1012).copy(alpha = 0.62f)),
                    blurRadius = 26.dp,
                    noiseFactor = 0.07f,
                    fallbackTint = HazeTint(Color(0xF2141618)),
                ),
            ) else Modifier.background(Color(0xF2141618)),
        )
        .drawBehind { drawRect(Glass.Sheen) }
        .border(1.dp, Glass.edge(GlassLevel.Regular), shape)

/**
 * The app's backdrop: ink with the isotype's lights refracted behind the glass. The brand
 * theme colour leads (top-left) so communities with another theme keep their identity.
 */
@Composable
fun PrismBackground(brand: Brand, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxSize()) {
        drawRect(Lw.Background)
        fun light(color: Color, alpha: Float, cx: Float, cy: Float, r: Float) =
            drawCircle(Brush.radialGradient(0f to color.copy(alpha = alpha), 1f to Color.Transparent, center = Offset(cx, cy), radius = r), r, Offset(cx, cy))
        val w = size.width
        val h = size.height
        light(brand.t400, 0.30f, w * 0.10f, h * 0.06f, w * 0.95f)
        light(Prism.Sun, 0.10f, w * 0.98f, h * 0.10f, w * 0.62f)
        light(Prism.Coral, 0.11f, w * 1.02f, h * 0.58f, w * 0.72f)
        light(Prism.Mint, 0.12f, w * 0.02f, h * 0.98f, w * 0.85f)
    }
}
