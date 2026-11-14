package io.lawallet.wallet.ui.screens

import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.animate
import kotlin.math.sign
import androidx.compose.ui.unit.Constraints
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.foundation.pager.PagerState
import androidx.compose.animation.AnimatedContent
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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import io.lawallet.wallet.ui.theme.Prism
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
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import io.lawallet.wallet.ui.components.LwAlertDialog
import io.lawallet.wallet.ui.components.ModalAction
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.ui.components.toast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
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
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.lawallet.wallet.ui.components.rememberAddressProfile
import io.lawallet.wallet.R
import io.lawallet.wallet.app.Tx
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.ln.Destination
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.sharedAvatar
import io.lawallet.wallet.ui.components.BrandImage
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.LoadingDots
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import io.lawallet.wallet.ui.components.CurrencyToggle
import io.lawallet.wallet.ui.components.GlassIconButton
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import io.lawallet.wallet.ui.sharedAddress
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import io.lawallet.wallet.ui.components.holdProgress
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
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
    fun revealAt(position: Float) = blend(position) { reveal[it]?.progress ?: 0f }
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
internal fun BalanceLabel(primary: Boolean, pin: Modifier, badge: (@Composable () -> Unit)? = null, primaryText: String? = null) {
    val pt by animateFloatAsState(if (primary) 1f else 0f, tween(560, easing = FastOutSlowInEasing), label = "primary")
    val labels = listOf(stringResource(R.string.home_onchainBalance), stringResource(R.string.home_lightningBalance), stringResource(R.string.home_usdtBalance))
    Layout(
        content = {
            Box(pin.clipToBounds(), contentAlignment = Alignment.Center) {
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
        HorizontalPager(
            // No edge stretch: it would drag the pinned switcher, grabber and figures off their spots.
            pager, Modifier.fillMaxSize(), beyondViewportPageCount = 1, overscrollEffect = null,
            flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = spring(0.86f, Spring.StiffnessMediumLow)),
        ) { page ->
            Box(Modifier.fillMaxSize()) {
                PageWatermark(page, { pager.currentPage - page + pager.currentPageOffsetFraction }, { anchors.sheet[page]?.progress ?: 0f })
                val current = pager.currentPage == page
                when (page) {
                    ONCHAIN -> OnchainPage(current, anchors)
                    LIGHTNING -> LightningPage(current, intro, anchors)
                    else -> UsdtPage(current, anchors)
                }
            }
        }
        // The pull-down arrows, fixed under the header for every page; they fade as the wallet list or the activity opens.
        SwipeHint(
            { maxOf(smooth(0f, 0.15f, anchors.revealAt(HomePager.position)), anchors.sheetAt(HomePager.position) * 2.2f) },
            Modifier.align(Alignment.TopCenter).padding(top = HEADER_H + 20.dp).riseIn(0, intro),
        )
        HomeHeader(intro, anchors)
        PageDots(anchors, rootTop, Modifier.riseIn(2, intro)) { scope.launch { pager.animateScrollToPage(it) } }
    }
}

/**
 * Home's light, drawn full-bleed behind everything (AppNav, under the status bar): the prism calmed, then each
 * page's tone (warm Bitcoin orange, Lightning blue, Tether green) crossfading 1:1 with the swipe. [shown] fades it
 * in and out as Home comes and goes.
 */
@Composable
internal fun HomeBackdrop(shown: () -> Float) {
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
        val on = HomePager.weight(ONCHAIN) * s
        glow(Lw.Bitcoin, 0.34f * on, w * 0.5f, h * 0.2f, w * 1.05f)
        glow(Color(0xFFFFB547), 0.14f * on, 0f, h * 0.66f, w * 0.85f)
        val ln = HomePager.weight(LIGHTNING) * s
        glow(Lw.Lightning, 0.32f * ln, w * 0.5f, h * 0.2f, w * 1.05f)
        glow(Color(0xFF38BDF8), 0.12f * ln, w, h * 0.66f, w * 0.85f)
        val us = HomePager.weight(USDT) * s
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
private fun PageWatermark(page: Int, offset: () -> Float, sheet: () -> Float) {
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

/** Avatar · centre pill · settings, fixed over every page; the pill turns between the onchain primary, the Lightning address and USDT. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomeHeader(intro: Boolean, pages: HomePages) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val addressPress = remember { MutableInteractionSource() }
    val addressHold = holdProgress(addressPress)
    val brand = LocalBrand.current
    val me by c.session.me.collectAsState()
    val accountPubkey by c.session.accountPubkey.collectAsState()
    val profiles by c.session.profiles.entries.collectAsState()
    val avatar = accountPubkey?.let { profiles[it]?.picture }
    val onchain by c.onchain.wallets.collectAsState()
    val primary = onchain.firstOrNull { it.isPrimary }
    val usdtSoon = stringResource(R.string.usdt_soon)
    // An activity sheet rising takes the header away with the page (as ActivitySheetLayout does); once it's
    // gone it leaves the composition, so it never catches taps meant for the raised list.
    val sheet = { pages.sheetAt(HomePager.position) }
    val gone by remember { derivedStateOf { sheet() > 0.45f } }
    if (gone) return
    Row(
        Modifier.fillMaxWidth().riseIn(0, intro).graphicsLayer {
            val p = sheet()
            alpha = (1f - p * 2.2f).coerceIn(0f, 1f)
            translationY = -p * 64.dp.toPx()
            scaleX = 1f - 0.06f * p; scaleY = scaleX
            transformOrigin = TransformOrigin(0.5f, 0f)
        }.padding(start = 16.dp, end = 16.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Opens the profile; the avatar itself flies there (log out lives on the profile now).
        Box(
            Modifier.sharedAvatar().size(44.dp).glass(CircleShape).clickable(onClickLabel = stringResource(R.string.profile_open)) { nav.navigate(R0.PROFILE) { launchSingleTop = true } }.testTag("avatar"),
            contentAlignment = Alignment.Center,
        ) {
            if (avatar != null) AsyncImage(avatar, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
            else BrandImage(brand.isotype, "isotype.svg", Modifier.padding(9.dp).fillMaxSize())
        }
        // Centre pill: the user's Lightning address (tap to receive) or the logotype; the onchain primary; USDT.
        val address = me?.lightningAddress
        // Swipe the address sideways to switch between the account's addresses (and their wallets): a local choice.
        val all by c.wallet.addresses.collectAsState()
        val selected by c.wallet.selectedAddress.collectAsState()
        val users = remember(all) { c.wallet.switchable(all).map { it.username } }
        val primaryUser = address?.substringBefore('@')
        var idx by remember { mutableIntStateOf(0) }
        LaunchedEffect(users, selected, primaryUser) { idx = users.indexOf(selected).takeIf { it >= 0 } ?: users.indexOf(primaryUser).coerceAtLeast(0) }
        val span = with(LocalDensity.current) { 72.dp.toPx() }
        var drag by remember { mutableFloatStateOf(0f) }
        val canSwipe = address != null && users.size > 1 && HomePager.page == LIGHTNING
        val dragState = rememberDraggableState { d ->
            val next = drag + d
            // Past the first or last address it only gives a little.
            drag = (if ((if (next < 0) idx + 1 else idx - 1) in users.indices) next else drag + d * 0.25f).coerceIn(-span, span)
        }
        Box(
            // Tap: receive. Hold (Lightning, with an address): the pill expands into the Lightning addresses screen,
            // "charging" (shrinks, dims) up to the long press.
            Modifier.weight(1f).graphicsLayer { val p = addressHold; scaleX = 1f - 0.05f * p; scaleY = scaleX; alpha = 1f - 0.25f * p }
                .then(if (address != null) Modifier.sharedAddress() else Modifier)
                .height(44.dp).glass(CircleShape)
                .draggable(dragState, Orientation.Horizontal, enabled = canSwipe, onDragStopped = { v ->
                    val dir = if (drag < 0) 1 else -1 // 1 = the next address, coming in from the right
                    val target = idx + dir
                    if (drag != 0f && target in users.indices && (abs(drag) > span * 0.35f || -dir * v > 600f)) {
                        animate(drag, -dir * span, animationSpec = tween(160, easing = FastOutSlowInEasing)) { x, _ -> drag = x }
                        idx = target; drag = 0f
                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        c.wallet.selectAddress(users[target].takeIf { it != primaryUser })
                    } else animate(drag, 0f, animationSpec = spring(0.8f, Spring.StiffnessMedium)) { x, _ -> drag = x }
                })
                .combinedClickable(
                    interactionSource = addressPress, indication = ripple(),
                    onLongClick = {
                        if (HomePager.page == LIGHTNING && address != null) {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            nav.navigate(R0.ADDRESSES) { launchSingleTop = true }
                        }
                    },
                ) {
                    when (HomePager.page) {
                        ONCHAIN -> if (!ONCHAIN_SOON) nav.navigate(primary?.let { R0.onchainWallet(it.id) } ?: R0.ONCHAIN_ADD) { launchSingleTop = true }
                        LIGHTNING -> if (address != null) nav.navigate(R0.RECEIVE)
                        else -> context.toast(usdtSoon)
                    }
                }.testTag("home-address"),
        ) {
            // A little carousel inside the pill (it clips): each page's face sits on its page's side and slides in
            // 1:1 with the swipe.
            val face = { page: Int -> Modifier.matchParentSize().graphicsLayer { translationX = (page - HomePager.position) * size.width; alpha = smooth(0.15f, 1f, HomePager.weight(page)) } }
            Row(face(ONCHAIN).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Box(Modifier.size(22.dp).clip(CircleShape).background(Lw.Bitcoin), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_bitcoin, size = 15.dp, tint = Color.White) }
                Spacer(Modifier.width(8.dp))
                Txt(primary?.name ?: stringResource(R.string.onchain_title), Modifier.weight(1f, fill = false), size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
            }
            Box(face(LIGHTNING), contentAlignment = Alignment.Center) {
                if (address != null) Row(Modifier.padding(horizontal = 14.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                    // Username leads; the domain sits a step dimmer and never truncates.
                    if (users.size > 1) SwipeUsername(users, idx.coerceIn(users.indices), { drag }, span, Modifier.weight(1f, fill = false))
                    else Txt(address.substringBefore('@'), Modifier.weight(1f, fill = false), size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
                    Txt("@" + address.substringAfter('@'), size = 16.sp, weight = FontWeight.Medium, color = Lw.Foreground.copy(alpha = 0.6f), maxLines = 1)
                }
                else BrandImage(brand.logotype, "logotype.svg", Modifier.height(20.dp).width(88.dp))
            }
            Row(face(USDT).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Image(painterResource(R.drawable.ic_usdt), null, Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Txt("USDT", size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
            }
        }
        GlassIconButton(
            L.drawable.lucide_ic_settings, { nav.navigate(R0.SETTINGS) { popUpTo(R0.HOME); launchSingleTop = true } },
            Modifier.testTag("home-settings"), contentDescription = stringResource(R.string.shared_nav_settings),
        )
    }
}

/** In place of the balance for an alias address: where its payments go, with that address's face and name. */
@Composable
private fun ForwardFace(target: String) {
    val c = LocalContainer.current
    val profile = rememberAddressProfile(target)
    Column(Modifier.testTag("balance-forward"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LwIcon(L.drawable.lucide_ic_forward, size = 14.dp, tint = Lw.Lightning)
            Spacer(Modifier.width(6.dp))
            Txt(stringResource(R.string.home_forwardsTo), size = 13.sp, weight = FontWeight.Medium, color = Lw.Lightning)
        }
        Spacer(Modifier.height(10.dp))
        Row(
            Modifier.glass(CircleShape, GlassLevel.Thin, tint = Lw.Lightning.copy(alpha = 0.08f)).padding(start = 6.dp, end = 18.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(profile?.picture ?: c.avatars.of(target), null, Modifier.size(44.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false)) {
                profile?.name?.let { Txt(it, size = 17.sp, weight = FontWeight.SemiBold, maxLines = 1) }
                Txt(target, size = if (profile?.name != null) 13.sp else 17.sp, weight = if (profile?.name != null) FontWeight.Normal else FontWeight.SemiBold, color = if (profile?.name != null) Lw.MutedFg else Lw.Foreground, maxLines = 1)
            }
        }
    }
}

/**
 * The username part of the pill while swiping between addresses: the current one slides and fades with [drag] while
 * its neighbour slides in from that side (cut at the "@", so the domain holds still). After a switch the slot eases to
 * the new name's width, which glides the whole address back to centre.
 */
@Composable
private fun SwipeUsername(users: List<String>, index: Int, drag: () -> Float, span: Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val width = { i: Int -> measurer.measure(users[i], style, maxLines = 1).size.width.toFloat() }
    val slot = remember { Animatable(width(index)) }
    LaunchedEffect(index, users) { slot.animateTo(width(index), spring(0.85f, Spring.StiffnessMediumLow)) }
    val d = drag()
    val neighbor = (if (d < 0) index + 1 else if (d > 0) index - 1 else -1).takeIf { it in users.indices }
    Layout(
        content = {
            Txt(users[index], size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
            if (neighbor != null) Txt(users[neighbor], size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
        },
        // Names may overflow to the left (the pill clips there), never right over the domain.
        modifier = modifier.drawWithContent { clipRect(left = -size.width * 4, right = size.width) { this@drawWithContent.drawContent() } },
    ) { ms, cs ->
        val names = ms.map { it.measure(Constraints(maxWidth = cs.maxWidth)) }
        val w = slot.value.roundToInt().coerceIn(0, cs.maxWidth)
        val h = names.maxOf { it.height }
        layout(w, h) {
            val x = drag()
            val p = (abs(x) / span).coerceIn(0f, 1f)
            names[0].placeWithLayer(w - names[0].width + x.roundToInt(), (h - names[0].height) / 2) { alpha = 1f - p }
            names.getOrNull(1)?.let { n -> n.placeWithLayer(w - n.width + (x - sign(x) * span).roundToInt(), (h - n.height) / 2) { alpha = p } }
        }
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
            // One height for all pages (the lowest page's slot), so a horizontal swipe never moves them; they still
            // ride the balance's vertical moves (wallet reveal), which every page mirrors.
            .offset {
                val y = anchors.y.filterNot(Float::isNaN).maxOrNull()
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

/** The Lightning page: the NWC balance, its activity, and the remote wallets above it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun LightningPage(current: Boolean, intro: Boolean, anchors: HomePages) {
    val c = LocalContainer.current
    val haptics = LocalHapticFeedback.current
    val balancePress = remember { MutableInteractionSource() }
    val balanceHold = holdProgress(balancePress)
    val nav = LocalNav.current
    val me by c.session.me.collectAsState()
    val settings by c.session.settings.collectAsState()
    val balance by c.wallet.balance.collectAsState()
    val loadingBalance by c.wallet.balanceLoading.collectAsState()
    val relayError by c.wallet.relayError.collectAsState()
    val client by c.wallet.client.collectAsState()
    val recent by c.wallet.recent.collectAsState()
    val hidden by c.prefs.hideBalance.collectAsState()
    val sensitiveGate = rememberSensitiveGate()
    val currencies by c.prefs.currencies.collectAsState()
    val rates by c.rates.rates.collectAsState()
    val scope = rememberCoroutineScope()
    var bonus by remember { mutableStateOf(c.prefs.bonusSats.takeIf { it > 0 }) }
    val context = LocalContext.current
    val density = LocalDensity.current

    // Swipe down: the balance settles at the bottom and the remote wallets show above it.
    val reveal = remember { HomeReveal() }
    reveal.bottomReserve = with(density) { 158.dp.toPx() } // floating tab bar (~110dp) plus breathing room
    reveal.minTravel = with(density) { 48.dp.toPx() }
    val open by remember { derivedStateOf { reveal.open } }
    var wallets by remember { mutableStateOf(c.prefs.cached("remote-wallets", remoteWalletsSer)) }
    val newWallet = rememberNewWallet()
    val walletBalances = rememberWalletBalances() // cached figures first, then live
    var promoting by remember { mutableStateOf<String?>(null) }
    // While the primary switches, the main balance shows the new wallet's known balance until its own arrives.
    var heldBalance by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        launch { c.wallet.refreshBalance() }
        fetchRemoteWallets(c, { wallets = it }, walletBalances)
        // Live while showing: every wallet is polled (failures keep the last figure).
        while (true) {
            delay(10_000)
            wallets.orEmpty().filter { it.status == "ACTIVE" }.forEach { w ->
                launch { readWalletBalance(c, w.id, walletBalances) }
            }
        }
    }
    // A payment on the main connection (NWC notification) re-reads the primary card at once, from its own endpoint.
    LaunchedEffect(open, balance) {
        val primary = wallets?.firstOrNull { it.isDefault && it.status == "ACTIVE" } ?: return@LaunchedEffect
        if (open && walletBalances.containsKey(primary.id)) readWalletBalance(c, primary.id, walletBalances)
    }
    // Sweep: hold a wallet → "Sweep funds" → pick the destination among the others (Cancel or back leaves).
    var sweepFrom by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(open) { if (!open) sweepFrom = null }
    // Coming back to Home (from a wallet's details, Add wallet, a sweep…) finds the wallet list as it was left:
    // open stays open (placed at once, no replay), and a just-added wallet opens it.
    var revealSaved by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val reopen = revealSaved || c.flow.revealWallets
        c.flow.revealWallets = false
        if (reopen) {
            snapshotFlow { reveal.range }.first { it > 0f } // wait for the layout to measure
            if (revealSaved) reveal.snapTo(1f) else reveal.settleTo(1f)
        }
        snapshotFlow { reveal.progress >= 1f }.collect { revealSaved = it }
    }
    // Delete: a wallet with funds is swept first (Sweeps.deleteAfter), then the prompt comes back here.
    var deleting by remember { mutableStateOf<RemoteWallet?>(null) }
    var sweepFirst by remember { mutableStateOf<Pair<RemoteWallet, Long>?>(null) }
    LaunchedEffect(wallets) {
        val id = Sweeps.deletePrompt ?: return@LaunchedEffect
        wallets?.find { it.id == id }?.let { deleting = it; Sweeps.deletePrompt = null }
    }
    BackHandler(open && current) { if (sweepFrom != null) { Sweeps.deleteAfter = null; sweepFrom = null } else scope.launch { reveal.settleTo(0f) } }
    // Optimistic: the tapped card turns primary at once; a failed request puts the old primary back.
    // A non-primary address picked on the pill: Home shows its wallet, where it forwards (alias), or that it has none.
    // The list marks its wallet (none when it has no active one), and a tap on a card links the address to that wallet.
    val addressList by c.wallet.addresses.collectAsState()
    val selectedAddress by c.wallet.selectedAddress.collectAsState()
    val picked = addressList.find { it.username == selectedAddress && !it.isPrimary }
    val forwardTo = picked?.takeIf { it.mode == "ALIAS" || it.mode == "PROXY_ALIAS" }?.redirect
    val shownWallet = picked?.takeIf { it.mode == "CUSTOM_NWC" && forwardTo == null }?.remoteWalletId
    val unlinked = picked != null && forwardTo == null && wallets?.none { it.id == shownWallet && it.status == "ACTIVE" } == true
    val detached = forwardTo != null || unlinked
    var forwarding by remember { mutableStateOf(false) }
    val linkPicked: (RemoteWallet) -> Unit = { w ->
        picked?.let { a ->
            scope.launch {
                runCatching { c.session.api.value.routeAddressToWallet(a.username, w.id) }
                    .onSuccess { c.wallet.refreshAddresses() }.onFailure { context.toast(it.message ?: "") }
            }
        }
    }
    val setPrimary: (RemoteWallet) -> Unit = { w ->
        if (promoting == null) scope.launch {
            val before = wallets
            val oldClient = c.wallet.client.value
            promoting = w.id
            wallets = wallets?.map { it.copy(isDefault = it.id == w.id) }
            heldBalance = walletBalances[w.id]
            runCatching { c.session.api.value.updateRemoteWallet(w.id, isDefault = true) }
                .onSuccess {
                    c.prefs.notePrimary(w.id)
                    wallets?.let { c.prefs.setCached("remote-wallets", remoteWalletsSer, it) }
                    promoting = null
                    runCatching { c.session.refreshMe() }
                    // Hold the list's figure until the new wallet answers: its first emission is the on-device
                    // cache (stale, often 0), so wait for a live read instead.
                    withTimeoutOrNull(10_000) { c.wallet.refreshBalance(c.wallet.client.first { it != null && it !== oldClient }) }
                    heldBalance = null
                }
                .onFailure { wallets = before; heldBalance = null; promoting = null; context.toast(it.message ?: "") }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { c.session.refreshMe() }
        c.wallet.refreshActivity()
        c.session.refreshProfile()
    }

    // The odometer rolls the digits; this only tints the figure while a change lands.
    var seenBalance by remember { mutableStateOf(balance) }
    var rolling by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(balance) {
        val prev = seenBalance
        seenBalance = balance
        if (prev == null || balance == null || prev == balance) return@LaunchedEffect
        rolling = if (balance!! > prev) "incoming" else "outgoing"
        delay(900)
        rolling = null
    }
    // Payment cue: lives as long as the payment notice (2.66s), like the web's `cue`.
    var cue by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        c.wallet.notices.collectLatest { n -> cue = if (n.type == "payment_received") "incoming" else "outgoing"; delay(2660); cue = null }
    }
    val motion = rolling ?: cue

    val hasNwc = client != null
    val autoCreate = settings?.lncurlAutoCreate == true
    val code = currencies.selected.takeIf { it in currencies.active } ?: "SAT"

    val shown = heldBalance ?: balance

    val sheet = rememberActivitySheet(enabled = hasNwc && recent.isNotEmpty() && !detached)
    SideEffect { anchors.reveal[LIGHTNING] = reveal; anchors.sheet[LIGHTNING] = sheet }
    reveal.sheetProgress = { sheet.progress }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { reveal.rootTop = it.positionInRoot().y; reveal.rootH = it.size.height.toFloat() }
            .nestedScroll(reveal.connection).testTag("home-lightning"),
    ) {
      ActivitySheetLayout(
        sheet,
        header = {
            // Room for the fixed header and its pull-down arrows (HomeScreen draws them over both pages).
            Spacer(Modifier.fillMaxWidth().height(HEADER_H).measureRoot { reveal.headerBottom = it.bottom })
        },
        floor = anchors.floorFor(LIGHTNING), onNeed = { anchors.need[LIGHTNING] = it },
        top = {
            // The balance: free on the prism light (no panel).
            Box(
                Modifier.fillMaxWidth()
                    // Measured at rest; the reveal then slides it down to the bottom, 1:1 with the finger.
                    .measureRoot { reveal.balanceTop = it.top; reveal.balanceH = it.height }
                    .graphicsLayer { translationY = reveal.progress * reveal.range }
                    .riseIn(1, intro).padding(start = 16.dp, end = 16.dp, top = 20.dp),
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 22.dp, bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Once the wallets show, the figure is named after the wallet it belongs to.
                    val primaryLabel by remember { derivedStateOf { reveal.progress > 0.5f } }
                    BalanceLabel(
                        primaryLabel, Modifier.pinned(anchors, LIGHTNING),
                        primaryText = wallets?.let { ws -> if (picked == null) ws.find { it.isDefault } else ws.find { it.id == shownWallet } }?.name,
                        badge = if (relayError != null && hasNwc) ({ RelayBadge(balance != null, relayError!!) { scope.launch { c.wallet.refreshBalance() } } }) else null,
                    )
                    // Fixed-height slot: cue effects never resize it.
                    val showLabel = stringResource(if (hidden) R.string.home_showBalance else R.string.home_hideBalance)
                    Box(
                        Modifier.fillMaxWidth().pinnedSlot(anchors, LIGHTNING)
                            // Tap the figure to hide/show it, same as the eye button (no ripple: it's the hero number).
                            // Hold it to open the wallet it belongs to (the primary), or the wallet list without one.
                            // Only on the page in front: the other's pinned copy lies over it and must let taps through.
                            .then(if (!current) Modifier else Modifier.combinedClickable(
                                interactionSource = balancePress, indication = null,
                                onClickLabel = showLabel,
                                onLongClick = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    val primary = wallets?.firstOrNull { it.isDefault }
                                    nav.navigate(if (primary != null) R0.remoteWallet(primary.id) else R0.REMOTE_WALLETS) { launchSingleTop = true }
                                },
                            ) { if (hidden) sensitiveGate { c.prefs.setHideBalance(false) } else c.prefs.setHideBalance(true) })
                            .padding(top = 16.dp, bottom = 2.dp).heightIn(min = 76.dp).testTag("balance-toggle")
                            .measureRoot { if (reveal.progress == 0f) reveal.figure = it }
                            // Holding "charges" the figure (shrinks, dims) up to the long press; inside measureRoot so the ghost's start isn't skewed.
                            .graphicsLayer { rollIn(LIGHTNING); val p = balanceHold; scaleX = 1f - 0.06f * p; scaleY = scaleX; alpha *= 1f - 0.3f * p },
                        contentAlignment = Alignment.Center,
                    ) {
                        FigureStrut()
                        when {
                            forwardTo != null -> ForwardFace(forwardTo)
                            unlinked -> Txt(stringResource(R.string.home_unlinked), Modifier.testTag("balance-unlinked"), size = 40.sp, weight = FontWeight.SemiBold, color = Lw.MutedFg)
                            shown == null && hasNwc && loadingBalance -> LoadingDots(Modifier.testTag("balance-loading"), dot = 12.dp)
                            shown == null && relayError != null -> Txt(stringResource(R.string.shared_balance_unavailable), size = 16.sp, color = Lw.Red)
                            else -> AnimatedContent(
                                code,
                                transitionSpec = {
                                    // Slide toward the newly picked pill: later in the list → enters from the right.
                                    val dir = if (currencies.active.indexOf(targetState) >= currencies.active.indexOf(initialState)) 1 else -1
                                    val ease = tween<IntOffset>(190, easing = FastOutSlowInEasing)
                                    (slideInHorizontally(ease) { dir * it / 3 } + fadeIn(tween(140, delayMillis = 40)))
                                        .togetherWith(slideOutHorizontally(ease) { -dir * it / 3 } + fadeOut(tween(90)))
                                        .using(SizeTransform(clip = false))
                                },
                                contentAlignment = Alignment.Center,
                                label = "currency",
                            ) { cc -> BalanceFigure(shown ?: 0, cc, rates, hidden, motion, fromZero = intro) }
                        }
                    }
                    // Receive / Send live in the bottom bar.
                    if (detached) Box(Modifier.padding(top = 12.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        // Closed: a button opens the wallet list to pick one. Open: the list is right there, so a note
                        // asks for a pick instead (and, without a wallet, offers forwarding to another address).
                        LwButton(
                            stringResource(if (forwardTo != null) R.string.home_setWallet else R.string.home_selectWallet), { scope.launch { reveal.settleTo(1f) } },
                            Modifier.graphicsLayer { alpha = 1f - reveal.progress }.testTag("home-select-wallet"), BtnVariant.Secondary, enabled = current && !open, height = 44.dp, icon = L.drawable.lucide_ic_wallet,
                        )
                        Column(
                            Modifier.graphicsLayer { alpha = reveal.progress }.fillMaxWidth().glass(RoundedCornerShape(20.dp), GlassLevel.Thin, tint = Lw.Gold.copy(alpha = 0.10f))
                                .padding(horizontal = 14.dp, vertical = 12.dp).testTag("home-assign-wallet"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                LwIcon(L.drawable.lucide_ic_circle_alert, size = 16.dp, tint = Lw.Gold)
                                Spacer(Modifier.width(8.dp))
                                Txt(stringResource(if (forwardTo != null) R.string.home_pickWalletAlias else R.string.home_assignWallet, picked?.username.orEmpty()), size = 13.sp, color = Lw.Foreground, lineHeight = 18.sp)
                            }
                            if (unlinked) LwButton(
                                stringResource(R.string.home_forwardInstead), { forwarding = true }, Modifier.padding(top = 10.dp).testTag("home-forward"),
                                BtnVariant.Secondary, enabled = current && open, height = 36.dp, icon = L.drawable.lucide_ic_forward,
                            )
                        }
                    }
                    else if (currencies.active.size >= 2) CurrencyToggle(
                        currencies.active, code, { cc -> c.prefs.setCurrencies(currencies.copy(selected = cc, localEdit = true)) }, Modifier.padding(top = 12.dp).riseIn(2, intro).pinnedGlide(anchors, LIGHTNING, anchors.toggleY),
                        onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); nav.navigate(R0.CURRENCIES) { launchSingleTop = true } }, // hold: currency settings
                        enabled = current,
                    )
                    DotsSlot(anchors, LIGHTNING)
                }
            }
        },
        below = {
            bonus?.let { b ->
                Row(Modifier.fillMaxWidth().revealAside(reveal).padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Top) {
                    Txt(stringResource(R.string.home_bonusNote, Currencies.formatSats(b)), size = 12.sp, color = Lw.MutedFg)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.size(16.dp).clickable { c.prefs.bonusSats = 0; bonus = null }) { LwIcon(L.drawable.lucide_ic_x, size = 14.dp, tint = Lw.MutedFg) }
                }
            }
            if (me != null && me?.lightningAddress == null) Row(
                    Modifier.revealAside(reveal).riseIn(3, intro).padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), tint = Lw.NwcPurple.copy(alpha = 0.10f))
                        .clickable { nav.navigate("${R0.CLAIM}?from=&bonus=0") }.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp).testTag("claim-cta"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(40.dp).glass(RoundedCornerShape(14.dp), GlassLevel.Thin, tint = Lw.NwcPurple.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                        LwIcon(L.drawable.lucide_ic_sparkles, size = 18.dp, tint = Lw.NwcPurple)
                    }
                    Spacer(Modifier.width(12.dp))
                    Txt(stringResource(R.string.home_claimAddress), Modifier.weight(1f), size = 15.sp, weight = FontWeight.Medium)
                    LwIcon(L.drawable.lucide_ic_chevron_right, size = 18.dp, tint = Lw.MutedFg)
                }
            if (me != null && !hasNwc) {
                Column(Modifier.revealAside(reveal).riseIn(3, intro).padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), GlassLevel.Thin).padding(18.dp)) {
                    Txt(stringResource(if (autoCreate) R.string.home_autoCreateTitle else R.string.home_noWalletTitle), size = 15.sp, weight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Txt(stringResource(if (autoCreate) R.string.home_autoCreateBody else R.string.home_noWalletBody), size = 13.sp, color = Lw.MutedFg, lineHeight = 18.sp)
                    if (!autoCreate) {
                        Spacer(Modifier.height(12.dp))
                        LwButton(stringResource(R.string.home_connectWallet), { nav.navigate(R0.REMOTE_WALLETS) }, variant = BtnVariant.Secondary, height = 40.dp)
                    }
                }
            }

        },
        sheetContent = {
            Box(Modifier.graphicsLayer {
                // The activity sheet drops away faster than the balance, so the two never overlap.
                translationY = reveal.progress * (reveal.range + 160.dp.toPx())
                alpha = (1f - reveal.progress * 1.6f).coerceIn(0f, 1f)
            }.riseIn(4, intro, rise = 48.dp)) {
                if (!detached) ActivitySheetContent(sheet, recent, hasNwc, current, Modifier.pinned(anchors, LIGHTNING), onOpen = { tx -> c.flow.activityDetail = tx; nav.navigate(R0.activity(tx.paymentHash)) })
            }
        },
      )
      WalletsReveal(
          reveal, wallets?.let { ws -> if (picked == null) ws else ws.map { it.copy(isDefault = it.id == shownWallet) } }, walletBalances, code, rates, hidden,
          onOpen = { nav.navigate(R0.remoteWallet(it.id)) },
          onSetPrimary = { w -> if (picked != null) linkPicked(w) else setPrimary(w) },
          onAdd = { nav.navigate(R0.REMOTE_WALLET_ADD) },
          newWalletId = newWallet,
          onManage = { nav.navigate(R0.REMOTE_WALLETS) },
          sweepFrom = sweepFrom,
          onSweep = { Sweeps.deleteAfter = null; sweepFrom = it.id },
          onSweepTo = { to -> sweepFrom?.let { from -> sweepFrom = null; nav.navigate(R0.sweep(from, to.id)) } },
          onSweepCancel = { Sweeps.deleteAfter = null; sweepFrom = null },
          onDelete = { w, bal -> if ((bal ?: 0L) > 0L) sweepFirst = w to bal!! else deleting = w },
          onTransfer = { w -> scope.launch { nav.navigate(startSend(c, Destination.Wallet(w.id, w.name))) } },
          // Optimistic: the card shows the new name at once; a failed save puts the old one back.
          onRename = { w, name ->
              val before = wallets
              wallets = wallets?.map { if (it.id == w.id) it.copy(name = name) else it }
              scope.launch {
                  runCatching { c.session.api.value.updateRemoteWallet(w.id, name = name) }
                      .onSuccess { wallets?.let { c.prefs.setCached("remote-wallets", remoteWalletsSer, it) } }
                      .onFailure { wallets = before; context.toast(it.message ?: "") }
              }
          },
      )
      if (forwarding) picked?.let { a ->
          val invalid = stringResource(R.string.home_forwardInvalid)
          TextPrompt(stringResource(R.string.home_forwardTitle), "", { forwarding = false }, saveLabel = stringResource(R.string.home_forwardSave), placeholder = "name@domain.com", maxLength = 120) { target ->
              if (!Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(target)) context.toast(invalid)
              else {
                  forwarding = false
                  scope.launch {
                      runCatching { c.session.api.value.redirectAddress(a.username, target.lowercase()) }
                          .onSuccess { c.wallet.refreshAddresses(); reveal.settleTo(0f) }.onFailure { context.toast(it.message ?: "") }
                  }
              }
          }
      }
      sweepFirst?.let { (w, bal) ->
          LwAlertDialog(
              onDismissRequest = { sweepFirst = null },
              title = { Txt(stringResource(R.string.rw_delete)) },
              text = { Txt(stringResource(R.string.rw_delete_sweepFirst, Currencies.formatSats(bal))) },
              confirmButton = { ModalAction(stringResource(R.string.sweep_menu), { sweepFirst = null; Sweeps.deleteAfter = w.id; sweepFrom = w.id }) },
          )
      }
      deleting?.let { w ->
          LwAlertDialog(
              onDismissRequest = { deleting = null },
              title = { Txt(w.name) },
              text = { Txt(stringResource(R.string.rw_delete_confirm)) },
              confirmButton = { ModalAction(stringResource(R.string.rw_delete), {
                  deleting = null
                  val before = wallets
                  wallets = wallets?.filter { it.id != w.id }
                  scope.launch {
                      runCatching { c.session.api.value.deleteRemoteWallet(w.id, permanent = w.status != "ACTIVE") }
                          .onSuccess {
                              wallets = wallets?.let { ensurePrimary(c, it) } // deleting the primary hands it on
                              wallets?.let { c.prefs.setCached("remote-wallets", remoteWalletsSer, it) }
                              runCatching { c.session.refreshMe() }
                          }
                          .onFailure { wallets = before; context.toast(it.message ?: "") }
                  }
              }, destructive = true) },
          )
      }
      if (open && wallets.orEmpty().any { it.isDefault } && shown != null) {
          BalanceGhost(reveal, if (hidden) "•••••" else Currencies.formatBalance(shown, code, rates), if (code == "BTC" && !hidden) 44.sp else 56.sp)
      }
    }
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

@Composable
private fun RowScope.OdometerWheel(digit: Int, index: Int, size: TextUnit, letterSpacing: TextUnit, color: Color, fromZero: Boolean, lineHeight: TextUnit) {
    val pos = remember { Animatable(if (fromZero) 0f else digit.toFloat()) }
    LaunchedEffect(digit) {
        // Intro: left-most wheels settle first, the units keep spinning a beat longer.
        if (pos.value == 0f && fromZero) delay(250L + index * 60L)
        pos.animateTo(digit.toFloat(), tween(if (fromZero) 1100 else 700, easing = FastOutSlowInEasing))
    }
    // One Text node (transparent target digit) owns layout + baseline; the rolling faces are painted over it.
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = Inter, fontFeatureSettings = "tnum", fontSize = size, fontWeight = FontWeight.SemiBold, lineHeight = lineHeight, letterSpacing = letterSpacing, color = color)
    Txt(
        digit.toString(),
        Modifier.alignByBaseline().drawWithContent {
            val p = pos.value
            val base = floor(p)
            val frac = p - base
            val lo = ((base.toInt() % 10) + 10) % 10
            val h = this.size.height
            clipRect(-h, 0f, this.size.width + h, h) {
                drawText(measurer.measure(lo.toString(), style), topLeft = Offset(0f, -frac * h), alpha = 1f - frac)
                if (frac > 0f) drawText(measurer.measure(((lo + 1) % 10).toString(), style), topLeft = Offset(0f, (1f - frac) * h), alpha = frac)
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
        title = { Txt(stringResource(R.string.shared_relay_title), weight = FontWeight.SemiBold, size = 16.sp) },
        text = { Txt(if (error.contains("time", true)) stringResource(R.string.shared_relay_timeout) else stringResource(R.string.shared_relay_generic), color = Lw.MutedFg) },
        confirmButton = { ModalAction(stringResource(R.string.common_retry), { open = false; onRetry() }) },
        containerColor = Glass.Dialog,
    )
}
