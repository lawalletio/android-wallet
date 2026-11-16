package io.lawallet.wallet.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.CurrencyToggle
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.launch
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import io.lawallet.wallet.ui.theme.GlassLevel
import androidx.compose.foundation.clickable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import io.lawallet.wallet.core.api.ApiException
import io.lawallet.wallet.core.api.PollState
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.rememberSettled
import com.composables.icons.lucide.R as L

/*
 * Home's USDT page, an upcoming asset: the same layout as Onchain and Lightning (balance, activity, the deck a swipe
 * down reveals), with "Soon" for the balance and nothing to add yet. It keeps every gesture so the pages read as one.
 */
@Composable
internal fun UsdtPage(current: Boolean, anchors: HomePages) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val currencies by c.prefs.currencies.collectAsState()
    val code = currencies.selected.takeIf { it in currencies.active } ?: "SAT"

    val reveal = remember { HomeReveal() }
    var heroExtra by remember { mutableFloatStateOf(0f) }
    reveal.bottomReserve = with(density) { 158.dp.toPx() }
    reveal.minTravel = with(density) { 48.dp.toPx() }
    val open by remember { derivedStateOf { reveal.open } }
    BackHandler(open && current) { scope.launch { reveal.settleTo(0f) } }
    val sheet = rememberActivitySheet(enabled = true)
    SideEffect { anchors.reveal[USDT] = reveal; anchors.sheet[USDT] = sheet }
    BackHandler(current && sheet.progress > 0f) { scope.launch { sheet.collapse() } }
    reveal.sheetProgress = { sheet.progress }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { reveal.rootTop = it.positionInRoot().y; reveal.rootH = it.size.height.toFloat() }
            .nestedScroll(reveal.connection).testTag("home-usdt"),
    ) {
        ActivitySheetLayout(
            sheet,
            header = { Spacer(Modifier.fillMaxWidth().height(HEADER_H).measureRoot { reveal.headerBottom = it.bottom }) },
            // The poll card is taller than the other pages' rows: only this page docks below it, the others keep their line.
            floor = anchors.floorFor(USDT), onNeed = { anchors.need[USDT] = it - heroExtra },
            top = {
                Box(
                    Modifier.fillMaxWidth()
                        .measureRoot { reveal.balanceTop = it.top; reveal.balanceH = it.height }
                        .graphicsLayer { translationY = reveal.progress * reveal.range }
                        .padding(start = 16.dp, end = 16.dp, top = 20.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 22.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val primaryLabel by remember { derivedStateOf { reveal.progress > 0.5f } }
                        BalanceLabel(primaryLabel, Modifier.pinned(anchors, USDT))
                        Box(
                            Modifier.fillMaxWidth().pinnedSlot(anchors, USDT).padding(top = 16.dp, bottom = 2.dp).heightIn(min = 76.dp)
                                .graphicsLayer { rollIn(USDT) }.testTag("usdt-soon"),
                            contentAlignment = Alignment.Center,
                        ) {
                            FigureStrut()
                            Txt(stringResource(R.string.onchain_soon_short), size = 56.sp, weight = FontWeight.SemiBold, color = Lw.MutedFg, lineHeight = 60.sp, letterSpacing = (-1.5).sp)
                        }
                        // No USDT amounts yet, so no currency to switch: the toggle keeps its space (invisible) so "Soon" sits
                        // where the other pages' balances do and the swipe doesn't jump.
                        if (currencies.active.size >= 2) CurrencyToggle(
                            currencies.active, code, {}, Modifier.padding(top = 12.dp).alpha(0f).clearAndSetSemantics {},
                            enabled = false,
                        )
                        DotsSlot(anchors, USDT)
                    }
                }
            },
            below = { UsdtCta(Modifier.revealAside(reveal), current) { heroExtra = it } },
            sheetContent = {
                Box(Modifier.graphicsLayer {
                    translationY = reveal.progress * (reveal.range + 160.dp.toPx())
                    alpha = (1f - reveal.progress * 1.6f).coerceIn(0f, 1f)
                }) { AssetActivity(sheet, stringResource(R.string.usdt_activity), Modifier.pinned(anchors, USDT)) }
            },
        )
        RevealDeck(
            reveal, emptyList<Unit>(), { it }, Modifier.testTag("usdt-wallets"),
            header = { DeckTitle(reveal, stringResource(R.string.usdt_wallets), null, {}, "usdt-wallets-add", addEnabled = false) },
            empty = { DeckEmpty(R.drawable.ic_usdt_mark, stringResource(R.string.usdt_empty)) },
        ) {}
    }
}

internal const val USDT_POLL = "usdt-tech"

/**
 * What USDT will be. While the instance runs the USDT poll it is a hero card: before voting it asks which network
 * users want; after, it shows their ranking with a way to change it (or that it's recorded, for a one-time poll).
 * Otherwise it is the one-row "Soon" teaser. [onExtra]: how much taller than that row it is, in px.
 */
@Composable
private fun UsdtCta(modifier: Modifier, current: Boolean, onExtra: (Float) -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val density = LocalDensity.current
    val ser = PollState.serializer()
    // Re-read on every return to Home, so a fresh answer shows right away.
    var poll by remember { mutableStateOf(c.prefs.cached(pollCacheKey(USDT_POLL), ser)) }
    val settled = rememberSettled()
    LaunchedEffect(current && settled) {
        if (current && settled) runCatching { c.session.api.value.poll(USDT_POLL) }
            .onSuccess { poll = it; c.prefs.setCached(pollCacheKey(USDT_POLL), ser, it) }
            .onFailure { if ((it as? ApiException)?.status == 404) poll = null }
    }
    val p = poll?.takeIf { it.poll.open || it.response != null }
    if (p == null) {
        SideEffect { onExtra(0f) }
        Row(
            modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).fillMaxWidth()
                .glass(RoundedCornerShape(24.dp), tint = Lw.Tether.copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 10.dp).testTag("usdt-cta"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Image(painterResource(R.drawable.ic_usdt), null, Modifier.size(22.dp))
            Spacer(Modifier.width(10.dp))
            Txt(stringResource(R.string.usdt_cta_title), Modifier.weight(1f), size = 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
            Spacer(Modifier.width(8.dp))
            Pill(stringResource(R.string.onchain_soon_short), Lw.Tether)
        }
        return
    }
    val open = { nav.navigate(R0.poll(USDT_POLL)) { launchSingleTop = true } }
    val ranked = p.response?.choices.orEmpty()
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).fillMaxWidth()
            .onSizeChanged { onExtra((it.height - with(density) { ROW_H.toPx() }).coerceAtLeast(0f)) }
            .glass(shape, GlassLevel.Regular, tint = Lw.Tether.copy(alpha = 0.16f))
            .border(1.dp, Brush.verticalGradient(listOf(Lw.Tether.copy(alpha = 0.55f), Lw.Tether.copy(alpha = 0.08f))), shape)
            .clip(shape).clickable(onClick = open).padding(16.dp).testTag("usdt-poll"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).glass(CircleShape, GlassLevel.Thin, tint = Lw.Tether.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Image(painterResource(R.drawable.ic_usdt), null, Modifier.size(26.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(stringResource(if (ranked.isEmpty()) R.string.usdt_poll_title else R.string.usdt_poll_voted_title), size = 17.sp, weight = FontWeight.SemiBold, lineHeight = 21.sp)
                if (ranked.isEmpty()) Txt(stringResource(R.string.usdt_poll_subtitle), size = 13.sp, color = Lw.MutedFg, lineHeight = 17.sp)
            }
        }
        if (ranked.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            // Their ranking, one per line, podium-tinted like the poll screen.
            Column(Modifier.padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                ranked.forEachIndexed { i, choice -> RankLine(i, p.label(choice)) }
            }
        }
        Spacer(Modifier.height(14.dp))
        when {
            ranked.isEmpty() -> UsdtPollButton(stringResource(R.string.usdt_poll_cta), onClick = open)
            p.locked || !p.poll.open -> Row(verticalAlignment = Alignment.CenterVertically) {
                LwIcon(L.drawable.lucide_ic_circle_check, size = 16.dp, tint = Lw.Tether)
                Spacer(Modifier.width(6.dp))
                Txt(stringResource(R.string.usdt_poll_recorded), size = 13.sp, color = Lw.Tether, weight = FontWeight.Medium)
            }
            else -> UsdtPollButton(stringResource(R.string.usdt_poll_change), icon = L.drawable.lucide_ic_pencil, onClick = open)
        }
    }
}

/** The compact "Soon" row's height (4dp top + 10dp padding twice + the 22dp icon): what the other pages dock under. */
private val ROW_H = 46.dp

@Composable
private fun RankLine(index: Int, label: String) {
    val tint = rankColor(index)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(24.dp).clip(CircleShape).background(tint.copy(alpha = if (index < 3) 0.22f else 0.10f)), contentAlignment = Alignment.Center) {
            Txt("${index + 1}", size = 12.sp, weight = FontWeight.Bold, color = if (index < 3) tint else Lw.Foreground)
        }
        Spacer(Modifier.width(12.dp))
        Txt(label, size = 15.sp, weight = FontWeight.Medium, maxLines = 1)
    }
}

/** Full-width solid Tether action: "Rank now" before voting, "Change preference" (with a pencil) after. */
@Composable
private fun UsdtPollButton(text: String, onClick: () -> Unit, @androidx.annotation.DrawableRes icon: Int? = null) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).clip(CircleShape).background(Lw.Tether).clickable(onClick = onClick).testTag("usdt-poll-action"),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { LwIcon(icon, size = 16.dp, tint = Color.White); Spacer(Modifier.width(8.dp)) }
        Txt(text, size = 15.sp, weight = FontWeight.SemiBold, color = Color.White)
    }
}
