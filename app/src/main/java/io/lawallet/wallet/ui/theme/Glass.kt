package io.lawallet.wallet.ui.theme

import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.IntSize
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
import dev.chrisbanes.haze.HazeInputScale
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
    // Precomputed: glass() runs on every panel in every recomposition.
    private val fills = arrayOf(Color.White.copy(alpha = 0.04f), Color.White.copy(alpha = 0.065f), Color.White.copy(alpha = 0.11f))
    private val edges = arrayOf(
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.10f), Color.White.copy(alpha = 0.03f))),
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))),
        Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.07f))),
    )
    fun fill(level: GlassLevel) = fills[level.ordinal]
    fun edge(level: GlassLevel) = edges[level.ordinal]
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
        .drawBehind(DrawSheen)
        .border(1.dp, Glass.edge(level), shape)

private val DrawSheen: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = { drawRect(Glass.Sheen) }

private val FrostedStyle = HazeStyle(
    backgroundColor = Lw.Background,
    tint = HazeTint(Color(0xFF0E1012).copy(alpha = 0.62f)),
    blurRadius = 26.dp,
    noiseFactor = 0.07f,
    fallbackTint = HazeTint(Color(0xF2141618)),
)

/** Haze source for overlays that float over scrolling content (tab bar, notices). */
val LocalHaze = staticCompositionLocalOf<HazeState?> { null }

/** Real backdrop-blur glass for floating overlays; falls back to a dense tint where blur is unavailable. */
fun Modifier.frosted(state: HazeState?, shape: Shape): Modifier =
    clip(shape)
        .then(
            if (state != null) Modifier.hazeEffect(state, FrostedStyle) { inputScale = HazeInputScale.Fixed(0.4f) } else Modifier.background(Color(0xF2141618)),
        )
        .drawBehind(DrawSheen)
        .border(1.dp, Glass.edge(GlassLevel.Regular), shape)

/**
 * The app's backdrop: ink with the isotype's lights refracted behind the glass. The brand
 * theme colour leads (top-left) so communities with another theme keep their identity.
 *
 * Rendered once per size into a quarter-resolution bitmap and blitted (bilinear) every frame:
 * four full-screen radial gradients are smooth enough that the downscale is invisible, and a
 * blit is far cheaper than re-shading every pixel on each frame (fill-rate bound on low-end GPUs).
 */
@Composable
fun PrismBackground(brand: Brand, modifier: Modifier = Modifier) {
    val t400 = brand.t400
    Spacer(
        modifier.fillMaxSize().drawWithCache {
            val scale = 0.25f
            val w = (size.width * scale).toInt().coerceAtLeast(1)
            val h = (size.height * scale).toInt().coerceAtLeast(1)
            val bitmap = ImageBitmap(w, h)
            CanvasDrawScope().draw(this, layoutDirection, Canvas(bitmap), Size(w.toFloat(), h.toFloat())) {
                drawRect(Lw.Background)
                fun light(color: Color, alpha: Float, cx: Float, cy: Float, r: Float) =
                    drawCircle(Brush.radialGradient(0f to color.copy(alpha = alpha), 1f to Color.Transparent, center = Offset(cx, cy), radius = r), r, Offset(cx, cy))
                val bw = this.size.width
                val bh = this.size.height
                light(t400, 0.30f, bw * 0.10f, bh * 0.06f, bw * 0.95f)
                light(Prism.Sun, 0.10f, bw * 0.98f, bh * 0.10f, bw * 0.62f)
                light(Prism.Coral, 0.11f, bw * 1.02f, bh * 0.58f, bw * 0.72f)
                light(Prism.Mint, 0.12f, bw * 0.02f, bh * 0.98f, bw * 0.85f)
            }
            onDrawBehind {
                drawImage(bitmap, dstSize = IntSize(size.width.toInt(), size.height.toInt()), filterQuality = FilterQuality.Low)
            }
        },
    )
}
