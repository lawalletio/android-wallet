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

/*
 * Home's USDT page, an upcoming asset: the same layout as Onchain and Lightning (balance, activity, the deck a swipe
 * down reveals), with "Soon" for the balance and nothing to add yet. It keeps every gesture so the pages read as one.
 */
@Composable
internal fun UsdtPage(current: Boolean, anchors: HomePages) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val currencies by c.prefs.currencies.collectAsState()
    val code = currencies.selected.takeIf { it in currencies.active } ?: "SAT"

    val reveal = remember { HomeReveal() }
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
            floor = anchors.floorFor(USDT), onNeed = { anchors.need[USDT] = it },
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
                        if (currencies.active.size >= 2) CurrencyToggle(
                            currencies.active, code, { cc -> c.prefs.setCurrencies(currencies.copy(selected = cc, localEdit = true)) }, Modifier.padding(top = 12.dp).pinnedGlide(anchors, USDT, anchors.toggleY),
                            onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); nav.navigate(R0.CURRENCIES) { launchSingleTop = true } },
                            enabled = current,
                        )
                        DotsSlot(anchors, USDT)
                    }
                }
            },
            below = { UsdtCta(Modifier.revealAside(reveal)) },
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

/**
 * What USDT will be, tagged Soon: one row, so it fits above the activity line every page shares (a taller card would
 * push that line down on all of them).
 */
@Composable
private fun UsdtCta(modifier: Modifier) {
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
}
