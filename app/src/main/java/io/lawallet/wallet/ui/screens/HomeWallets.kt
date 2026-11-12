package io.lawallet.wallet.ui.screens

import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
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
    val landing get() = rootTop + rootH - bottomReserve - balanceH
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

    /** On Home's root: down-drags nothing else used open it; while open, up-drags close it first. */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource) =
            if (available.y < 0f && progress > 0f && source == NestedScrollSource.UserInput) Offset(0f, dragBy(available.y)) else Offset.Zero

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
            if (available.y > 0f && source == NestedScrollSource.UserInput) Offset(0f, dragBy(available.y)) else Offset.Zero

        override suspend fun onPreFling(available: Velocity) = fling(available.y) ?: Velocity.Zero
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
    busy: String?,
    onOpen: (RemoteWallet) -> Unit,
    onSetPrimary: (RemoteWallet) -> Unit,
    onAdd: () -> Unit,
    onManage: () -> Unit,
) {
    val top = reveal.headerBottom - reveal.rootTop
    val bottom = reveal.landing - reveal.rootTop
    if (bottom - top <= 0f) return
    Column(
        Modifier.fillMaxWidth()
            .layoutBetween(top, bottom)
            .graphicsLayer { alpha = smooth(0.05f, 0.35f, reveal.progress) }
            .testTag("home-wallets"),
    ) {
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
                Modifier.glass(CircleShape, GlassLevel.Thin).clickable(onClick = onAdd).padding(horizontal = 12.dp, vertical = 7.dp).testTag("home-wallets-add"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LwIcon(L.drawable.lucide_ic_plus, size = 14.dp)
                Spacer(Modifier.width(6.dp))
                Txt(stringResource(R.string.rw_add), size = 13.sp, weight = FontWeight.Medium)
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
                val sorted = wallets.sortedWith(compareByDescending<RemoteWallet> { it.isDefault }.thenBy { it.status != "ACTIVE" })
                LazyColumn(
                    Modifier.fillMaxWidth().nestedScroll(reveal.listConnection),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    itemsIndexed(sorted, key = { _, w -> w.id }) { i, w ->
                        Box(
                            Modifier.animateItem(placementSpec = spring(0.8f, Spring.StiffnessMediumLow)).graphicsLayer {
                                // The deck fans out from under the header, one card a beat after the other.
                                val t = smooth(0.12f + i * 0.06f, 0.7f + i * 0.06f, reveal.progress)
                                alpha = t
                                translationY = (1f - t) * -(18.dp.toPx() * (i + 1))
                                val s = 0.92f + 0.08f * t
                                scaleX = s; scaleY = s
                            },
                        ) {
                            WalletRevealCard(
                                w, if (w.status == "ACTIVE") balances[w.id] else null, w.status == "ACTIVE" && !balances.containsKey(w.id),
                                code, rates, hidden, busy == w.id, reveal, { onOpen(w) }, { onSetPrimary(w) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Places the content in the band [top]..[bottom] (px, Home-root space) without measuring it twice. */
private fun Modifier.layoutBetween(top: Float, bottom: Float) = this.then(
    Modifier.layout { m, c ->
        val h = (bottom - top).toInt().coerceAtLeast(0)
        val p = m.measure(c.copy(minHeight = h, maxHeight = h))
        layout(p.width, h) { p.place(0, top.toInt()) }
    },
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WalletRevealCard(
    w: RemoteWallet, balance: Long?, loading: Boolean, code: String, rates: Rates?, hidden: Boolean, busy: Boolean,
    reveal: HomeReveal, onClick: () -> Unit, onSetPrimary: () -> Unit,
) {
    val brand = LocalBrand.current
    val haptics = LocalHapticFeedback.current
    val lncurl = w.provider == "lncurl"
    val accent = if (lncurl) Lw.Gold else Lw.NwcPurple
    val active = w.status == "ACTIVE"
    val canPromote = active && !w.isDefault
    val ring by animateColorAsState(if (w.isDefault) brand.t400.copy(alpha = 0.55f) else Color.White.copy(alpha = 0.06f), tween(380), label = "ring")
    val wash by animateColorAsState(if (w.isDefault) brand.t400.copy(alpha = 0.10f) else Color.Transparent, tween(380), label = "wash")
    val shape = RoundedCornerShape(24.dp)
    Row(
        Modifier.fillMaxWidth()
            .glass(shape, GlassLevel.Regular, tint = wash)
            .border(1.dp, ring, shape)
            .combinedClickable(
                onClick = onClick,
                onLongClick = if (canPromote) ({ haptics.performHapticFeedback(HapticFeedbackType.LongPress); onSetPrimary() }) else null,
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
            .testTag("home-wallet-${w.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.34f), accent.copy(alpha = 0.10f)))),
            contentAlignment = Alignment.Center,
        ) {
            LwIcon(if (lncurl) L.drawable.lucide_ic_zap else L.drawable.lucide_ic_link, size = 20.dp, tint = if (active) accent else Lw.MutedFg)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(w.name, size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1, color = if (active) Lw.Foreground else Lw.MutedFg)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                when (w.status) {
                    "DEAD" -> Chip(stringResource(R.string.rw_dead), Lw.Red)
                    "DISABLED" -> Chip(stringResource(R.string.remoteWallets_paused), Lw.MutedFg)
                    else -> Chip(if (lncurl) "LNCurl" else "NWC", accent)
                }
                // The primary badge pops in where the "Set primary" action was, and back.
                AnimatedContent(
                    when { busy -> 2; w.isDefault -> 1; canPromote -> 0; else -> -1 },
                    transitionSpec = {
                        (scaleIn(spring(0.55f, Spring.StiffnessMedium), initialScale = 0.6f) + fadeIn(tween(160)))
                            .togetherWith(scaleOut(tween(140), targetScale = 0.8f) + fadeOut(tween(120)))
                    },
                    contentAlignment = Alignment.CenterStart,
                    label = "primary",
                ) { s ->
                    when (s) {
                        2 -> Box(Modifier.padding(horizontal = 6.dp)) { Spinner(14.dp, brand.t400) }
                        1 -> Row(
                            Modifier.clip(CircleShape).background(brand.t400.copy(alpha = 0.18f)).padding(start = 7.dp, end = 9.dp, top = 3.dp, bottom = 3.dp).testTag("home-wallet-primary"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            LwIcon(L.drawable.lucide_ic_check, size = 12.dp, tint = brand.t400)
                            Spacer(Modifier.width(4.dp))
                            Txt(stringResource(R.string.remoteWallets_list_primary), size = 11.sp, weight = FontWeight.SemiBold, color = brand.t400)
                        }
                        0 -> Txt(
                            stringResource(R.string.remoteWallets_list_setPrimary),
                            Modifier.clip(CircleShape).border(1.dp, Lw.Foreground.copy(alpha = 0.16f), CircleShape).clickable(onClick = onSetPrimary)
                                .padding(horizontal = 9.dp, vertical = 3.dp).testTag("home-wallet-set-primary-${w.name}"),
                            size = 11.sp, weight = FontWeight.Medium, color = Lw.Foreground.copy(alpha = 0.8f),
                        )
                        else -> Spacer(Modifier.size(0.dp))
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Box(contentAlignment = Alignment.CenterEnd) {
            when {
                loading -> Spinner(16.dp, Lw.MutedFg)
                balance != null -> Txt(
                    if (hidden) "•••••" else Currencies.formatBalance(balance, code, rates),
                    Modifier
                        // The primary's figure is where the ghost lands: measured at rest, revealed as it arrives.
                        .then(if (w.isDefault) Modifier.onGloballyPositioned { reveal.target = it.rootRect() } else Modifier)
                        .graphicsLayer { if (w.isDefault) alpha = 0.15f + 0.85f * smooth(0.78f, 1f, reveal.progress) }
                        .testTag("home-wallet-balance-${w.name}"),
                    size = CARD_FIGURE, weight = FontWeight.SemiBold,
                )
                else -> Txt("—", size = CARD_FIGURE, color = Lw.MutedFg)
            }
        }
    }
}

private val CARD_FIGURE = 18.sp

@Composable
private fun Chip(text: String, color: Color) =
    Txt(text, Modifier.clip(CircleShape).background(color.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp), size = 11.sp, weight = FontWeight.SemiBold, color = color)

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
