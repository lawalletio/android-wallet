package io.lawallet.wallet.ui.screens

import android.os.Build
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ExperimentalMaterial3Api
import io.lawallet.wallet.ui.theme.Glass
import androidx.compose.runtime.remember
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.animation.core.tween
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.fx.Rates
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlin.math.PI
import kotlin.math.sin
import com.composables.icons.lucide.R as L

/*
 * Home's wallet reveal. Swiping down slides the balance block to the bottom of the screen, 1:1 with the
 * finger, and the remote wallets fan out in the space it leaves. Releases spring to the end the gesture
 * aimed at; a swipe up or back closes it. A translucent copy of the balance travels into the primary
 * wallet's card on the way, scrubbed by the same progress.
 */
@Stable
internal class HomeReveal {
    /** 0 = closed, 1 = balance at the bottom and wallets showing (springs overshoot a touch). */
    var progress by mutableFloatStateOf(0f)
        private set

    // Root-space geometry, measured by Home.
    var rootTop by mutableFloatStateOf(0f)
    var rootH by mutableFloatStateOf(0f)
    var bottomReserve by mutableFloatStateOf(0f) // the floating tab bar
    var headerBottom by mutableFloatStateOf(0f)
    var balanceTop by mutableFloatStateOf(0f)
    var balanceH by mutableFloatStateOf(0f)
    /** The balance figure at rest, and the primary card's balance: the ghost's two ends. */
    var figure by mutableStateOf(Rect.Zero)
    var target by mutableStateOf(Rect.Zero)

    /** Where the balance block's top lands when open. */
    /** Height the wallet list wants (measured even while closed, so the first drag already knows where it lands). */
    var listH by mutableFloatStateOf(0f)
    var minTravel by mutableFloatStateOf(0f)
    /** Lowest the balance may go: just above the tab bar. */
    val maxLanding get() = rootTop + rootH - bottomReserve - balanceH
    /** The balance tucks right under the list: more wallets, further down (never past [maxLanding]). */
    val landing get() = (headerBottom + listH).coerceAtMost(maxLanding).coerceAtLeast(balanceTop + minTravel)
    /** Px the balance travels: the finger moves it 1:1. */
    val range get() = (landing - balanceTop).coerceAtLeast(0f)
    val open get() = progress > 0f

    private var settling: Job? = null

    /** Moves by a finger delta (positive = down = opening); returns the part of [dy] used. */
    private fun dragBy(dy: Float): Float {
        val r = range
        if (r <= 0f) return 0f
        settling?.cancel()
        val old = progress
        progress = (old + dy / r).coerceIn(0f, 1f)
        return (progress - old) * r
    }

    /** Jumps straight to [target] (restoring a state, nothing to animate). */
    fun snapTo(target: Float) { settling?.cancel(); progress = target }

    suspend fun settleTo(target: Float, velocity: Float = 0f) = coroutineScope {
        settling = coroutineContext[Job]
        val r = range.takeIf { it > 0f } ?: 1f
        // A soft bounce landing open; closing settles without one so nothing peeks above the fold.
        val spec = spring<Float>(if (target == 1f) 0.78f else Spring.DampingRatioNoBouncy, Spring.StiffnessMediumLow)
        animate(progress, target, velocity / r, spec) { v, _ -> progress = v.coerceIn(0f, 1.04f) }
    }

    private suspend fun fling(v: Float): Velocity? {
        if (progress <= 0f || progress >= 1f) return null
        settleTo(when { v > FLING -> 1f; v < -FLING -> 0f; progress > 0.5f -> 1f; else -> 0f }, v)
        return Velocity(0f, v)
    }

    /** The activity sheet's progress (0 = docked), so one swipe never runs through Home's middle state. */
    var sheetProgress: () -> Float = { 0f }

    /** Who the current swipe moves; decided on its first delta, cleared when it is released. */
    private enum class Owner { Reveal, Other }
    private var owner: Owner? = null

    /**
     * Home has three rests: wallets (reveal open), the middle (balance + docked activity) and the
     * activity sheet raised. A swipe moves one layer and stops at the middle: closing the wallets never
     * carries on into lifting the activity, and lowering the activity never carries on into the wallets.
     * From the middle, the first direction picks the layer (down = wallets, up = activity).
     */
    private fun ownerFor(dy: Float) = owner ?: when {
        progress > 0f -> Owner.Reveal
        sheetProgress() > 0f -> Owner.Other
        dy > 0f -> Owner.Reveal
        else -> Owner.Other
    }.also { owner = it }

    /** On Home's root. */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y == 0f || ownerFor(available.y) != Owner.Reveal) return Offset.Zero
            // While the swipe is ours, up-drags close the wallets and the rest is swallowed: the sheet never lifts.
            if (available.y < 0f) { dragBy(available.y); return Offset(0f, available.y) }
            return Offset.Zero
        }

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
            if (available.y > 0f && source == NestedScrollSource.UserInput && ownerFor(available.y) == Owner.Reveal) Offset(0f, dragBy(available.y)) else Offset.Zero

        override suspend fun onPreFling(available: Velocity): Velocity {
            val mine = owner == Owner.Reveal
            owner = null
            return if (mine) fling(available.y) ?: Velocity.Zero else Velocity.Zero
        }
    }

    /** On the wallet list: it scrolls first, whatever it leaves over moves the reveal. */
    val listConnection = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
            if (source == NestedScrollSource.UserInput) Offset(0f, dragBy(available.y)) else Offset.Zero

        override suspend fun onPreFling(available: Velocity) = fling(available.y) ?: Velocity.Zero
    }

    companion object {
        private const val FLING = 900f // px/s
    }
}

private fun LayoutCoordinates.rootRect() = positionInRoot().let { Rect(it, Offset(it.x + size.width, it.y + size.height)) }
private fun smooth(a: Float, b: Float, x: Float) = ((x - a) / (b - a)).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

/** The wallet list, filling the space between the header and the landed balance. */
@Composable
internal fun WalletsReveal(
    reveal: HomeReveal,
    wallets: List<RemoteWallet>?,
    balances: Map<String, Long?>,
    code: String,
    rates: Rates?,
    hidden: Boolean,
    onOpen: (RemoteWallet) -> Unit,
    onSetPrimary: (RemoteWallet) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
    /** Set while picking where to sweep this wallet's funds: the list turns into a destination picker. */
    sweepFrom: String? = null,
    onSweep: (RemoteWallet) -> Unit = {},
    onSweepTo: (RemoteWallet) -> Unit = {},
    onSweepCancel: () -> Unit = {},
    onRename: (RemoteWallet, String) -> Unit = { _, _ -> },
) {
    val top = reveal.headerBottom - reveal.rootTop
    val bottom = reveal.maxLanding - reveal.rootTop
    if (bottom - top <= 0f) return
    Column(
        Modifier.fillMaxWidth()
            .layout { m, c ->
                // Wraps its content up to the room above the tab bar; only placed (drawn, touchable) while open.
                val p = m.measure(c.copy(minHeight = 0, maxHeight = (bottom - top).toInt().coerceAtLeast(0)))
                reveal.listH = p.height.toFloat()
                layout(p.width, p.height) { if (reveal.open) p.place(0, top.toInt()) }
            }
            .graphicsLayer { alpha = smooth(0.05f, 0.35f, reveal.progress) }
            .testTag("home-wallets"),
    ) {
        AnimatedContent(
            sweepFrom != null,
            transitionSpec = {
                (fadeIn(tween(220, 60)) + slideInVertically(spring(0.85f, Spring.StiffnessMediumLow)) { -it / 2 })
                    .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(180)) { it / 3 })
            },
            label = "sweepBanner",
        ) { picking ->
            if (picking) SweepPickBanner(onSweepCancel)
            else {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).graphicsLayer {
                    translationY = (1f - smooth(0f, 0.6f, reveal.progress)) * -16.dp.toPx()
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(Modifier.weight(1f).clip(CircleShape).clickable(onClick = onManage).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Txt(stringResource(R.string.settings_remoteWallets), size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
                    LwIcon(L.drawable.lucide_ic_chevron_right, size = 16.dp, tint = Lw.MutedFg)
                }
                Row(
                    Modifier.height(40.dp).glass(CircleShape, GlassLevel.Regular).clickable(onClick = onAdd).padding(horizontal = 16.dp).testTag("home-wallets-add"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LwIcon(L.drawable.lucide_ic_plus, size = 18.dp)
                    Spacer(Modifier.width(8.dp))
                    Txt(stringResource(R.string.rw_add), size = 15.sp, weight = FontWeight.SemiBold)
                }
            }
            }
        }
        when {
            wallets == null -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) { Spinner() }
            wallets.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).glass(CircleShape), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_wallet, size = 28.dp, tint = Lw.MutedFg) }
                Spacer(Modifier.height(12.dp))
                MutedText(stringResource(R.string.remoteWallets_list_emptyTitle))
            }
            else -> {
                val sorted = wallets.sortedWith(compareBy<RemoteWallet> { it.isDefault }.thenBy { it.status == "ACTIVE" }) // primary last: right above the balance
                LazyColumn(
                    Modifier.fillMaxWidth().nestedScroll(reveal.listConnection),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 0.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(sorted, key = { _, w -> w.id }) { i, w ->
                        Box(
                            Modifier.animateItem(placementSpec = spring(0.8f, Spring.StiffnessMediumLow)).graphicsLayer {
                                // The deck fans out from under the header, one card a beat after the other.
                                val d = sorted.size - 1 - i // the primary (nearest the balance) leads
                                val t = smooth(0.12f + d * 0.06f, 0.7f + d * 0.06f, reveal.progress)
                                alpha = t
                                translationY = (1f - t) * -(18.dp.toPx() * (d + 1))
                                val s = 0.92f + 0.08f * t
                                scaleX = s; scaleY = s
                            },
                        ) {
                            WalletRevealCard(
                                w, if (w.status == "ACTIVE") balances[w.id] else null, w.status == "ACTIVE" && !balances.containsKey(w.id),
                                code, rates, hidden, reveal, { onOpen(w) }, { onSetPrimary(w) },
                                sweepFrom, { onSweep(w) }, { onSweepTo(w) }, { name -> onRename(w, name) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * The primary reads as selected (brand ring and wash, full colour). The rest sit back, desaturated and
 * dimmed; tapping one makes it primary, and the two cards cross-fade their states. Long press opens details.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WalletRevealCard(
    w: RemoteWallet, balance: Long?, loading: Boolean, code: String, rates: Rates?, hidden: Boolean,
    reveal: HomeReveal, onOpen: () -> Unit, onSetPrimary: () -> Unit,
    sweepFrom: String?, onSweep: () -> Unit, onSweepTo: () -> Unit, onRename: (String) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val picking = sweepFrom != null
    val isSource = w.id == sweepFrom
    val brand = LocalBrand.current
    val haptics = LocalHapticFeedback.current
    val lncurl = w.provider == "lncurl"
    val active = w.status == "ACTIVE"
    // While picking a sweep destination, every eligible target lights up and the source steps back.
    val target = picking && !isSource && w.status == "ACTIVE"
    val sel by animateFloatAsState(if (if (picking) target else w.isDefault) 1f else 0f, spring(0.85f, Spring.StiffnessMediumLow), label = "selected")
    val pulse = if (target) rememberInfiniteTransition(label = "pick").animateFloat(0.35f, 0.85f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "ring").value else 0.6f
    val accent = lerp(Lw.MutedFg, if (lncurl) Lw.Gold else Lw.NwcPurple, sel)
    val shape = RoundedCornerShape(24.dp)
    val rest = if (isSource) 0.35f else if (active) 0.55f else 0.35f
    Box {
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { alpha = rest + (1f - rest) * sel }
            .glass(shape, if (sel > 0.5f) GlassLevel.Regular else GlassLevel.Thin, tint = brand.t400.copy(alpha = 0.10f * sel))
            .border(1.dp, lerp(Color.White.copy(alpha = 0.05f), brand.t400.copy(alpha = if (target) pulse else 0.6f), sel), shape)
            .combinedClickable(
                onClick = {
                    when {
                        picking -> if (target) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onSweepTo() }
                        w.isDefault || !active -> onOpen()
                        else -> { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSetPrimary() }
                    }
                },
                // Hold for the wallet's menu.
                onLongClick = if (picking) null else ({ haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true }),
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .semantics { selected = w.isDefault }
            .testTag("home-wallet-${w.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.16f + 0.18f * sel), accent.copy(alpha = 0.06f + 0.04f * sel)))),
            contentAlignment = Alignment.Center,
        ) {
            LwIcon(if (lncurl) L.drawable.lucide_ic_zap else L.drawable.lucide_ic_link, size = 20.dp, tint = accent)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(w.name, size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1, color = lerp(Lw.MutedFg, Lw.Foreground, sel))
            Spacer(Modifier.height(4.dp))
            Txt(
                when {
                    isSource -> stringResource(R.string.sweep_from)
                    w.status == "DEAD" -> stringResource(R.string.rw_dead)
                    w.status == "DISABLED" -> stringResource(R.string.remoteWallets_paused)
                    else -> if (lncurl) "LNCurl" else "NWC"
                },
                size = 12.sp, weight = FontWeight.Medium, color = if (w.status == "DEAD") Lw.Red.copy(alpha = 0.8f) else accent,
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(contentAlignment = Alignment.CenterEnd) {
            val figure = lerp(Lw.MutedFg, Lw.Foreground, sel)
            when {
                loading -> Spinner(16.dp, Lw.MutedFg)
                balance != null -> {
                    val m = Modifier
                        // The primary's figure is where the ghost lands: measured at rest, revealed as it arrives.
                        .then(if (w.isDefault) Modifier.onGloballyPositioned { reveal.target = it.rootRect() } else Modifier)
                        .graphicsLayer { if (w.isDefault) alpha = 0.15f + 0.85f * smooth(0.78f, 1f, reveal.progress) }
                        .testTag("home-wallet-balance-${w.name}")
                    // Live updates roll digit by digit, like the main balance.
                    if (hidden) Txt("•••••", m, size = CARD_FIGURE, weight = FontWeight.SemiBold, color = figure)
                    else Odometer(Currencies.formatBalance(balance, code, rates), m, CARD_FIGURE, 0.sp, figure, fromZero = false, lineHeight = 24.sp)
                }
                else -> Txt("—", size = CARD_FIGURE, color = Lw.MutedFg)
            }
        }
    }
    if (menu) WalletActionsSheet(
        w, if (hidden || balance == null) null else Currencies.formatBalance(balance, code, rates),
        canSweep = active && (balance ?: 0L) > 0L,
        onDismiss = { menu = false }, onDetails = onOpen, onSweep = onSweep,
        onSetPrimary = if (active && !w.isDefault) onSetPrimary else null,
        onRename = { renaming = true },
    )
    if (renaming) TextPrompt(stringResource(R.string.rw_rename), w.name, onDismiss = { renaming = false }) { v ->
        renaming = false
        v.trim().takeIf { it.isNotEmpty() && it != w.name }?.let(onRename)
    }
    }
}

private val CARD_FIGURE = 18.sp

/**
 * The ghost: a translucent copy of the balance that lifts off it, arcs into the primary card's figure and
 * shrinks to its size, fading as it lands. Pure function of [HomeReveal.progress], so it scrubs with the finger.
 */
@Composable
internal fun BalanceGhost(reveal: HomeReveal, text: String, size: TextUnit) {
    val from = reveal.figure
    val to = reveal.target
    if (from == Rect.Zero || to == Rect.Zero) return
    Box(
        Modifier.offset { IntOffset(from.left.toInt(), (from.top - reveal.rootTop).toInt()) }
            .layout { m, c ->
                val p = m.measure(c.copy(minWidth = from.width.toInt(), maxWidth = from.width.toInt(), minHeight = from.height.toInt(), maxHeight = from.height.toInt()))
                layout(p.width, p.height) { p.place(0, 0) }
            }
            .graphicsLayer {
                val p = reveal.progress.coerceIn(0f, 1f)
                val t = FastOutSlowInEasing.transform(p)
                // Start rides along with the real figure as it slides down; end is the card's figure.
                val sx = from.center.x
                val sy = from.center.y + p * reveal.range
                val x = sx + (to.center.x - sx) * t
                val y = sy + (to.center.y - sy) * t - sin(PI * t).toFloat() * 36.dp.toPx()
                translationX = x - from.center.x
                translationY = y - from.center.y
                val s = 1f + (CARD_FIGURE.value / size.value - 1f) * t
                scaleX = s; scaleY = s
                alpha = 0.55f * smooth(0.02f, 0.14f, p) * (1f - smooth(0.74f, 0.98f, p))
                if (Build.VERSION.SDK_INT >= 31) {
                    val b = sin(PI * p).toFloat() * 3.dp.toPx()
                    renderEffect = if (b > 0.5f) BlurEffect(b, b, TileMode.Decal) else null
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, size = size, weight = FontWeight.SemiBold, color = Lw.Foreground, lineHeight = 60.sp, letterSpacing = (-1.5).sp, maxLines = 1)
    }
}

/** Measures [this] node at rest in root space (before any reveal translation applied after it). */
internal fun Modifier.measureRoot(onRect: (Rect) -> Unit) = onGloballyPositioned { onRect(it.rootRect()) }

/**
 * [from] rolls into [to] like an odometer wheel when [flipped]: only the words that differ roll (the
 * shared prefix/suffix, e.g. " balance", stays put), and the wheel's width eases between the two.
 */
@Composable
internal fun RollingLabel(from: String, to: String, flipped: Boolean, size: TextUnit, color: Color, weight: FontWeight = FontWeight.Medium) {
    val t by animateFloatAsState(if (flipped) 1f else 0f, tween(560, easing = FastOutSlowInEasing), label = "roll")
    val fw = from.split(' ')
    val tw = to.split(' ')
    val head = fw.zip(tw).takeWhile { (x, y) -> x == y }.size
    val tail = fw.drop(head).reversed().zip(tw.drop(head).reversed()).takeWhile { (x, y) -> x == y }.size
    val pre = fw.take(head).joinToString(" ").let { if (it.isEmpty()) it else "$it " }
    val suf = fw.takeLast(tail).joinToString(" ").let { if (it.isEmpty()) it else " $it" }
    val a = fw.subList(head, fw.size - tail).joinToString(" ")
    val b = tw.subList(head, tw.size - tail).joinToString(" ")
    Row(Modifier.semantics(mergeDescendants = true) {}) {
        if (pre.isNotEmpty()) Txt(pre, size = size, weight = weight, color = color, maxLines = 1)
        Layout(
            content = {
                Txt(a, size = size, weight = weight, color = color, maxLines = 1)
                Txt(b, size = size, weight = weight, color = color, maxLines = 1)
            },
            modifier = Modifier.clipToBounds(),
        ) { ms, c ->
            val pa = ms[0].measure(c.copy(minWidth = 0))
            val pb = ms[1].measure(c.copy(minWidth = 0))
            val h = maxOf(pa.height, pb.height)
            layout((pa.width + (pb.width - pa.width) * t).toInt(), h) {
                // Old face rolls up and out, new face rolls up from below (the odometer's direction).
                pa.placeWithLayer(0, 0) { translationY = -t * h; alpha = 1f - t }
                pb.placeWithLayer(0, 0) { translationY = (1f - t) * h; alpha = t }
            }
        }
        if (suf.isNotEmpty()) Txt(suf, size = size, weight = weight, color = color, maxLines = 1)
    }
}

/** Replaces the list title while picking a sweep destination: what to do, and a way out. */
@Composable
private fun SweepPickBanner(onCancel: () -> Unit) {
    val brand = LocalBrand.current
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 4.dp)
            .glass(RoundedCornerShape(20.dp), GlassLevel.Thin, tint = brand.t400.copy(alpha = 0.10f))
            .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).testTag("sweep-pick"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LwIcon(L.drawable.lucide_ic_arrow_right_left, size = 18.dp, tint = brand.t400)
        Spacer(Modifier.width(10.dp))
        Txt(stringResource(R.string.sweep_pick), Modifier.weight(1f), size = 14.sp, weight = FontWeight.Medium, lineHeight = 18.sp)
        Spacer(Modifier.width(8.dp))
        Txt(
            stringResource(R.string.common_cancel),
            Modifier.height(36.dp).clip(CircleShape).border(1.dp, Lw.Foreground.copy(alpha = 0.18f), CircleShape).clickable(onClick = onCancel)
                .padding(horizontal = 14.dp).wrapContentHeight().testTag("sweep-cancel"),
            size = 14.sp, weight = FontWeight.SemiBold,
        )
    }
}

/**
 * The long-press menu, as a bottom sheet (Spotify-style): the wallet up top so it's clear what the
 * actions apply to, then one big row per action. The sheet slides away before the action runs.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletActionsSheet(w: RemoteWallet, balance: String?, canSweep: Boolean, onDismiss: () -> Unit, onDetails: () -> Unit, onSweep: () -> Unit, onSetPrimary: (() -> Unit)?, onRename: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val brand = LocalBrand.current
    val lncurl = w.provider == "lncurl"
    val accent = if (lncurl) Lw.Gold else Lw.NwcPurple
    fun then(action: () -> Unit) { scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); action() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = Glass.Dialog,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) },
        modifier = Modifier.testTag("home-wallet-menu"),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.36f), accent.copy(alpha = 0.10f)))),
                contentAlignment = Alignment.Center,
            ) { LwIcon(if (lncurl) L.drawable.lucide_ic_zap else L.drawable.lucide_ic_link, size = 24.dp, tint = accent) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(w.name, size = 17.sp, weight = FontWeight.SemiBold, maxLines = 1)
                Txt(listOfNotNull(if (lncurl) "LNCurl" else "NWC", balance?.let { "$it sats" }).joinToString(" · "), size = 13.sp, color = Lw.MutedFg, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        // Only for active wallets that aren't the primary already.
        if (onSetPrimary != null) SheetAction(L.drawable.lucide_ic_circle_check, stringResource(R.string.remoteWallets_list_setPrimary), brand.t400, "home-wallet-menu-primary") { then(onSetPrimary) }
        SheetAction(L.drawable.lucide_ic_pencil, stringResource(R.string.rw_rename), Lw.Foreground, "home-wallet-menu-rename") { then(onRename) }
        SheetAction(L.drawable.lucide_ic_info, stringResource(R.string.sweep_menu_details), Lw.Foreground, "home-wallet-menu-details") { then(onDetails) }
        if (canSweep) SheetAction(L.drawable.lucide_ic_arrow_right_left, stringResource(R.string.sweep_menu), brand.t400, "home-wallet-menu-sweep") { then(onSweep) }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

@Composable
private fun SheetAction(icon: Int, label: String, tint: Color, tag: String, onClick: () -> Unit) =
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LwIcon(icon, size = 22.dp, tint = tint)
        Spacer(Modifier.width(18.dp))
        Txt(label, size = 16.sp, weight = FontWeight.Medium)
    }
