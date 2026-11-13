package io.lawallet.wallet.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt
import io.lawallet.wallet.R
import io.lawallet.wallet.app.Tx
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/*
 * Home's activity sheet. Docked under the balance with its tail faded out. A drag anywhere on Home
 * lifts the whole list (Home fades and recedes behind it) until it locks at the top; from there the
 * same gesture scrolls the list. Dragging down with the list at its top brings Home back. Releases
 * settle to the end the gesture aimed at (velocity first, else the nearer end).
 */
@Stable
internal class ActivitySheet(initial: Float) {
    /** 0 = docked under the balance, 1 = covering Home. */
    var progress by mutableFloatStateOf(initial)
        private set
    var enabled by mutableStateOf(false)

    /** Docked-to-expanded distance in px, set by the layout. */
    internal var range = 0f
    private var settling: Job? = null

    /** Moves the sheet by a finger delta (negative = up); returns the part of [dy] it used. */
    private fun dragBy(dy: Float): Float {
        if (range <= 0f || !enabled) return 0f
        settling?.cancel()
        val old = progress
        progress = (old - dy / range).coerceIn(0f, 1f)
        return (old - progress) * range
    }

    private suspend fun settleTo(target: Float, velocity: Float = 0f) = coroutineScope {
        settling = coroutineContext[Job]
        animate(progress, target, if (range > 0f) velocity / range else 0f, spring(stiffness = Spring.StiffnessMediumLow)) { v, _ -> progress = v.coerceIn(0f, 1f) }
    }

    suspend fun collapse() = settleTo(0f)

    /** Jumps to [p] (mirroring the other Home page; nothing to animate, it's out of sight). */
    fun snapTo(p: Float) { settling?.cancel(); progress = p.coerceIn(0f, 1f) }

    /** Up-drags lift the sheet before the list scrolls; down-drags the list didn't use lower it. */
    val connection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource) =
            if (available.y < 0f && progress < 1f && source == NestedScrollSource.UserInput) Offset(0f, dragBy(available.y)) else Offset.Zero

        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource) =
            if (available.y > 0f && progress > 0f && source == NestedScrollSource.UserInput) Offset(0f, dragBy(available.y)) else Offset.Zero

        override suspend fun onPreFling(available: Velocity): Velocity {
            if (progress <= 0f || progress >= 1f) return Velocity.Zero
            val v = available.y
            val target = when { v < -FLING -> 1f; v > FLING -> 0f; progress > 0.5f -> 1f; else -> 0f }
            settleTo(target, -v)
            return available
        }
    }

    companion object {
        private const val FLING = 900f // px/s
        val Saver = Saver<ActivitySheet, Float>({ if (it.progress > 0.5f) 1f else 0f }, { ActivitySheet(it) })
    }
}

@Composable
internal fun rememberActivitySheet(enabled: Boolean): ActivitySheet {
    val sheet = rememberSaveable(saver = ActivitySheet.Saver) { ActivitySheet(0f) }
    sheet.enabled = enabled
    LaunchedEffect(enabled) { if (!enabled) sheet.collapse() }
    return sheet
}

/**
 * Home's [header], [top] (the balance) and [below] (banners) with the activity sheet docked beneath. The sheet
 * docks with its grabber a little above mid-screen, and [top] centres between the header and that line as if nothing hung
 * under it, so the balance sits at the same spot on every page (Onchain and Lightning swipe past each other with
 * it fixed). [below] hangs under the balance and pushes the sheet down instead. Home itself doesn't scroll: every
 * drag drives the sheet.
 */
@Composable
internal fun ActivitySheetLayout(
    sheet: ActivitySheet, header: @Composable () -> Unit, top: @Composable ColumnScope.() -> Unit, below: @Composable ColumnScope.() -> Unit = {},
    /** Dock at least this low (the other Home page's need), and report this page's through [onNeed]: the line matches across the swipe. */
    floor: Float = 0f, onNeed: (Float) -> Unit = {},
    sheetContent: @Composable () -> Unit,
) {
    var fullH by remember { mutableIntStateOf(0) }
    var headH by remember { mutableIntStateOf(0) }
    var topH by remember { mutableIntStateOf(0) }
    var belowH by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    // ponytail: if Home's top content is taller than the screen minus a peek, its tail hides under the sheet; make it scroll if banners ever stack that high.
    // Grabber line (16dp + half its 4dp) on the centre line, then raised so the docked sheet is ~15% taller (+10%, then +5%).
    val mid = with(density) { fullH - (fullH / 2f + 18.dp.toPx()) * 1.155f }
    val lift = ((mid - headH - topH) / 2f).coerceAtLeast(0f)
    val content = headH + lift + topH + belowH
    SideEffect { onNeed(content) }
    val docked = if (fullH == 0) content else minOf(maxOf(content, mid, floor), fullH - with(density) { 200.dp.toPx() }).coerceAtLeast(0f)
    sheet.range = docked
    Box(
        Modifier.fillMaxSize().onSizeChanged { fullH = it.height }
            .nestedScroll(sheet.connection)
            // Drags that start outside the list (on the balance, buttons…) go through the same connection.
            .scrollable(rememberScrollableState { 0f }, Orientation.Vertical),
    ) {
        Column(
            Modifier.fillMaxWidth().graphicsLayer {
                val p = sheet.progress
                alpha = (1f - p * 2.2f).coerceIn(0f, 1f) // gone by ~45%, so it never fights the rising list
                // No offscreen buffer: it would clip to this column's measured height, and [top] is translated past it
                // (the lift), cropping the currency toggle and dots as soon as the fade starts.
                compositingStrategy = CompositingStrategy.ModulateAlpha
                translationY = -p * 64.dp.toPx()
                scaleX = 1f - 0.06f * p
                scaleY = scaleX
                transformOrigin = TransformOrigin(0.5f, 0f)
            },
        ) {
            Column(Modifier.fillMaxWidth().onSizeChanged { headH = it.height }) { header() }
            // Translation, not padding, so the measured heights stay natural (no measure loop).
            Column(Modifier.fillMaxWidth().graphicsLayer { translationY = lift }) {
                Column(Modifier.fillMaxWidth().onSizeChanged { topH = it.height }, content = top)
                Column(Modifier.fillMaxWidth().onSizeChanged { belowH = it.height }, content = below)
            }
        }
        Box(Modifier.fillMaxSize().graphicsLayer { translationY = docked * (1f - sheet.progress) }) { sheetContent() }
    }
}

/**
 * Fades out the docked tail of a list that runs to the sheet's bottom (behind the tab bar); it clears as the sheet
 * rises, gone when fully expanded. On the rows only, not the whole sheet: an offscreen layer clips to its page, and
 * the title and grabber above are pinned while the Home pages swipe.
 */
internal fun Modifier.sheetTailFade(sheet: ActivitySheet) = graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val bottom = size.height - sheet.range * (1f - sheet.progress)
        drawRect(Brush.verticalGradient(0f to Color.Black, 1f to Color.Black.copy(alpha = sheet.progress), startY = bottom - 260.dp.toPx(), endY = bottom - 70.dp.toPx()), blendMode = BlendMode.DstIn)
    }

/** The sheet's "Activity" title, shared by every Home page. */
@Composable
internal fun SheetTitle(sheet: ActivitySheet, pin: Modifier) {
    // The title only shows as the sheet rises: docked, it takes no room and the rows sit right under the grabber.
    Row(
        Modifier.fillMaxWidth().then(pin).clipToBounds()
            .layout { m, c -> val p = m.measure(c); val h = (p.height * sheet.progress).roundToInt(); layout(p.width, h) { p.place(0, 0) } }
            .graphicsLayer { alpha = sheet.progress }
            .padding(start = 16.dp, end = 16.dp, top = 6.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Txt(
            stringResource(R.string.home_activityTitle), size = 15.sp, weight = FontWeight.SemiBold,
            modifier = Modifier.graphicsLayer { val s = 1f + 0.45f * sheet.progress; scaleX = s; scaleY = s; transformOrigin = TransformOrigin(0f, 0.5f) },
        )
    }
}

@Composable
internal fun ActivitySheetContent(sheet: ActivitySheet, recent: List<Tx>, hasNwc: Boolean, current: Boolean, pin: Modifier, onOpen: (Tx) -> Unit) {
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val paging = rememberActivityPaging(list, firstPage = false) { sheet.progress > 0.98f }
    BackHandler(current && sheet.progress > 0f) { scope.launch { launch { list.animateScrollToItem(0) }; sheet.collapse() } }
    // The bottom bar (AppNav) swaps to search + filters with this; docking clears them.
    LaunchedEffect(sheet) { snapshotFlow { sheet.progress }.collect { ActivitySearch.sheet = it; if (it == 0f) ActivitySearch.reset() } }
    DisposableEffect(Unit) { onDispose { ActivitySearch.sheet = 0f } }
    LaunchedEffect(recent) { ActivitySearch.maxSats = recent.maxOfOrNull { it.amountSats }?.coerceAtLeast(1L) ?: 1L }
    val s = ActivitySearch
    val filtered by remember(recent) { derivedStateOf { if (s.active) recent.filter(s::matches) else recent } }
    LaunchedEffect(s.query, s.sent, s.received, s.sizeOn, s.size) { if (s.active) list.scrollToItem(0) }
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        // Grabber: says "this moves", firmer as the sheet takes over.
        // Grabber and title stay put while the Home pages swipe ([pin]); the rows slide.
        Box(Modifier.align(Alignment.CenterHorizontally).then(pin).size(36.dp, 4.dp).graphicsLayer { alpha = 0.16f + 0.24f * sheet.progress }.background(Color.White, CircleShape))
        SheetTitle(sheet, pin)
        val empty = when {
            !hasNwc -> stringResource(R.string.home_list_activity_connect)
            recent.isEmpty() && !s.active -> stringResource(R.string.home_list_activity_empty)
            else -> null
        }
        if (empty != null) Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), GlassLevel.Thin).padding(vertical = 28.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
            MutedText(empty, align = TextAlign.Center)
        }
        else {
            val labels = rememberDayLabels()
            // Bottom padding lets the last rows scroll clear of the faded tail and the tab bar.
            val rows = remember(filtered) { activityRows(filtered, months = true) }
            val expanded by remember { derivedStateOf { sheet.progress > 0.98f } }
            Box(Modifier.weight(1f).sheetTailFade(sheet)) {
                LazyColumn(Modifier.fillMaxSize().testTag("home-activity"), state = list, contentPadding = PaddingValues(bottom = 260.dp)) {
                    activityList(rows, filtered, labels, paging, filtering = s.active, onOpen = onOpen)
                }
                androidx.compose.animation.AnimatedVisibility(filtered.isEmpty(), enter = fadeIn(tween(200)), exit = fadeOut(tween(120))) {
                    Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), GlassLevel.Thin).padding(vertical = 28.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        MutedText(stringResource(R.string.activity_search_noMatch), align = TextAlign.Center)
                    }
                }
                // Clear of the floating Receive/Scan/Send bar.
                if (expanded) TimelineScrubber(list, rows, Modifier.align(Alignment.TopEnd).padding(bottom = 138.dp))
                // Only once the sheet is up: a drag that lifts it isn't a scroll through months.
                if (expanded) ScrollMonthLabel(list, rows, Modifier.align(Alignment.TopStart).padding(start = 16.dp, top = 8.dp))
            }
        }
    }
}
