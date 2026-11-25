package io.lawallet.wallet.ui.screens

import io.lawallet.wallet.ui.components.Callout
import io.lawallet.wallet.ui.components.TextPrompt
import io.lawallet.wallet.ui.components.toastError
import io.lawallet.wallet.ui.components.ConfirmDialog
import io.lawallet.wallet.ui.components.DialogTitle
import io.lawallet.wallet.app.withPlugins
import io.lawallet.wallet.core.nwc.Lncurl
import io.lawallet.wallet.ui.components.WalletMark
import androidx.annotation.DrawableRes
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.mutableIntStateOf
import kotlin.math.sign
import androidx.compose.animation.AnimatedContent
import androidx.compose.runtime.SideEffect
import kotlin.math.floor
import androidx.compose.ui.semantics.text
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import io.lawallet.wallet.ui.components.LwAlertDialog
import io.lawallet.wallet.ui.components.ModalAction
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.ui.components.toast
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.lawallet.wallet.ui.components.rememberAddressProfile
import io.lawallet.wallet.R
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.ln.Destination
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.awaitSettled
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.LoadingDots
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import io.lawallet.wallet.ui.components.CurrencyToggle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import io.lawallet.wallet.ui.components.holdProgress
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.composables.icons.lucide.R as L

/** What Home's Lightning page shows, for AppNav's tab bar: a free address can't send or receive, an alias can't send. */
object HomeLightningAddress {
    var free by mutableStateOf(false)
    var alias by mutableStateOf(false)
    /** How open the Lightning wallet list is (0..1), read at draw time: the tab bar steps aside for its Add button. */
    var deck: () -> Float = { 0f }
}

/** A button's leading mark: its icon on a small light disc, so it reads on any button surface. */
@Composable
internal fun ActionMark(content: @Composable () -> Unit) =
    Box(Modifier.size(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.9f)), contentAlignment = Alignment.Center) { content() }

/** Above an alias address's label: a glowing round "@" mark. */
@Composable
private fun StateMark(@DrawableRes icon: Int, tint: Color) {
    Box(Modifier.padding(bottom = 18.dp).size(112.dp).drawBehind {
        drawCircle(Brush.radialGradient(listOf(tint.copy(alpha = 0.30f), Color.Transparent), radius = size.minDimension / 2))
    }, contentAlignment = Alignment.Center) {
        Box(
            Modifier.size(76.dp).glass(CircleShape, GlassLevel.Strong, tint = tint.copy(alpha = 0.14f)).border(1.dp, tint.copy(alpha = 0.45f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { LwIcon(icon, size = 34.dp, tint = tint) }
    }
}

/** In place of the balance for an alias address: where its payments go, with that address's face and name. Tap to change it. */
@Composable
private fun ForwardFace(target: String, onEdit: () -> Unit) {
    val c = LocalContainer.current
    val profile = rememberAddressProfile(target)
    Column(Modifier.testTag("balance-forward"), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LwIcon(L.drawable.lucide_ic_forward, size = 14.dp, tint = Lw.Lightning)
            Spacer(Modifier.width(6.dp))
            Txt(stringResource(R.string.home_forwardsTo), size = 13.sp, weight = FontWeight.Medium, color = Lw.Lightning)
        }
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.clip(CircleShape).glass(CircleShape, GlassLevel.Thin, tint = Lw.Lightning.copy(alpha = 0.08f)).clickable(onClick = onEdit)
                .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp).testTag("forward-edit"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AsyncImage(profile?.picture ?: c.avatars.of(target), null, Modifier.size(44.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f, fill = false)) {
                profile?.name?.let { Txt(it, size = 17.sp, weight = FontWeight.SemiBold, maxLines = 1) }
                Txt(target, size = if (profile?.name != null) 13.sp else 17.sp, weight = if (profile?.name != null) FontWeight.Normal else FontWeight.SemiBold, color = if (profile?.name != null) Lw.MutedFg else Lw.Foreground, maxLines = 1)
            }
            Spacer(Modifier.width(12.dp))
            LwIcon(L.drawable.lucide_ic_pencil, size = 16.dp, tint = Lw.MutedFg)
        }
    }
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
    var wallets by remember { mutableStateOf(c.remoteWallets.cached) }
    val newWallet = rememberNewWallet()
    val walletBalances = rememberWalletBalances() // cached figures first, then live
    // Plugin wallets join the server's list as cards of their own (`plugin:<id>`): listed only, never the primary.
    val pluginsInstalled by c.plugins.installed.collectAsState()
    val pluginAccounts by c.plugins.accounts.collectAsState()
    val deck = remember(wallets, pluginsInstalled, pluginAccounts) { withPlugins(wallets, pluginsInstalled, pluginAccounts) }
    val currentDeck by rememberUpdatedState(deck) // the poll below reads today's cards, not the ones it started with
    // A wallet being added from here (optimistic): once LaWallet has it, it joins the list under its card.
    val pendingWallet by c.flow.pendingWallet.collectAsState()
    // Created from the sheet with an automatic name: asked for one (prefilled) once its card has landed.
    var naming by remember { mutableStateOf<String?>(null) } // the pending wallet's key
    LaunchedEffect(pendingWallet?.created) {
        val w = pendingWallet?.created ?: return@LaunchedEffect
        if (pendingWallet?.lncurl == true) walletBalances[w.id] = 0L // a fresh disposable wallet starts empty
        wallets = wallets.orEmpty().filter { it.id != w.id }.map { if (w.isDefault) it.copy(isDefault = false) else it } + w
        readWalletBalance(c, w.id, walletBalances)
    }
    // Named right away, while LaWallet sets it up: the card takes the name at once, LaWallet gets it once it exists.
    LaunchedEffect(pendingWallet?.key) {
        val p = pendingWallet?.takeIf { it.askName } ?: return@LaunchedEffect
        delay(350) // the card's entrance first
        naming = p.key
    }
    // Landed: it lets go of its setting-up card once the list closes or Home leaves, so a return doesn't replay it.
    val settled = { if (c.flow.pendingWallet.value?.created != null) c.flow.pendingWallet.value = null }
    LaunchedEffect(open) { if (!open) settled() }
    DisposableEffect(Unit) { onDispose { settled() } }
    // While the primary switches, the main balance shows the new wallet's known balance until its own arrives.
    var heldBalance by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        launch { c.wallet.refreshBalance() }
        // Signed in or out since: a plugin wallet's card follows.
        pluginsInstalled.filter { it.enabled && it.manifest.isWallet }.forEach { launch { c.plugins.refreshAccount(it.id) } }
        fetchRemoteWallets(c, { wallets = it }, walletBalances)
        // Live while showing: every wallet is polled (failures keep the last figure). Only while the app is visible: a
        // LaunchedEffect outlives onStop, and the field log had 93% of plugin balance reads in background, every 10 s.
        while (true) {
            delay(10_000)
            c.wallet.active.first { it }
            // One round at a time: a slow wallet (reads time out at 20–45 s) mustn't pile up reads, each holding a thread.
            kotlinx.coroutines.coroutineScope {
                currentDeck.orEmpty().filter { it.isActive }.forEach { w ->
                    launch { readWalletBalance(c, w.id, walletBalances) }
                }
            }
        }
    }
    // A payment on the main connection (NWC notification) re-reads the primary card at once, from its own endpoint.
    LaunchedEffect(open, balance) {
        val primary = deck?.firstOrNull { it.isDefault && it.isActive } ?: return@LaunchedEffect
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
    var leaving by remember { mutableStateOf(setOf<String>()) }
    var sweepFirst by remember { mutableStateOf<Pair<RemoteWallet, Long>?>(null) }
    var freeing by remember { mutableStateOf(false) }
    var regenSkipped by remember { mutableStateOf(setOf<String>()) }
    var regenerating by remember { mutableStateOf(false) }
    val regenDone = stringResource(R.string.rw_regen_done)
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
    // Optimistic: a card tapped for the picked address shows as its wallet at once, until LaWallet's list agrees.
    var routed by remember(selectedAddress) { mutableStateOf<String?>(null) }
    val forwardTo = picked?.takeIf { routed == null && (it.mode == "ALIAS" || it.mode == "PROXY_ALIAS") }?.redirect
    LaunchedEffect(picked?.remoteWalletId, picked?.mode) { if (routed != null && picked?.mode == "CUSTOM_NWC" && picked.remoteWalletId == routed) routed = null }
    val shownWallet = routed ?: picked?.takeIf { it.mode == "CUSTOM_NWC" && forwardTo == null }?.remoteWalletId
    val unlinked = routed == null && picked != null && wallets != null && addressKind(picked, wallets) == AddressKind.IDLE
    val detached = forwardTo != null || unlinked
    // The wallet in use, shown as a pill in the balance label's place (none for a free or alias address): tap opens
    // the list, hold its menu; opening the list morphs it into that wallet's card.
    val pillWallet = if (detached) null else deck?.let { ws -> if (picked == null) ws.find { it.isDefault && it.isActive } else ws.find { it.id == shownWallet } }
    // The address in use, locked to its wallet: the list then shows only that wallet (a free or alias one has none to lock).
    val lockedAddresses by c.prefs.lockedAddresses.collectAsState()
    val lockUser = if (detached) null else (picked?.username ?: addressList.find { it.isPrimary }?.username)
    val lockedHere = lockUser != null && lockUser in lockedAddresses
    // A transfer between wallets comes back here instead of a sent screen: once the list is open, the paying card
    // shows what left (red) and the receiving one what came in (green) while both figures roll to their new balance.
    var deltas by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    LaunchedEffect(Unit) {
        val t = c.flow.transfer ?: return@LaunchedEffect
        c.flow.transfer = null
        val from = t.from ?: pillWallet?.id ?: return@LaunchedEffect
        val before = walletBalances[from] to walletBalances[t.to]
        snapshotFlow { reveal.progress >= 1f }.first { it }
        delay(250)
        deltas = mapOf(from to -(t.sats + t.fee), t.to to t.sats)
        before.first?.let { walletBalances[from] = (it - t.sats - t.fee).coerceAtLeast(0) }
        before.second?.let { walletBalances[t.to] = it + t.sats }
        delay(2400)
        deltas = emptyMap()
        // Then the wallets' own figures (fees and rounding), quietly.
        readWalletBalance(c, from, walletBalances); readWalletBalance(c, t.to, walletBalances)
    }
    // An alias address forwards elsewhere: linking it to a wallet stops that, so it's asked first.
    var confirmLink by remember(selectedAddress) { mutableStateOf<RemoteWallet?>(null) }
    SideEffect { HomeLightningAddress.free = unlinked; HomeLightningAddress.alias = forwardTo != null }
    DisposableEffect(Unit) {
        HomeLightningAddress.deck = { reveal.progress }
        onDispose { HomeLightningAddress.free = false; HomeLightningAddress.alias = false; HomeLightningAddress.deck = { 0f } }
    }
    // Switching is instant and never blocks: the tap shows at once (card, pill, balance held at the card's figure) and
    // a newer tap cancels the older one's wait. Saves run one at a time, so LaWallet ends on the last wallet tapped.
    val switcher = remember { object { var job: Job? = null; val lock = Mutex() } }
    fun switchWallet(w: RemoteWallet, send: suspend () -> Unit, confirm: suspend () -> Unit, revert: () -> Unit) {
        heldBalance = walletBalances[w.id]
        switcher.job?.cancel()
        switcher.job = scope.launch {
            val oldClient = c.wallet.client.value
            try {
                switcher.lock.withLock { send() }
                confirm()
                // Hold the figure until the new wallet answers: its first emission is the on-device cache (stale,
                // often 0), so wait for a live read instead.
                withTimeoutOrNull(10_000) { c.wallet.refreshBalance(c.wallet.client.first { it != null && it !== oldClient }) }
                heldBalance = null
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                revert(); heldBalance = null; context.toastError(e)
            }
        }
    }
    val linkPicked: (RemoteWallet) -> Unit = { w ->
        picked?.let { a ->
            routed = w.id
            switchWallet(w, { c.session.api.value.routeAddressToWallet(a.username, w.id) }, { c.wallet.refreshAddresses() }, { routed = null })
        }
    }
    // The primary as LaWallet last confirmed it: where a failed switch goes back to.
    var savedWallets by remember { mutableStateOf<List<RemoteWallet>?>(null) }
    val setPrimary: (RemoteWallet) -> Unit = { w ->
        // A plugin wallet is local: LaWallet can't route an address to it, so it's never the primary. Its card opens
        // its details instead.
        if (w.isPlugin) {
            nav.navigate(R0.PLUGINS)
        } else {
            if (switcher.job?.isActive != true) savedWallets = wallets
            wallets = wallets?.map { it.copy(isDefault = it.id == w.id) }
            switchWallet(
                w,
                { c.session.api.value.updateRemoteWallet(w.id, isDefault = true) },
                {
                    c.prefs.notePrimary(w.id)
                    wallets?.let { c.remoteWallets.cache(it) }
                    runCatching { c.session.refreshMe() }
                },
                { wallets = savedWallets },
            )
        }
    }

    LaunchedEffect(Unit) {
        nav.awaitSettled() // their answers repaint Home: not while it slides in (enter max was 536 ms)
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
                    // An address without a balance of its own leads with a mark of what it is: an alias, or unlinked.
                    // What fades as the wallet list opens (the mark, the label) gives the list its room.
                    var markH by remember { mutableIntStateOf(0) }
                    var labelH by remember { mutableIntStateOf(0) }
                    SideEffect {
                        reveal.tuckTop = if (detached) (markH + labelH).toFloat() else 0f
                    }
                    if (forwardTo != null) Box(Modifier.onSizeChanged { markH = it.height }.graphicsLayer { alpha = 1f - reveal.progress }) {
                        StateMark(L.drawable.lucide_ic_at_sign, Lw.Lightning)
                    }
                    // A free address has no mark: some air under the header instead.
                    else if (unlinked) Spacer(Modifier.height(56.dp).onSizeChanged { markH = it.height })
                    // Once the wallets show, the figure is named after the wallet it belongs to.
                    val primaryLabel by remember { derivedStateOf { reveal.progress > 0.5f && !detached } }
                    BalanceLabel(
                        // A free or alias address has no balance to name once the wallet list is open.
                        primaryLabel, Modifier.pinned(anchors, LIGHTNING).onSizeChanged { labelH = it.height }.then(if (detached) Modifier.graphicsLayer { alpha = 1f - reveal.progress } else Modifier),
                        lightningText = when { forwardTo != null -> stringResource(R.string.home_lightningAlias); unlinked -> stringResource(R.string.home_lightningAddress); else -> null },
                        primaryText = deck?.let { ws -> if (picked == null) ws.find { it.isDefault } else ws.find { it.id == shownWallet } }?.name,
                        badge = if (relayError != null && hasNwc) ({ RelayBadge(balance != null, relayError!!) { scope.launch { c.wallet.refreshBalance() } } }) else null,
                        replace = pillWallet?.let { pw -> {
                            WalletPill(
                                pw, reveal, { sheet.progress * 2.2f },
                                onOpen = { scope.launch { reveal.settleTo(1f) } },
                                onMenu = { reveal.menuFor = pw.id },
                            )
                        } },
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
                                    val primary = deck?.firstOrNull { it.isDefault }
                                    nav.navigate(if (primary != null) R0.remoteWallet(primary.id) else R0.REMOTE_WALLETS) { launchSingleTop = true }
                                },
                            ) { if (hidden) sensitiveGate { c.prefs.setHideBalance(false) } else c.prefs.setHideBalance(true) })
                            .padding(top = 16.dp, bottom = 2.dp).heightIn(min = 76.dp).testTag("balance-toggle")
                            // Holding "charges" the figure (shrinks, dims) up to the long press.
                            .graphicsLayer { rollIn(LIGHTNING); val p = balanceHold(); scaleX = 1f - 0.06f * p; scaleY = scaleX; alpha *= 1f - 0.3f * p },
                        contentAlignment = Alignment.Center,
                    ) {
                        FigureStrut()
                        when {
                            forwardTo != null -> ForwardFace(forwardTo) { picked?.let { nav.navigate(R0.aliasTarget(it.username)) } }
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
                    if (!detached && currencies.active.size >= 2) CurrencyToggle(
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
            if (me != null && me?.lightningAddress == null) Callout(
                L.drawable.lucide_ic_sparkles, stringResource(R.string.home_claimAddress), Lw.NwcPurple, { nav.navigate("${R0.CLAIM}?from=&bonus=0") },
                Modifier.revealAside(reveal).riseIn(3, intro).padding(start = 16.dp, end = 16.dp, top = 12.dp).testTag("claim-cta"),
            )
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
      // A free or alias address's way forward, down by the thumb just above the tab bar; it fades as the wallet list
      // opens (the list is the choice then).
      if (detached) Column(
          Modifier.align(Alignment.BottomCenter).padding(start = 20.dp, end = 20.dp, bottom = 132.dp).fillMaxWidth().graphicsLayer { alpha = 1f - reveal.progress }
              // Over the activity sheet's drag area (a sibling: it never gets touches that land here), and right where
              // the balance sits with the list open. Drags here move the reveal like anywhere else on Home.
              .nestedScroll(reveal.connection).scrollable(rememberScrollableState { 0f }, Orientation.Vertical),
          verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
          LwButton(
              stringResource(if (forwardTo != null) R.string.home_setWallet else R.string.home_selectWallet), { scope.launch { reveal.settleTo(1f) } },
              Modifier.fillMaxWidth().testTag("home-select-wallet"), BtnVariant.Theme, enabled = current && !open, height = 58.dp,
              leading = { ActionMark { WalletMark(lncurl = false, size = 20.dp) } },
          )
          if (unlinked) LwButton(
              stringResource(R.string.home_forwardInstead), { picked?.let { nav.navigate(R0.aliasTarget(it.username)) } },
              Modifier.fillMaxWidth().testTag("home-forward"), BtnVariant.Secondary, enabled = current && !open, height = 58.dp,
              leading = { ActionMark { LwIcon(L.drawable.lucide_ic_forward, size = 18.dp, tint = Lw.Lightning) } },
          )
      }
      confirmLink?.let { w ->
          ConfirmDialog(
              stringResource(R.string.home_aliasToWallet, forwardTo.orEmpty()), stringResource(R.string.home_aliasToWallet_confirm), { confirmLink = null; linkPicked(w) }, { confirmLink = null },
              Modifier.testTag("home-alias-to-wallet"),
          )
      }
      if (freeing && picked != null) ConfirmDialog(
          stringResource(R.string.home_freeAddress_text), stringResource(R.string.home_freeAddress), onDismiss = { freeing = false },
          title = stringResource(R.string.home_freeAddress_title, picked.username), destructive = true, onConfirm = {
              freeing = false
              scope.launch {
                  runCatching { c.session.api.value.freeAddress(picked.username) }
                      .onSuccess { c.wallet.refreshAddresses() }.onFailure { context.toastError(it) }
              }
          },
      )
      // Optimistic: the card shows the new name at once; a failed save puts the old one back.
      val renameWallet: (RemoteWallet, String) -> Unit = { w, name ->
          val before = wallets
          wallets = wallets?.map { if (it.id == w.id) it.copy(name = name) else it }
          scope.launch {
              runCatching { c.remoteWallets.withFreeName(name) { n -> c.session.api.value.updateRemoteWallet(w.id, name = n) } }
                  .onSuccess { saved -> wallets = wallets?.map { if (it.id == w.id) it.copy(name = saved.name) else it }; wallets?.let { c.remoteWallets.cache(it) } }
                  .onFailure { wallets = before; context.toastError(it) }
          }
      }
      WalletsReveal(
          // An address with a wallet of its own can't be pointed at a plugin (LaWallet routes addresses over NWC).
          reveal, deck?.let { markShown(it, picked != null, shownWallet) }, walletBalances, code, rates, hidden,
          onOpen = { nav.navigate(if (it.isPlugin) R0.PLUGINS else R0.remoteWallet(it.id)) },
          onSetPrimary = { w -> when { picked == null -> setPrimary(w); forwardTo != null -> confirmLink = w; else -> linkPicked(w) } },
          onAdd = { step ->
              if (step.startsWith("plugin:")) {
                  // A wallet plugin: its sign-in opens; once signed in, its card joins this list.
                  c.plugins.get(step.removePrefix("plugin:"))?.let { signIn(c, context, it.manifest) }
              } else if (step == "lncurl") {
                  // Straight from the sheet: no form, the card shows at once and sets itself up. An address without a
                  // wallet names it, as when an address gets one on claim.
                  val provider = c.prefs.lncurlProvider ?: Lncurl.DEFAULT
                  val name = unlinkedPickedAddress(c)
                  // Named automatically so it's instant; once it exists, Home asks for a name (prefilled).
                  addWalletOptimistic(c, context.applicationContext, name ?: Lncurl.host(provider), lncurl = true, askName = true) { c.remoteWallets.createLncurl(provider, name, it) }
              } else nav.navigate(R0.addRemoteWallet(step))
          },
          newWalletId = newWallet,
          pending = pendingWallet,
          onManage = { nav.navigate(R0.REMOTE_WALLETS) },
          sweepFrom = sweepFrom,
          // Sweeps, transfers, renames and deletes go through LaWallet's wallets: a plugin's are managed in Settings → Plugins.
          onSweep = { if (!it.isPlugin) { Sweeps.deleteAfter = null; sweepFrom = it.id } },
          onSweepTo = { to -> if (!to.isPlugin) sweepFrom?.let { from -> sweepFrom = null; nav.navigate(R0.sweep(from, to.id)) } },
          onSweepCancel = { Sweeps.deleteAfter = null; sweepFrom = null },
          onDelete = { w, bal -> if (w.isPlugin) nav.navigate(R0.PLUGINS) else if ((bal ?: 0L) > 0L) sweepFirst = w to bal!! else deleting = w },
          onTransfer = { w -> scope.launch { nav.navigate(startSend(c, Destination.Wallet(w.id, w.name))) } },
          onWithdraw = pillWallet?.let { active -> { w -> nav.navigate(startWithdraw(c, from = w, to = active)) } },
          // A plugin can only be withdrawn from when it pays (its manifest lists pay_invoice).
          canWithdraw = { w -> RemoteWallet.pluginIdOf(w.id)?.let { id -> "pay_invoice" in c.plugins.get(id)?.manifest?.wallet?.methods.orEmpty() } ?: true },
          onRename = { w, name -> if (!w.isPlugin) renameWallet(w, name) },
          leaving = leaving,
          deltas = deltas,
          locked = lockedHere,
          onLock = lockUser?.let { u -> { c.prefs.setAddressLocked(u, !lockedHere) } },
          // A linked or alias address can be unlinked: bottom-left under the list, opposite the total (not while locked).
          footerStart = if (picked != null && !unlinked && sweepFrom == null && !lockedHere) ({
              Row(
                  Modifier.height(32.dp).clip(CircleShape).border(1.dp, Lw.Foreground.copy(alpha = 0.18f), CircleShape)
                      .clickable(enabled = open && current) { freeing = true }
                      .padding(start = 10.dp, end = 12.dp).testTag("home-free-address"),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                  LwIcon(L.drawable.lucide_ic_unlink, size = 14.dp, tint = Lw.Foreground.copy(alpha = 0.7f))
                  Spacer(Modifier.width(6.dp))
                  Txt(stringResource(R.string.home_freeAddress), size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
              }
          }) else null,
      )
      pendingWallet?.takeIf { it.key == naming }?.let { p ->
          TextPrompt(stringResource(R.string.rw_name_new), p.name, { naming = null }, saveLabel = stringResource(R.string.rw_name_save)) { v ->
              naming = null
              val created = p.created
              when {
                  v == p.name -> {}
                  created != null -> { c.flow.pendingWallet.value = p.copy(name = v); renameWallet(wallets?.find { it.id == created.id } ?: created, v) }
                  // Still being set up: the card shows it now, and the create sends it once the wallet exists.
                  else -> c.flow.pendingWallet.update { cur -> if (cur?.key == p.key) cur.copy(name = v, renamed = true) else cur }
              }
          }
      }
      sweepFirst?.let { (w, bal) ->
          ConfirmDialog(
              stringResource(R.string.rw_delete_sweepFirst, Currencies.formatSats(bal)), stringResource(R.string.sweep_menu), { sweepFirst = null; Sweeps.deleteAfter = w.id; sweepFrom = w.id }, { sweepFirst = null },
              title = stringResource(R.string.rw_delete),
          )
      }
      // The wallet in use is a disposable LNCurl one that stopped answering: offer to regenerate it (once per wallet).
      val silent by c.wallet.walletSilent.collectAsState()
      val inUse = deck?.let { ws -> if (picked != null) ws.find { it.id == shownWallet } else ws.find { it.isDefault } }
      inUse?.takeIf { silent && current && it.isLncurl && it.lncurlServerUrl != null && it.id !in regenSkipped }?.let { w ->
          LwAlertDialog(
              onDismissRequest = { if (!regenerating) regenSkipped = regenSkipped + w.id },
              title = { DialogTitle(stringResource(R.string.rw_regen_title)) },
              text = { Txt(stringResource(R.string.rw_regen_text, w.name, Lncurl.host(w.lncurlServerUrl!!))) },
              confirmButton = {
                  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                      ModalAction(stringResource(R.string.rw_regen), {
                          regenerating = true
                          scope.launch {
                              runCatching { c.remoteWallets.regenerateLncurl(w) }
                                  .onSuccess { id ->
                                      regenSkipped = regenSkipped + w.id + id // the new one still reads silent until it connects
                                      fetchRemoteWallets(c, { wallets = it }, walletBalances)
                                      context.toast(regenDone)
                                  }
                                  .onFailure { context.toastError(it) }
                              regenerating = false
                          }
                      }, loading = regenerating, destructive = true)
                      LwButton(stringResource(R.string.common_cancel), { regenSkipped = regenSkipped + w.id }, Modifier.fillMaxWidth(), BtnVariant.Secondary, enabled = !regenerating, height = 52.dp)
                  }
              },
          )
      }
      deleting?.let { w ->
          ConfirmDialog(
              stringResource(R.string.rw_delete_confirm), stringResource(R.string.rw_delete), onDismiss = { deleting = null }, title = w.name, destructive = true, onConfirm = {
                  deleting = null
                  val before = wallets
                  // The card folds away at once (the rest close over it), and leaves the list once it has.
                  leaving = leaving + w.id
                  scope.launch {
                      val fold = launch {
                          delay(LEAVE_MS); wallets = wallets?.filter { it.id != w.id }; leaving = leaving - w.id
                          // A just-added wallet's card goes with it (else the list would put it back as still being added).
                          c.flow.pendingWallet.update { p -> if (p?.created?.id == w.id) null else p }
                      }
                      runCatching { c.session.api.value.deleteRemoteWallet(w.id, permanent = !w.isActive) }
                          .onSuccess {
                              fold.join()
                              wallets = wallets?.let { c.remoteWallets.ensurePrimary(it) } // deleting the primary hands it on
                              wallets?.let { c.remoteWallets.cache(it) }
                              runCatching { c.session.refreshMe() }
                          }
                          .onFailure { fold.cancel(); leaving = leaving - w.id; wallets = before; context.toastError(it) }
                  }
              },
          )
      }
      SideEffect { reveal.hero = pillWallet != null }
      if (pillWallet != null && open) WalletPillMorph(pillWallet, reveal)
    }
}

/** How long a deleted wallet's card takes to fold out of the list before it leaves it. */
private const val LEAVE_MS = 450L

/**
 * The wallet list as Home marks it: the account's own primary when no other address is picked, else only the picked
 * address's wallet (none when it routes to an archived or missing one; plugins can't back an address).
 */
internal fun markShown(wallets: List<io.lawallet.wallet.core.api.RemoteWallet>, picked: Boolean, shown: String?) =
    if (!picked) wallets else wallets.filter { !it.isPlugin }.map { it.copy(isDefault = it.id == shown) }
