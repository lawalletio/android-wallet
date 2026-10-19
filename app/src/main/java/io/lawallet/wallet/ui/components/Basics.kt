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
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.heightIn
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

/**
 * Buttons: Theme is the brand gradient lit from above with a soft brand glow underneath;
 * Secondary/Outline are glass; Default keeps the web's neutral metal gradient.
 */
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
    val s = shape ?: RoundedCornerShape(if (brand.radius == 0.dp) 0.dp else (brand.radius + 4.dp).coerceAtMost(height / 2))
    val active = enabled && !loading
    val fg = when (variant) { BtnVariant.Default -> Lw.Background; BtnVariant.Destructive -> Lw.DestructiveFg; else -> Lw.Foreground }
    val surface = when (variant) {
        BtnVariant.Theme -> Modifier
            .shadow(if (enabled) 18.dp else 0.dp, s, ambientColor = brand.t400, spotColor = brand.t400)
            .clip(s)
            .background(Brush.verticalGradient(listOf(brand.t400, brand.t300, brand.t200)), s)
            .drawBehind { drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.22f), 0.5f to Color.Transparent)) }
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), brand.t300.copy(alpha = 0.6f))), s)
        BtnVariant.Default -> Modifier.clip(s)
            .background(Brush.verticalGradient(listOf(Lw.Neutral100, Lw.Neutral400)), s)
            .border(1.dp, Color.White.copy(alpha = 0.5f), s)
        BtnVariant.Secondary -> Modifier.glass(s, if (pressed) GlassLevel.Strong else GlassLevel.Regular)
        BtnVariant.Outline -> Modifier.clip(s).border(1.dp, Glass.edge(GlassLevel.Strong), s)
        BtnVariant.Destructive -> Modifier.glass(s, GlassLevel.Regular, tint = Lw.Red.copy(alpha = 0.22f))
        BtnVariant.Ghost -> Modifier.clip(s)
    }
    Box(
        modifier
            .scale(scale)
            .height(height)
            .alpha(if (enabled) 1f else 0.45f)
            .then(surface)
            .clickable(interaction, null, enabled = active, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = fg, strokeWidth = 2.dp)
        else Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            if (icon != null) { LwIcon(icon, size = 18.dp, tint = fg); Spacer(Modifier.width(8.dp)) }
            Txt(text, size = 15.sp, weight = FontWeight.SemiBold, color = fg, maxLines = 1)
        }
    }
}

/** A round glass icon button (44dp), used for back/close and header actions. */
@Composable
fun GlassIconButton(@DrawableRes icon: Int, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp, contentDescription: String? = null) {
    Box(modifier.size(size).glass(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        LwIcon(icon, size = 20.dp, contentDescription = contentDescription)
    }
}

/** Flow header: round glass back/close button, centered title. */
@Composable
fun ScreenHeader(title: String?, onBack: (() -> Unit)?, close: Boolean = false, trailing: @Composable (() -> Unit)? = null) {
    Box(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp)) {
        if (onBack != null) GlassIconButton(
            if (close) L.drawable.lucide_ic_x else L.drawable.lucide_ic_chevron_left, onBack,
            Modifier.align(Alignment.CenterStart).testTag("header-back"), size = 40.dp,
        )
        if (title != null) Txt(title, Modifier.align(Alignment.Center), size = 17.sp, weight = FontWeight.SemiBold)
        if (trailing != null) Box(Modifier.align(Alignment.CenterEnd)) { trailing() }
    }
}

/** Settings pages: same round glass back button as flows, large left-aligned title below. */
@Composable
fun SettingsHeader(title: String, onBack: () -> Unit, backLabel: String) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp)) {
        GlassIconButton(L.drawable.lucide_ic_chevron_left, onBack, Modifier.testTag("header-back"), size = 40.dp, contentDescription = backLabel)
        Spacer(Modifier.height(16.dp))
        Txt(title, Modifier.padding(start = 4.dp), size = 28.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
    }
}

/** A glass panel grouping related rows; optional sentence-case caption above. */
@Composable
fun SettingsGroup(title: String? = null, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        if (title != null) Txt(title, Modifier.padding(start = 6.dp, bottom = 8.dp), size = 13.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
        Column(Modifier.fillMaxWidth().glass()) { content() }
    }
}

@Composable
fun GroupDivider() = HorizontalDivider(Modifier.padding(start = 60.dp), color = Color.White.copy(alpha = 0.06f))

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
        modifier.fillMaxWidth().heightIn(min = 60.dp)
            .background(if (pressed) Color.White.copy(alpha = 0.05f) else Color.Transparent)
            .then(if (onClick != null) Modifier.clickable(interaction, null, onClick = onClick) else Modifier)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(Modifier.size(32.dp).glass(RoundedCornerShape(10.dp), GlassLevel.Strong), contentAlignment = Alignment.Center) {
                LwIcon(icon, size = 17.dp, tint = if (labelColor == Lw.Foreground) Lw.Foreground else labelColor)
            }
            Spacer(Modifier.width(14.dp))
        }
        Txt(label, Modifier.weight(1f), size = 16.sp, weight = FontWeight.Medium, color = labelColor, maxLines = 1)
        if (value != null) { Txt(value, Modifier.padding(start = 8.dp), size = 14.sp, color = Lw.MutedFg, maxLines = 1); Spacer(Modifier.width(6.dp)) }
        if (trailing != null) trailing()
        else if (onClick != null) LwIcon(if (external) L.drawable.lucide_ic_external_link else L.drawable.lucide_ic_chevron_right, size = 18.dp, tint = Lw.MutedFg)
    }
}

/** A glass surface. */
@Composable
fun LwCard(modifier: Modifier = Modifier, radius: Dp = 20.dp, level: GlassLevel = GlassLevel.Regular, content: @Composable () -> Unit) {
    Box(modifier.glass(RoundedCornerShape(radius), level)) { content() }
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
