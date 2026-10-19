package io.lawallet.wallet.ui.components

import android.graphics.Bitmap
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
import androidx.compose.ui.graphics.Color
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
import io.lawallet.wallet.ui.theme.Lw
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import com.composables.icons.lucide.R as L

/** components/wallet/shared/nav-tabbar.tsx — floating pill + raised theme scan button. */
@Composable
fun NavTabBar(current: String, onHome: () -> Unit, onSettings: () -> Unit, onScan: () -> Unit, modifier: Modifier = Modifier) {
    val brand = LocalBrand.current
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 16.dp, top = 12.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            Modifier.widthIn(max = 286.dp).fillMaxWidth().height(64.dp).shadow(24.dp, CircleShape)
                .clip(CircleShape).background(Lw.Card.copy(alpha = 0.95f)).border(1.dp, Lw.Border.copy(alpha = 0.6f), CircleShape)
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TabItem(L.drawable.lucide_ic_house, current == "home", stringResource(R.string.shared_nav_home), onHome)
            Spacer(Modifier.size(54.dp))
            TabItem(L.drawable.lucide_ic_settings, current == "settings", stringResource(R.string.shared_nav_settings), onSettings)
        }
        // Bottom-aligned with the pill (both share the Box's bottom edge); it rises above the pill's top.
        Box(
            Modifier.size(87.dp).shadow(16.dp, CircleShape).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(brand.t200, brand.t400))).border(1.dp, brand.t300, CircleShape)
                .clickable(onClick = onScan).testTag("nav-scan"),
            contentAlignment = Alignment.Center,
        ) { LwIcon(L.drawable.lucide_ic_scan_qr_code, size = 37.dp, contentDescription = stringResource(R.string.shared_nav_scan)) }
    }
}

@Composable
private fun TabItem(icon: Int, active: Boolean, label: String, onClick: () -> Unit) {
    Box(Modifier.size(54.dp).clip(CircleShape).clickable(onClick = onClick).testTag("nav-$label"), contentAlignment = Alignment.Center) {
        LwIcon(icon, size = 28.dp, tint = if (active) Lw.Foreground else Lw.MutedFg, contentDescription = label)
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
                        Modifier.weight(1f).fillMaxHeight().heightIn(min = 58.dp).clip(RoundedCornerShape(16.dp))
                            .then(if (key != null) Modifier.background(Lw.Card.copy(alpha = 0.9f)).clickable(enabled = enabled) { onChange(Keypad.press(value, key, mode)) }.testTag("key-$key") else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        when (key) {
                            null -> {}
                            Keypad.BACKSPACE -> LwIcon(L.drawable.lucide_ic_delete, size = 26.dp, contentDescription = stringResource(R.string.shared_keypad_deleteLast))
                            else -> Txt(key, size = 30.sp, weight = FontWeight.SemiBold)
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
    Row(modifier.clip(CircleShape).background(Lw.Card).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        codes.forEach { code ->
            val active = code == selected
            Box(
                Modifier.clip(CircleShape).then(if (active) Modifier.shadow(2.dp, CircleShape).background(Lw.Background) else Modifier)
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
    val color = if (tx.incoming) Lw.Green else Lw.Orange
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp).testTag("tx-row"), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(color.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
            LwIcon(if (tx.incoming) L.drawable.lucide_ic_arrow_down_left else L.drawable.lucide_ic_arrow_up_right, size = 20.dp, tint = color)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(tx.description ?: stringResource(if (tx.incoming) R.string.shared_tx_received else R.string.shared_tx_sent), size = 14.sp, weight = FontWeight.Medium, maxLines = 1)
            Txt(if (tx.state == "pending") stringResource(R.string.shared_receipt_pending) else if (tx.state == "failed") stringResource(R.string.shared_receipt_failed) else relativeTime(tx.settledAt ?: tx.createdAt), size = 12.sp, color = Lw.MutedFg)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Txt((if (tx.incoming) "+" else "−") + Currencies.formatSats(tx.amountSats), size = 14.sp, weight = FontWeight.SemiBold, color = if (tx.incoming) Lw.Green else Lw.Foreground)
            Spacer(Modifier.width(4.dp))
            Txt("sats", size = 12.sp, color = Lw.MutedFg)
        }
    }
}

fun qrBitmap(data: String, size: Int = 720): Bitmap {
    val m = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 0))
    val px = IntArray(size * size) { if (m[it % size, it / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}

/** qr-display.tsx — 240dp QR on a white rounded-2xl tile, caption, Copy / Share. */
@Composable
fun QrDisplay(data: String, caption: String?, copyValue: String = data, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val bmp = remember(data) { qrBitmap(data) }
    val copied = stringResource(R.string.common_copied)
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.shadow(12.dp, RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(16.dp).clickable { context.copyToClipboard(copyValue, copied) }) {
            Image(bmp.asImageBitmap(), null, Modifier.size(240.dp).testTag("qr"))
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

/** swipe-button.tsx — 52dp pill, 48dp thumb, confirms at 70%. */
@Composable
fun SwipeButton(label: String, onConfirm: () -> Unit, loading: Boolean, done: Boolean, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val scope = rememberCoroutineScope()
    val offset = remember { Animatable(0f) }
    var trackPx by remember { mutableStateOf(1f) }
    val thumbPx = with(LocalDensity.current) { 48.dp.toPx() }
    val shimmer = rememberInfiniteTransition(label = "shimmer").animateFloat(-1f, 2f, infiniteRepeatable(tween(2500, easing = LinearEasing), RepeatMode.Restart), label = "x")
    val max = (trackPx - thumbPx - 8f).coerceAtLeast(1f)
    BoxWithConstraints(
        modifier.fillMaxWidth().height(52.dp).clip(CircleShape).background(if (done) Lw.Green.copy(alpha = 0.25f) else Lw.Card)
            .border(1.dp, Lw.Border, CircleShape).testTag("swipe"),
    ) {
        trackPx = constraints.maxWidth.toFloat()
        Box(Modifier.fillMaxSize().drawBehind {
            if (!loading && !done) drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.06f), Color.Transparent), Offset(size.width * shimmer.value, 0f), Offset(size.width * (shimmer.value + 0.4f), 0f)))
        }, contentAlignment = Alignment.Center) {
            when {
                loading -> Spinner()
                done -> LwIcon(L.drawable.lucide_ic_check, tint = Lw.Green)
                else -> Txt(label, size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
            }
        }
        if (!loading && !done) Box(
            Modifier.offset { IntOffset(offset.value.roundToInt() + 4, with(this) { 2.dp.roundToPx() }) }.size(48.dp).clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Lw.Neutral400, Lw.Neutral100)))
                .pointerInput(enabled, max) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            scope.launch {
                                if (offset.value >= max * 0.7f) { offset.animateTo(max); onConfirm(); offset.snapTo(0f) } else offset.animateTo(0f)
                            }
                        },
                    ) { _, d -> scope.launch { offset.snapTo((offset.value + d).coerceIn(0f, max)) } }
                }
                .clickable(enabled = false) {},
            contentAlignment = Alignment.Center,
        ) { LwIcon(L.drawable.lucide_ic_chevrons_right, tint = Lw.Background) }
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
) {
    val brand = LocalBrand.current
    val context = LocalContext.current
    val copiedMsg = stringResource(R.string.common_copied)
    Column(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().shadow(20.dp, RoundedCornerShape(24.dp)).clip(RoundedCornerShape(24.dp)).background(Lw.Card).border(1.dp, Lw.Border, RoundedCornerShape(24.dp))
                .drawBehind { drawRect(Brush.horizontalGradient(listOf(Color.Transparent, brand.t300, Color.Transparent)), size = size.copy(height = 1.dp.toPx())) },
        ) {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                StatusMark(status)
                Txt(title, size = 24.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
                Row(verticalAlignment = Alignment.Bottom) {
                    Txt(Currencies.format(amountSats, currency, rates), size = 36.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("receipt-amount"))
                    Spacer(Modifier.width(8.dp))
                    Txt(Currencies.unitLabel(currency), Modifier.padding(bottom = 4.dp), size = 16.sp, weight = FontWeight.SemiBold, color = Lw.MutedFg)
                }
                if (currency != "SAT") Txt(stringResource(R.string.common_sats, Currencies.formatSats(amountSats)), size = 14.sp, color = Lw.MutedFg)
                CurrencyToggle(currencies, currency, onCurrency)
            }
            if (rows.isNotEmpty()) Box(Modifier.fillMaxWidth().background(Lw.Background.copy(alpha = 0.35f)).padding(16.dp)) {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Lw.Card.copy(alpha = 0.7f)).border(1.dp, Lw.Border.copy(alpha = 0.7f), RoundedCornerShape(16.dp)).padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    rows.forEach { (k, v) ->
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Txt(k, size = 14.sp, color = Lw.MutedFg)
                            Txt(v, Modifier.weight(1f), size = 14.sp, align = TextAlign.End, maxLines = 3)
                        }
                    }
                }
            }
        }
        if (proofs.isNotEmpty()) {
            Spacer(Modifier.height(20.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(Lw.Card.copy(alpha = 0.85f)).border(1.dp, Lw.Border.copy(alpha = 0.8f), RoundedCornerShape(24.dp)).padding(16.dp)) {
                Txt(stringResource(R.string.shared_receipt_proofTitle).uppercase(), size = 12.sp, weight = FontWeight.Medium, color = Lw.MutedFg, letterSpacing = 2.sp)
                Spacer(Modifier.height(4.dp))
                MutedText(stringResource(R.string.shared_receipt_proofHint))
                proofs.forEach { (label, value) ->
                    var shown by remember { mutableStateOf(false) }
                    Spacer(Modifier.height(16.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(label.uppercase(), Modifier.weight(1f), size = 10.sp, weight = FontWeight.SemiBold, color = Lw.MutedFg, letterSpacing = 1.2.sp)
                        if (value != null) {
                            Box(Modifier.size(28.dp).clip(CircleShape).clickable { shown = !shown }, contentAlignment = Alignment.Center) { LwIcon(if (shown) L.drawable.lucide_ic_eye_off else L.drawable.lucide_ic_eye, size = 16.dp, tint = Lw.MutedFg) }
                            Box(Modifier.size(28.dp).clip(CircleShape).clickable { context.copyToClipboard(value, copiedMsg) }, contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_copy, size = 16.dp, tint = Lw.MutedFg) }
                        }
                    }
                    Txt(
                        when { value == null -> stringResource(R.string.shared_receipt_notProvided); shown -> value; else -> "•".repeat(24) },
                        size = 10.sp, mono = true, color = if (value == null) Lw.MutedFg else Lw.Foreground,
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
    Box(Modifier.size(80.dp).clip(CircleShape).background(bg), contentAlignment = Alignment.Center) { LwIcon(icon, size = 40.dp, tint = tint) }
}

/** Receipt footer: Share | View activity, then Done. */
@Composable
fun ReceiptFooter(onShare: () -> Unit, onActivity: (() -> Unit)?, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Lw.Background.copy(alpha = 0.9f)).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LwButton(stringResource(R.string.common_share), onShare, Modifier.weight(1f), BtnVariant.Secondary, icon = L.drawable.lucide_ic_share_2)
            if (onActivity != null) LwButton(stringResource(R.string.shared_receipt_viewActivity), onActivity, Modifier.weight(1f), BtnVariant.Secondary)
        }
        LwButton(stringResource(R.string.common_done), onDone, Modifier.fillMaxWidth().testTag("done"))
    }
}

/** home/payment-notice.tsx — pill 64dp from the top, green/orange border, ±amount. */
@Composable
fun PaymentNoticePill(type: String, amountSats: Long, modifier: Modifier = Modifier) {
    val received = type == "payment_received"
    val c = if (received) Lw.Green else Lw.Orange
    Row(
        modifier.shadow(16.dp, CircleShape).clip(CircleShape).background(Lw.Card.copy(alpha = 0.95f)).border(1.dp, c.copy(alpha = 0.4f), CircleShape)
            .padding(start = 6.dp, end = 16.dp, top = 6.dp, bottom = 6.dp).testTag("payment-notice"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(32.dp).clip(CircleShape).background(c.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
            LwIcon(if (received) L.drawable.lucide_ic_arrow_down_left else L.drawable.lucide_ic_arrow_up_right, size = 18.dp, tint = c)
        }
        Spacer(Modifier.width(10.dp))
        Txt(stringResource(if (received) R.string.shared_tx_received else R.string.shared_tx_sent).uppercase(), size = 12.sp, weight = FontWeight.SemiBold, color = Lw.MutedFg, letterSpacing = 0.8.sp)
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
    Box(modifier.aspectRatio(1.586f).clip(RoundedCornerShape(12.dp)).background(Lw.Secondary)) {
        if (url != null) coil3.compose.AsyncImage(url, null, Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.Crop)
    }
}
