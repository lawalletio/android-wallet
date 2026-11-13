package io.lawallet.wallet.ui.components

import io.lawallet.wallet.ui.LocalContainer
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.layout.ContentScale
import coil3.compose.AsyncImage
import io.lawallet.wallet.core.ln.splitPaymentDescription
import android.graphics.Bitmap
import androidx.compose.animation.Crossfade
import androidx.compose.ui.unit.Dp
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
import io.lawallet.wallet.app.paymentKey
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.ui.layout.layout
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ripple
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import java.text.DateFormat
import java.util.Date
import kotlin.math.roundToInt
import androidx.compose.animation.animateColorAsState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.unit.Constraints
import com.composables.icons.lucide.R as L

/** Floating frosted pill (real backdrop blur over the scrolling screen): Receive · brand scan button · Send. */
@Composable
fun NavTabBar(onReceive: () -> Unit, onSend: () -> Unit, onScan: () -> Unit, haze: HazeState?, modifier: Modifier = Modifier, canReceive: Boolean = true, canSend: Boolean = true) {
    val brand = LocalBrand.current
    Box(modifier.fillMaxWidth().navigationBarsPadding().padding(start = 24.dp, end = 24.dp, bottom = 16.dp, top = 12.dp), contentAlignment = Alignment.BottomCenter) {
        Row(
            // Nearly as tall as the scan button, so the three read as one bar.
            Modifier.widthIn(max = 380.dp).fillMaxWidth().height(82.dp)
                .shadow(16.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                .frosted(haze, CircleShape)
                .padding(horizontal = 6.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionItem(L.drawable.lucide_ic_arrow_down_right, stringResource(R.string.home_receive), "nav-receive", canReceive, onReceive, Modifier.weight(1f))
            Spacer(Modifier.width(96.dp)) // the scan button sits here
            ActionItem(L.drawable.lucide_ic_arrow_up_right, stringResource(R.string.home_send), "nav-send", canSend, onSend, Modifier.weight(1f))
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

/** Icon over its label in a lit capsule of its own (a button, not text on glass). Dimmed (not hidden) when unavailable. */
@Composable
private fun ActionItem(icon: Int, label: String, tag: String, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    val tint = if (enabled) Lw.Foreground else Lw.MutedFg.copy(alpha = 0.5f)
    Column(
        modifier.fillMaxHeight().clip(CircleShape)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = if (enabled) 0.14f else 0.05f), Color.White.copy(alpha = if (enabled) 0.06f else 0.02f))))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (enabled) 0.22f else 0.08f), Color.Transparent)), CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).testTag(tag),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        LwIcon(icon, size = 28.dp, tint = tint)
        Spacer(Modifier.height(3.dp))
        Txt(label, size = 15.sp, weight = FontWeight.SemiBold, color = tint, maxLines = 1)
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

/** currency-toggle.tsx — pill group; one glass indicator springs between the measured segments. */
/** [onLongPress], when given: holding any segment (e.g. Home's, to open currency settings). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CurrencyToggle(codes: List<String>, selected: String, onSelect: (String) -> Unit, modifier: Modifier = Modifier, onLongPress: (() -> Unit)? = null) {
    if (codes.size < 2) return
    // Segment x/width in px, relative to the Row; labels vary in width ("SAT" vs "USD" vs "EUR").
    val bounds = remember { mutableStateMapOf<String, Pair<Float, Float>>() }
    val x = remember { Animatable(0f) }
    val w = remember { Animatable(0f) }
    val target = bounds[selected]
    LaunchedEffect(target) {
        val (tx, tw) = target ?: return@LaunchedEffect
        if (w.value == 0f) { x.snapTo(tx); w.snapTo(tw); return@LaunchedEffect } // first layout: no slide-in from 0
        val bounce = spring<Float>(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium)
        launch { x.animateTo(tx, bounce) }
        w.animateTo(tw, bounce)
    }
    // One press source for every segment: holding any of them "charges" the whole switcher toward [onLongPress].
    val press = remember { MutableInteractionSource() }
    val hold = if (onLongPress != null) holdProgress(press) else 0f
    Box(modifier.graphicsLayer { scaleX = 1f - 0.08f * hold; scaleY = scaleX; alpha = 1f - 0.25f * hold }.glass(CircleShape, GlassLevel.Thin).padding(4.dp)) {
        Box(
            Modifier.matchParentSize().layout { m, c ->
                val p = m.measure(Constraints.fixed(w.value.roundToInt().coerceAtLeast(0), c.maxHeight))
                layout(c.maxWidth, c.maxHeight) { p.place(x.value.roundToInt(), 0) }
            }.glass(CircleShape, GlassLevel.Strong),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            codes.forEach { code ->
                val fg by animateColorAsState(if (code == selected) Lw.Foreground else Lw.MutedFg, spring(stiffness = Spring.StiffnessMedium), label = "currency-fg")
                Box(
                    Modifier.onPlaced { bounds[code] = it.positionInParent().x to it.size.width.toFloat() }
                        .clip(CircleShape).combinedClickable(interactionSource = press, indication = ripple(), onLongClick = onLongPress) { onSelect(code) }.padding(horizontal = 12.dp, vertical = 6.dp).testTag("currency-$code"),
                ) { Txt(code, size = 12.sp, weight = FontWeight.SemiBold, color = fg) }
            }
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
    // A faint wash says the direction at a glance (received green, sent red). Inset + rounded like a group
    // row; the paddings add back up to the old 16/12, so content and row height don't move.
    Row(
        Modifier.freshEntrance(tx).fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).clip(RoundedCornerShape(20.dp))
            .background(txWash(tx.incoming)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 10.dp).testTag("tx-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
            Txt(dest?.name ?: dest?.address ?: label.ifEmpty { null } ?: stringResource(if (tx.incoming) R.string.shared_tx_received else R.string.shared_tx_sent), size = 14.sp, weight = FontWeight.Medium, maxLines = 1, color = txTitle(tx.incoming))
            if (comment != null) Txt("“$comment”", size = 12.sp, color = Lw.Foreground.copy(alpha = 0.8f), maxLines = 1)
            Txt(if (tx.state == "pending") stringResource(R.string.shared_receipt_pending) else if (tx.state == "failed") stringResource(R.string.shared_receipt_failed) else relativeTime(tx.settledAt ?: tx.createdAt), size = 12.sp, color = Lw.MutedFg)
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Txt((if (tx.incoming) "+" else "−") + Currencies.formatSats(tx.amountSats), size = 14.sp, weight = FontWeight.SemiBold, color = txAmount(tx.incoming))
            Spacer(Modifier.width(4.dp))
            Txt("sats", size = 12.sp, color = Lw.MutedFg)
        }
    }
}

/** The activity rows' direction wash: very low-opacity green (received) or red (sent). */
private fun txWash(incoming: Boolean) = (if (incoming) Lw.Green else Lw.Red).copy(alpha = 0.06f)

/** The row title: mostly white with a hint of the direction colour. */
private fun txTitle(incoming: Boolean) = androidx.compose.ui.graphics.lerp(Lw.Foreground, if (incoming) Lw.Green else Lw.Red, 0.35f)

/** The amount: clearly in the direction colour, a touch softened toward white. */
private fun txAmount(incoming: Boolean) = androidx.compose.ui.graphics.lerp(Lw.Foreground, if (incoming) Lw.Green else Lw.Red, 0.7f)

/**
 * A run of same-titled payments (e.g. hosting fees) as one row: summed amount, count badge, and a
 * chevron that unfolds the individual rows in a soft glass well.
 */
@Composable
fun TransactionGroup(txs: List<Tx>, onOpen: (Tx) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val first = txs.first()
    val color = if (first.incoming) Lw.Green else Lw.Orange
    val icon = if (first.incoming) L.drawable.lucide_ic_arrow_down_left else L.drawable.lucide_ic_arrow_up_right
    val label = splitPaymentDescription(first.description).first.ifEmpty { stringResource(if (first.incoming) R.string.shared_tx_received else R.string.shared_tx_sent) }
    val turn by animateFloatAsState(if (open) 180f else 0f, spring(stiffness = Spring.StiffnessMediumLow), label = "chevron")
    val well by animateFloatAsState(if (open) 0.05f else 0f, tween(220), label = "well")
    val shape = RoundedCornerShape(24.dp)
    Column(Modifier.freshEntrance(first).fillMaxWidth().padding(horizontal = 8.dp).clip(shape).background(Color.White.copy(alpha = well))) {
        Row(
            Modifier.fillMaxWidth().clip(shape).background(txWash(first.incoming))
                .clickable(onClickLabel = stringResource(if (open) R.string.activity_group_collapse else R.string.activity_group_expand)) { open = !open }
                .padding(horizontal = 8.dp, vertical = 12.dp).testTag("tx-group"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box {
                Box(Modifier.size(40.dp).glass(CircleShape, GlassLevel.Thin, tint = color.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
                    LwIcon(icon, size = 20.dp, tint = color)
                }
                // Count badge: says "several of these" before the user reads a word.
                Box(Modifier.align(Alignment.BottomEnd).offset(5.dp, 4.dp).border(2.dp, Lw.Background, CircleShape).padding(2.dp).clip(CircleShape).background(color).padding(horizontal = 5.dp), contentAlignment = Alignment.Center) {
                    Txt("${txs.size}", size = 10.sp, weight = FontWeight.Bold, color = Lw.Background)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(label, size = 14.sp, weight = FontWeight.Medium, maxLines = 1, color = txTitle(first.incoming))
                Txt(pluralStringResource(R.plurals.activity_group_count, txs.size, txs.size) + " · " + relativeTime(first.settledAt ?: first.createdAt), size = 12.sp, color = Lw.MutedFg, maxLines = 1)
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Txt((if (first.incoming) "+" else "−") + Currencies.formatSats(txs.sumOf { it.amountSats }), size = 14.sp, weight = FontWeight.SemiBold, color = txAmount(first.incoming))
                Spacer(Modifier.width(4.dp))
                Txt("sats", size = 12.sp, color = Lw.MutedFg)
            }
            Spacer(Modifier.width(6.dp))
            LwIcon(L.drawable.lucide_ic_chevron_down, Modifier.graphicsLayer { rotationZ = turn }, size = 18.dp, tint = Lw.MutedFg)
        }
        AnimatedVisibility(open, enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(220)), exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut(tween(150))) {
            Column(Modifier.padding(bottom = 4.dp)) {
                Box(Modifier.padding(horizontal = 16.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.06f)))
                txs.forEach { tx -> TransactionRow(tx) { onOpen(tx) } }
            }
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
            Column(Modifier.fillMaxWidth().heightIn(min = 300.dp).padding(start = 20.dp, end = 20.dp, top = 36.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically)) {
                StatusMark(status, 64.dp)
                Txt(title, size = 22.sp, weight = FontWeight.SemiBold)
                Row(verticalAlignment = Alignment.Bottom) {
                    Txt(Currencies.format(amountSats, currency, rates), size = 52.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("receipt-amount"))
                    Spacer(Modifier.width(8.dp))
                    Txt(Currencies.unitLabel(currency), Modifier.padding(bottom = 8.dp), size = 18.sp, weight = FontWeight.SemiBold, color = Lw.MutedFg)
                }
                if (currency != "SAT") Txt(stringResource(R.string.common_sats, Currencies.formatSats(amountSats)), size = 15.sp, color = Lw.MutedFg)
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
            var open by rememberSaveable { mutableStateOf(false) }
            Spacer(Modifier.height(16.dp))
            LwButton(
                stringResource(if (open) R.string.shared_receipt_hideProof else R.string.shared_receipt_displayProof), { open = !open },
                Modifier.fillMaxWidth().testTag("proof-toggle"), BtnVariant.Ghost, icon = if (open) L.drawable.lucide_ic_eye_off else L.drawable.lucide_ic_eye,
            )
            AnimatedVisibility(open, enter = expandVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeIn(), exit = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()) {
                Column(Modifier.padding(top = 8.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(16.dp)) {
                    Txt(stringResource(R.string.shared_receipt_proofTitle), size = 15.sp, weight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    MutedText(stringResource(R.string.shared_receipt_proofHint))
                    proofs.forEach { (label, value) ->
                        Spacer(Modifier.height(16.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Txt(label, Modifier.weight(1f), size = 12.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
                            if (value != null) Box(Modifier.minimumInteractiveComponentSize().clip(CircleShape).clickable { context.copyToClipboard(value, copiedMsg) }, contentAlignment = Alignment.Center) {
                                LwIcon(L.drawable.lucide_ic_copy, size = 16.dp, tint = Lw.MutedFg, contentDescription = stringResource(R.string.common_copy))
                            }
                        }
                        Txt(value ?: stringResource(R.string.shared_receipt_notProvided), Modifier.testTag("proof-$label"), size = 11.sp, mono = true, color = if (value == null) Lw.MutedFg else Lw.Foreground)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusMark(status: String, size: Dp = 40.dp) {
    val brand = LocalBrand.current
    val (bg, icon, tint) = when (status) {
        "pending" -> Triple(Lw.Secondary, L.drawable.lucide_ic_clock, Lw.MutedFg)
        "failed" -> Triple(Lw.Destructive.copy(alpha = 0.15f), L.drawable.lucide_ic_x, Lw.Red)
        else -> Triple(brand.t400.copy(alpha = 0.15f), L.drawable.lucide_ic_check, brand.t400)
    }
    Box(Modifier.size(size).glass(CircleShape, GlassLevel.Regular, tint = bg), contentAlignment = Alignment.Center) { LwIcon(icon, size = size / 2, tint = tint) }
}

/** Receipt footer: Share, then Done. */
@Composable
fun ReceiptFooter(onShare: () -> Unit, onDone: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(Brush.verticalGradient(listOf(Color.Transparent, Lw.Background.copy(alpha = 0.85f)))).navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LwButton(stringResource(R.string.common_share), onShare, Modifier.fillMaxWidth(), BtnVariant.Secondary, icon = L.drawable.lucide_ic_share_2)
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

/**
 * A payment that just arrived makes its entrance once: the row opens its own height (so the rows below
 * glide down rather than jump), slides in from the side its money moved (incoming from the left,
 * outgoing from the right) and fades up, then a green/coral wash lingers and fades. Older rows: no-op.
 */
@Composable
private fun Modifier.freshEntrance(tx: Tx): Modifier {
    val c = LocalContainer.current
    val key = paymentKey(tx)
    val p = remember(key) { Animatable(if (c.wallet.consumeFresh(key)) 0f else 1f) }
    if (p.value >= 1f && !p.isRunning) return this
    LaunchedEffect(key) { p.animateTo(1f, tween(1800, easing = LinearEasing)) }
    val tint = if (tx.incoming) Lw.Green else Lw.Orange
    val dir = if (tx.incoming) -1f else 1f
    return this
        .layout { m, cs ->
            val pl = m.measure(cs)
            val h = (pl.height * FastOutSlowInEasing.transform((p.value / 0.28f).coerceIn(0f, 1f))).roundToInt()
            layout(pl.width, h) { pl.place(0, 0) }
        }
        .graphicsLayer {
            val t = FastOutSlowInEasing.transform(((p.value - 0.08f) / 0.32f).coerceIn(0f, 1f))
            translationX = (1f - t) * dir * 56.dp.toPx()
            alpha = t
            clip = false
        }
        .drawBehind {
            val g = 1f - ((p.value - 0.4f) / 0.6f).coerceIn(0f, 1f)
            if (g > 0f) {
                val inset = 8.dp.toPx()
                drawRoundRect(tint.copy(alpha = 0.16f * g), Offset(inset, 0f), Size(size.width - 2 * inset, size.height), CornerRadius(20.dp.toPx()))
            }
        }
}
