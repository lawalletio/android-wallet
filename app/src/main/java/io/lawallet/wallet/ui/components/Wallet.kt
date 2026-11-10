package io.lawallet.wallet.ui.components

import io.lawallet.wallet.ui.LocalContainer
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import io.lawallet.wallet.core.ln.splitPaymentDescription
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.lawallet.wallet.R
import io.lawallet.wallet.app.Tx
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.fx.Keypad
import io.lawallet.wallet.core.fx.KeypadMode
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.frosted
import io.lawallet.wallet.ui.theme.glass
import dev.chrisbanes.haze.HazeState
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import com.composables.icons.lucide.R as L

/** Floating frosted pill (real backdrop blur over the scrolling screen) + the brand scan button. */
@Composable
fun NavTabBar(current: String, onHome: () -> Unit, onSettings: () -> Unit, onScan: () -> Unit, haze: HazeState?, modifier: Modifier = Modifier) {
    val brand = LocalBrand.current
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 16.dp, top = 12.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.widthIn(max = 286.dp).fillMaxWidth().height(64.dp)
                .shadow(16.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .frosted(haze, CircleShape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TabItem(L.drawable.lucide_ic_house, current == "home", stringResource(R.string.shared_nav_home), onHome)
            Spacer(Modifier.size(54.dp))
            TabItem(L.drawable.lucide_ic_settings, current == "settings", stringResource(R.string.shared_nav_settings), onSettings)
        }
        // Bottom-aligned with the pill; lit from above with a brand glow below.
        Box(
            Modifier.size(87.dp)
                .shadow(12.dp, CircleShape, ambientColor = brand.t400.copy(alpha = 0.6f), spotColor = brand.t400.copy(alpha = 0.6f))
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(brand.t400, brand.t300, brand.t200)))
                .drawBehind { drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = 0.28f), 0.5f to Color.Transparent)) }
                .border(1.5.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), brand.t300.copy(alpha = 0.5f))), CircleShape)
                .clickable(onClick = onScan).testTag("nav-scan"),
            contentAlignment = Alignment.Center,
        ) { LwIcon(L.drawable.lucide_ic_scan_qr_code, size = 36.dp, contentDescription = stringResource(R.string.shared_nav_scan)) }
    }
}

@Composable
private fun TabItem(icon: Int, active: Boolean, label: String, onClick: () -> Unit) {
    Box(
        Modifier.size(54.dp).clip(CircleShape).then(if (active) Modifier.background(Color.White.copy(alpha = 0.08f)) else Modifier)
            .clickable(onClick = onClick).testTag("nav-$label"),
        contentAlignment = Alignment.Center,
    ) {
        LwIcon(icon, size = 26.dp, tint = if (active) Lw.Foreground else Lw.MutedFg, contentDescription = label)
    }
}

/** amount-display.tsx — 48sp semibold value + 18sp muted unit. */
@Composable
fun AmountDisplay(value: String, unit: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.Center) {
        Txt(value, size = 48.sp, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.testTag("amount-value"))
        Spacer(Modifier.width(8.dp))
        Txt(unit, Modifier.padding(bottom = 8.dp), size = 18.sp, color = Lw.MutedFg)
    }
}

/** Keypad string → grouped display (1234 → 1,234; fiat keeps its 2 decimals). */
fun keypadDisplay(raw: String, code: String): String {
    if (code == "SAT") return Currencies.formatSats(raw.toLongOrNull() ?: 0)
    val int = raw.substringBefore('.')
    val dec = raw.substringAfter('.', "")
    val grouped = Currencies.formatSats(int.toLongOrNull() ?: 0)
    val sep = java.text.DecimalFormatSymbols.getInstance().decimalSeparator
    return if (raw.contains('.')) "$grouped$sep$dec" else grouped
}

/** amount-keypad.tsx — 3×4 grid, 12dp gaps, rounded-2xl bg-card/90, 30sp keys; fills the height. */
@Composable
fun AmountKeypad(value: String, onChange: (String) -> Unit, mode: KeypadMode, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val keys = if (mode.integerOnly) listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", null, "0", Keypad.BACKSPACE)
    else listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", "00", "0", Keypad.BACKSPACE)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { key ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().heightIn(min = 58.dp)
                            .then(if (key != null) Modifier.glass(RoundedCornerShape(20.dp), GlassLevel.Thin).clickable(enabled = enabled) { onChange(Keypad.press(value, key, mode)) }.testTag("key-$key") else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (key) {
                            null -> {}
                            Keypad.BACKSPACE -> LwIcon(L.drawable.lucide_ic_delete, size = 26.dp, contentDescription = stringResource(R.string.shared_keypad_deleteLast))
                            else -> Txt(key, size = 28.sp, weight = FontWeight.Medium)
                        }
                    }
                }
            }
        }
    }
}

/** currency-toggle.tsx — pill group; active is bg-background with shadow. */
@Composable
fun CurrencyToggle(codes: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    if (codes.size < 2) return
    Row(modifier.glass(CircleShape, GlassLevel.Thin).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        codes.forEach { code ->
            val active = code == selected
            Box(
                Modifier.clip(CircleShape).then(if (active) Modifier.glass(CircleShape, GlassLevel.Strong) else Modifier)
                    .clickable { onSelect(code) }.padding(horizontal = 12.dp, vertical = 6.dp).testTag("currency-$code"),
            ) { Txt(if (code == "SAT") "SAT" else code, size = 12.sp, weight = FontWeight.SemiBold, color = if (active) Lw.Foreground else Lw.MutedFg) }
        }
    }
}

@Composable
fun relativeTime(ms: Long): String {
    val diff = System.currentTimeMillis() - ms
    val minutes = (diff / 60_000.0).roundToInt()
    return when {
        diff < 0 || minutes < 1 -> stringResource(R.string.shared_tx_justNow)
        minutes < 60 -> stringResource(R.string.shared_tx_minutesAgo, minutes)
        (minutes / 60.0).roundToInt() < 24 -> stringResource(R.string.shared_tx_hoursAgo, (minutes / 60.0).roundToInt())
        (minutes / 1440.0).roundToInt() < 7 -> stringResource(R.string.shared_tx_daysAgo, (minutes / 1440.0).roundToInt())
        else -> DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))
    }
}

/** transaction-row.tsx */
@Composable
fun TransactionRow(tx: Tx, onClick: () -> Unit) {
    val c = LocalContainer.current
    val color = if (tx.incoming) Lw.Green else Lw.Orange
    val icon = if (tx.incoming) L.drawable.lucide_ic_arrow_down_left else L.drawable.lucide_ic_arrow_up_right
    // Lightning Address sends are remembered at pay time; everything else only has the invoice description.
    val destinations by c.prefs.paymentDestinations.collectAsState()
    val dest = if (tx.incoming) null else destinations[tx.paymentHash]
    val (label, describedComment) = splitPaymentDescription(tx.description)
    val comment = if (dest != null) dest.comment else describedComment
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag("tx-row"), verticalAlignment = Alignment.CenterVertically) {
        if (dest != null) Box {
            AsyncImage(c.avatars.of(dest.address), null, Modifier.size(40.dp).glass(CircleShape, GlassLevel.Strong).clip(CircleShape), contentScale = ContentScale.Crop)
            Box(Modifier.align(Alignment.BottomEnd).offset(2.dp, 2.dp).size(18.dp).border(2.dp, Lw.Background, CircleShape).padding(2.dp).clip(CircleShape).background(color), contentAlignment = Alignment.Center) {
                LwIcon(icon, size = 10.dp, tint = Lw.Background)
            }
        } else Box(Modifier.size(40.dp).glass(CircleShape, GlassLevel.Thin, tint = color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            LwIcon(icon, size = 20.dp, tint = color)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(dest?.name ?: dest?.address ?: label.ifEmpty { null } ?: stringResource(if (tx.incoming) R.string.shared_tx_received else R.string.shared_tx_sent), size = 14.sp, weight = FontWeight.Medium, maxLines = 1)
            if (comment != null) Txt("“$comment”", size = 12.sp, color = Lw.Foreground.copy(alpha = 0.8f), maxLines = 1)
            Txt(if (tx.state == "pending") stringResource(R.string.shared_receipt_pending) else if (tx.state == "failed") stringResource(R.string.shared_receipt_failed) else relativeTime(tx.settledAt ?: tx.createdAt), size = 12.sp, color = Lw.MutedFg)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Txt((if (tx.incoming) "+" else "−") + Currencies.formatSats(tx.amountSats), size = 14.sp, weight = FontWeight.SemiBold, color = if (tx.incoming) Lw.Green else Lw.Foreground)
            Spacer(Modifier.width(4.dp))
            Txt("sats", size = 12.sp, color = Lw.MutedFg)
        }
    }
}

/** One pixel per module (size 0): drawn scaled with [FilterQuality.None]. A 720px render cost ~4 MB and a 518k-pixel loop on the main thread per open. */
fun qrBitmap(data: String): Bitmap {
    val m = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
    val w = m.width
    val px = IntArray(w * w) { if (m[it % w, it / w]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, w, w, Bitmap.Config.ARGB_8888)
}

/** qr-display.tsx — 240dp QR on a white rounded-2xl tile, caption, Copy / Share. */
@Composable
fun QrDisplay(data: String, caption: String?, copyValue: String = data, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp = remember(data) { qrBitmap(data).asImageBitmap() }
    val copied = stringResource(R.string.common_copied)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.glass(RoundedCornerShape(32.dp), GlassLevel.Regular).padding(12.dp)) {
            Box(Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White).padding(16.dp).clickable { context.copyToClipboard(copyValue, copied) }) {
                Image(bmp, null, Modifier.size(232.dp).testTag("qr"), filterQuality = FilterQuality.None)
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(16.dp))
            Txt(caption, size = 14.sp, color = Lw.MutedFg, align = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(horizontal = 16.dp))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LwButton(stringResource(R.string.common_copy), { context.copyToClipboard(copyValue, copied) }, Modifier.weight(1f), BtnVariant.Secondary, icon = L.drawable.lucide_ic_copy)
            LwButton(stringResource(R.string.common_share), { context.shareText(copyValue) }, Modifier.weight(1f), BtnVariant.Secondary, icon = L.drawable.lucide_ic_share_2)
        }
    }
}

/** iOS slide-to-unlock: white knob in a dark capsule, shimmering label that fades as you drag, springs back unless taken to the end. */
@Composable
fun SwipeButton(label: String, onConfirm: () -> Unit, loading: Boolean, done: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val offset = remember { Animatable(0f) }
    var trackPx by remember { mutableStateOf(1f) }
    val density = LocalDensity.current
    val inset = with(density) { 4.dp.toPx() }
    val thumbPx = with(density) { 64.dp.toPx() }
    val shine = rememberInfiniteTransition(label = "shine").animateFloat(-0.5f, 1.5f, infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart), label = "x")
    val max = (trackPx - thumbPx - inset * 2).coerceAtLeast(1f)
    val progress = offset.value / max
    // Knob stays at the end through the hand-off to "Paying…" (so "Swipe to pay" never flashes back); it returns only if the payment fails.
    LaunchedEffect(loading, done) { if (!loading && !done && offset.value > 0f) offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow)) }
    BoxWithConstraints(
        modifier.fillMaxWidth().height(72.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.35f))
            .glass(CircleShape, GlassLevel.Thin, tint = if (done) Lw.Green.copy(alpha = 0.25f) else null).testTag("swipe"),
    ) {
        trackPx = constraints.maxWidth.toFloat()
        // Green trail behind the knob: fades in with progress, darker at the bottom with a lit top edge so it reads as recessed.
        if (!done) Box(Modifier.matchParentSize().drawBehind {
            val fill = (if (loading) 1f else progress).coerceIn(0f, 1f)
            if (fill <= 0f) return@drawBehind
            val w = (if (loading) max else offset.value) + thumbPx + inset * 2
            val r = CornerRadius(size.height / 2)
            drawRoundRect(Brush.verticalGradient(listOf(Lw.Green.copy(alpha = 0.35f), Lw.Green.copy(alpha = 0.75f))), size = Size(w, size.height), cornerRadius = r, alpha = fill)
            drawRoundRect(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent), endY = size.height * 0.4f), size = Size(w, size.height), cornerRadius = r, alpha = fill)
            drawRoundRect(Brush.horizontalGradient(listOf(Color.Transparent, Lw.Green.copy(alpha = 0.6f)), startX = w - thumbPx * 2, endX = w), size = Size(w, size.height), cornerRadius = r, alpha = fill)
        })
        // Symmetric padding keeps the label centred on the full track while clearing the knob.
        Crossfade(if (done) 2 else if (loading) 1 else 0, Modifier.fillMaxSize().padding(horizontal = 68.dp), label = "swipe-label") { state ->
          Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            when (state) {
                1 -> Txt(stringResource(R.string.send_preview_paying), size = 20.sp, weight = FontWeight.Normal, color = Color.White.copy(alpha = 0.7f))
                2 -> LwIcon(L.drawable.lucide_ic_check, tint = Lw.Green)
                // Label is drawn white, then masked by a grey gradient with a bright band sweeping through the glyphs.
                else -> Txt(
                    label,
                    Modifier.graphicsLayer { alpha = (1f - progress * 1.8f).coerceIn(0f, 1f); compositingStrategy = CompositingStrategy.Offscreen }
                        .drawWithContent {
                            drawContent()
                            val x = size.width * shine.value
                            val dim = Color.White.copy(alpha = if (enabled) 0.45f else 0.2f)
                            drawRect(Brush.horizontalGradient(listOf(dim, if (enabled) Color.White else dim, dim), x - size.width * 0.25f, x + size.width * 0.25f), blendMode = BlendMode.SrcIn)
                        },
                    size = 20.sp, weight = FontWeight.Normal, color = Color.White,
                )
            }
          }
        }
        // While paying, the knob stays parked at the end and spins there.
        if (!done) Box(
            Modifier.offset { IntOffset(((if (loading) max else offset.value) + inset).roundToInt(), inset.roundToInt()) }.size(64.dp)
                .shadow(6.dp, CircleShape).clip(CircleShape).background(Brush.verticalGradient(listOf(Color.White, Lw.Neutral100)))
                .pointerInput(enabled, max, loading) {
                    if (!enabled || loading) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offset.value >= max * 0.9f) {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    offset.animateTo(max); onConfirm()
                                } else offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow))
                            }
                        },
                    ) { _, d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(0f, max)) } }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (loading) Spinner(24.dp, Lw.Neutral700)
            else LwIcon(L.drawable.lucide_ic_chevron_right, size = 28.dp, tint = if (enabled) Lw.Neutral700 else Lw.Neutral400)
        }
    }
}

/** payment-receipt.tsx */
@Composable
fun PaymentReceiptCard(
    title: String,
    status: String,
    amountSats: Long,
    currency: String,
    currencies: List<String>,
    rates: Map<String, Double>?,
    onCurrency: (String) -> Unit,
    rows: List<Pair<String, String>>,
    proofs: List<Pair<String, String?>>,
    /** Replaces a row's plain value, keyed by its label — e.g. a tappable recipient. */
    valueNodes: Map<String, @Composable () -> Unit> = emptyMap(),
) {
    val brand = LocalBrand.current
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.common_copied)
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp))
                .drawBehind { drawRect(Brush.horizontalGradient(listOf(Color.Transparent, brand.t400, Color.Transparent)), size = size.copy(height = 1.5.dp.toPx())) },
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusMark(status)
                    Txt(title, size = 20.sp, weight = FontWeight.SemiBold)
                }
                Row(verticalAlignment = Alignment.Bottom) {
                    Txt(Currencies.format(amountSats, currency, rates), size = 36.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("receipt-amount"))
                    Spacer(Modifier.width(8.dp))
                    Txt(Currencies.unitLabel(currency), Modifier.padding(bottom = 4.dp), size = 16.sp, weight = FontWeight.SemiBold, color = Lw.MutedFg)
                }
                if (currency != "SAT") Txt(stringResource(R.string.common_sats, Currencies.formatSats(amountSats)), size = 14.sp, color = Lw.MutedFg)
                CurrencyToggle(currencies, currency, onCurrency)
            }
            if (rows.isNotEmpty()) Box(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp)) {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), GlassLevel.Thin).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Txt(k, size = 14.sp, color = Lw.MutedFg)
                            val node = valueNodes[k]
                            if (node != null) Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) { node() }
                            else Txt(v, Modifier.weight(1f), size = 14.sp, align = TextAlign.End, maxLines = 3)
                        }
                    }
                }
            }
        }
        if (proofs.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(16.dp)) {
                Txt(stringResource(R.string.shared_receipt_proofTitle), size = 15.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                MutedText(stringResource(R.string.shared_receipt_proofHint))
                proofs.forEach { (label, value) ->
                    var shown by remember { mutableStateOf(false) }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(label, Modifier.weight(1f), size = 12.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
                        if (value != null) {
                            Box(Modifier.minimumInteractiveComponentSize().clip(CircleShape).clickable(onClickLabel = label) { shown = !shown }.testTag("proof-toggle-$label"), contentAlignment = Alignment.Center) {
                                LwIcon(if (shown) L.drawable.lucide_ic_eye_off else L.drawable.lucide_ic_eye, size = 16.dp, tint = Lw.MutedFg, contentDescription = stringResource(if (shown) R.string.shared_receipt_hide else R.string.shared_receipt_show, label))
                            }
                            Box(Modifier.minimumInteractiveComponentSize().clip(CircleShape).clickable { context.copyToClipboard(value, copiedMsg) }, contentAlignment = Alignment.Center) {
                                LwIcon(L.drawable.lucide_ic_copy, size = 16.dp, tint = Lw.MutedFg, contentDescription = stringResource(R.string.common_copy))
                            }
                        }
                    }
                    Txt(
                        when { value == null -> stringResource(R.string.shared_receipt_notProvided); shown -> value; else -> "•".repeat(24) },
                        Modifier.testTag("proof-$label"), size = 11.sp, mono = true, color = if (value == null) Lw.MutedFg else Lw.Foreground,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusMark(status: String) {
    val brand = LocalBrand.current
    val (bg, icon, tint) = when (status) {
        "pending" -> Triple(Lw.Secondary, L.drawable.lucide_ic_clock, Lw.MutedFg)
        "failed" -> Triple(Lw.Destructive.copy(alpha = 0.15f), L.drawable.lucide_ic_x, Lw.Red)
        else -> Triple(brand.t400.copy(alpha = 0.15f), L.drawable.lucide_ic_check, brand.t400)
    }
    Box(Modifier.size(40.dp).glass(CircleShape, GlassLevel.Regular, tint = bg), contentAlignment = Alignment.Center) { LwIcon(icon, size = 20.dp, tint = tint) }
}

/** Receipt footer: Share | View activity, then Done. */
@Composable
fun ReceiptFooter(onShare: () -> Unit, onActivity: (() -> Unit)?, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Lw.Background.copy(alpha = 0.85f)))).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LwButton(stringResource(R.string.common_share), onShare, Modifier.weight(1f), BtnVariant.Secondary, icon = L.drawable.lucide_ic_share_2)
            if (onActivity != null) LwButton(stringResource(R.string.shared_receipt_viewActivity), onActivity, Modifier.weight(1f), BtnVariant.Secondary)
        }
        LwButton(stringResource(R.string.common_done), onDone, Modifier.fillMaxWidth().testTag("done"))
    }
}

/** Frosted payment notice: tinted disc, direction, ±amount. */
@Composable
fun PaymentNoticePill(type: String, amountSats: Long, haze: HazeState?, modifier: Modifier = Modifier) {
    val received = type == "payment_received"
    val c = if (received) Lw.Green else Lw.Orange
    Row(
        modifier.shadow(24.dp, CircleShape, ambientColor = c, spotColor = c).frosted(haze, CircleShape)
            .padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp).testTag("payment-notice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).glass(CircleShape, GlassLevel.Thin, tint = c.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
            LwIcon(if (received) L.drawable.lucide_ic_arrow_down_left else L.drawable.lucide_ic_arrow_up_right, size = 18.dp, tint = c)
        }
        Spacer(Modifier.width(10.dp))
        Txt(stringResource(if (received) R.string.shared_tx_received else R.string.shared_tx_sent), size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
        Spacer(Modifier.width(10.dp))
        Txt((if (received) "+" else "−") + Currencies.formatSats(amountSats), size = 16.sp, weight = FontWeight.SemiBold, color = c)
        Spacer(Modifier.width(4.dp))
        Txt("sats", size = 12.sp, color = Lw.MutedFg)
    }
}

/** A full-height screen column with the standard 16dp gutters. */
@Composable
fun ScreenColumn(modifier: Modifier = Modifier, padding: PaddingValues = PaddingValues(horizontal = 16.dp), content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxSize().padding(padding), content = content)

@Composable
fun AspectCard(url: String?, modifier: Modifier = Modifier) {
    // Drawn drop shadow: an elevation shadow() under translucent glass rendered as dark
    // square blocks on some GPUs (same issue as the landing isotype tile).
    Box(modifier.aspectRatio(1.586f).drawBehind(CardShadow).glass(RoundedCornerShape(16.dp))) {
        if (url != null) coil3.compose.AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}

private val CardShadow: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit = {
    val r = 16.dp.toPx()
    val blur = 18.dp.toPx()
    // Clip the card itself out so the shadow doesn't darken through the translucent glass.
    val card = androidx.compose.ui.graphics.Path().apply {
        addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, r, r))
    }
    clipPath(card, androidx.compose.ui.graphics.ClipOp.Difference) { drawIntoCanvas { canvas ->
        val paint = androidx.compose.ui.graphics.Paint()
        paint.asFrameworkPaint().apply {
            isAntiAlias = true
            color = android.graphics.Color.argb(110, 0, 0, 0)
            maskFilter = android.graphics.BlurMaskFilter(blur, android.graphics.BlurMaskFilter.Blur.NORMAL)
        }
        canvas.drawRoundRect(0f, 6.dp.toPx(), size.width, size.height + 6.dp.toPx(), r, r, paint)
    } }
}
