package io.lawallet.wallet.ui.screens

import androidx.compose.animation.animateColorAsState
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.lawallet.wallet.R
import io.lawallet.wallet.app.Tx
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.BrandImage
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.TransactionRow
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
    var menu by remember { mutableStateOf(false) }
    var confirmLogout by remember { mutableStateOf(false) }
    var avatar by remember { mutableStateOf<String?>(null) }
    var bonus by remember { mutableStateOf(c.prefs.bonusSats.takeIf { it > 0 }) }

    LaunchedEffect(Unit) {
        runCatching { c.session.refreshMe() }
        c.wallet.refreshActivity()
        c.session.signer.value?.pubkey?.let { pk ->
            avatar = c.prefs.avatarCache[pk]
            runCatching { c.session.api.value.profiles(listOf(pk)).firstOrNull()?.picture }.getOrNull()?.let {
                avatar = it; c.prefs.avatarCache = c.prefs.avatarCache + (pk to it)
            }
        }
    }

    // Balance roll (use-animated-number.ts: 600ms ease-out cubic; the first value snaps).
    val shown = remember { Animatable((balance ?: 0).toFloat()) }
    var seenBalance by remember { mutableStateOf(balance) }
    var rolling by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(balance) {
        val target = balance ?: run { seenBalance = null; return@LaunchedEffect }
        val first = seenBalance == null
        seenBalance = target
        if (first || shown.value == target.toFloat()) { shown.snapTo(target.toFloat()); return@LaunchedEffect }
        rolling = if (target > shown.value) "incoming" else "outgoing"
        shown.animateTo(target.toFloat(), tween(600, easing = EaseOutCubic))
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
    val canReceive = hasNwc || autoCreate
    val code = currencies.selected.takeIf { it in currencies.active } ?: "SAT"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 140.dp)) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box {
                Box(Modifier.size(44.dp).glass(CircleShape).clickable { menu = true }.testTag("avatar"), contentAlignment = Alignment.Center) {
                    if (avatar != null) AsyncImage(avatar, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                    else BrandImage(brand.isotype, "isotype.svg", Modifier.padding(9.dp).fillMaxSize())
                }
                DropdownMenu(menu, { menu = false }, containerColor = Glass.Dialog, shape = RoundedCornerShape(16.dp)) {
                    DropdownMenuItem(
                        { Txt(stringResource(R.string.home_logOut)) },
                        { menu = false; confirmLogout = true },
                        leadingIcon = { LwIcon(L.drawable.lucide_ic_log_out, size = 16.dp) },
                    )
                }
            }
            Box(Modifier.weight(1f).height(44.dp).glass(CircleShape), contentAlignment = Alignment.Center) {
                BrandImage(brand.logotype, "logotype.svg", Modifier.height(20.dp).width(88.dp))
            }
            GlassIconButton(
                if (hidden) L.drawable.lucide_ic_eye_off else L.drawable.lucide_ic_eye, { c.prefs.setHideBalance(!hidden) },
                Modifier.testTag("toggle-balance"), contentDescription = stringResource(if (hidden) R.string.home_showBalance else R.string.home_hideBalance),
            )
        }

        // The balance slab: glass over the prism light, which blooms green/coral on a payment.
        Box(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp).drawBehind {
                if (glowAlpha > 0f) {
                    val r = size.width * 0.75f
                    drawCircle(Brush.radialGradient(0f to glowColor.copy(alpha = 0.42f * glowAlpha), 1f to Color.Transparent, center = Offset(size.width / 2, size.height * 0.38f), radius = r), r, Offset(size.width / 2, size.height * 0.38f))
                }
            },
        ) {
            Column(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(32.dp)).padding(start = 18.dp, end = 18.dp, top = 22.dp, bottom = 18.dp),
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
                Box(Modifier.fillMaxWidth().heightIn(min = 76.dp), contentAlignment = Alignment.Center) {
                    when {
                        balance == null && hasNwc && loadingBalance -> Box(Modifier.width(170.dp).height(44.dp).glass(RoundedCornerShape(12.dp), GlassLevel.Thin))
                        balance == null && relayError != null -> Txt(stringResource(R.string.shared_balance_unavailable), size = 16.sp, color = Lw.Red)
                        else -> BalanceFigure({ shown.value.toLong() }, code, rates, hidden, motion)
                    }
                }
                bonus?.let { b ->
                    Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.Top) {
                        Txt(stringResource(R.string.home_bonusNote, Currencies.formatSats(b)), size = 12.sp, color = Lw.MutedFg)
                        Spacer(Modifier.width(6.dp))
                        Box(Modifier.size(16.dp).clickable { c.prefs.bonusSats = 0; bonus = null }) { LwIcon(L.drawable.lucide_ic_x, size = 14.dp, tint = Lw.MutedFg) }
                    }
                }
                if (currencies.active.size >= 2) CurrencyToggle(currencies.active, code, { cc -> c.prefs.setCurrencies(currencies.copy(selected = cc, localEdit = true)) })
                Spacer(Modifier.height(20.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LwButton(stringResource(R.string.home_receive), { nav.navigate(R0.RECEIVE) }, Modifier.weight(1f).testTag("home-receive"), BtnVariant.Secondary, enabled = canReceive, height = 52.dp, icon = L.drawable.lucide_ic_arrow_down_left)
                    LwButton(stringResource(R.string.home_send), { nav.navigate(R0.SEND) }, Modifier.weight(1f).testTag("home-send"), BtnVariant.Theme, enabled = hasNwc, height = 52.dp, icon = L.drawable.lucide_ic_arrow_up_right)
                }
            }
        }

        val address = me?.lightningAddress
        when {
            me == null -> {}
            address != null -> Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp))
                    .clickable { nav.navigate(R0.RECEIVE) }.padding(start = 18.dp, end = 10.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Txt(stringResource(R.string.home_address), size = 12.sp, color = Lw.MutedFg)
                    Txt(address, size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.testTag("home-address"))
                }
                Box(Modifier.size(44.dp).glass(RoundedCornerShape(14.dp), GlassLevel.Strong), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_qr_code, size = 20.dp) }
            }
            else -> Row(
                Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), tint = Lw.NwcPurple.copy(alpha = 0.10f))
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
        }
        if (me != null && !hasNwc) {
            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().glass(RoundedCornerShape(24.dp), GlassLevel.Thin).padding(18.dp)) {
                Txt(stringResource(if (autoCreate) R.string.home_autoCreateTitle else R.string.home_noWalletTitle), size = 15.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Txt(stringResource(if (autoCreate) R.string.home_autoCreateBody else R.string.home_noWalletBody), size = 13.sp, color = Lw.MutedFg, lineHeight = 18.sp)
                if (!autoCreate) {
                    Spacer(Modifier.height(12.dp))
                    LwButton(stringResource(R.string.home_connectWallet), { nav.navigate(R0.REMOTE_WALLETS) }, variant = BtnVariant.Secondary, height = 40.dp)
                }
            }
        }

        ActivityPreview(recent, hasNwc, onOpen = { tx -> c.flow.activityDetail = tx; nav.navigate(R0.activity(tx.paymentHash)) }, onAll = { nav.navigate(R0.ACTIVITY) })
    }

    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        confirmButton = { TextButton({ confirmLogout = false; c.session.logout() }) { Txt(stringResource(R.string.home_logOut), color = Lw.Red) } },
        dismissButton = { TextButton({ confirmLogout = false }) { Txt(stringResource(R.string.common_cancel)) } },
        text = { Txt(stringResource(R.string.settings_removeWalletConfirm)) },
        containerColor = Glass.Dialog,
    )
}

/**
 * The rolling number. Reads the animated value through [sats] inside its own scope, so a
 * 600ms roll recomposes only this row — not the whole home screen — on every frame.
 */
@Composable
private fun BalanceFigure(sats: () -> Long, code: String, rates: io.lawallet.wallet.core.fx.Rates?, hidden: Boolean, motion: String?) {
    val color by animateColorAsState(
        when (motion) { "incoming" -> Lw.Green; "outgoing" -> Lw.Orange; else -> Lw.Foreground }, tween(300), label = "tint",
    )
    Row {
        val text = if (hidden) "•••••" else Currencies.format(sats(), code, rates)
        if (code == "BTC" && !hidden) BtcEmphasis(text, color, Modifier.alignByBaseline())
        else Txt(text, Modifier.alignByBaseline().testTag("balance"), size = 56.sp, weight = FontWeight.SemiBold, color = color, lineHeight = 60.sp, letterSpacing = (-1.5).sp)
        Spacer(Modifier.width(8.dp))
        Txt(Currencies.unitLabel(code), Modifier.alignByBaseline(), size = 18.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
    }
}

/** BTC: mute the leading zeros (splitBtcForEmphasis). */
@Composable
private fun BtcEmphasis(text: String, color: Color, modifier: Modifier = Modifier) {
    val idx = text.indexOfFirst { it in '1'..'9' }.let { if (it < 0) text.length else it }
    Row(modifier) {
        Txt(text.substring(0, idx), Modifier.alignByBaseline(), size = 44.sp, weight = FontWeight.SemiBold, color = Lw.MutedFg.copy(alpha = 0.55f), lineHeight = 60.sp, letterSpacing = (-1).sp)
        Txt(text.substring(idx), Modifier.alignByBaseline().testTag("balance"), size = 44.sp, weight = FontWeight.SemiBold, color = color, lineHeight = 60.sp, letterSpacing = (-1).sp)
    }
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

@Composable
fun ActivityPreview(recent: List<Tx>, hasNwc: Boolean, onOpen: (Tx) -> Unit, onAll: () -> Unit) {
    var tab by remember { mutableStateOf("activity") }
    val items = (if (tab == "transfers") recent.filter { !it.incoming } else recent).take(5)
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 28.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.glass(CircleShape, GlassLevel.Thin).padding(4.dp)) {
                listOf("activity" to R.string.home_tabActivity, "transfers" to R.string.home_tabTransfers).forEach { (k, label) ->
                    Box(Modifier.clip(CircleShape).then(if (tab == k) Modifier.glass(CircleShape, GlassLevel.Strong) else Modifier).clickable { tab = k }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Txt(stringResource(label), size = 13.sp, weight = FontWeight.SemiBold, color = if (tab == k) Lw.Foreground else Lw.MutedFg)
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            Txt(stringResource(R.string.home_viewAll), Modifier.clip(CircleShape).clickable(onClick = onAll).padding(horizontal = 10.dp, vertical = 8.dp).testTag("view-all"), size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
        }
        Spacer(Modifier.height(12.dp))
        val empty = when {
            !hasNwc -> stringResource(if (tab == "activity") R.string.home_list_activity_connect else R.string.home_list_transfers_connect)
            items.isEmpty() -> stringResource(if (tab == "activity") R.string.home_list_activity_empty else R.string.home_list_transfers_empty)
            else -> null
        }
        if (empty != null) Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), GlassLevel.Thin).padding(vertical = 28.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
            MutedText(empty, align = androidx.compose.ui.text.style.TextAlign.Center)
        }
        else Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp))) {
            items.forEachIndexed { i, tx ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 68.dp), color = Color.White.copy(alpha = 0.06f))
                TransactionRow(tx) { onOpen(tx) }
            }
        }
    }
}
