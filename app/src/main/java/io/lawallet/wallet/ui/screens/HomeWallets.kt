package io.lawallet.wallet.ui.screens

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import android.os.Build
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.animation.Crossfade
import io.lawallet.wallet.app.FlowStore
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
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
import androidx.compose.animation.AnimatedVisibility
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.BtnVariant
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
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
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.toSize
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
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
import io.lawallet.wallet.ui.components.WalletMark
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
    /** The wallet pill at rest, and the selected card where the open list lands it: [WalletPillMorph]'s two ends. */
    var pill by mutableStateOf(Rect.Zero)
    var card by mutableStateOf(Rect.Zero)
    /** The pill's mark and name at rest (root space), and the selected card's, relative to the card: they morph across. */
    var pillMark by mutableStateOf(Rect.Zero)
    var pillName by mutableStateOf(Rect.Zero)
    var cardMark by mutableStateOf(Rect.Zero)
    var cardName by mutableStateOf(Rect.Zero)
    /** A pill shows above: the selected card (nearest the balance) comes in as the pill's shape lands, not on its own. */
    var hero by mutableStateOf(false)
    /** A wallet whose long-press menu should open (the pill's long press asks its card). */
    var menuFor by mutableStateOf<String?>(null)

    /** Where the balance block's top lands when open. */
    /** Height the wallet list wants (measured even while closed, so the first drag already knows where it lands). */
    var listH by mutableFloatStateOf(0f)
    var minTravel by mutableFloatStateOf(0f)
    /** Px at the balance block's top that fade out as the list opens: the list may cover them. */
    var tuckTop by mutableFloatStateOf(0f)
    /** Lowest the list may reach (the balance's visible top): the balance then sits just above the tab bar. */
    val maxLanding get() = rootTop + rootH - bottomReserve - balanceH + tuckTop
    /** The balance tucks right under the list: more wallets, further down (never past [maxLanding]). */
    val landing get() = (headerBottom + listH).coerceAtMost(maxLanding).coerceAtLeast(balanceTop + tuckTop + minTravel)
    /** Px the balance travels: the finger moves it 1:1. */
    val range get() = (landing - tuckTop - balanceTop).coerceAtLeast(0f)
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
    /** A finger is down on the wallet list: it scrolls first, and only its leftover moves the reveal. */
    var inList = false

    private fun ownerFor(dy: Float) = owner ?: when {
        progress > 0f -> Owner.Reveal
        sheetProgress() > 0f -> Owner.Other
        dy > 0f -> Owner.Reveal
        else -> Owner.Other
    }.also { owner = it }

    /** On Home's root. */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            if (source != NestedScrollSource.UserInput || available.y == 0f || ownerFor(available.y) != Owner.Reveal || inList) return Offset.Zero
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
internal fun smooth(a: Float, b: Float, x: Float) = ((x - a) / (b - a)).coerceIn(0f, 1f).let { it * it * (3 - 2 * it) }

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
    /** "nwc" or "lncurl", picked in the add sheet. */
    onAdd: (String) -> Unit,
    onManage: () -> Unit,
    /** Set while picking where to sweep this wallet's funds: the list turns into a destination picker. */
    sweepFrom: String? = null,
    onSweep: (RemoteWallet) -> Unit = {},
    onSweepTo: (RemoteWallet) -> Unit = {},
    onSweepCancel: () -> Unit = {},
    onRename: (RemoteWallet, String) -> Unit = { _, _ -> },
    onTransfer: (RemoteWallet) -> Unit = {},
    onDelete: (RemoteWallet, Long?) -> Unit = { _, _ -> },
    /** Just added: its card plays the entrance once the list is open. */
    newWalletId: String? = null,
    /** Being added from here: a setting-up card until LaWallet answers, then the wallet under the same key. */
    pending: FlowStore.PendingWallet? = null,
    /** Where the Add wallet button goes (the caller's bottom slot); it rises in as the list opens. */
    addButton: Modifier = Modifier,
    /** Bottom-left under the list, across from the total. */
    footerStart: (@Composable () -> Unit)? = null,
    /** The address is locked to its wallet: only that wallet shows. */
    locked: Boolean = false,
    /** Locks or unlocks it (the selected card's lock button); null hides the button. */
    onLock: (() -> Unit)? = null,
    /** Being deleted: folded out of the list before they leave it, the rest closing over them. */
    leaving: Set<String> = emptySet(),
) {
    val created = pending?.created
    val placeholder = pending?.takeIf { created == null }?.let {
        RemoteWallet(it.key, it.name, status = PENDING, isDefault = it.selected, provider = if (it.lncurl) "lncurl" else null)
    }
    val all = wallets?.let { ws -> if (placeholder == null) ws else ws.map { if (placeholder.isDefault) it.copy(isDefault = false) else it } + placeholder }
    val sorted = all?.sortedWith(compareBy<RemoteWallet> { it.isDefault }.thenBy { it.status == "ACTIVE" || it.status == PENDING }) // primary last: right above the balance

    val keyOf = { w: RemoteWallet -> if (created != null && w.id == created.id) pending.key else w.id }
    // Locked: just its wallet shows; the rest fold away (and back), so none can be tapped into its place by accident.
    val folds = locked && sorted.orEmpty().any { it.isDefault }
    val folded = { w: RemoteWallet -> (folds && !w.isDefault) || w.id in leaving }
    var adding by remember { mutableStateOf(false) }
    if (adding) AddWalletSheet({ adding = false }, onAdd)
    // Derived: read every frame of the reveal, but only these flips recompose.
    val open by remember { derivedStateOf { reveal.open } }
    val ready by remember { derivedStateOf { reveal.progress > 0.5f } }
    if (open && sweepFrom == null) LwButton(
        stringResource(R.string.rw_add), { adding = true },
        addButton.fillMaxWidth()
            .graphicsLayer { val p = smooth(0.45f, 1f, reveal.progress); alpha = p; translationY = (1f - p) * 28.dp.toPx() }
            // Over the activity sheet's drag area like Home's other floating buttons: drags here still move the list.
            .nestedScroll(reveal.connection).scrollable(rememberScrollableState { 0f }, Orientation.Vertical)
            .testTag("home-wallets-add"),
        BtnVariant.Theme, enabled = ready && placeholder == null, height = 58.dp, icon = L.drawable.lucide_ic_plus,
    )
    RevealDeck(
        reveal, sorted, keyOf, Modifier.testTag("home-wallets"), hidden = folded,
        header = {
            AnimatedContent(
                sweepFrom != null,
                transitionSpec = {
                    (fadeIn(tween(220, 60)) + slideInVertically(spring(0.85f, Spring.StiffnessMediumLow)) { -it / 2 })
                        .togetherWith(fadeOut(tween(120)) + slideOutVertically(tween(180)) { it / 3 })
                },
                label = "sweepBanner",
            ) { picking ->
                if (picking) SweepPickBanner(onSweepCancel)
                // Add lives at the bottom, where the tab bar was (the bar steps aside while the list is open).
                else Column {
                    DeckTitle(reveal, stringResource(R.string.settings_remoteWallets), onManage, null)
                    // Where the other wallets would be: how to get them back.
                    AnimatedVisibility(locked, enter = fadeIn(tween(220)) + expandVertically(), exit = fadeOut(tween(150)) + shrinkVertically()) {
                        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 4.dp).testTag("home-wallets-locked"), verticalAlignment = Alignment.CenterVertically) {
                            LwIcon(L.drawable.lucide_ic_lock, size = 14.dp, tint = Lw.MutedFg)
                            Spacer(Modifier.width(8.dp))
                            Txt(stringResource(R.string.rw_locked_note), size = 13.sp, color = Lw.MutedFg)
                        }
                    }
                }
            }
        },
        empty = { DeckEmpty(L.drawable.lucide_ic_wallet, stringResource(R.string.remoteWallets_list_emptyTitle)) },
        footer = {
            val many = sorted.orEmpty().count { !folded(it) } > 1
            if (many || footerStart != null) WalletsTotal(sorted.orEmpty().filter { it.status == "ACTIVE" }.sumOf { balances[it.id] ?: 0L }, code, rates, hidden, reveal, many, footerStart)
        },
    ) { w ->
        val adding = pending != null && keyOf(w) == pending.key
        NewWalletEntrance(w.id == newWalletId || adding, RoundedCornerShape(24.dp), start = { snapshotFlow { reveal.progress >= 0.97f }.first { it }; delay(60) }, ready = w.status != PENDING) {
            WalletRevealCard(
                w, if (w.status == "ACTIVE") balances[w.id] else null, w.status == PENDING || (w.status == "ACTIVE" && !balances.containsKey(w.id)),
                code, rates, hidden, reveal, { onOpen(w) }, { onSetPrimary(w) },
                sweepFrom, { onSweep(w) }, { onSweepTo(w) }, { name -> onRename(w, name) }, { onTransfer(w) }, { onDelete(w, if (w.status == "ACTIVE") balances[w.id] else null) },
                lock = if (w.isDefault && onLock != null && w.status == "ACTIVE") locked else null, onLock = { onLock?.invoke() },
            )
        }
    }
}

/**
 * The deck both Home pages reveal (remote wallets on Lightning, onchain wallets on Onchain): [items] come
 * sorted with the primary last, right above the balance, and fan out of it as the reveal opens.
 */
@Composable
internal fun <T> RevealDeck(
    reveal: HomeReveal,
    items: List<T>?,
    key: (T) -> Any,
    modifier: Modifier = Modifier,
    header: @Composable () -> Unit,
    empty: @Composable () -> Unit,
    footer: @Composable () -> Unit = {},
    /** Cards folded out of the list (shrinking and fading, the rest closing over them) without leaving it. */
    hidden: (T) -> Boolean = { false },
    card: @Composable (T) -> Unit,
) {
    val top = reveal.headerBottom - reveal.rootTop
    val bottom = reveal.maxLanding - reveal.rootTop
    if (bottom - top <= 0f) return
    Column(
        modifier.fillMaxWidth()
            .layout { m, c ->
                // Wraps its content up to the room above the tab bar; only placed (drawn, touchable) while open.
                val p = m.measure(c.copy(minHeight = 0, maxHeight = (bottom - top).toInt().coerceAtLeast(0)))
                reveal.listH = p.height.toFloat()
                layout(p.width, p.height) { if (reveal.open) p.place(0, top.toInt()) }
            }
            .graphicsLayer { alpha = smooth(0.05f, 0.35f, reveal.progress) },
    ) {
        header()
        when {
            items == null -> Box(Modifier.fillMaxWidth().padding(top = 32.dp), contentAlignment = Alignment.Center) { Spinner() }
            items.isEmpty() -> empty()
            else -> {
                Column(Modifier.weight(1f, fill = false)) {
                // Laid out from the bottom (reverseLayout), so the primary stays right above the balance even when
                // the room changes (switching address moves the balance); item 0 is the primary.
                val listState = rememberLazyListState()
                // Back to the primary on a new list, a new primary (switching address re-picks it) and every close.
                LaunchedEffect(listState, items.size, key(items.last())) {
                    listState.scrollToItem(0)
                    snapshotFlow { reveal.open }.collect { if (!it) listState.scrollToItem(0) }
                }
                // Fades the bottom edge only while cards run past it; at the primary the bottom is just padding.
                val fade by animateFloatAsState(if (listState.canScrollBackward) 1f else 0f, tween(180), label = "deck-fade")
                LazyColumn(
                    // Shrinks (and scrolls) before it pushes the total out of the room.
                    Modifier.weight(1f, fill = false).fillMaxWidth().nestedScroll(reveal.listConnection).pointerInput(reveal) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            reveal.inList = true
                            try { do { val e = awaitPointerEvent(PointerEventPass.Initial) } while (e.changes.any { it.pressed }) }
                            finally { reveal.inList = false }
                        }
                    }.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }.drawWithContent {
                        drawContent()
                        if (fade > 0f) drawRect(
                            Brush.verticalGradient(0f to Color.Black, 1f to Color.Black.copy(alpha = 1f - fade), startY = size.height - 36.dp.toPx(), endY = size.height),
                            blendMode = BlendMode.DstIn,
                        )
                    },
                    state = listState,
                    reverseLayout = true,
                    // Bottom room so a bounce or the cards' scale never clips sharply at the edge.
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 12.dp),
                ) {
                    itemsIndexed(items.asReversed(), key = { _, w -> key(w) }) { d, w ->
                        Box(
                            Modifier.animateItem(placementSpec = spring(0.8f, Spring.StiffnessMediumLow))
                                // The selected card's place in the open list (before its own layer): the pill lands there.
                                .then(if (d == 0) Modifier.onGloballyPositioned { reveal.card = it.rootRect() } else Modifier)
                                .graphicsLayer {
                                // The deck rides down on top of the balance: shifted up by the travel the balance has
                                // left, so the primary sits right above the moving figure from its first frame and
                                // the cards slide out from under the header. At rest (open) the shift is zero.
                                // d: distance from the primary (nearest the balance), which leads
                                // Under a pill, the selected card fades in only as the pill's shape lands on it.
                                val hero = d == 0 && reveal.hero
                                val t = if (hero) smooth(MORPH_LAND, MORPH_DONE, reveal.progress)
                                    // Under a pill, the rest follow its growth instead of racing it.
                                    else deckEntrance(d, if (reveal.hero) smooth(0.2f, 1f, reveal.progress) else reveal.progress)
                                alpha = t
                                translationY = -(1f - reveal.progress) * reveal.range - (1f - t) * 12.dp.toPx() * d
                                val s = if (hero) 1f else 0.92f + 0.08f * t
                                scaleX = s; scaleY = s
                                transformOrigin = TransformOrigin(0.5f, 1f) // grow from the bottom edge: it stays on the balance
                            },
                        ) {
                            // The gap lives in the card's slot (not the list's spacing), so a folded card takes its gap with it.
                            androidx.compose.animation.AnimatedVisibility(
                                !hidden(w),
                                enter = fadeIn(tween(260, 80)) + expandVertically(spring(0.9f, Spring.StiffnessMediumLow), expandFrom = Alignment.Top),
                                exit = fadeOut(tween(160)) + shrinkVertically(spring(0.9f, Spring.StiffnessMediumLow), shrinkTowards = Alignment.Top),
                            ) { Box(if (d > 0) Modifier.padding(bottom = 10.dp) else Modifier) { card(w) } }
                        }
                    }
                }
                footer()
                }
            }
        }
    }
}

/**
 * How far the deck card [d] places from the primary has come in (0 → 1) at reveal [progress]: the nearer cards lead.
 * The lag is capped so every window ends by progress 1. Uncapped, cards past the 6th never reached 1, and stayed
 * shifted up (12dp × d), shrunk and dimmed in the open list, the gaps growing up the list.
 */
internal fun deckEntrance(d: Int, progress: Float): Float {
    val lag = (d * 0.06f).coerceAtMost(0.3f)
    return smooth(0.12f + lag, 0.7f + lag, progress)
}

/** The deck's title row: what the list is (tap to manage, when [onManage]) and an Add button. */
@Composable
internal fun DeckTitle(reveal: HomeReveal, title: String, onManage: (() -> Unit)?, onAdd: (() -> Unit)?, addTag: String = "home-wallets-add", addEnabled: Boolean = true) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 4.dp).graphicsLayer {
            // Under a pill, the row waits until the pill has grown down past it (it would sit right under it).
            val p = if (reveal.hero) smooth(0.45f, 0.8f, reveal.progress) else reveal.progress
            translationY = (1f - smooth(0f, 0.6f, p)) * -16.dp.toPx()
            if (reveal.hero) alpha = p
        },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(Modifier.weight(1f).clip(CircleShape).then(if (onManage != null) Modifier.clickable(onClick = onManage) else Modifier).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(title, size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
            if (onManage != null) LwIcon(L.drawable.lucide_ic_chevron_right, size = 16.dp, tint = Lw.MutedFg)
        }
        if (onAdd != null) Row(
            Modifier.height(40.dp).graphicsLayer { alpha = if (addEnabled) 1f else 0.4f }.glass(CircleShape, GlassLevel.Regular)
                .clickable(enabled = addEnabled, onClick = onAdd).padding(horizontal = 16.dp).testTag(addTag),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LwIcon(L.drawable.lucide_ic_plus, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Txt(stringResource(R.string.rw_add), size = 15.sp, weight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun DeckEmpty(icon: Int, text: String) = Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
    Box(Modifier.size(64.dp).glass(CircleShape), contentAlignment = Alignment.Center) { LwIcon(icon, size = 28.dp, tint = Lw.MutedFg) }
    Spacer(Modifier.height(12.dp))
    MutedText(text)
}

/** Bottom right of the list: every active wallet's balance summed, rolling like the cards as figures arrive. */
@Composable
internal fun WalletsTotal(sats: Long, code: String, rates: Rates?, hidden: Boolean, reveal: HomeReveal, total: Boolean = true, start: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 10.dp)
            .graphicsLayer {
                alpha = smooth(0.5f, 1f, reveal.progress)
                translationY = -(1f - reveal.progress) * reveal.range // moves with the deck
            }.testTag("home-wallets-total"),
        verticalAlignment = Alignment.Bottom,
    ) {
        start?.invoke()
        Spacer(Modifier.weight(1f))
        if (!total) return@Row
        Txt(stringResource(R.string.send_preview_total), Modifier.padding(end = 8.dp, bottom = 2.dp), size = 13.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
        if (hidden) Txt("•••••", size = CARD_FIGURE, weight = FontWeight.SemiBold)
        else Odometer(Currencies.formatBalance(sats, code, rates), Modifier, CARD_FIGURE, 0.sp, Lw.Foreground, fromZero = false, lineHeight = 24.sp)
        Txt(Currencies.unitLabel(code), Modifier.padding(start = 4.dp, bottom = 2.dp), size = 13.sp, color = Lw.MutedFg)
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
    sweepFrom: String?, onSweep: () -> Unit, onSweepTo: () -> Unit, onRename: (String) -> Unit, onTransfer: () -> Unit, onDelete: () -> Unit,
    /** The selected card's lock: whether its address is locked to it; null shows no lock. */
    lock: Boolean? = null, onLock: () -> Unit = {},
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    LaunchedEffect(reveal.menuFor) { if (reveal.menuFor == w.id) { reveal.menuFor = null; menu = true } }
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
    val accent = lerp(Lw.MutedFg, if (lncurl) Lw.LncurlGreen else Lw.NwcPurple, sel)
    val shape = RoundedCornerShape(24.dp)
    val settingUp = w.status == PENDING
    val rest by animateFloatAsState(if (isSource) 0.35f else if (active || settingUp) 0.7f else 0.35f, tween(400), label = "rest")
    Box {
    // Where the mark and name sit inside the card (relative, so the card's own reveal motion doesn't skew them).
    val parts = remember { arrayOfNulls<LayoutCoordinates>(3) } // card, mark, name
    val measureParts = {
        val (row, mark, name) = parts
        if (w.isDefault && row != null && row.isAttached) {
            if (mark != null && mark.isAttached) reveal.cardMark = Rect(row.localPositionOf(mark, Offset.Zero), mark.size.toSize())
            if (name != null && name.isAttached) reveal.cardName = Rect(row.localPositionOf(name, Offset.Zero), name.size.toSize())
        }
    }
    Row(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { parts[0] = it; measureParts() }
            .graphicsLayer { alpha = rest + (1f - rest) * sel }
            .glass(shape, if (sel > 0.5f) GlassLevel.Regular else GlassLevel.Thin, tint = brand.t400.copy(alpha = 0.10f * sel))
            .border(1.dp, lerp(Color.White.copy(alpha = 0.05f), brand.t400.copy(alpha = if (target) pulse else 0.6f), sel), shape)
            .combinedClickable(
                enabled = !settingUp,
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
            Modifier.onGloballyPositioned { parts[1] = it; measureParts() }.size(44.dp).clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.16f + 0.18f * sel), accent.copy(alpha = 0.06f + 0.04f * sel)))),
            contentAlignment = Alignment.Center,
        ) {
            WalletMark(lncurl, 20.dp)
        }
        Spacer(Modifier.width(14.dp))
        // The name takes what it needs (truncating only when it must) and the lock follows it, centred on the card.
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f, fill = false)) {
            Crossfade(w.name, Modifier.onGloballyPositioned { parts[2] = it; measureParts() }, label = "name") { Txt(it, size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1, color = lerp(Lw.MutedFg, Lw.Foreground, sel)) }
            Spacer(Modifier.height(4.dp))
            Crossfade(
                when {
                    settingUp -> stringResource(R.string.rw_setting_up)
                    isSource -> stringResource(R.string.sweep_from)
                    w.status == "DEAD" -> stringResource(R.string.rw_dead)
                    w.status == "DISABLED" -> stringResource(R.string.remoteWallets_paused)
                    else -> if (lncurl) "LNCurl" else "NWC"
                },
                label = "kind",
            ) { Txt(it, size = 12.sp, weight = FontWeight.Medium, color = if (w.status == "DEAD") Lw.Red.copy(alpha = 0.8f) else accent) }
        }
        if (lock != null) LockButton(lock) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); onLock() }
        }
        Spacer(Modifier.width(12.dp))
        AnimatedContent(loading, contentAlignment = Alignment.CenterEnd, transitionSpec = { fadeIn(tween(300)).togetherWith(fadeOut(tween(200))) }, label = "figure") { loading ->
            val figure = lerp(Lw.MutedFg, Lw.Foreground, sel)
            when {
                loading -> Spinner(16.dp, Lw.MutedFg)
                balance != null -> {
                    val m = Modifier
                        // The primary's figure is where the ghost lands: tracked live as the card rides down, revealed as it arrives.
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
        onRename = { renaming = true },
        // Paid from the primary, so the primary can't be its own destination.
        onTransfer = if (active && !w.isDefault) onTransfer else null,
        onDelete = onDelete,
    )
    if (renaming) TextPrompt(stringResource(R.string.rw_rename), w.name, onDismiss = { renaming = false }) { v ->
        renaming = false
        v.trim().takeIf { it.isNotEmpty() && it != w.name }?.let(onRename)
    }
    }
}

/** Locks the address to this wallet (or unlocks it): a small icon button next to the selected card's name. */
@Composable
private fun LockButton(locked: Boolean, onClick: () -> Unit) {
    val label = stringResource(if (locked) R.string.rw_unlock else R.string.rw_lock)
    Box(
        Modifier.padding(start = 4.dp).size(44.dp).clip(CircleShape)
            .clickable(onClickLabel = label, onClick = onClick).testTag("home-wallet-lock"),
        contentAlignment = Alignment.Center,
    ) {
        Crossfade(locked, label = "lock") { l ->
            LwIcon(if (l) L.drawable.lucide_ic_lock else L.drawable.lucide_ic_lock_open, size = 24.dp, tint = if (l) Lw.Foreground else Lw.MutedFg, contentDescription = label)
        }
    }
}

internal val CARD_FIGURE = 18.sp
/** A wallet card still being set up by LaWallet (see [FlowStore.PendingWallet]). */
internal const val PENDING = "PENDING"

/**
 * The ghost: a translucent copy of the balance that lifts off it, arcs into the primary card's figure and
 * shrinks to its size, fading as it lands. Pure function of [HomeReveal.progress], so it scrubs with the finger.
 */
@Composable
internal fun BalanceGhost(reveal: HomeReveal, text: String, size: TextUnit, intoPill: Boolean = false) {
    val from = reveal.figure
    if (intoPill) return PillBoundGhost(reveal, text, size)
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

/**
 * The balance zooming out into the wallet pill as the list opens: a copy of the figure lifts off, shrinks to the pill
 * and fades into it; the pill then grows into the card ([WalletPillMorph]).
 */
@Composable
private fun PillBoundGhost(reveal: HomeReveal, text: String, size: TextUnit) {
    val from = reveal.figure
    val to = reveal.pill
    if (from == Rect.Zero || to == Rect.Zero) return
    Box(
        Modifier.offset { IntOffset(from.left.toInt(), (from.top - reveal.rootTop).toInt()) }
            .layout { m, c ->
                val p = m.measure(c.copy(minWidth = from.width.toInt(), maxWidth = from.width.toInt(), minHeight = from.height.toInt(), maxHeight = from.height.toInt()))
                layout(p.width, p.height) { p.place(0, 0) }
            }
            .graphicsLayer {
                val p = reveal.progress.coerceIn(0f, 1f)
                val t = FastOutSlowInEasing.transform(smooth(0f, GHOST_END, p))
                // From the real figure (sliding down with the balance) to the pill's centre.
                val sy = from.center.y + p * reveal.range
                translationX = (to.center.x - from.center.x) * t
                translationY = sy + (to.center.y - sy) * t - from.center.y
                val s = 1f + (to.height * 0.5f / from.height - 1f) * t
                scaleX = s; scaleY = s
                alpha = 0.7f * smooth(0.01f, 0.05f, p) * (1f - smooth(0.12f, GHOST_END, p))
                if (Build.VERSION.SDK_INT >= 31) {
                    val b = sin(PI * smooth(0f, GHOST_END, p)).toFloat() * 4.dp.toPx()
                    renderEffect = if (b > 0.5f) BlurEffect(b, b, TileMode.Decal) else null
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Txt(text, size = size, weight = FontWeight.SemiBold, color = Lw.Foreground, lineHeight = 60.sp, letterSpacing = (-1.5).sp, maxLines = 1)
    }
}

/**
 * The wallet in use, as a small pill in the balance label's place: its mark and name. Tap opens the wallet list; hold opens
 * the wallet's menu. As the list opens it hands over to [WalletPillMorph] and the selected card.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun WalletPill(w: RemoteWallet, reveal: HomeReveal, hidden: () -> Float, onOpen: () -> Unit, onMenu: () -> Unit, modifier: Modifier = Modifier) {
    val haptics = LocalHapticFeedback.current
    val lncurl = w.provider == "lncurl"
    Row(
        modifier
            .measureRoot { if (reveal.progress == 0f) reveal.pill = it }
            // Once the list starts opening, [WalletPillMorph] draws it (and carries it into the card).
            .graphicsLayer { alpha = if (reveal.progress > 0f) 0f else 1f - hidden().coerceIn(0f, 1f) }
            .requiredHeight(PILL_H).widthIn(max = 240.dp).pillGlass()
            .combinedClickable(
                onClickLabel = stringResource(R.string.settings_remoteWallets),
                onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onMenu() },
                onClick = onOpen,
            )
            .padding(start = 3.dp, end = 14.dp)
            .testTag("home-wallet-pill"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillMark(lncurl, Modifier.measureRoot { if (reveal.progress == 0f) reveal.pillMark = it })
        Spacer(Modifier.width(8.dp))
        PillName(w.name, Modifier.weight(1f, fill = false).measureRoot { if (reveal.progress == 0f) reveal.pillName = it })
    }
}

private val PILL_H = 30.dp
private val PILL_R = PILL_H / 2
private val PillShape = RoundedCornerShape(PILL_R)

/** The pill's glass: dimmer than [glass] (it's secondary to the balance), a soft hairline round it. */
private val PillFill = Color.White.copy(alpha = 0.04f)
private val PillEdge = Color.White.copy(alpha = 0.08f)
private fun Modifier.pillGlass() = clip(PillShape).background(PillFill).border(1.dp, PillEdge, PillShape)

@Composable
private fun PillMark(lncurl: Boolean, modifier: Modifier = Modifier) = Box(
    modifier.size(24.dp).clip(CircleShape).background((if (lncurl) Lw.LncurlGreen else Lw.NwcPurple).copy(alpha = 0.12f)),
    contentAlignment = Alignment.Center,
) { WalletMark(lncurl, 13.dp, Modifier.alpha(0.8f)) }

@Composable
private fun PillName(name: String, modifier: Modifier = Modifier) =
    Txt(name, modifier, size = PILL_TEXT, weight = FontWeight.Medium, color = Lw.Foreground.copy(alpha = 0.62f), maxLines = 1)

private val PILL_TEXT = 13.sp

/**
 * The pill becoming the selected card as the list opens (a container transform, scrubbed by the swipe): one glass
 * shape grows from the pill to where the card lands, riding down with the list, while the pill's content fades and
 * the card fades in on arrival. Drawn, not laid out: no layout pass per frame.
 */
@Composable
internal fun WalletPillMorph(w: RemoteWallet, reveal: HomeReveal) {
    val from = reveal.pill
    val to = reveal.card
    if (from == Rect.Zero || to == Rect.Zero) return
    val edge = Glass.edge(GlassLevel.Regular)
    val fill = Glass.fill(GlassLevel.Regular)
    val path = remember { Path() } // reused every frame
    // The shape: the pill growing into the card from the first pixel of the swipe, riding down with the list.
    Spacer(Modifier.fillMaxSize().drawBehind {
        val p = reveal.progress.coerceIn(0f, 1f)
        if (p <= 0f || p >= MORPH_DONE) return@drawBehind
        val e = morphAt(p)
        val dy = -(1f - p) * reveal.range // the card's place now: it rests at [to] once open
        val l = from.left + (to.left - from.left) * e
        val t = from.top + (to.top + dy - from.top) * e - reveal.rootTop
        val r = from.right + (to.right - from.right) * e
        val b = from.bottom + (to.bottom + dy - from.bottom) * e - reveal.rootTop
        // The pill's round ends ease into the card's corners.
        val card = 24.dp.toPx()
        val corner = CornerRadius(PILL_R.toPx() + (card - PILL_R.toPx()) * e)
        val a = 1f - smooth(MORPH_LAND, MORPH_DONE, p)
        path.rewind()
        path.addRoundRect(RoundRect(l, t, r, b, corner, corner, corner, corner))
        drawPath(path, lerp(PillFill, fill, e), alpha = a)
        drawPath(path, Glass.Sheen, alpha = a * e)
        drawPath(path, edge, alpha = a * (0.5f + 0.5f * e), style = Stroke(1.dp.toPx()))
    })
    // The pill's mark and name travel and grow into the card's, which takes over as they land.
    val lncurl = w.provider == "lncurl"
    MorphPart(reveal, reveal.pillMark, { reveal.cardMark }) { PillMark(lncurl) }
    MorphPart(reveal, reveal.pillName, { reveal.cardName }, uniform = true) { PillName(w.name) }
}

/** The pill's shape timeline: growing into the card as soon as the swipe starts, landing early in it. */
private fun morphAt(p: Float) = FastOutSlowInEasing.transform(smooth(0f, MORPH_LAND, p))
/** The balance's ghost has landed in the pill by here. */
private const val GHOST_END = 0.2f
/** The morph reaches the card here; the real card takes over by [MORPH_DONE]. */
private const val MORPH_LAND = 0.55f
private const val MORPH_DONE = 0.65f

/**
 * One piece of the pill ([from], root space) moving onto its counterpart in the card ([to], relative to the card), laid
 * out once at its pill size and moved by its layer. [uniform]: scaled by height only (text keeps its proportions).
 */
@Composable
private fun MorphPart(reveal: HomeReveal, from: Rect, to: () -> Rect, uniform: Boolean = false, content: @Composable () -> Unit) {
    if (from == Rect.Zero) return
    Box(
        Modifier.offset { IntOffset(from.left.toInt(), (from.top - reveal.rootTop).toInt()) }
            .layout { m, c ->
                val pl = m.measure(c.copy(minWidth = from.width.toInt(), maxWidth = from.width.toInt(), minHeight = from.height.toInt(), maxHeight = from.height.toInt()))
                layout(pl.width, pl.height) { pl.place(0, 0) }
            }
            .graphicsLayer {
                val p = reveal.progress.coerceIn(0f, 1f)
                val rel = to()
                if (p <= 0f || rel == Rect.Zero || reveal.card == Rect.Zero) { alpha = 0f; return@graphicsLayer }
                val e = morphAt(p)
                val dest = rel.translate(reveal.card.left, reveal.card.top - (1f - p) * reveal.range)
                transformOrigin = TransformOrigin(0f, 0f)
                val sy = 1f + (dest.height / from.height - 1f) * e
                val sx = if (uniform) sy else 1f + (dest.width / from.width - 1f) * e
                scaleX = sx; scaleY = sy
                translationX = (dest.left - from.left) * e
                translationY = (dest.top - from.top) * e
                alpha = 1f - smooth(MORPH_LAND, MORPH_DONE, p)
            },
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

/** Measures [this] node at rest in root space (before any reveal translation applied after it). */
internal fun Modifier.measureRoot(onRect: (Rect) -> Unit) = onGloballyPositioned { onRect(it.rootRect()) }

/**
 * [from] rolls into [to] like an odometer wheel when [flipped]: only the words that differ roll (the
 * shared prefix/suffix, e.g. " balance", stays put), and the wheel's width eases between the two.
 */
@Composable
internal fun RollingLabel(from: String, to: String, flipped: Boolean, size: TextUnit, color: Color, weight: FontWeight = FontWeight.Medium) {
    val t = animateFloatAsState(if (flipped) 1f else 0f, tween(560, easing = FastOutSlowInEasing), label = "roll")
    RollingLabel(from, to, { t.value }, size, color, weight)
}

/** Same wheel, turned by [t] (0 = [from], 1 = [to]) directly. */
@Composable
internal fun RollingLabel(from: String, to: String, t: () -> Float, size: TextUnit, color: Color, weight: FontWeight = FontWeight.Medium) {
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
            val t = t().coerceIn(0f, 1f)
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
private fun WalletActionsSheet(w: RemoteWallet, balance: String?, canSweep: Boolean, onDismiss: () -> Unit, onDetails: () -> Unit, onSweep: () -> Unit, onRename: () -> Unit, onTransfer: (() -> Unit)?, onDelete: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val brand = LocalBrand.current
    val lncurl = w.provider == "lncurl"
    val accent = if (lncurl) Lw.LncurlGreen else Lw.NwcPurple
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
            ) { WalletMark(lncurl, 24.dp) }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(w.name, size = 17.sp, weight = FontWeight.SemiBold, maxLines = 1)
                Txt(listOfNotNull(if (lncurl) "LNCurl" else "NWC", balance?.let { "$it sats" }).joinToString(" · "), size = 13.sp, color = Lw.MutedFg, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        SheetAction(L.drawable.lucide_ic_info, stringResource(R.string.sweep_menu_details), Lw.Foreground, "home-wallet-menu-details") { then(onDetails) }
        SheetAction(L.drawable.lucide_ic_pencil, stringResource(R.string.rw_rename), Lw.Foreground, "home-wallet-menu-rename") { then(onRename) }
        if (onTransfer != null) SheetAction(L.drawable.lucide_ic_arrow_down_left, stringResource(R.string.transfer_menu), brand.t400, "home-wallet-menu-transfer") { then(onTransfer) }
        if (canSweep) SheetAction(L.drawable.lucide_ic_arrow_right_left, stringResource(R.string.sweep_menu), brand.t400, "home-wallet-menu-sweep") { then(onSweep) }
        SheetAction(L.drawable.lucide_ic_trash_2, stringResource(R.string.rw_delete), Lw.Red, "home-wallet-menu-delete", Lw.Red) { then(onDelete) }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

/** The deck's Add: the same two choices as "Connect a remote wallet", in a sheet; it slides away before the form opens. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddWalletSheet(onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun then(step: String) { scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); onPick(step) } }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = Glass.Dialog,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) },
        modifier = Modifier.testTag("home-wallets-add-sheet"),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 8.dp, bottom = 16.dp).navigationBarsPadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt(stringResource(R.string.rw_add_title), size = 17.sp, weight = FontWeight.SemiBold)
            MutedText(stringResource(R.string.rw_add_subtitle))
            Spacer(Modifier.height(4.dp))
            AddWalletChoices(onNwc = { then("nwc") }, onLncurl = { then("lncurl") })
        }
    }
}

@Composable
internal fun SheetAction(icon: Int, label: String, tint: Color, tag: String, labelColor: Color = Lw.Foreground, onClick: () -> Unit) =
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 16.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LwIcon(icon, size = 22.dp, tint = tint)
        Spacer(Modifier.width(18.dp))
        Txt(label, size = 16.sp, weight = FontWeight.Medium, color = labelColor)
    }
