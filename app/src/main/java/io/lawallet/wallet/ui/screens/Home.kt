package io.lawallet.wallet.ui.screens

import androidx.compose.animation.AnimatedContent
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.derivedStateOf
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
import io.lawallet.wallet.R
import io.lawallet.wallet.app.Tx
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.sharedAvatar
import io.lawallet.wallet.ui.components.BrandImage
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.Spinner
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
import com.composables.icons.lucide.R as L

@Composable
fun HomeScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val brand = LocalBrand.current
    val me by c.session.me.collectAsState()
    val settings by c.session.settings.collectAsState()
    val balance by c.wallet.balance.collectAsState()
    val loadingBalance by c.wallet.balanceLoading.collectAsState()
    val relayError by c.wallet.relayError.collectAsState()
    val client by c.wallet.client.collectAsState()
    val recent by c.wallet.recent.collectAsState()
    val hidden by c.prefs.hideBalance.collectAsState()
    val currencies by c.prefs.currencies.collectAsState()
    val rates by c.rates.rates.collectAsState()
    val scope = rememberCoroutineScope()
    val signer by c.session.signer.collectAsState()
    val profiles by c.session.profiles.entries.collectAsState()
    val avatar = signer?.pubkey?.let { profiles[it]?.picture }
    var bonus by remember { mutableStateOf(c.prefs.bonusSats.takeIf { it > 0 }) }
    val context = LocalContext.current
    val density = LocalDensity.current

    // Swipe down: the balance settles at the bottom and the remote wallets show above it.
    val reveal = remember { HomeReveal() }
    reveal.bottomReserve = with(density) { 96.dp.toPx() } // floating tab bar (64dp + its 12/16dp margins)
    val open by remember { derivedStateOf { reveal.open } }
    var wallets by remember { mutableStateOf(c.prefs.cached("remote-wallets", remoteWalletsSer)) }
    val walletBalances = remember { mutableStateMapOf<String, Long?>() } // missing = loading, null = unavailable
    var promoting by remember { mutableStateOf<String?>(null) }
    // While the primary switches, the main balance shows the new wallet's known balance until its own arrives.
    var heldBalance by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(open) {
        if (!open) return@LaunchedEffect
        launch { c.wallet.refreshBalance() }
        fetchRemoteWallets(c, { wallets = it }, walletBalances)
    }
    BackHandler(open) { scope.launch { reveal.settleTo(0f) } }
    val setPrimary: (RemoteWallet) -> Unit = { w ->
        if (promoting == null) scope.launch {
            val before = wallets
            val oldClient = c.wallet.client.value
            promoting = w.id
            runCatching { c.session.api.value.updateRemoteWallet(w.id, isDefault = true) }
                .onSuccess {
                    wallets = wallets?.map { it.copy(isDefault = it.id == w.id) }?.also { c.prefs.setCached("remote-wallets", remoteWalletsSer, it) }
                    heldBalance = walletBalances[w.id]
                    promoting = null
                    runCatching { c.session.refreshMe() }
                    withTimeoutOrNull(10_000) { c.wallet.client.first { it !== oldClient }; c.wallet.balance.first { it != null } }
                    heldBalance = null
                }
                .onFailure { wallets = before; promoting = null; context.toast(it.message ?: "") }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { c.session.refreshMe() }
        c.wallet.refreshActivity()
        c.session.refreshProfile()
    }

    // First entry into Home plays the intro (staggered rise + balance rolling up from 0); coming back
    // from another screen doesn't (saved with the back-stack entry).
    var intro by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(balance != null) { if (balance != null) { delay(1600); intro = false } }
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
    // Payment cue: lives as long as the payment notice (3.8s), like the web's `cue`.
    var cue by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        c.wallet.notices.collectLatest { n -> cue = if (n.type == "payment_received") "incoming" else "outgoing"; delay(3800); cue = null }
    }
    val motion = rolling ?: cue
    var glowColor by remember { mutableStateOf(Lw.Green) }
    LaunchedEffect(motion) { if (motion != null) glowColor = if (motion == "incoming") Lw.Green else Lw.Orange }
    val glowAlpha by animateFloatAsState(if (cue != null) 1f else 0f, tween(450), label = "glow")

    val hasNwc = client != null
    val autoCreate = settings?.lncurlAutoCreate == true
    val code = currencies.selected.takeIf { it in currencies.active } ?: "SAT"

    val shown = heldBalance ?: balance

    val sheet = rememberActivitySheet(enabled = hasNwc && recent.isNotEmpty())
    Box(
        Modifier.fillMaxSize().onGloballyPositioned { reveal.rootTop = it.positionInRoot().y; reveal.rootH = it.size.height.toFloat() }
            .nestedScroll(reveal.connection).testTag("home-root"),
    ) {
      ActivitySheetLayout(
        sheet,
        header = {
            Row(Modifier.fillMaxWidth().measureRoot { reveal.headerBottom = it.bottom }.riseIn(0, intro).padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Opens the profile; the avatar itself flies there (log out lives on the profile now).
                Box(
                    Modifier.sharedAvatar().size(44.dp).glass(CircleShape).clickable(onClickLabel = stringResource(R.string.profile_open)) { nav.navigate(R0.PROFILE) { launchSingleTop = true } }.testTag("avatar"),
                    contentAlignment = Alignment.Center,
                ) {
                    if (avatar != null) AsyncImage(avatar, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                    else BrandImage(brand.isotype, "isotype.svg", Modifier.padding(9.dp).fillMaxSize())
                }
                // Centre pill: the user's Lightning address (tap to receive), or the logotype until there is one.
                val address = me?.lightningAddress
                if (address != null) Row(
                    Modifier.weight(1f).height(44.dp).glass(CircleShape).clickable { nav.navigate(R0.RECEIVE) }.padding(horizontal = 14.dp)
                        .testTag("home-address").semantics(mergeDescendants = true) {},
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
                ) {
                    // Username leads; the domain sits a step dimmer and never truncates.
                    Txt(address.substringBefore('@'), Modifier.weight(1f, fill = false), size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
                    Txt("@" + address.substringAfter('@'), size = 16.sp, weight = FontWeight.Medium, color = Lw.Foreground.copy(alpha = 0.6f), maxLines = 1)
                }
                else Box(Modifier.weight(1f).height(44.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                    BrandImage(brand.logotype, "logotype.svg", Modifier.height(20.dp).width(88.dp))
                }
                // Hiding the balance stays one tap away: tap the figure itself.
                GlassIconButton(
                    L.drawable.lucide_ic_settings, { nav.navigate(R0.SETTINGS) { popUpTo(R0.HOME); launchSingleTop = true } },
                    Modifier.testTag("home-settings"), contentDescription = stringResource(R.string.shared_nav_settings),
                )
            }
        },
        top = {
            // The balance: free on the prism light (no panel), which blooms green/coral on a payment.
            Box(
                Modifier.fillMaxWidth()
                    // Measured at rest; the reveal then slides it down to the bottom, 1:1 with the finger.
                    .measureRoot { reveal.balanceTop = it.top; reveal.balanceH = it.height }
                    .graphicsLayer { translationY = reveal.progress * reveal.range }
                    .riseIn(1, intro).padding(start = 16.dp, end = 16.dp, top = 20.dp).drawBehind {
                    if (glowAlpha > 0f) {
                        val r = size.width * 0.75f
                        drawCircle(Brush.radialGradient(0f to glowColor.copy(alpha = 0.42f * glowAlpha), 1f to Color.Transparent, center = Offset(size.width / 2, size.height * 0.38f), radius = r), r, Offset(size.width / 2, size.height * 0.38f))
                    }
                },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, top = 22.dp, bottom = 18.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Txt(stringResource(R.string.home_yourBalance), size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
                        if (relayError != null && hasNwc) {
                            Spacer(Modifier.width(8.dp))
                            RelayBadge(balance != null, relayError!!) { scope.launch { c.wallet.refreshBalance() } }
                        }
                    }
                    // Fixed-height slot: cue effects never resize it.
                    Box(
                        Modifier.fillMaxWidth()
                            // Tap the figure to hide/show it, same as the eye button (no ripple: it's the hero number).
                            .clickable(null, null, onClickLabel = stringResource(if (hidden) R.string.home_showBalance else R.string.home_hideBalance)) { c.prefs.setHideBalance(!hidden) }
                            .padding(top = 16.dp, bottom = 2.dp).heightIn(min = 76.dp).testTag("balance-toggle")
                            .measureRoot { if (reveal.progress == 0f) reveal.figure = it },
                        contentAlignment = Alignment.Center,
                    ) {
                        when {
                            shown == null && hasNwc && loadingBalance -> Box(Modifier.width(170.dp).height(44.dp).glass(RoundedCornerShape(12.dp), GlassLevel.Thin))
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
                    bonus?.let { b ->
                        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
                            Txt(stringResource(R.string.home_bonusNote, Currencies.formatSats(b)), size = 12.sp, color = Lw.MutedFg)
                            Spacer(Modifier.width(6.dp))
                            Box(Modifier.size(16.dp).clickable { c.prefs.bonusSats = 0; bonus = null }) { LwIcon(L.drawable.lucide_ic_x, size = 14.dp, tint = Lw.MutedFg) }
                        }
                    }
                    // Receive / Send live in the bottom bar.
                    if (currencies.active.size >= 2) CurrencyToggle(currencies.active, code, { cc -> c.prefs.setCurrencies(currencies.copy(selected = cc, localEdit = true)) }, Modifier.padding(top = 12.dp).riseIn(2, intro))
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
                ActivitySheetContent(sheet, recent, hasNwc, onOpen = { tx -> c.flow.activityDetail = tx; nav.navigate(R0.activity(tx.paymentHash)) })
            }
        },
      )
      if (open) {
          WalletsReveal(
              reveal, wallets, walletBalances, code, rates, hidden, promoting,
              onOpen = { nav.navigate(R0.remoteWallet(it.id)) },
              onSetPrimary = setPrimary,
              onAdd = { nav.navigate(R0.REMOTE_WALLET_ADD) },
              onManage = { nav.navigate(R0.REMOTE_WALLETS) },
          )
          if (wallets.orEmpty().any { it.isDefault } && shown != null) {
              BalanceGhost(reveal, if (hidden) "•••••" else Currencies.formatBalance(shown, code, rates), if (code == "BTC" && !hidden) 44.sp else 56.sp)
          }
      }
    }
}

/** Banners under the balance: ride down with it and fade, so the landed balance stands alone. */
private fun Modifier.revealAside(reveal: HomeReveal) = graphicsLayer {
    translationY = reveal.progress * reveal.range
    alpha = (1f - reveal.progress * 2.5f).coerceIn(0f, 1f)
}

/** Fades and lifts into place, [index] steps (70ms each) after the first; a no-op unless [play]. */
@Composable
private fun Modifier.riseIn(index: Int, play: Boolean, rise: Dp = 24.dp): Modifier {
    val p = remember { Animatable(if (play) 0f else 1f) }
    LaunchedEffect(Unit) { if (p.value < 1f) { delay(80L + index * 70L); p.animateTo(1f, tween(560, easing = EaseOutCubic)) } }
    return graphicsLayer { alpha = p.value; translationY = (1f - p.value) * rise.toPx() }
}

@Composable
private fun BalanceFigure(sats: Long, code: String, rates: io.lawallet.wallet.core.fx.Rates?, hidden: Boolean, motion: String?, fromZero: Boolean) {
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
private fun Odometer(text: String, modifier: Modifier, size: TextUnit, letterSpacing: TextUnit, color: Color, fromZero: Boolean) {
    Row(modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }) {
        text.forEachIndexed { i, ch ->
            key(text.length - i) {
                if (ch.isDigit()) OdometerWheel(ch - '0', i, size, letterSpacing, color, fromZero)
                else Txt(ch.toString(), Modifier.alignByBaseline(), size = size, weight = FontWeight.SemiBold, color = color, lineHeight = 60.sp, letterSpacing = letterSpacing)
            }
        }
    }
}

@Composable
private fun RowScope.OdometerWheel(digit: Int, index: Int, size: TextUnit, letterSpacing: TextUnit, color: Color, fromZero: Boolean) {
    val pos = remember { Animatable(if (fromZero) 0f else digit.toFloat()) }
    LaunchedEffect(digit) {
        // Intro: left-most wheels settle first, the units keep spinning a beat longer.
        if (pos.value == 0f && fromZero) delay(250L + index * 60L)
        pos.animateTo(digit.toFloat(), tween(if (fromZero) 1100 else 700, easing = FastOutSlowInEasing))
    }
    // One Text node (transparent target digit) owns layout + baseline; the rolling faces are painted over it.
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = Inter, fontFeatureSettings = "tnum", fontSize = size, fontWeight = FontWeight.SemiBold, lineHeight = 60.sp, letterSpacing = letterSpacing, color = color)
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
        size = size, weight = FontWeight.SemiBold, color = Color.Transparent, lineHeight = 60.sp, letterSpacing = letterSpacing,
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
    if (open) AlertDialog(
        onDismissRequest = { open = false },
        title = { Txt(stringResource(R.string.shared_relay_title), weight = FontWeight.SemiBold, size = 16.sp) },
        text = { Txt(if (error.contains("time", true)) stringResource(R.string.shared_relay_timeout) else stringResource(R.string.shared_relay_generic), color = Lw.MutedFg) },
        confirmButton = { TextButton({ open = false; onRetry() }) { Txt(stringResource(R.string.common_retry)) } },
        containerColor = Glass.Dialog,
    )
}
