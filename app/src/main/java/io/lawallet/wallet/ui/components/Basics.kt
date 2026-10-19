package io.lawallet.wallet.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.lawallet.wallet.ui.theme.Inter
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import com.composables.icons.lucide.R as L

/** Lucide icon by drawable id, e.g. `LwIcon(L.drawable.lucide_ic_house)`. */
@Composable
fun LwIcon(@DrawableRes id: Int, modifier: Modifier = Modifier, size: Dp = 20.dp, tint: Color = Lw.Foreground, contentDescription: String? = null) =
    Icon(painterResource(id), contentDescription, modifier.size(size), tint = tint)

@Composable
fun Txt(
    text: String,
    modifier: Modifier = Modifier,
    size: TextUnit = 14.sp,
    weight: FontWeight = FontWeight.Normal,
    color: Color = Lw.Foreground,
    align: TextAlign? = null,
    maxLines: Int = Int.MAX_VALUE,
    mono: Boolean = false,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    lineHeight: TextUnit = TextUnit.Unspecified,
) = Text(
    text, modifier, color = color, fontSize = size, fontWeight = weight, textAlign = align, maxLines = maxLines,
    overflow = TextOverflow.Ellipsis, letterSpacing = letterSpacing, lineHeight = lineHeight,
    style = TextStyle(fontFamily = if (mono) FontFamily.Monospace else Inter, fontFeatureSettings = "tnum"),
)

enum class BtnVariant { Default, Theme, Secondary, Destructive, Outline, Ghost }

/** components/ui/button.tsx — gradients + inset highlight, press scale 0.97. */
@Composable
fun LwButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: BtnVariant = BtnVariant.Default,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Dp = 48.dp,
    @DrawableRes icon: Int? = null,
    shape: Shape? = null,
) {
    val brand = LocalBrand.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "press")
    val s = shape ?: RoundedCornerShape((brand.radius - 2.dp).coerceAtLeast(0.dp))
    val (bg, border, fg, highlight) = when (variant) {
        BtnVariant.Default -> Quad(Brush.verticalGradient(listOf(Lw.Neutral400, Lw.Neutral100)), Lw.Neutral700, Lw.Background, Color.White.copy(alpha = 0.4f))
        BtnVariant.Theme -> Quad(Brush.verticalGradient(listOf(brand.t200, brand.t400)), brand.t300, Lw.Foreground, brand.t400)
        BtnVariant.Secondary -> Quad(Brush.verticalGradient(listOf(Lw.Secondary, Lw.Secondary)), null, Lw.Foreground, null)
        BtnVariant.Destructive -> Quad(Brush.verticalGradient(listOf(Lw.Destructive, Lw.Destructive)), null, Lw.DestructiveFg, null)
        BtnVariant.Outline -> Quad(Brush.verticalGradient(listOf(Lw.Background, Lw.Background)), Lw.Border, Lw.Foreground, null)
        BtnVariant.Ghost -> Quad(Brush.verticalGradient(listOf(Color.Transparent, Color.Transparent)), null, Lw.Foreground, null)
    }
    val active = enabled && !loading
    Box(
        modifier
            .scale(scale)
            .height(height)
            .alpha(if (enabled) 1f else if (variant == BtnVariant.Default) 0.4f else 0.5f)
            .clip(s)
            .background(bg, s)
            .then(if (border != null) Modifier.border(BorderStroke(1.dp, border), s) else Modifier)
            .then(if (highlight != null) Modifier.drawWithContent {
                drawContent()
                drawRect(Brush.verticalGradient(0f to highlight, 0.06f to Color.Transparent))
            } else Modifier)
            .clickable(interaction, null, enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { LwIcon(icon, size = 18.dp, tint = fg); Spacer(Modifier.width(8.dp)) }
            Txt(text, size = 14.sp, weight = FontWeight.Medium, color = fg, maxLines = 1)
        }
    }
}

private data class Quad(val bg: Brush, val border: Color?, val fg: Color, val highlight: Color?)

/** components/wallet/shared/screen-header.tsx — 56dp, round back/close, centered title. */
@Composable
fun ScreenHeader(title: String?, onBack: (() -> Unit)?, close: Boolean = false, trailing: @Composable (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp)) {
        if (onBack != null) Box(
            Modifier.align(Alignment.CenterStart).size(40.dp).clip(CircleShape).clickable(onClick = onBack).testTag("header-back"),
            contentAlignment = Alignment.Center,
        ) { LwIcon(if (close) L.drawable.lucide_ic_x else L.drawable.lucide_ic_chevron_left, size = 22.dp) }
        if (title != null) Txt(title, Modifier.align(Alignment.Center), size = 16.sp, weight = FontWeight.Medium)
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/** Settings pages: "‹ Back" pill + centered semibold title. */
@Composable
fun SettingsHeader(title: String, onBack: () -> Unit, backLabel: String) {
    Box(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 16.dp)) {
        Row(
            Modifier.align(Alignment.CenterStart).height(36.dp).clip(CircleShape).background(Lw.Card).clickable(onClick = onBack).padding(start = 8.dp, end = 14.dp).testTag("header-back"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LwIcon(L.drawable.lucide_ic_chevron_left, size = 18.dp)
            Txt(backLabel, size = 14.sp, weight = FontWeight.Medium)
        }
        Txt(title, Modifier.align(Alignment.Center), size = 16.sp, weight = FontWeight.SemiBold)
    }
}

/** rounded-2xl bg-card group with border/40 dividers. */
@Composable
fun SettingsGroup(title: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) Txt(title.uppercase(), Modifier.padding(start = 4.dp, bottom = 8.dp), size = 12.sp, weight = FontWeight.Medium, color = Lw.MutedFg, letterSpacing = 0.6.sp)
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Lw.Card)) { content() }
    }
}

@Composable
fun GroupDivider() = HorizontalDivider(Modifier.padding(start = 16.dp), color = Lw.Border.copy(alpha = 0.4f))

@Composable
fun SettingsRow(
    label: String,
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    @DrawableRes icon: Int? = null,
    value: String? = null,
    external: Boolean = false,
    labelColor: Color = Lw.Foreground,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier.fillMaxWidth().height(56.dp).scale(if (pressed) 0.985f else 1f)
            .then(if (onClick != null) Modifier.clickable(interaction, null, onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { LwIcon(icon, size = 20.dp, tint = Lw.MutedFg); Spacer(Modifier.width(12.dp)) }
        Txt(label, Modifier.weight(1f), size = 16.sp, weight = FontWeight.Medium, color = labelColor, maxLines = 1)
        if (value != null) { Txt(value, size = 14.sp, color = Lw.MutedFg, maxLines = 1); Spacer(Modifier.width(6.dp)) }
        if (trailing != null) trailing()
        else if (onClick != null) LwIcon(if (external) L.drawable.lucide_ic_external_link else L.drawable.lucide_ic_chevron_right, size = 18.dp, tint = Lw.MutedFg)
    }
}

/** A plain bg-card surface. */
@Composable
fun LwCard(modifier: Modifier = Modifier, radius: Dp = 16.dp, color: Color = Lw.Card, border: Color? = null, content: @Composable () -> Unit) {
    val s = RoundedCornerShape(radius)
    Box(modifier.clip(s).background(color, s).then(if (border != null) Modifier.border(1.dp, border, s) else Modifier)) { content() }
}

/** Community image (logotype/isotype) from settings, falling back to the bundled LaWallet art. */
@Composable
fun BrandImage(url: String?, fallbackAsset: String, modifier: Modifier = Modifier, contentDescription: String? = null) {
    AsyncImage(model = url ?: "file:///android_asset/brand/$fallbackAsset", contentDescription = contentDescription, modifier = modifier)
}

@Composable
fun Spinner(size: Dp = 20.dp, color: Color = Lw.Foreground) = CircularProgressIndicator(Modifier.size(size), color = color, strokeWidth = 2.dp)

@Composable
fun MutedText(text: String, modifier: Modifier = Modifier, align: TextAlign? = null, size: TextUnit = 14.sp) = Txt(text, modifier, size = size, color = Lw.MutedFg, align = align, lineHeight = (size.value * 1.45f).sp)

@Composable
fun ErrorText(text: String?, modifier: Modifier = Modifier) {
    if (!text.isNullOrBlank()) Txt(text, modifier, size = 13.sp, color = Lw.Red, align = TextAlign.Center)
}

fun Context.copyToClipboard(text: String, toast: String? = null) {
    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("LaWallet", text))
    if (toast != null) Toast.makeText(this, toast, Toast.LENGTH_SHORT).show()
}

fun Context.shareText(text: String, title: String? = null) {
    startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), title))
}

fun Context.toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
