package io.lawallet.wallet.ui.screens

import io.lawallet.wallet.ui.components.DialogTitle
import androidx.compose.runtime.CompositionLocalProvider
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import io.lawallet.wallet.ui.theme.LocalHaze
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.pager.PagerState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.runtime.Stable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import kotlin.math.roundToInt
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.abs
import io.lawallet.wallet.ui.theme.Inter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.floor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.semantics.text
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.Dp
import androidx.compose.foundation.layout.RowScope
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import io.lawallet.wallet.ui.components.LwAlertDialog
import io.lawallet.wallet.ui.components.ModalAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

internal const val ONCHAIN = 0
internal const val LIGHTNING = 1
internal const val USDT = 2
internal const val PAGES = 3

/** Which Home page shows: 0 = Onchain, 1 = Lightning, 2 = USDT, fractional mid-swipe. The tab bar reads it. */
object HomePager {
    /** Home's pager while it's on screen. Read straight from it, in the same frame the pages draw: a copy collected
     *  through a flow lands a frame late, and the pinned switcher, grabber and figures wobbled by that much mid-swipe. */
    var state by mutableStateOf<PagerState?>(null)
    val position: Float get() = state?.let { it.currentPage + it.currentPageOffsetFraction } ?: last
    /** Where Home's pager last was, for while it's off screen. */
    private var last = LIGHTNING.toFloat()
    fun release(s: PagerState) { if (state === s) { last = s.currentPage + s.currentPageOffsetFraction; state = null } }
    /** The page nearest the front. */
    val page get() = position.roundToInt().coerceIn(0, PAGES - 1)
    /** How much [page] is in front: 1 when it is, 0 a page or more away (the weights always sum to 1). */
    fun weight(page: Int, at: Float = position) = (1f - abs(at - page)).coerceIn(0f, 1f)
}

/** Receive's pager (Onchain, Lightning: the same positions as Home's first two pages), for the backdrop. */
object ReceivePager {
    var state by mutableStateOf<PagerState?>(null)
    val position: Float get() = state?.let { it.currentPage + it.currentPageOffsetFraction } ?: LIGHTNING.toFloat()
}

/** Each page's tone: Bitcoin orange, Lightning blue, Tether green. */
internal val PAGE_TONES = listOf(Lw.Bitcoin, Lw.Lightning, Lw.Tether)

/** The tone at [position], blended between the two pages either side of it. */
internal fun pageTone(position: Float = HomePager.position): Color {
    val p = position.coerceIn(0f, PAGES - 1f)
    val i = p.toInt().coerceAtMost(PAGES - 2)
    return lerp(PAGE_TONES[i], PAGE_TONES[i + 1], p - i)
}

/**
 * What the pages share. [y]: root-space y of each page's dots slot (NaN until laid out). [reveal] and [sheet]: each
 * page's vertical state (wallet list, activity panel), registered by the page; HomeScreen mirrors the settled page's
 * onto the others, so a swipe lands in the same place: activity to activity, wallets to wallets.
 */
@Stable
internal class HomePages {
    val y = mutableStateListOf(*Array(PAGES) { Float.NaN })
    /** Root-space top of each page's currency switcher, for [pinnedGlide]. */
    val toggleY = mutableStateListOf(*Array(PAGES) { Float.NaN })
    val reveal = mutableStateListOf<HomeReveal?>(*arrayOfNulls(PAGES))
    val sheet = mutableStateListOf<ActivitySheet?>(*arrayOfNulls(PAGES))
    /** What each page needs above its docked activity line; all dock at the largest, so the line never moves. */
    val need = mutableStateListOf(*Array(PAGES) { 0f })
    fun floorFor(page: Int) = need.filterIndexed { i, _ -> i != page }.maxOrNull() ?: 0f
    /** A page's width in px: how far a pinned element counter-slides. */
    var width by mutableFloatStateOf(0f)
    /** The activity panel's rise, blended across the swipe: what the fixed header and dots fade with. */
    fun sheetAt(position: Float) = blend(position) { sheet[it]?.progress ?: 0f }
    private inline fun blend(position: Float, v: (Int) -> Float) = (0 until PAGES).sumOf { (HomePager.weight(it, position) * v(it)).toDouble() }.toFloat()
}

/**
 * Holds an element still on screen while its page slides (counter-translated by the page's offset). Every page
 * draws the same thing there, so only the page nearest the front shows its copy: they swap unseen at mid-swipe.
 */
internal fun Modifier.pinned(pages: HomePages, page: Int) = graphicsLayer {
    val o = HomePager.position - page
    translationX = o * pages.width
    alpha = if (abs(o) < 0.5f) 1f else 0f
}

/**
 * [pinned], and vertically gliding: every page's copy sits at the swipe's blend of the pages' own heights ([ys],
 * measured here), so if the pages put it at different heights it slides from one to the next with the finger
 * instead of jumping when the copies swap at mid-swipe.
 */
internal fun Modifier.pinnedGlide(pages: HomePages, page: Int, ys: SnapshotStateList<Float>) =
    onGloballyPositioned { ys[page] = it.positionInRoot().y }
        .graphicsLayer {
            val o = HomePager.position - page
            translationX = o * pages.width
            alpha = if (abs(o) < 0.5f) 1f else 0f
            val own = ys[page]
            if (!own.isNaN()) translationY = (0 until PAGES).sumOf { k -> (HomePager.weight(k) * (ys[k].takeUnless(Float::isNaN) ?: own)).toDouble() }.toFloat() - own
        }

/**
 * Pinned, for what differs between the pages (the balance figure): a stationary window the pages' figures slide
 * through, 1:1 with the swipe, each coming in from its page's side, like the pages and the label's word. Put
 * [rollIn] on the content inside it.
 */
internal fun Modifier.pinnedSlot(pages: HomePages, page: Int) = graphicsLayer {
    translationX = (HomePager.position - page) * pages.width
    clip = true
}

internal fun GraphicsLayerScope.rollIn(page: Int) {
    val o = HomePager.position - page
    translationX = -o * size.width
    alpha = (1f - abs(o) * 1.3f).coerceIn(0f, 1f)
}

/**
 * "Onchain balance" ⇄ "Lightning balance" ⇄ "USDT balance", the page word sliding sideways with the swipe, and
 * "Primary balance" once the wallet list is open. Centred on its own; [badge] (Lightning's relay status) sits to its
 * right without pushing it over, so the label lands on the same spot on every page.
 */
@Composable
internal fun BalanceLabel(primary: Boolean, pin: Modifier, badge: (@Composable () -> Unit)? = null, primaryText: String? = null, lightningText: String? = null, replace: (@Composable () -> Unit)? = null) {
    val pt by animateFloatAsState(if (primary) 1f else 0f, tween(560, easing = FastOutSlowInEasing), label = "primary")
    val labels = listOf(stringResource(R.string.home_onchainBalance), lightningText ?: stringResource(R.string.home_lightningBalance), stringResource(R.string.home_usdtBalance))
    Layout(
        content = {
            // [replace] (the wallet pill) stands in for the words, taller than the row: unclipped, centred on it.
            if (replace != null) Box(pin, contentAlignment = Alignment.Center) { replace() }
            else Box(pin.clipToBounds(), contentAlignment = Alignment.Center) {
                Box(Modifier.graphicsLayer { translationY = -pt * size.height; alpha = 1f - pt }) { PagedWords(labels, 14.sp, Lw.MutedFg) }
                Txt(primaryText ?: stringResource(R.string.home_primaryBalance), Modifier.graphicsLayer { translationY = (1f - pt) * size.height; alpha = pt }, size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg, maxLines = 1)
            }
            badge?.invoke()
        },
        modifier = Modifier.fillMaxWidth().height(BALANCE_LABEL_H),
    ) { ms, c ->
        val free = c.copy(minWidth = 0, minHeight = 0)
        val label = ms[0].measure(free)
        val b = ms.getOrNull(1)?.measure(free)
        layout(c.maxWidth, c.maxHeight) {
            val x = (c.maxWidth - label.width) / 2
            label.place(x, (c.maxHeight - label.height) / 2)
            b?.place(x + label.width + 8.dp.roundToPx(), (c.maxHeight - b.height) / 2)
        }
    }
}

/**
 * One label per page; only the words that differ move ("balance" / "Saldo" stays put): each page's word sits on its
 * page's side and slides into the middle as that page does, the slot's width easing between neighbours.
 */
@Composable
private fun PagedWords(labels: List<String>, size: TextUnit, color: Color) {
    val words = labels.map { it.split(' ') }
    val shortest = words.minOf { it.size }
    val head = (0 until shortest).takeWhile { i -> words.all { w -> w[i] == words[0][i] } }.size
    val tail = (0 until shortest - head).takeWhile { i -> words.all { w -> w[w.size - 1 - i] == words[0][words[0].size - 1 - i] } }.size
    val pre = words[0].take(head).joinToString(" ").let { if (it.isEmpty()) it else "$it " }
    val suf = words[0].takeLast(tail).joinToString(" ").let { if (it.isEmpty()) it else " $it" }
    Row(Modifier.semantics(mergeDescendants = true) {}) {
        if (pre.isNotEmpty()) Txt(pre, size = size, weight = FontWeight.Medium, color = color, maxLines = 1)
        Layout(
            content = { words.forEach { w -> Txt(w.subList(head, w.size - tail).joinToString(" "), size = size, weight = FontWeight.Medium, color = color, maxLines = 1) } },
            modifier = Modifier.clipToBounds(),
        ) { ms, c ->
            val ps = ms.map { it.measure(c.copy(minWidth = 0)) }
            val p = HomePager.position.coerceIn(0f, ps.size - 1f)
            val i = p.toInt().coerceAtMost(ps.size - 2)
            val w = (ps[i].width + (ps[i + 1].width - ps[i].width) * (p - i)).toInt()
            layout(w, ps.maxOf { it.height }) {
                ps.forEachIndexed { k, pl -> pl.placeWithLayer((w - pl.width) / 2, 0) { val o = k - p; translationX = o * w; alpha = (1f - abs(o)).coerceIn(0f, 1f) } }
            }
        }
        if (suf.isNotEmpty()) Txt(suf, size = size, weight = FontWeight.Medium, color = color, maxLines = 1)
    }
}

/** The fixed header's height; each page leaves this much room for it. */
internal val HEADER_H = 56.dp
/** The balance label row's height, the same on every page. */
internal val BALANCE_LABEL_H = 20.dp

/**
 * Home: Onchain on the left, Lightning in the middle (where it opens), USDT on the right. A horizontal swipe anywhere
 * on a page (balance, activity, wallet deck) slides between them; the header, balance and the dots under it stay
 * put, the dots tracking which page is in front.
 */
@Composable
fun HomeScreen() {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val pager = rememberPagerState(initialPage = if (c.flow.revealOnchain) ONCHAIN else LIGHTNING) { PAGES }
    LaunchedEffect(Unit) {
        if (c.flow.revealWallets) pager.scrollToPage(LIGHTNING)
        if (c.flow.revealOnchain) pager.scrollToPage(ONCHAIN)
    }
    DisposableEffect(pager) { HomePager.state = pager; onDispose { HomePager.release(pager) } }
    // Back from Onchain or USDT goes to Lightning first (the pages' own handlers, composed later, win while they're open).
    BackHandler(pager.currentPage != LIGHTNING) { scope.launch { pager.animateScrollToPage(LIGHTNING) } }
    // First entry into Home plays the intro (staggered rise + balance rolling up from 0); coming back
    // from another screen doesn't (saved with the back-stack entry).
    val balance by c.wallet.balance.collectAsState()
    var intro by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(balance != null) { if (balance != null) { delay(1600); intro = false } }
    val anchors = remember { HomePages() }
    // Keep the pages out of sight in the same state as the one in front (only while nothing is swiping, so the
    // page being swiped to is never moved under the finger). A page that can't follow (no activity) stays put.
    LaunchedEffect(pager, anchors) {
        snapshotFlow {
            if (pager.isScrollInProgress) null
            else pager.settledPage.let { s -> Triple(s, anchors.reveal[s]?.progress, anchors.sheet[s]?.progress) }
        }.collect { t ->
            val (s, r, sh) = t ?: return@collect
            for (other in 0 until PAGES) if (other != s) {
                r?.let { anchors.reveal[other]?.snapTo(it.coerceIn(0f, 1f)) }
                sh?.let { v -> anchors.sheet[other]?.takeIf { it.enabled }?.snapTo(v) }
            }
        }
    }
    var rootTop by remember { mutableFloatStateOf(0f) }
    Box(Modifier.fillMaxSize().onGloballyPositioned { rootTop = it.positionInRoot().y; anchors.width = it.size.width.toFloat() }.testTag("home-root")) {
        // Home's pages are their own Haze source: the header layer (address drop-down, its backdrop) sits beside them,
        // and Haze never blurs a source an effect is inside of (the app-wide one wraps the header too).
        val pagesHaze = rememberHazeState()
        HorizontalPager(
            // No edge stretch: it would drag the pinned switcher, grabber and figures off their spots.
            pager, Modifier.fillMaxSize().hazeSource(pagesHaze), beyondViewportPageCount = 1, overscrollEffect = null,
            flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = spring(0.86f, Spring.StiffnessMediumLow)),
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                PageWatermark(page, { pager.currentPage - page + pager.currentPageOffsetFraction }, { anchors.sheet[page]?.progress ?: 0f })
                // At rest: currentPage flips mid-swipe and would recompose both heavy pages in that one frame.
                val current = pager.settledPage == page
                when (page) {
                    ONCHAIN -> OnchainPage(current, anchors)
                    LIGHTNING -> LightningPage(current, intro, anchors)
                    else -> UsdtPage(current, anchors)
                }
            }
        }
        PageDots(anchors, rootTop, Modifier.riseIn(2, intro)) { scope.launch { pager.animateScrollToPage(it) } }
        // Last, so the address drop-down and its backdrop cover everything on Home (the tab bar steps aside for it).
        CompositionLocalProvider(LocalHaze provides pagesHaze) { HomeHeader(intro, anchors) }
    }
}

/**
 * Home's light, drawn full-bleed behind everything (AppNav, under the status bar): the prism calmed, then each
 * page's tone (warm Bitcoin orange, Lightning blue, Tether green) crossfading 1:1 with the swipe. [shown] fades it
 * in and out as Home comes and goes.
 */
@Composable
internal fun HomeBackdrop(shown: () -> Float, at: () -> Float = { HomePager.position }) {
    Spacer(Modifier.fillMaxSize().drawBehind {
        val s = shown()
        if (s <= 0f) return@drawBehind
        val w = size.width
        val h = size.height
        drawRect(Lw.Background.copy(alpha = 0.62f * s))
        fun glow(c: Color, a: Float, x: Float, y: Float, r: Float) {
            if (a > 0.003f) drawRect(Brush.radialGradient(0f to c.copy(alpha = a), 0.55f to c.copy(alpha = a * 0.35f), 1f to Color.Transparent, center = Offset(x, y), radius = r))
        }
        // A dome of the page's tone over the balance, and a lighter pool low on one side.
        val p = at()
        val on = HomePager.weight(ONCHAIN, p) * s
        glow(Lw.Bitcoin, 0.34f * on, w * 0.5f, h * 0.2f, w * 1.05f)
        glow(Color(0xFFFFB547), 0.14f * on, 0f, h * 0.66f, w * 0.85f)
        val ln = HomePager.weight(LIGHTNING, p) * s
        glow(Lw.Lightning, 0.32f * ln, w * 0.5f, h * 0.2f, w * 1.05f)
        glow(Color(0xFF38BDF8), 0.12f * ln, w, h * 0.66f, w * 0.85f)
        val us = HomePager.weight(USDT, p) * s
        glow(Lw.Tether, 0.42f * us, w * 0.5f, h * 0.2f, w * 1.05f)
        glow(Color(0xFF50AF95), 0.14f * us, w, h * 0.66f, w * 0.85f)
    })
}

/**
 * A huge, faint mark behind each page (the tilted ₿ of the Bitcoin logo, the Lightning bolt, Tether's ₮ mark),
 * filled with a wash of its tone that fades out toward the bottom right. Only on its own page: it fades in as the
 * page arrives and out as it leaves (the half-speed drift would otherwise carry it onto the next page). Steps back
 * as the activity panel rises over it. [offset]: the page's distance from the front, in pages.
 */
@Composable
internal fun PageWatermark(page: Int, offset: () -> Float, sheet: () -> Float) {
    val tone = PAGE_TONES[page]
    val (icon, tilt) = when (page) {
        ONCHAIN -> L.drawable.lucide_ic_bitcoin to 14f
        LIGHTNING -> L.drawable.lucide_ic_zap to -10f
        else -> R.drawable.ic_usdt_mark to -8f
    }
    Box(Modifier.fillMaxSize().graphicsLayer {
        val o = offset()
        translationX = o * size.width * 0.5f
        alpha = smooth(0.15f, 1f, 1f - abs(o)) * (1f - 0.7f * sheet())
    }) {
        Image(
            painterResource(icon), null,
            Modifier.size(500.dp).align(Alignment.Center).offset(x = 120.dp, y = 30.dp)
                .graphicsLayer { rotationZ = tilt; compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.linearGradient(listOf(tone.copy(alpha = 0.17f), tone.copy(alpha = 0.05f), Color.Transparent), Offset.Zero, Offset(size.width, size.height)), blendMode = BlendMode.SrcIn)
                },
        )
    }
}

/**
 * An invisible balance-sized line in every page's figure slot, so the slot keeps one height whatever it shows (loading
 * dots, an error, "Soon"): the switcher, dots and sheet line then sit at the same spot on every page.
 */
@Composable
internal fun FigureStrut() = Txt("0", Modifier.alpha(0f).clearAndSetSemantics {}, size = 56.sp, weight = FontWeight.SemiBold, lineHeight = 60.sp, letterSpacing = (-1.5).sp)

/** Under each page's balance: where the fixed dots sit. */
@Composable
internal fun DotsSlot(anchors: HomePages, page: Int) {
    Spacer(Modifier.padding(top = 18.dp).height(DOT).onGloballyPositioned { anchors.y[page] = it.positionInRoot().y + it.size.height / 2f })
    // A page the pager drops stops counting (its last spot may be stale).
    DisposableEffect(page) { onDispose { anchors.y[page] = Float.NaN } }
}

private val DOT = 6.dp

/**
 * One dot per page, Onchain (orange), Lightning (blue), USDT (green): the page in front stretches into a lit pill,
 * scrubbed by the swipe itself. They hold still while the pages slide under them, at the same height on every page,
 * and follow the balance's vertical moves (reveal), which the pages mirror. Tap a dot to go to its page.
 */
@Composable
private fun PageDots(anchors: HomePages, rootTop: Float, modifier: Modifier, onTap: (Int) -> Unit) {
    val desc = stringResource(R.string.home_pages_hint)
    Box(
        modifier.fillMaxWidth().wrapContentWidth(Alignment.CenterHorizontally)
            // Under the balance of the page in front, gliding between the pages' spots with the swipe (they differ
            // when a page's wallet list is open and shorter or longer than its neighbours').
            .offset {
                val known = anchors.y.filterNot(Float::isNaN)
                val y = if (known.isEmpty()) null else {
                    val fallback = known.max()
                    (0 until PAGES).sumOf { k -> (HomePager.weight(k) * (anchors.y[k].takeUnless(Float::isNaN) ?: fallback)).toDouble() }.toFloat()
                }
                IntOffset(0, if (y == null) 0 else (y - rootTop - 16.dp.toPx()).toInt())
            }
            .graphicsLayer {
                alpha = if (anchors.y.all(Float::isNaN)) 0f else (1f - anchors.sheetAt(HomePager.position) * 2.2f).coerceIn(0f, 1f)
            }
            .size(84.dp, 32.dp).clip(CircleShape)
            .pointerInput(onTap) { detectTapGestures { onTap((it.x / size.width * PAGES).toInt().coerceIn(0, PAGES - 1)) } }
            .semantics { contentDescription = desc }.testTag("home-pages")
            .drawBehind {
                val d = DOT.toPx()
                val long = 22.dp.toPx()
                val gap = 6.dp.toPx()
                val y = (size.height - d) / 2f
                val off = Color.White.copy(alpha = 0.22f)
                var x = (size.width - (PAGES * d + (long - d) + (PAGES - 1) * gap)) / 2f
                for (k in 0 until PAGES) {
                    val wk = HomePager.weight(k)
                    val wd = d + (long - d) * wk
                    drawRoundRect(lerp(off, PAGE_TONES[k], wk), Offset(x, y), Size(wd, d), CornerRadius(d / 2))
                    x += wd + gap
                }
            },
    )
}

/** Banners under the balance: ride down with it and fade, so the landed balance stands alone. */
internal fun Modifier.revealAside(reveal: HomeReveal) = graphicsLayer {
    translationY = reveal.progress * reveal.range
    alpha = (1f - reveal.progress * 2.5f).coerceIn(0f, 1f)
}

/** Fades and lifts into place, [index] steps (70ms each) after the first; a no-op unless [play]. */
@Composable
internal fun Modifier.riseIn(index: Int, play: Boolean, rise: Dp = 24.dp): Modifier {
    val p = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) { if (p.value < 1f) { delay(80L + index * 70L); p.animateTo(1f, tween(560, easing = EaseOutCubic)) } }
    return graphicsLayer { alpha = p.value; translationY = (1f - p.value) * rise.toPx() }
}

@Composable
internal fun BalanceFigure(sats: Long, code: String, rates: io.lawallet.wallet.core.fx.Rates?, hidden: Boolean, motion: String?, fromZero: Boolean) {
    val color by animateColorAsState(
        when (motion) { "incoming" -> Lw.Green; "outgoing" -> Lw.Orange; else -> Lw.Foreground }, tween(300), label = "tint",
    )
    Row {
        val text = if (hidden) "•••••" else Currencies.formatBalance(sats, code, rates)
        val big = Modifier.alignByBaseline().testTag("balance")
        when {
            hidden -> Txt(text, big, size = 56.sp, weight = FontWeight.SemiBold, color = color, lineHeight = 60.sp, letterSpacing = (-1.5).sp)
            code == "BTC" -> BtcEmphasis(text, color, fromZero, Modifier.alignByBaseline())
            else -> Odometer(text, big, 56.sp, (-1.5).sp, color, fromZero)
        }
    }
}

/** BTC: mute the leading zeros (splitBtcForEmphasis). */
@Composable
private fun BtcEmphasis(text: String, color: Color, fromZero: Boolean, modifier: Modifier = Modifier) {
    val idx = text.indexOfFirst { it in '1'..'9' }.let { if (it < 0) text.length else it }
    Row(modifier) {
        Txt(text.substring(0, idx), Modifier.alignByBaseline(), size = 44.sp, weight = FontWeight.SemiBold, color = Lw.MutedFg.copy(alpha = 0.55f), lineHeight = 60.sp, letterSpacing = (-1).sp)
        Odometer(text.substring(idx), Modifier.alignByBaseline().testTag("balance"), 44.sp, (-1).sp, color, fromZero)
    }
}

/**
 * Odometer: each digit is a wheel that rolls to its new value (from 0 when [fromZero]); separators stay put.
 * Wheels are keyed from the right, so units stay units when the number grows a digit.
 */
@Composable
internal fun Odometer(text: String, modifier: Modifier, size: TextUnit, letterSpacing: TextUnit, color: Color, fromZero: Boolean, lineHeight: TextUnit = 60.sp) {
    Row(modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }) {
        text.forEachIndexed { i, ch ->
            key(text.length - i) {
                if (ch.isDigit()) OdometerWheel(ch - '0', i, size, letterSpacing, color, fromZero, lineHeight)
                else Txt(ch.toString(), Modifier.alignByBaseline(), size = size, weight = FontWeight.SemiBold, color = color, lineHeight = lineHeight, letterSpacing = letterSpacing)
            }
        }
    }
}

/** Wheel springs (critically damped: no overshoot past the digit): a normal roll ≈ 650ms, an interrupted one ≈ 300ms. */
private const val ODO_STIFF = 90f
private const val ODO_FAST = 400f

@Composable
private fun RowScope.OdometerWheel(digit: Int, index: Int, size: TextUnit, letterSpacing: TextUnit, color: Color, fromZero: Boolean, lineHeight: TextUnit) {
    val pos = remember { Animatable(if (fromZero) 0f else digit.toFloat()) }
    val target by rememberUpdatedState(digit)
    LaunchedEffect(Unit) {
        // Intro: left-most wheels settle first, the units keep spinning a beat longer.
        if (fromZero && pos.value == 0f) { delay(250L + index * 60L); pos.animateTo(target.toFloat(), tween(1100, easing = FastOutSlowInEasing)) }
        // Each change starts its roll while the last is still running (not after cancelling it), so a change mid-roll
        // (switching wallets fast) takes over at the wheel's current speed and settles quicker: the figure keeps up
        // and lands on the latest balance without restarting from rest.
        snapshotFlow { target }.collect { d ->
            if (pos.targetValue == d.toFloat()) return@collect
            val fast = pos.isRunning
            launch { pos.animateTo(d.toFloat(), spring(1f, if (fast) ODO_FAST else ODO_STIFF)) }
        }
    }
    // One Text node (transparent target digit) owns layout + baseline; the rolling faces are painted over it.
    // The ten faces laid out once (colour applied at draw time): measuring per frame missed the measurer's 8-entry
    // cache on every full roll, and a tint change (a new style) missed it on every frame of the tint.
    val measurer = rememberTextMeasurer()
    val faces = remember(measurer, size, letterSpacing, lineHeight) {
        val style = TextStyle(fontFamily = Inter, fontFeatureSettings = "tnum", fontSize = size, fontWeight = FontWeight.SemiBold, lineHeight = lineHeight, letterSpacing = letterSpacing)
        Array(10) { measurer.measure(it.toString(), style) }
    }
    val tint by rememberUpdatedState(color)
    Txt(
        digit.toString(),
        Modifier.alignByBaseline().drawWithContent {
            val p = pos.value
            val base = floor(p)
            val frac = p - base
            val lo = ((base.toInt() % 10) + 10) % 10
            val h = this.size.height
            clipRect(-h, 0f, this.size.width + h, h) {
                drawText(faces[lo], color = tint, topLeft = Offset(0f, -frac * h), alpha = 1f - frac)
                if (frac > 0f) drawText(faces[(lo + 1) % 10], color = tint, topLeft = Offset(0f, (1f - frac) * h), alpha = frac)
            }
        },
        size = size, weight = FontWeight.SemiBold, color = Color.Transparent, lineHeight = lineHeight, letterSpacing = letterSpacing,
    )
}

/** relay-error-badge.tsx — red dot over a cached value, else an "Offline" chip; both open details + Retry. */
@Composable
fun RelayBadge(hasCached: Boolean, error: String, onRetry: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.clickable { open = true }.testTag("relay-badge")) {
        if (hasCached) Box(Modifier.size(8.dp).clip(CircleShape).background(Lw.Red))
        else Box(Modifier.glass(CircleShape, GlassLevel.Thin, tint = Lw.Red.copy(alpha = 0.18f)).padding(horizontal = 8.dp, vertical = 2.dp)) { Txt(stringResource(R.string.shared_relay_offline), size = 11.sp, color = Lw.Red) }
    }
    if (open) LwAlertDialog(
        onDismissRequest = { open = false },
        title = { DialogTitle(stringResource(R.string.shared_relay_title)) },
        text = { Txt(if (error.contains("time", true)) stringResource(R.string.shared_relay_timeout) else stringResource(R.string.shared_relay_generic), color = Lw.MutedFg) },
        confirmButton = { ModalAction(stringResource(R.string.common_retry), { open = false; onRetry() }) },
    )
}
