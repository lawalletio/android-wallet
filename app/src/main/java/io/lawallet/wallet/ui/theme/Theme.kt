package io.lawallet.wallet.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.lawallet.wallet.R

/** apps/web/app/globals.css — the wallet is dark-only. */
object Lw {
    val Background = Color(0xFF0A0A0A)
    val Foreground = Color(0xFFF5F5F5)
    val Card = Color(0xFF171717)
    val Secondary = Color(0xFF262626)
    val Border = Color(0xFF262626)
    val MutedFg = Color(0xFFA3A3A3)
    val Ring = Color(0xFF737373)
    val Destructive = Color(0xFF9A1919)
    val DestructiveFg = Color(0xFFFEE2E2)
    val Red = Color(0xFFEF4444)
    val Green = Color(0xFF22C55E)
    val Orange = Color(0xFFF97316)
    val NwcPurple = Color(0xFF937AFF)
    val Gold = Color(0xFFF5A623)
    val Neutral400 = Color(0xFFA3A3A3)
    val Neutral100 = Color(0xFFF5F5F5)
    val Neutral700 = Color(0xFF404040)
}

/** Brand theme from `GET /api/settings` (theme-presets.ts): brand_theme hex + brand_rounding. */
@Immutable
data class Brand(val t200: Color, val t300: Color, val t400: Color, val radius: Dp, val name: String?, val logotype: String?, val isotype: String?)

private data class Preset(val hex: String, val t200: Long, val t300: Long, val t400: Long)

private val PRESETS = listOf(
    Preset("#f5f5f5", 0xFF404040, 0xFF404040, 0xFFA3A3A3),
    Preset("#ec4899", 0xFF9D174D, 0xFFBE185D, 0xFFDB2777),
    Preset("#f97316", 0xFF9A3412, 0xFFC2410C, 0xFFEA580C),
    Preset("#eab308", 0xFF854D0E, 0xFFA16207, 0xFFCA8A04),
    Preset("#22c55e", 0xFF166534, 0xFF15803D, 0xFF16A34A),
    Preset("#3b82f6", 0xFF1E40AF, 0xFF1D4ED8, 0xFF2563EB),
    Preset("#8b5cf6", 0xFF5B21B6, 0xFF6D28D9, 0xFF7C3AED),
    Preset("#a855f7", 0xFF6B21A8, 0xFF7E22CE, 0xFF9333EA),
)

fun brandOf(theme: String?, rounding: String?, name: String? = null, logotype: String? = null, isotype: String? = null): Brand {
    val p = PRESETS.firstOrNull { it.hex.equals(theme, ignoreCase = true) } ?: PRESETS[4] // Green default
    val radius = when (rounding) { "None" -> 0.dp; "Small" -> 6.dp; "Full" -> 24.dp; else -> 12.dp }
    return Brand(Color(p.t200), Color(p.t300), Color(p.t400), radius, name, logotype?.takeIf { it.isNotBlank() }, isotype?.takeIf { it.isNotBlank() })
}

val LocalBrand = staticCompositionLocalOf { brandOf(null, null) }

@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
private fun inter(w: Int) = Font(R.font.inter, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))

val Inter = FontFamily(inter(400), inter(500), inter(600), inter(700))

@Composable
fun LaWalletTheme(brand: Brand = brandOf(null, null), content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = brand.t400,
        onPrimary = Lw.Foreground,
        background = Lw.Background,
        onBackground = Lw.Foreground,
        surface = Lw.Background,
        onSurface = Lw.Foreground,
        surfaceVariant = Lw.Card,
        onSurfaceVariant = Lw.MutedFg,
        surfaceContainer = Lw.Card,
        surfaceContainerHigh = Lw.Secondary,
        surfaceContainerHighest = Lw.Secondary,
        outline = Lw.Border,
        outlineVariant = Lw.Border,
        error = Lw.Red,
        secondaryContainer = Lw.Secondary,
        onSecondaryContainer = Lw.Foreground,
    )
    val base = TextStyle(fontFamily = Inter, color = Lw.Foreground)
    val typography = MaterialTheme.typography.let { t ->
        t.copy(
            bodyLarge = t.bodyLarge.merge(base), bodyMedium = t.bodyMedium.merge(base), bodySmall = t.bodySmall.merge(base),
            titleLarge = t.titleLarge.merge(base), titleMedium = t.titleMedium.merge(base), titleSmall = t.titleSmall.merge(base),
            labelLarge = t.labelLarge.merge(base), labelMedium = t.labelMedium.merge(base), labelSmall = t.labelSmall.merge(base),
            headlineLarge = t.headlineLarge.merge(base), headlineMedium = t.headlineMedium.merge(base), headlineSmall = t.headlineSmall.merge(base),
            displayLarge = t.displayLarge.merge(base), displayMedium = t.displayMedium.merge(base), displaySmall = t.displaySmall.merge(base),
        )
    }
    CompositionLocalProvider(LocalBrand provides brand) {
        MaterialTheme(colorScheme = scheme, typography = typography, content = content)
    }
}
