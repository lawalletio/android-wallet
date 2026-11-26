@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.lawallet.wallet.ui.screens

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.ColumnScope
import io.lawallet.wallet.app.AppContainer
import io.lawallet.wallet.app.Fees
import io.lawallet.wallet.app.FlowStore
import io.lawallet.wallet.app.OnchainDraft
import io.lawallet.wallet.app.OnchainException
import io.lawallet.wallet.app.OnchainRepository
import io.lawallet.wallet.app.OnchainWallet
import io.lawallet.wallet.app.SendOutcome
import io.lawallet.wallet.core.btc.Bip21
import io.lawallet.wallet.core.btc.Chain
import io.lawallet.wallet.core.btc.Descriptors
import io.lawallet.wallet.core.btc.Payjoin
import io.lawallet.wallet.core.btc.PsbtCodec
import io.lawallet.wallet.core.btc.SilentPayment
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.AmountDisplay
import io.lawallet.wallet.ui.components.AmountKeypad
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.Callout
import io.lawallet.wallet.ui.components.CurrencyToggle
import io.lawallet.wallet.ui.components.DialogTitle
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.KeyValueRow
import io.lawallet.wallet.ui.components.LwBottomSheet
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.ModalAction
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.PaymentReceipt
import io.lawallet.wallet.ui.components.Pill
import io.lawallet.wallet.ui.components.QrDisplay
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.SettingsGroup
import io.lawallet.wallet.ui.components.SettingsRow
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.SwipeButton
import io.lawallet.wallet.ui.components.TextPrompt
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.CopyValue
import io.lawallet.wallet.ui.components.copyToClipboard
import io.lawallet.wallet.ui.components.keypadDisplay
import io.lawallet.wallet.ui.components.lwFieldColors
import io.lawallet.wallet.ui.components.qrBitmap
import io.lawallet.wallet.ui.components.shareText
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import com.composables.icons.lucide.R as L

/*
 * Onchain payments: receive to a rotating address, send with a fee tier and RBF on, follow a pending tx and speed it
 * up, and pass PSBTs between signers (multisig, watch-only). The engine is OnchainRepository; these are its screens.
 */

/** Wallets on [chain] that can build a payment (all but a single watched address). */
internal fun payingWallets(c: AppContainer, chain: Chain) = c.onchain.wallets.value.filter { it.chain == chain && it.descriptors != null }

/**
 * Starts an onchain send to [b] from [preferred] when it's on the address's chain, else the primary (or first) wallet
 * there; the route to go to, or null if no wallet can pay it.
 */
fun startOnchainSend(c: AppContainer, b: Bip21, preferred: String? = null): String? {
    val chain = Descriptors.chainOf(b.address) ?: return null
    // A silent payment is derived from the spending keys: only a wallet whose keys are on this phone can make one.
    val wallets = payingWallets(c, chain).filter { it.seed || !SilentPayment.isAddress(b.address) }
    val from = wallets.firstOrNull { it.id == preferred } ?: wallets.firstOrNull { it.isPrimary } ?: wallets.firstOrNull() ?: return null
    c.flow.onchainSend = FlowStore.OnchainSend(from.id, b.address, b.sats, b.label, payjoin = b.pj)
    return if (b.sats != null) R0.ONCHAIN_SEND_REVIEW else R0.ONCHAIN_SEND_AMOUNT
}

/** An onchain failure in the user's words. */
@Composable
internal fun onchainError(e: Throwable?): String? = when {
    e == null -> null
    e is OnchainException -> stringResource(when (e.reason) {
        OnchainException.Reason.NO_FUNDS -> R.string.onchain_err_noFunds
        OnchainException.Reason.DUST -> R.string.onchain_err_dust
        OnchainException.Reason.FEE_TOO_LOW -> R.string.onchain_err_feeTooLow
        OnchainException.Reason.NO_KEY -> R.string.onchain_err_noKey
        OnchainException.Reason.WRONG_WALLET -> R.string.onchain_err_wrongPsbt
        OnchainException.Reason.GAP -> R.string.onchain_receive_gap
        OnchainException.Reason.NOT_READY -> R.string.onchain_err_offline
    })
    else -> stringResource(R.string.onchain_err_offline)
}

/** "bc1q w508 d6qe …": an address in groups of four, easier to check against the other screen. */
internal fun grouped(address: String) = address.chunked(4).joinToString(" ")

internal fun short(address: String) = if (address.length > 20) address.take(10) + "…" + address.takeLast(8) else address

// ---- receive ----------------------------------------------------------------------------------------------------

/** One wallet's receive screen (from its detail): the same body as Receive's Onchain page. */
@Composable
fun OnchainReceiveScreen(id: String) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.onchain_receive_title), { nav.popBackStack() }, close = true)
        OnchainReceiveBody(id)
    }
}

/** Receive's Onchain page: the primary onchain wallet's address (its chip switches wallet), or the ways to get a wallet. */
@Composable
internal fun ColumnScope.OnchainReceivePage() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val wallets by c.onchain.wallets.collectAsState()
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val w = wallets.firstOrNull { it.id == picked } ?: wallets.firstOrNull { it.isPrimary }
    if (w != null) { OnchainReceiveBody(w.id) { picked = it }; return }
    Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(72.dp).glass(CircleShape, GlassLevel.Regular, tint = Lw.Bitcoin.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_bitcoin, size = 34.dp, tint = Lw.Bitcoin) }
        Txt(stringResource(R.string.onchain_cta_title), size = 18.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LwButton(stringResource(R.string.onchain_create), { nav.navigate(R0.ONCHAIN_CREATE) }, Modifier.weight(1f), BtnVariant.Secondary, icon = L.drawable.lucide_ic_plus)
            LwButton(stringResource(R.string.onchain_import), { nav.navigate(R0.ONCHAIN_IMPORT) }, Modifier.weight(1f), BtnVariant.Secondary, icon = L.drawable.lucide_ic_import)
        }
    }
}

/**
 * One wallet's receive: its chip (tap to pick another wallet when [onPick] is set), the big QR of its unused address
 * (BIP-21 with the amount, when one is set), the address in groups of four, and Copy / Share / Amount / New address.
 */
@Composable
private fun ColumnScope.OnchainReceiveBody(id: String, onPick: ((String) -> Unit)? = null) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wallets by c.onchain.wallets.collectAsState()
    val states by c.onchainWallets.states.collectAsState()
    val w = wallets.find { it.id == id }
    if (w == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val address = states[id]?.receive
    var sats by rememberSaveable(id) { mutableStateOf<Long?>(null) }
    var amountOpen by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) }
    var credit by remember { mutableStateOf<Long?>(null) }
    var busy by remember { mutableStateOf(false) }
    val copied = stringResource(R.string.common_copied)
    val gap = stringResource(R.string.onchain_receive_gap)
    val offline = stringResource(R.string.onchain_err_offline)
    LaunchedEffect(id) { c.onchainWallets.received.filter { it.first.id == id }.collect { credit = it.second.amount; delay(3000); credit = null } }
    val request = address?.let { Bip21(it, sats) }
    val copyValue = if (sats == null) address else request?.uri()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
        val choosable = onPick != null && wallets.size > 1
        Row(
            Modifier.clip(CircleShape).glass(CircleShape, GlassLevel.Thin).then(if (choosable) Modifier.clickable { picking = true } else Modifier)
                .padding(start = 6.dp, end = 14.dp, top = 6.dp, bottom = 6.dp).testTag("receive-wallet"),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OnchainMark(w, 28)
            Txt(w.name, size = 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
            if (w.chain == Chain.SIGNET) Pill(stringResource(R.string.onchain_signet), Lw.Signet)
            if (choosable) LwIcon(L.drawable.lucide_ic_chevrons_up_down, size = 16.dp, tint = Lw.MutedFg)
        }
        // Upper-cased in the QR (alphanumeric mode, a smaller code); copied and shared as typed.
        BigQr(request?.uri(qr = true), if (w.chain == Chain.SIGNET) Lw.Signet else Lw.Bitcoin, L.drawable.lucide_ic_bitcoin, credit) { copyValue?.let { context.copyToClipboard(it, copied) } }
        if (address != null) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(
                grouped(address), Modifier.clip(RoundedCornerShape(16.dp)).clickable { context.copyToClipboard(address, copied) }.padding(horizontal = 12.dp, vertical = 4.dp).testTag("onchain-address"),
                size = 16.sp, mono = true, align = TextAlign.Center, lineHeight = 24.sp,
            )
            if (sats != null) Row(
                Modifier.clip(CircleShape).background(Lw.Bitcoin.copy(alpha = 0.16f)).clickable { sats = null }.padding(start = 14.dp, end = 10.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Txt(stringResource(R.string.common_sats, Currencies.formatSats(sats!!)), size = 15.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                LwIcon(L.drawable.lucide_ic_x, size = 14.dp, tint = Lw.MutedFg)
            }
        }
        ReceiveActions(buildList {
            add(ReceiveAction(R.drawable.ic_copy, stringResource(R.string.common_copy), "onchain-copy") { copyValue?.let { context.copyToClipboard(it, copied) } })
            add(ReceiveAction(L.drawable.lucide_ic_share_2, stringResource(R.string.common_share), "onchain-share") { copyValue?.let { context.shareText(it) } })
            add(ReceiveAction(L.drawable.lucide_ic_hash, stringResource(R.string.receive_action_amount), "onchain-receive-amount") { amountOpen = true })
            if (w.descriptors != null) add(ReceiveAction(L.drawable.lucide_ic_refresh_cw, stringResource(R.string.onchain_receive_newAddress), "onchain-new-address", busy) {
                busy = true
                scope.launch {
                    runCatching { c.onchainWallets.newAddress(id) }.onFailure { context.toast(if ((it as? OnchainException)?.reason == OnchainException.Reason.GAP) gap else offline) }
                    busy = false
                }
            })
        })
        MutedText(stringResource(if (w.chain == Chain.SIGNET) R.string.onchain_receive_signetHint else R.string.onchain_receive_hint), Modifier.padding(horizontal = 12.dp), align = TextAlign.Center, size = 12.sp)
        Spacer(Modifier.height(16.dp))
    }
    if (amountOpen) AmountSheet(sats, { amountOpen = false }) { sats = it; amountOpen = false }
    if (picking) LwBottomSheet({ picking = false }, "receive-wallet-sheet") {
        wallets.forEach { o ->
            SettingsRow(o.name, { picking = false; onPick?.invoke(o.id) }, leading = { OnchainMark(o, 36) }, subtitle = onchainSubtitle(o),
                trailing = { if (o.id == id) LwIcon(L.drawable.lucide_ic_check, size = 18.dp, tint = Lw.Bitcoin) })
        }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

/** The keypad in a sheet, for a receive request's amount (0 clears it). */
@Composable
private fun AmountSheet(initial: Long?, onDismiss: () -> Unit, onDone: (Long?) -> Unit) {
    val c = LocalContainer.current
    val currencies by c.prefs.currencies.collectAsState()
    val rates by c.rates.rates.collectAsState()
    var code by rememberSaveable { mutableStateOf(currencies.selected.takeIf { it in currencies.active } ?: "SAT") }
    var raw by rememberSaveable { mutableStateOf(Currencies.inputFromSats(initial, code, rates)) }
    val sats = Currencies.parseToSats(raw, code, rates)
    LwBottomSheet(onDismiss, "onchain-amount-sheet") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            AmountDisplay(keypadDisplay(raw, code), Currencies.unitLabel(code))
            if (code != "SAT") MutedText(stringResource(R.string.common_sats, Currencies.formatSats(sats ?: 0)))
            CurrencyToggle(currencies.active, code, { next -> raw = Currencies.inputFromSats(sats, next, rates); code = next })
            AmountKeypad(raw, { raw = it }, Currencies.keypadMode(code), Modifier.fillMaxWidth().height(300.dp))
            LwButton(stringResource(R.string.common_done), { onDone(sats?.takeIf { it > 0 }) }, Modifier.fillMaxWidth().padding(bottom = 16.dp), BtnVariant.Theme)
        }
    }
}

// ---- send -------------------------------------------------------------------------------------------------------

/** Where to: an address or `bitcoin:` link, typed, pasted or scanned. */
@Composable
fun OnchainSendScreen(from: String? = null) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    var text by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val request = remember(text) { Bip21.parse(text)?.takeIf { Descriptors.chainOf(it.address) != null } }
    val invalid = stringResource(R.string.onchain_send_invalid)
    val noWallet = stringResource(R.string.onchain_send_noWallet)
    fun go(b: Bip21) { startOnchainSend(c, b, from)?.let { nav.navigate(it) } ?: run { error = noWallet } }
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(stringResource(R.string.onchain_send_title), { nav.popBackStack() }, close = true)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (scanning) QrCamera({ error = it; scanning = false }) { raw ->
                if (!scanning) return@QrCamera
                scanning = false
                text = raw.trim()
                Bip21.parse(text)?.takeIf { Descriptors.chainOf(it.address) != null }?.let(::go)
            }
            OutlinedTextField(
                text, { text = it.trim(); error = null }, Modifier.fillMaxWidth().testTag("onchain-send-field"),
                placeholder = { Txt(stringResource(R.string.onchain_send_field), color = Lw.MutedFg) },
                minLines = 2, maxLines = 4, colors = lwFieldColors(), shape = RoundedCornerShape(20.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 15.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LwButton(stringResource(R.string.onchain_import_paste), {
                    context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let { text = it }
                }, Modifier.weight(1f).testTag("onchain-send-paste"), BtnVariant.Secondary, height = 42.dp, icon = L.drawable.lucide_ic_clipboard_paste)
                LwButton(stringResource(if (scanning) R.string.common_cancel else R.string.shared_nav_scan), { scanning = !scanning }, Modifier.weight(1f),
                    BtnVariant.Secondary, height = 42.dp, icon = if (scanning) L.drawable.lucide_ic_x else L.drawable.lucide_ic_scan_qr_code)
            }
            if (request != null) {
                val chain = Descriptors.chainOf(request.address)!!
                Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), GlassLevel.Thin, tint = Lw.Green.copy(alpha = 0.10f)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    LwIcon(L.drawable.lucide_ic_circle_check, size = 18.dp, tint = Lw.Green)
                    Spacer(Modifier.width(10.dp))
                    Txt(short(request.address), Modifier.weight(1f), size = 14.sp, mono = true, maxLines = 1)
                    if (chain == Chain.SIGNET) Pill(stringResource(R.string.onchain_signet), Lw.Signet)
                }
            } else if (text.length > 20) ErrorText(invalid)
            ErrorText(error)
        }
        LwButton(stringResource(R.string.common_continue), { request?.let(::go) }, Modifier.fillMaxWidth().padding(16.dp).testTag("onchain-send-continue"), BtnVariant.Theme, enabled = request != null)
    }
}

/** Who's paid; [payjoin]: the request offers one, badged so it's noticed from the first screen. */
@Composable
private fun RecipientRow(address: String, label: String?, payjoin: Boolean = false) =
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), GlassLevel.Thin).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).glass(CircleShape, GlassLevel.Thin, tint = Lw.Bitcoin.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_bitcoin, size = 20.dp, tint = Lw.Bitcoin) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(label ?: stringResource(R.string.send_to), size = 13.sp, color = Lw.MutedFg, maxLines = 1)
            Txt(short(address), size = 15.sp, mono = true, maxLines = 1)
        }
        if (payjoin) Box(Modifier.testTag("onchain-payjoin-badge")) { Pill(stringResource(R.string.onchain_payjoin), Lw.Green) }
    }

/** What a payjoin is, on a payment to a `pj=` request; [joins]: this wallet can make one, else it says why not. */
@Composable
private fun PayjoinNote(joins: Boolean) =
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), GlassLevel.Thin, tint = Lw.Green.copy(alpha = if (joins) 0.10f else 0.04f)).padding(14.dp).testTag("onchain-payjoin")) {
        Box(Modifier.size(40.dp).glass(CircleShape, GlassLevel.Thin, tint = Lw.Green.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_users, size = 20.dp, tint = Lw.Green) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(stringResource(R.string.onchain_payjoin_title), size = 15.sp, weight = FontWeight.SemiBold)
            MutedText(stringResource(if (joins) R.string.onchain_payjoin_body else R.string.onchain_payjoin_unavailable), size = 13.sp)
        }
    }

@Composable
fun OnchainSendAmountScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val s = c.flow.onchainSend
    if (s == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val currencies by c.prefs.currencies.collectAsState()
    val rates by c.rates.rates.collectAsState()
    val states by c.onchainWallets.states.collectAsState()
    var code by rememberSaveable { mutableStateOf(currencies.selected.takeIf { it in currencies.active } ?: "SAT") }
    var raw by rememberSaveable { mutableStateOf(Currencies.inputFromSats(s.sats, code, rates)) }
    val sats = Currencies.parseToSats(raw, code, rates)
    val balance = states[s.walletId]?.balance?.total
    val over = balance != null && sats != null && sats > balance
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.send_amountTitle), { nav.popBackStack() })
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RecipientRow(s.address, s.label, s.payjoin != null)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountDisplay(keypadDisplay(raw, code), Currencies.unitLabel(code))
                if (code != "SAT") MutedText(stringResource(R.string.common_sats, Currencies.formatSats(sats ?: 0)))
                CurrencyToggle(currencies.active, code, { next -> raw = Currencies.inputFromSats(sats, next, rates); code = next })
                if (balance != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MutedText("${stringResource(R.string.claim_available)}: ${stringResource(R.string.common_sats, Currencies.formatSats(balance))}", size = 13.sp)
                    if (balance > 0) Txt(
                        stringResource(R.string.send_amount_max),
                        // Max is "everything, minus whatever the fee turns out to be": decided on the next screen.
                        Modifier.clip(CircleShape).background(Lw.Green.copy(alpha = 0.15f)).clickable { c.flow.onchainSend = s.copy(sats = null, max = true); nav.navigate(R0.ONCHAIN_SEND_REVIEW) }
                            .padding(horizontal = 12.dp, vertical = 4.dp).testTag("amount-max"),
                        size = 13.sp, weight = FontWeight.SemiBold, color = Lw.Green,
                    )
                }
                if (over) ErrorText(stringResource(R.string.send_preview_notEnoughBalance))
            }
            AmountKeypad(raw, { raw = it }, Currencies.keypadMode(code), Modifier.weight(1f).fillMaxWidth())
        }
        LwButton(stringResource(R.string.common_continue), {
            c.flow.onchainSend = s.copy(sats = sats, max = false)
            nav.navigate(R0.ONCHAIN_SEND_REVIEW)
        }, Modifier.fillMaxWidth().padding(16.dp).testTag("amount-continue"), BtnVariant.Theme, enabled = (sats ?: 0) > 0 && !over)
    }
}

/** Fast / normal / slow from the server's estimates, or a custom rate; [floor] is the least allowed (a bump's). */
@Composable
internal fun FeeTiers(fees: Fees?, selected: Int, onSelect: (Int) -> Unit, custom: Double?, onCustom: (Double) -> Unit, floor: Double = OnchainRepository.MIN_RATE) {
    var asking by remember { mutableStateOf(false) }
    val labels = listOf(R.string.onchain_fee_fast to R.string.onchain_fee_fastEta, R.string.onchain_fee_medium to R.string.onchain_fee_mediumEta, R.string.onchain_fee_slow to R.string.onchain_fee_slowEta)
    val rates = fees?.let { listOf(it.fast, it.medium, it.slow).map { r -> r.coerceAtLeast(floor) } }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, (name, eta) ->
            FeeChip(stringResource(name), stringResource(eta), rates?.get(i), selected == i, Modifier.weight(1f).testTag("fee-$i")) { onSelect(i) }
        }
        FeeChip(stringResource(R.string.onchain_fee_custom), null, custom, selected == 3, Modifier.weight(1f).testTag("fee-custom")) { asking = true }
    }
    if (asking) TextPrompt(stringResource(R.string.onchain_fee_customTitle), custom?.let(::rateText) ?: "", { asking = false }, saveLabel = stringResource(R.string.common_done)) { v ->
        asking = false
        v.replace(',', '.').toDoubleOrNull()?.takeIf { it in floor..10_000.0 }?.let { onCustom(it); onSelect(3) }
    }
}

internal fun rateText(r: Double) = if (r >= 10) "%.0f".format(r) else "%.1f".format(r).removeSuffix(".0").removeSuffix(",0")

@Composable
private fun FeeChip(name: String, eta: String?, rate: Double?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(18.dp)
    Column(
        modifier.glass(shape, if (on) GlassLevel.Strong else GlassLevel.Thin, tint = if (on) Lw.Bitcoin.copy(alpha = 0.16f) else null)
            .border(1.dp, if (on) Lw.Bitcoin.copy(alpha = 0.6f) else Color.Transparent, shape).clickable(onClick = onClick).padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Txt(name, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
        Txt(rate?.let { stringResource(R.string.onchain_fee_rate, rateText(it)) } ?: "—", size = 12.sp, color = if (on) Lw.Bitcoin else Lw.MutedFg, maxLines = 1)
        Txt(eta ?: " ", size = 11.sp, color = Lw.MutedFg, maxLines = 1)
    }
}

@Composable
fun OnchainSendReviewScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s0 = c.flow.onchainSend
    if (s0 == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val wallets by c.onchain.wallets.collectAsState()
    val chain = Descriptors.chainOf(s0.address) ?: Chain.MAINNET
    var walletId by rememberSaveable { mutableStateOf(s0.walletId) }
    val w = wallets.find { it.id == walletId }
    val fees by produceState<Fees?>(null, chain) { value = runCatching { c.onchainWallets.fees(chain) }.getOrNull() }
    var tier by rememberSaveable { mutableStateOf(1) }
    var custom by rememberSaveable { mutableStateOf<Double?>(null) }
    val rate = when (tier) { 0 -> fees?.fast; 1 -> fees?.medium; 2 -> fees?.slow; else -> custom }
    val draft by produceState<Result<OnchainDraft>?>(null, walletId, rate) {
        value = null
        val r = rate ?: return@produceState
        value = runCatching { c.onchainWallets.draft(walletId, s0.address, if (s0.max) null else s0.sats, r) }
    }
    var picking by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var sent by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val d = draft?.getOrNull()
    val failed = draft?.exceptionOrNull()
    if (w == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val canSign = !w.watchOnly
    val payjoin = s0.payjoin?.let { Payjoin.endpoint(it) } != null
    val joins = payjoin && c.onchainWallets.canPayjoin(w, s0.address)
    // The most the receiver may take from our change for its input: one input's worth at our rate.
    val joinFee = d?.takeIf { joins }?.let { dr -> w.script?.let { kotlin.math.ceil(dr.feeRate * Payjoin.inputVbytes(it)).toLong() } }
    fun onDone(outcome: SendOutcome, dr: OnchainDraft) {
        when (outcome) {
            is SendOutcome.Sent -> {
                sent = true
                c.flow.onchainSend = s0.copy(walletId = walletId, draft = dr.copy(fee = dr.fee + outcome.extraFee), txid = outcome.txid, joined = outcome.payjoin)
                nav.navigate(R0.ONCHAIN_SENT) { popUpTo(R0.HOME) }
            }
            is SendOutcome.NeedsSignatures -> { c.flow.psbt = outcome.psbt; nav.navigate(R0.onchainPsbt(walletId)) }
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.onchain_send_title), { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val amount = d?.amount ?: s0.sats
                if (amount != null) AmountDisplay(Currencies.formatSats(amount), "sats") else Box(Modifier.height(58.dp), contentAlignment = Alignment.Center) { Spinner() }
            }
            RecipientRow(s0.address, s0.label, payjoin)
            if (payjoin) PayjoinNote(joins)
            SettingsGroup {
                val others = payingWallets(c, chain).size > 1
                SettingsRow(
                    stringResource(R.string.onchain_send_from), if (others) ({ picking = true }) else null, Modifier.testTag("onchain-send-from"),
                    leading = { OnchainMark(w, 32) }, value = w.name,
                    trailing = if (others) null else ({}),
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(stringResource(R.string.send_networkFee), Modifier.padding(start = 4.dp), size = 13.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
                FeeTiers(fees, tier, { tier = it }, custom, { custom = it })
            }
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), GlassLevel.Thin).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                KeyValueRow(stringResource(R.string.send_networkFee), d?.let { stringResource(R.string.common_sats, Currencies.formatSats(it.fee)) } ?: "…")
                if (joinFee != null && joinFee > 0) KeyValueRow(stringResource(R.string.onchain_payjoin_fee), stringResource(R.string.onchain_payjoin_feeUpTo, Currencies.formatSats(joinFee)))
                KeyValueRow(stringResource(R.string.onchain_send_total), d?.let { stringResource(R.string.common_sats, Currencies.formatSats(it.amount + it.fee)) } ?: "…")
            }
            Row(Modifier.padding(horizontal = 6.dp)) {
                LwIcon(L.drawable.lucide_ic_rocket, Modifier.padding(top = 2.dp), size = 15.dp, tint = Lw.MutedFg)
                Spacer(Modifier.width(8.dp))
                MutedText(stringResource(if (canSign && !w.multisig) R.string.onchain_send_rbf else R.string.onchain_send_psbtNote), size = 12.sp)
            }
            ErrorText(onchainError(error ?: failed))
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            if (canSign) SwipeButton(stringResource(R.string.onchain_send_swipe), {
                val dr = d ?: return@SwipeButton
                // A payjoin takes up to a minute and several steps: its own screen shows them as they happen.
                if (joins) {
                    sent = true
                    c.flow.onchainSend = s0.copy(walletId = walletId, draft = dr)
                    c.onchainWallets.startPayjoin(dr, s0.payjoin!!)
                    nav.navigate(R0.ONCHAIN_PAYJOIN) { popUpTo(R0.HOME) }
                    return@SwipeButton
                }
                sending = true; error = null
                scope.launch {
                    runCatching { c.onchainWallets.send(dr, s0.payjoin) }.onSuccess { onDone(it, dr) }.onFailure { error = it }
                    sending = false
                }
            }, loading = sending, done = sent, enabled = d != null)
            else LwButton(stringResource(R.string.onchain_send_psbt), {
                val dr = d ?: return@LwButton
                c.flow.psbt = dr.psbt
                nav.navigate(R0.onchainPsbt(walletId))
            }, Modifier.fillMaxWidth().testTag("onchain-create-psbt"), BtnVariant.Theme, enabled = d != null, icon = L.drawable.lucide_ic_file_pen_line)
        }
    }
    if (picking) LwBottomSheet({ picking = false }, "onchain-from-sheet") {
        val states by c.onchainWallets.states.collectAsState()
        payingWallets(c, chain).forEach { o ->
            SettingsRow(o.name, { walletId = o.id; picking = false }, leading = { OnchainMark(o, 36) }, subtitle = onchainSubtitle(o),
                value = states[o.id]?.balance?.let { Currencies.formatSats(it.total) }, trailing = { if (o.id == walletId) LwIcon(L.drawable.lucide_ic_check, size = 18.dp, tint = Lw.Bitcoin) })
        }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

@Composable
fun OnchainSentScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val s = c.flow.onchainSend
    val d = s?.draft
    if (s?.txid == null || d == null) { LaunchedEffect(Unit) { nav.goHome() }; return }
    val to = stringResource(R.string.send_to)
    val rows = listOf(
        to to short(s.address),
        stringResource(R.string.send_networkFee) to stringResource(R.string.onchain_tx_feeRate, Currencies.formatSats(d.fee), rateText(d.feeRate)),
        stringResource(R.string.onchain_tx_status) to stringResource(R.string.onchain_tx_unconfirmed),
    ) + listOfNotNull(s.joined?.let { stringResource(R.string.onchain_payjoin) to stringResource(if (it) R.string.onchain_payjoin_joined else R.string.onchain_payjoin_regular) })
    Column(Modifier.fillMaxSize()) {
        PaymentReceipt(stringResource(R.string.send_summary_title), "pending", d.amount, rows, listOf(stringResource(R.string.onchain_tx_id) to s.txid),
            PaddingValues(horizontal = 16.dp, vertical = 32.dp), onDone = {
                // Back to where the payment started: Home as it was (its page, the wallet list closed), not a fresh one.
                c.flow.onchainSend = null
                if (!nav.popBackStack(R0.HOME, inclusive = false)) nav.goHome()
            }, valueNodes = mapOf(to to { CopyValue(short(s.address), s.address, Modifier.testTag("onchain-sent-address")) }))
    }
}

// ---- a transaction ----------------------------------------------------------------------------------------------

@Composable
fun OnchainTxScreen(walletId: String, txid: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val wallets by c.onchain.wallets.collectAsState()
    val states by c.onchainWallets.states.collectAsState()
    val w = wallets.find { it.id == walletId }
    val tx = states[walletId]?.txs?.find { it.txid == txid }
    var speeding by remember { mutableStateOf(false) }
    if (w == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val tip by c.onchainWallets.tip(w.chain).collectAsState()
    val addressKey = stringResource(if (tx?.incoming == true) R.string.onchain_tx_address else R.string.send_to)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(null, { nav.popBackStack() })
        if (tx == null) {
            // A replaced (sped-up) tx drops out of the wallet; its replacement is in the list.
            Column(Modifier.weight(1f).padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                if (states[walletId]?.syncing == true || states[walletId]?.balance == null) Spinner() else MutedText(stringResource(R.string.onchain_tx_gone), align = TextAlign.Center)
            }
            return@Column
        }
        if (tx.pending && !tx.incoming && tx.rbf && w.descriptors != null) Callout(
            L.drawable.lucide_ic_rocket, stringResource(R.string.onchain_speedup_body), Lw.Bitcoin, { speeding = true },
            Modifier.padding(horizontal = 16.dp).testTag("onchain-speedup"),
        )
        val confirmations = tx.confirmations(tip)
        val rows = buildList {
            add(stringResource(R.string.onchain_tx_status) to if (tx.pending) stringResource(R.string.onchain_tx_unconfirmed) else if (confirmations >= 6) stringResource(R.string.onchain_tx_confirmed6) else pluralStringResource(R.plurals.onchain_confirmations, confirmations, confirmations))
            tx.counterpart?.let { add(addressKey to short(it)) }
            add(stringResource(R.string.onchain_tx_wallet) to w.name)
            if (!tx.incoming && tx.fee != null) add(stringResource(R.string.send_networkFee) to stringResource(R.string.onchain_tx_feeRate, Currencies.formatSats(tx.fee), rateText(tx.feeRate ?: 0.0)))
            add(stringResource(R.string.send_summary_time) to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(tx.time)))
            add(stringResource(R.string.onchain_tx_explorer) to "")
        }
        val explorer = stringResource(R.string.onchain_tx_explorer)
        val statusKey = stringResource(R.string.onchain_tx_status)
        val statusText = rows.first().second
        PaymentReceipt(
            stringResource(if (tx.incoming) R.string.shared_tx_received else R.string.shared_tx_sent), if (tx.pending) "pending" else "settled", tx.amount, rows,
            listOf(stringResource(R.string.onchain_tx_id) to tx.txid), PaddingValues(horizontal = 16.dp, vertical = 16.dp), onDone = { nav.popBackStack() },
            valueNodes = (if (confirmations >= SAFE_CONFIRMATIONS) emptyMap() else mapOf<String, @Composable () -> Unit>(statusKey to {
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    BlockStrip(confirmations, if (tx.incoming) Lw.Green else Lw.Bitcoin, block = 14.dp)
                    Txt(statusText, size = 14.sp, align = TextAlign.End)
                }
            })) + listOfNotNull(tx.counterpart?.let { a -> addressKey to @Composable { CopyValue(short(a), a, Modifier.testTag("onchain-tx-address")) } }) + mapOf(explorer to {
                Txt(
                    "mempool.space ↗", Modifier.clickable { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(w.chain.txUrl(tx.txid)))) } }.testTag("onchain-explorer"),
                    size = 14.sp, weight = FontWeight.Medium, color = Lw.Bitcoin,
                )
            }),
        )
    }
    if (speeding && tx != null) SpeedUpSheet(w, tx.txid, tx.feeRate ?: OnchainRepository.MIN_RATE, { speeding = false }) { newTxid ->
        speeding = false
        nav.navigate(R0.onchainTx(walletId, newTxid)) { popUpTo(R0.onchainTx(walletId, txid)) { inclusive = true } }
    }
}

/** RBF: the same payment again at a higher rate (at least the old one plus 1 sat/vB, BIP-125's increment). */
@Composable
private fun SpeedUpSheet(w: OnchainWallet, txid: String, oldRate: Double, onDismiss: () -> Unit, onSent: (String) -> Unit) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val floor = oldRate + 1.0
    val fees by produceState<Fees?>(null) { value = runCatching { c.onchainWallets.fees(w.chain) }.getOrNull() }
    var tier by remember { mutableStateOf(0) }
    var custom by remember { mutableStateOf<Double?>(null) }
    val rate = (when (tier) { 0 -> fees?.fast; 1 -> fees?.medium; 2 -> fees?.slow; else -> custom })?.coerceAtLeast(floor)
    val draft by produceState<Result<OnchainDraft>?>(null, rate) {
        value = null
        val r = rate ?: return@produceState
        value = runCatching { c.onchainWallets.bump(w.id, txid, r) }
    }
    var sending by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Throwable?>(null) }
    val d = draft?.getOrNull()
    LwBottomSheet(onDismiss, "onchain-speedup-sheet") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            DialogTitle(stringResource(R.string.onchain_speedup_title))
            MutedText(stringResource(R.string.onchain_speedup_now, rateText(oldRate)), size = 13.sp)
            FeeTiers(fees, tier, { tier = it }, custom, { custom = it }, floor)
            Txt(d?.let { stringResource(R.string.onchain_speedup_newFee, Currencies.formatSats(it.fee), rateText(it.feeRate)) } ?: "…", size = 14.sp, weight = FontWeight.Medium)
            ErrorText(onchainError(error ?: draft?.exceptionOrNull()))
            ModalAction(stringResource(R.string.onchain_speedup), {
                val dr = d ?: return@ModalAction
                sending = true; error = null
                scope.launch {
                    runCatching { c.onchainWallets.send(dr) }.onSuccess { o ->
                        when (o) {
                            is SendOutcome.Sent -> onSent(o.txid)
                            is SendOutcome.NeedsSignatures -> { onDismiss(); c.flow.psbt = o.psbt; nav.navigate(R0.onchainPsbt(w.id)) }
                        }
                    }.onFailure { error = it }
                    sending = false
                }
            }, Modifier.testTag("onchain-speedup-confirm"), enabled = d != null, loading = sending)
        }
    }
}

// ---- PSBT -------------------------------------------------------------------------------------------------------

/** An animated `ur:crypto-psbt` QR (a static one when it fits in a frame). */
@Composable
private fun PsbtQr(psbt: String) {
    val frame by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, psbt) {
        val enc = PsbtCodec.encoder(psbt)
        while (true) {
            value = withContext(Dispatchers.Default) { qrBitmap(enc.nextPart().uppercase()).asImageBitmap() }
            if (enc.isSinglePart) break
            delay(250)
        }
    }
    Box(Modifier.glass(RoundedCornerShape(32.dp), GlassLevel.Regular).padding(12.dp)) {
        Box(Modifier.clip(RoundedCornerShape(22.dp)).background(Color.White).padding(16.dp)) {
            frame?.let { Image(it, null, Modifier.size(232.dp).testTag("psbt-qr"), filterQuality = FilterQuality.None) } ?: Spacer(Modifier.size(232.dp))
        }
    }
}

/**
 * A transaction waiting for signatures: show it to the other signers (animated QR, file, text), take theirs back
 * (scan, paste, file), sign here when this phone holds a key, and broadcast once there are enough.
 */
@Composable
fun OnchainPsbtScreen(id: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wallets by c.onchain.wallets.collectAsState()
    val w = wallets.find { it.id == id }
    var psbt by remember { mutableStateOf(c.flow.psbt) }
    var adding by remember { mutableStateOf(psbt == null) }
    var busy by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    if (w == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    val status = remember(psbt) { psbt?.let { runCatching { c.onchainWallets.status(id, it) }.getOrNull() } }
    val wrong = stringResource(R.string.onchain_err_wrongPsbt)
    val invalid = stringResource(R.string.onchain_psbt_invalid)
    val saved = stringResource(R.string.onchain_psbt_saved)
    val copied = stringResource(R.string.common_copied)
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        val p = psbt ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openOutputStream(uri)?.use { it.write(PsbtCodec.bytes(p)) } } }.onSuccess { context.toast(saved) }
        }
    }
    /** Another copy of this PSBT (or the first one): merged in, then signed here if this phone can. */
    fun take(incoming: String) {
        error = null
        busy = true
        scope.launch {
            runCatching {
                val merged = psbt?.let { c.onchainWallets.combine(it, incoming) } ?: incoming
                c.onchainWallets.status(id, merged) // throws for a PSBT this wallet can't read
                if (!w.watchOnly) c.onchainWallets.sign(id, merged) else merged
            }.onSuccess { psbt = it; c.flow.psbt = it; adding = false }.onFailure { error = if (psbt == null) invalid else wrong }
            busy = false
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.onchain_psbt_title), { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).animateContentSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (status != null) {
                SignatureDots(status.signed.coerceAtMost(status.required), status.required)
                Txt(
                    if (status.complete) stringResource(R.string.onchain_psbt_ready) else stringResource(R.string.onchain_psbt_signatures, status.signed, status.required),
                    size = 15.sp, weight = FontWeight.SemiBold, color = if (status.complete) Lw.Green else Lw.Foreground,
                )
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), GlassLevel.Thin).padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    status.sends.forEach { (to, sats) -> KeyValueRow(short(to), stringResource(R.string.common_sats, Currencies.formatSats(sats))) }
                    KeyValueRow(stringResource(R.string.send_networkFee), stringResource(R.string.common_sats, Currencies.formatSats(status.fee)))
                }
                if (!status.complete) {
                    MutedText(stringResource(R.string.onchain_psbt_body), align = TextAlign.Center, size = 13.sp)
                    PsbtQr(psbt!!)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LwButton(stringResource(R.string.common_copy), { context.copyToClipboard(psbt!!, copied) }, Modifier.weight(1f), BtnVariant.Secondary, icon = R.drawable.ic_copy)
                        LwButton(stringResource(R.string.common_share), { context.shareText(psbt!!) }, Modifier.weight(1f), BtnVariant.Secondary, icon = L.drawable.lucide_ic_share_2)
                        LwButton(stringResource(R.string.onchain_psbt_save), { save.launch("lawallet-${System.currentTimeMillis() / 1000}.psbt") }, Modifier.weight(1f), BtnVariant.Secondary, icon = L.drawable.lucide_ic_download)
                    }
                }
            } else if (psbt == null) MutedText(stringResource(R.string.onchain_psbt_load), Modifier.padding(top = 8.dp), align = TextAlign.Center)
            if (adding || psbt == null) PsbtInput(busy, ::take, { error = it })
            ErrorText(error)
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            if (status?.complete == true) SwipeButton(stringResource(R.string.onchain_psbt_broadcast), {
                busy = true; error = null
                scope.launch {
                    runCatching { c.onchainWallets.broadcast(id, psbt!!) }.onSuccess { txid ->
                        done = true
                        c.flow.psbt = null
                        nav.navigate(R0.onchainTx(id, txid)) { popUpTo(R0.HOME) }
                    }.onFailure { error = it.message }
                    busy = false
                }
            }, loading = busy, done = done)
            else if (psbt != null && !adding) LwButton(stringResource(R.string.onchain_psbt_add), { adding = true }, Modifier.fillMaxWidth().testTag("psbt-add"), BtnVariant.Theme, icon = L.drawable.lucide_ic_scan_qr_code)
        }
    }
}

@Composable
private fun SignatureDots(signed: Int, required: Int) = Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
    repeat(required) { i ->
        val on = i < signed
        Box(Modifier.size(36.dp).glass(CircleShape, GlassLevel.Thin, tint = if (on) Lw.Green.copy(alpha = 0.25f) else null), contentAlignment = Alignment.Center) {
            LwIcon(if (on) L.drawable.lucide_ic_check else L.drawable.lucide_ic_key_round, size = 16.dp, tint = if (on) Lw.Green else Lw.MutedFg)
        }
    }
}

/** Takes a PSBT in: scanned (animated UR frames or a plain one), pasted, or opened from a file. */
@Composable
private fun PsbtInput(busy: Boolean, onPsbt: (String) -> Unit, onError: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var scanning by remember { mutableStateOf(false) }
    val invalid = stringResource(R.string.onchain_psbt_invalid)
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val p = runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { it.readBytes() } } }.getOrNull()?.let(PsbtCodec::fromBytes)
            if (p != null) onPsbt(p) else onError(invalid)
        }
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (scanning) QrReader({ scanning = false }) { text ->
            if (scanning) PsbtCodec.parse(text)?.let { scanning = false; onPsbt(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LwButton(stringResource(if (scanning) R.string.common_cancel else R.string.scan_short), { scanning = !scanning }, Modifier.weight(1f).testTag("psbt-scan"),
                BtnVariant.Secondary, height = 42.dp, icon = if (scanning) L.drawable.lucide_ic_x else L.drawable.lucide_ic_scan_qr_code, enabled = !busy)
            LwButton(stringResource(R.string.onchain_import_paste), {
                val t = context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                PsbtCodec.parse(t)?.let(onPsbt) ?: onError(invalid)
            }, Modifier.weight(1f).testTag("psbt-paste"), BtnVariant.Secondary, height = 42.dp, icon = L.drawable.lucide_ic_clipboard_paste, enabled = !busy)
            LwButton(stringResource(R.string.onchain_psbt_file), { open.launch(arrayOf("*/*")) }, Modifier.weight(1f), BtnVariant.Secondary, height = 42.dp, icon = L.drawable.lucide_ic_folder_open, enabled = !busy)
        }
    }
}

/** Confirmations an onchain tx needs before the app calls it settled (and stops showing its blocks). */
internal const val SAFE_CONFIRMATIONS = 6

/**
 * Six blocks in a row, one per confirmation: the confirmed ones solid, the rest at half strength, and the one being mined
 * breathing in and out. When a block lands it springs in (a small pop as it fills). Animated values are read in the
 * draw/layer phase only.
 */
@Composable
internal fun BlockStrip(confirmed: Int, tone: Color, modifier: Modifier = Modifier, block: androidx.compose.ui.unit.Dp = 12.dp) {
    val mining = androidx.compose.animation.core.rememberInfiniteTransition(label = "mining")
    val pulse = mining.animateFloat(0f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(950, easing = androidx.compose.animation.core.FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
    Row(modifier.testTag("block-strip"), horizontalArrangement = Arrangement.spacedBy(block / 3), verticalAlignment = Alignment.CenterVertically) {
        repeat(SAFE_CONFIRMATIONS) { i ->
            val fill = androidx.compose.animation.core.animateFloatAsState(
                if (i < confirmed) 1f else 0f,
                androidx.compose.animation.core.spring(dampingRatio = 0.5f, stiffness = androidx.compose.animation.core.Spring.StiffnessLow), label = "block$i",
            )
            val active = i == confirmed
            Box(
                Modifier.size(block).graphicsLayer {
                    val f = fill.value.coerceIn(0f, 1.2f)
                    val p = if (active) pulse.value else 0f
                    alpha = (0.5f + 0.5f * f.coerceAtMost(1f)).coerceAtLeast(0.5f + 0.42f * p)
                    // Landing: overshoots a little past full size; mining: breathes slightly.
                    val pop = if (f > 0f && f < 1.2f) 1f + 0.22f * kotlin.math.sin(Math.PI * f.coerceAtMost(1f)).toFloat() else 1f
                    scaleX = pop * (1f - 0.08f * (1f - p) * (if (active) 1f else 0f)); scaleY = scaleX
                }.drawBehind {
                    val r = androidx.compose.ui.geometry.CornerRadius(size.minDimension * 0.28f)
                    drawRoundRect(tone, cornerRadius = r)
                    // A lit top edge: reads as a block, not a dot.
                    drawRoundRect(Color.White.copy(alpha = 0.28f), size = androidx.compose.ui.geometry.Size(size.width, size.height * 0.34f), cornerRadius = r)
                },
            )
        }
    }
}

/** For the activity list: a pending row's second line. */
@Composable
internal fun pendingLine(tx: io.lawallet.wallet.app.OnchainTx) =
    if (tx.incoming) stringResource(R.string.onchain_tx_unconfirmed)
    else tx.feeRate?.let { stringResource(R.string.onchain_pending_outSub, rateText(it)) } ?: stringResource(R.string.onchain_tx_unconfirmed)

