package io.lawallet.wallet.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.app.OnchainWallet
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.fx.Rates
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.CurrencyToggle
import io.lawallet.wallet.ui.components.LwAlertDialog
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.ModalAction
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.holdProgress
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

/** Onchain is an upcoming feature: the page shows "Soon" for its balance and its ways in are disabled. Flip to ship. */
internal const val ONCHAIN_SOON = true

/*
 * Home's Onchain page: the Lightning page's layout for the onchain wallets kept on this device. Balance,
 * activity, and the wallet deck a swipe down reveals (tap a card to make it primary, hold it for its menu).
 */

/** "Native SegWit", "Watch-only · Taproot", or the backup warning: what a wallet is, in one line. */
@Composable
internal fun onchainSubtitle(w: OnchainWallet) = when {
    !w.backedUp -> stringResource(R.string.onchain_notBackedUp)
    w.watchOnly -> stringResource(R.string.onchain_watchOnly) + " · " + (w.script?.label ?: stringResource(R.string.onchain_address))
    else -> w.script?.label.orEmpty()
}

/** The wallet's tile: the ₿ for keys held here, an eye for watch-only. */
@Composable
internal fun OnchainMark(w: OnchainWallet, size: Int, sel: Float = 1f) {
    val accent = lerp(Lw.MutedFg, Lw.Bitcoin, sel)
    Box(
        Modifier.size(size.dp).clip(RoundedCornerShape((size * 0.32f).dp))
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.16f + 0.2f * sel), accent.copy(alpha = 0.06f + 0.04f * sel)))),
        contentAlignment = Alignment.Center,
    ) { LwIcon(if (w.watchOnly) L.drawable.lucide_ic_eye else L.drawable.lucide_ic_bitcoin, size = (size * 0.5f).dp, tint = lerp(Lw.MutedFg, Lw.Bitcoin, 0.4f + 0.6f * sel)) }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OnchainPage(current: Boolean, anchors: HomePages) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val density = LocalDensity.current
    val wallets by c.onchain.wallets.collectAsState()
    val hidden by c.prefs.hideBalance.collectAsState()
    val currencies by c.prefs.currencies.collectAsState()
    val rates by c.rates.rates.collectAsState()
    val code = currencies.selected.takeIf { it in currencies.active } ?: "SAT"
    val primary = wallets.firstOrNull { it.isPrimary }
    // ponytail: UI only, no chain backend yet: every wallet reads 0. Feed real balances here once addresses are derived and synced.
    val balances = remember(wallets) { wallets.associate { it.id to 0L } }
    val shown = primary?.let { balances[it.id] } ?: 0L
    val press = remember { MutableInteractionSource() }
    val hold = holdProgress(press)

    // Same reveal as Lightning: swipe down and the deck fans out of the balance.
    val reveal = remember { HomeReveal() }
    reveal.bottomReserve = with(density) { 158.dp.toPx() }
    reveal.minTravel = with(density) { 48.dp.toPx() }
    val open by remember { derivedStateOf { reveal.open } }
    BackHandler(open && current) { scope.launch { reveal.settleTo(0f) } }
    var revealSaved by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        val reopen = revealSaved || c.flow.revealOnchain
        c.flow.revealOnchain = false
        if (reopen) {
            snapshotFlow { reveal.range }.first { it > 0f }
            if (revealSaved) reveal.snapTo(1f) else reveal.settleTo(1f)
        }
        snapshotFlow { reveal.progress >= 1f }.collect { revealSaved = it }
    }
    var deleting by remember { mutableStateOf<OnchainWallet?>(null) }

    // Liftable like Lightning's, so a swipe from the raised Lightning activity lands on this one.
    val sheet = rememberActivitySheet(enabled = true)
    SideEffect { anchors.reveal[ONCHAIN] = reveal; anchors.sheet[ONCHAIN] = sheet }
    BackHandler(current && sheet.progress > 0f) { scope.launch { sheet.collapse() } }
    reveal.sheetProgress = { sheet.progress }
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { reveal.rootTop = it.positionInRoot().y; reveal.rootH = it.size.height.toFloat() }
            .nestedScroll(reveal.connection).testTag("home-onchain"),
    ) {
        ActivitySheetLayout(
            sheet,
            header = {
                Spacer(Modifier.fillMaxWidth().height(HEADER_H).measureRoot { reveal.headerBottom = it.bottom })
            },
            floor = anchors.floorFor(ONCHAIN), onNeed = { anchors.need[ONCHAIN] = it },
            top = {
                Box(
                    Modifier.fillMaxWidth()
                        .measureRoot { reveal.balanceTop = it.top; reveal.balanceH = it.height }
                        .graphicsLayer { translationY = reveal.progress * reveal.range }
                        .padding(start = 16.dp, end = 16.dp, top = 20.dp),
                ) {
                    Column(Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 22.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        val primaryLabel by remember { derivedStateOf { reveal.progress > 0.5f } }
                        BalanceLabel(primaryLabel, Modifier.pinned(anchors, ONCHAIN))
                        val showLabel = stringResource(if (hidden) R.string.home_showBalance else R.string.home_hideBalance)
                        Box(
                            Modifier.fillMaxWidth().pinnedSlot(anchors, ONCHAIN)
                                // Tap hides/shows (shared with Lightning); hold opens the primary wallet, or Add without one.
                                // Only on the page in front (the other's pinned copy lies over it).
                                .then(if (!current) Modifier else Modifier.combinedClickable(
                                    interactionSource = press, indication = null,
                                    onClickLabel = showLabel,
                                    onLongClick = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        if (!ONCHAIN_SOON) nav.navigate(primary?.let { R0.onchainWallet(it.id) } ?: R0.ONCHAIN_ADD) { launchSingleTop = true }
                                    },
                                ) { c.prefs.setHideBalance(!hidden) })
                                .padding(top = 16.dp, bottom = 2.dp).heightIn(min = 76.dp).testTag("onchain-balance-toggle")
                                .measureRoot { if (reveal.progress == 0f) reveal.figure = it }
                                .graphicsLayer { rollIn(ONCHAIN); scaleX = 1f - 0.06f * hold; scaleY = scaleX; alpha *= 1f - 0.3f * hold },
                            contentAlignment = Alignment.Center,
                        ) {
                            FigureStrut()
                            AnimatedContent(
                                code,
                                transitionSpec = {
                                    val dir = if (currencies.active.indexOf(targetState) >= currencies.active.indexOf(initialState)) 1 else -1
                                    val ease = tween<IntOffset>(190, easing = FastOutSlowInEasing)
                                    (slideInHorizontally(ease) { dir * it / 3 } + fadeIn(tween(140, delayMillis = 40)))
                                        .togetherWith(slideOutHorizontally(ease) { -dir * it / 3 } + fadeOut(tween(90)))
                                        .using(SizeTransform(clip = false))
                                },
                                contentAlignment = Alignment.Center,
                                label = "currency",
                            ) { cc ->
                                if (ONCHAIN_SOON) Txt(stringResource(R.string.onchain_soon_short), size = 56.sp, weight = FontWeight.SemiBold, color = Lw.MutedFg, lineHeight = 60.sp, letterSpacing = (-1.5).sp, modifier = Modifier.testTag("onchain-soon"))
                                else BalanceFigure(shown, cc, rates, hidden, null, fromZero = false)
                            }
                        }
                        if (currencies.active.size >= 2) CurrencyToggle(
                            currencies.active, code, { cc -> c.prefs.setCurrencies(currencies.copy(selected = cc, localEdit = true)) }, Modifier.padding(top = 12.dp).pinnedGlide(anchors, ONCHAIN, anchors.toggleY),
                            onLongPress = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); nav.navigate(R0.CURRENCIES) { launchSingleTop = true } },
                            enabled = current,
                        )
                        DotsSlot(anchors, ONCHAIN)
                    }
                }
            },
            below = {
                when {
                    wallets.isEmpty() -> OnchainCta(Modifier.revealAside(reveal), { nav.navigate(R0.ONCHAIN_CREATE) }, { nav.navigate(R0.ONCHAIN_IMPORT) })
                    primary?.backedUp == false -> Row(
                        Modifier.revealAside(reveal).padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth()
                            .glass(RoundedCornerShape(24.dp), tint = Lw.Bitcoin.copy(alpha = 0.10f))
                            .clickable { nav.navigate(R0.onchainBackup(primary.id)) }.padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp).testTag("onchain-backup-nudge"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(40.dp).glass(RoundedCornerShape(14.dp), GlassLevel.Thin, tint = Lw.Bitcoin.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                            LwIcon(L.drawable.lucide_ic_shield_alert, size = 18.dp, tint = Lw.Bitcoin)
                        }
                        Spacer(Modifier.width(12.dp))
                        Txt(stringResource(R.string.onchain_backup_nudge), Modifier.weight(1f), size = 15.sp, weight = FontWeight.Medium)
                        LwIcon(L.drawable.lucide_ic_chevron_right, size = 18.dp, tint = Lw.MutedFg)
                    }
                }
            },
            sheetContent = {
                Box(Modifier.graphicsLayer {
                    translationY = reveal.progress * (reveal.range + 160.dp.toPx())
                    alpha = (1f - reveal.progress * 1.6f).coerceIn(0f, 1f)
                }) { AssetActivity(sheet, stringResource(if (wallets.isNotEmpty()) R.string.onchain_activity_empty else R.string.onchain_activity_noWallet), Modifier.pinned(anchors, ONCHAIN)) }
            },
        )
        RevealDeck(
            reveal, wallets.sortedBy { it.isPrimary }, { it.id }, Modifier.testTag("onchain-wallets"),
            header = { DeckTitle(reveal, stringResource(R.string.onchain_wallets), null, { nav.navigate(R0.ONCHAIN_ADD) }, "onchain-wallets-add", addEnabled = !ONCHAIN_SOON) },
            empty = { DeckEmpty(L.drawable.lucide_ic_bitcoin, stringResource(R.string.onchain_empty_title)) },
            footer = { if (wallets.size > 1) WalletsTotal(wallets.sumOf { balances[it.id] ?: 0L }, code, rates, hidden, reveal) },
        ) { w ->
            OnchainRevealCard(
                w, balances[w.id], code, rates, hidden, reveal,
                onOpen = { nav.navigate(R0.onchainWallet(w.id)) },
                onSetPrimary = { c.onchain.setPrimary(w.id) },
                onRename = { name -> c.onchain.update(w.id) { it.copy(name = name) } },
                onBackup = { nav.navigate(R0.onchainBackup(w.id)) },
                onDelete = { deleting = w },
            )
        }
        if (open && primary != null && !ONCHAIN_SOON) BalanceGhost(reveal, if (hidden) "•••••" else Currencies.formatBalance(shown, code, rates), if (code == "BTC" && !hidden) 44.sp else 56.sp)
    }
    deleting?.let { w -> RemoveOnchainDialog(w, { deleting = null }) { deleting = null; c.onchain.remove(w.id) } }
}

/**
 * No wallets yet: the two ways in. Compact, so it fits above the activity line Lightning docks at (both pages
 * share that line, so a taller card would push Lightning's activity down too).
 */
@Composable
private fun OnchainCta(modifier: Modifier, onCreate: () -> Unit, onImport: () -> Unit) {
    Column(
        modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).fillMaxWidth()
            .glass(RoundedCornerShape(24.dp), tint = Lw.Bitcoin.copy(alpha = 0.07f)).padding(12.dp).testTag("onchain-cta"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(Modifier.padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            LwIcon(L.drawable.lucide_ic_bitcoin, size = 18.dp, tint = Lw.Bitcoin)
            Spacer(Modifier.width(8.dp))
            Txt(stringResource(R.string.onchain_cta_title), size = 14.sp, weight = FontWeight.SemiBold)
            if (ONCHAIN_SOON) { Spacer(Modifier.width(8.dp)); Pill(stringResource(R.string.onchain_soon_short), Lw.Bitcoin) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LwButton(stringResource(R.string.onchain_create), onCreate, Modifier.weight(1f).testTag("onchain-cta-create"), BtnVariant.Secondary, enabled = !ONCHAIN_SOON, height = 40.dp, icon = L.drawable.lucide_ic_plus)
            LwButton(stringResource(R.string.onchain_import), onImport, Modifier.weight(1f).testTag("onchain-cta-import"), BtnVariant.Secondary, enabled = !ONCHAIN_SOON, height = 40.dp, icon = L.drawable.lucide_ic_import)
        }
    }
}

/** The docked activity panel for a page without activity yet (Onchain, USDT): Lightning's twin, with [empty] in it. */
@Composable
internal fun AssetActivity(sheet: ActivitySheet, empty: String, pin: Modifier) {
    Column(Modifier.fillMaxSize().padding(top = 16.dp)) {
        Box(Modifier.align(Alignment.CenterHorizontally).then(pin).size(36.dp, 4.dp).graphicsLayer { alpha = 0.16f + 0.24f * sheet.progress }.background(Color.White, CircleShape))
        SheetTitle(sheet, pin)
        Box(Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), GlassLevel.Thin).padding(vertical = 28.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
            MutedText(empty, align = TextAlign.Center)
        }
    }
}

/** Lightning's card, in Bitcoin orange: the primary lit, the rest stepped back. Tap = make primary (or open it), hold = menu. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun OnchainRevealCard(
    w: OnchainWallet, balance: Long?, code: String, rates: Rates?, hidden: Boolean, reveal: HomeReveal,
    onOpen: () -> Unit, onSetPrimary: () -> Unit, onRename: (String) -> Unit, onBackup: () -> Unit, onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val haptics = LocalHapticFeedback.current
    val sel by animateFloatAsState(if (w.isPrimary) 1f else 0f, spring(0.85f, Spring.StiffnessMediumLow), label = "selected")
    val shape = RoundedCornerShape(24.dp)
    Box {
        Row(
            Modifier.fillMaxWidth()
                .graphicsLayer { alpha = 0.7f + 0.3f * sel }
                .glass(shape, if (sel > 0.5f) GlassLevel.Regular else GlassLevel.Thin, tint = Lw.Bitcoin.copy(alpha = 0.09f * sel))
                .border(1.dp, lerp(Color.White.copy(alpha = 0.05f), Lw.Bitcoin.copy(alpha = 0.55f), sel), shape)
                .combinedClickable(
                    onClick = { if (w.isPrimary) onOpen() else { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onSetPrimary() } },
                    onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); menu = true },
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
                .semantics { selected = w.isPrimary }
                .testTag("onchain-wallet-${w.name}"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OnchainMark(w, 44, sel)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(w.name, size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1, color = lerp(Lw.MutedFg, Lw.Foreground, sel))
                Spacer(Modifier.height(4.dp))
                Txt(onchainSubtitle(w), size = 12.sp, weight = FontWeight.Medium, color = if (!w.backedUp) Lw.Orange else lerp(Lw.MutedFg, Lw.Bitcoin, sel), maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            val figure = lerp(Lw.MutedFg, Lw.Foreground, sel)
            if (balance == null) Txt("—", size = CARD_FIGURE, color = Lw.MutedFg)
            else {
                val m = Modifier
                    // The primary's figure is where the ghost lands, revealed as it arrives.
                    .then(if (w.isPrimary) Modifier.measureRoot { reveal.target = it } else Modifier)
                    .graphicsLayer { if (w.isPrimary) alpha = 0.15f + 0.85f * smooth(0.78f, 1f, reveal.progress) }
                if (hidden) Txt("•••••", m, size = CARD_FIGURE, weight = FontWeight.SemiBold, color = figure)
                else Odometer(Currencies.formatBalance(balance, code, rates), m, CARD_FIGURE, 0.sp, figure, fromZero = false, lineHeight = 24.sp)
            }
        }
        if (menu) OnchainActionsSheet(w, { menu = false }, onOpen, { renaming = true }, onBackup, onDelete)
        if (renaming) TextPrompt(stringResource(R.string.rw_rename), w.name, onDismiss = { renaming = false }) { v ->
            renaming = false
            v.trim().takeIf { it.isNotEmpty() && it != w.name }?.let(onRename)
        }
    }
}

/** Hold menu, the same sheet as a remote wallet's. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnchainActionsSheet(w: OnchainWallet, onDismiss: () -> Unit, onDetails: () -> Unit, onRename: () -> Unit, onBackup: () -> Unit, onDelete: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    fun then(action: () -> Unit) { scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); action() } }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = Glass.Dialog,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) },
        modifier = Modifier.testTag("onchain-wallet-menu"),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OnchainMark(w, 52)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Txt(w.name, size = 17.sp, weight = FontWeight.SemiBold, maxLines = 1)
                Txt(onchainSubtitle(w), size = 13.sp, color = if (!w.backedUp) Lw.Orange else Lw.MutedFg, maxLines = 1)
            }
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        SheetAction(L.drawable.lucide_ic_info, stringResource(R.string.sweep_menu_details), Lw.Foreground, "onchain-menu-details") { then(onDetails) }
        SheetAction(L.drawable.lucide_ic_pencil, stringResource(R.string.rw_rename), Lw.Foreground, "onchain-menu-rename") { then(onRename) }
        if (!w.watchOnly) SheetAction(L.drawable.lucide_ic_key_round, stringResource(if (w.backedUp) R.string.onchain_showPhrase else R.string.onchain_backupNow), Lw.Bitcoin, "onchain-menu-backup") { then(onBackup) }
        SheetAction(L.drawable.lucide_ic_trash_2, stringResource(R.string.onchain_remove), Lw.Red, "onchain-menu-remove", Lw.Red) { then(onDelete) }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

/** Removing a wallet that holds keys says what's at stake; a watch-only one is easy to bring back. */
@Composable
internal fun RemoveOnchainDialog(w: OnchainWallet, onDismiss: () -> Unit, onConfirm: () -> Unit) = LwAlertDialog(
    onDismissRequest = onDismiss, containerColor = Glass.Dialog,
    title = { Txt(w.name, size = 17.sp, weight = FontWeight.SemiBold) },
    text = {
        Txt(
            stringResource(when { w.watchOnly -> R.string.onchain_remove_watch; !w.backedUp -> R.string.onchain_remove_notBackedUp; else -> R.string.onchain_remove_seed }),
            color = if (!w.watchOnly && !w.backedUp) Lw.Orange else Lw.MutedFg,
        )
    },
    confirmButton = { ModalAction(stringResource(R.string.onchain_remove), onConfirm, Modifier.testTag("onchain-remove-confirm"), destructive = true) },
)

/** "12 words", "24 words + passphrase". */
@Composable
internal fun phraseSummary(w: OnchainWallet) = pluralStringResource(R.plurals.onchain_word_count, w.words, w.words) + if (w.passphrase) " + passphrase" else ""
