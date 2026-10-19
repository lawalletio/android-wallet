package io.lawallet.wallet.ui.screens

import android.nfc.NfcAdapter
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.app.FlowStore
import io.lawallet.wallet.app.NfcBus
import io.lawallet.wallet.app.Tx
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.ln.BoltcardPayException
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.AmountDisplay
import io.lawallet.wallet.ui.components.AmountKeypad
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.CurrencyToggle
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.PaymentReceiptCard
import io.lawallet.wallet.ui.components.QrDisplay
import io.lawallet.wallet.ui.components.ReceiptFooter
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.keypadDisplay
import io.lawallet.wallet.ui.components.shareText
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.theme.Lw
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import com.composables.icons.lucide.R as L

@Composable
fun ReceiveAddressScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val me by c.session.me.collectAsState()
    var credit by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) { runCatching { c.session.refreshMe() } }
    LaunchedEffect(Unit) {
        c.wallet.notices.filter { it.type == "payment_received" }.collect { credit = it.tx.amountSats; delay(2500); credit = null }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.receive_title), { nav.popBackStack() }, close = true)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(24.dp))
            val address = me?.lightningAddress
            if (address != null) {
                Box(contentAlignment = Alignment.TopCenter) {
                    QrDisplay("lightning:$address".uppercase(), address, copyValue = address)
                    androidx.compose.animation.AnimatedVisibility(credit != null, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
                        Txt("+" + Currencies.formatSats(credit ?: 0), Modifier.clip(RoundedCornerShape(50)).background(Lw.Green.copy(alpha = 0.15f)).padding(horizontal = 14.dp, vertical = 6.dp), size = 20.sp, weight = FontWeight.SemiBold, color = Lw.Green)
                    }
                }
            } else if (me != null) {
                MutedText(stringResource(R.string.receive_noAddress), align = TextAlign.Center)
                Spacer(Modifier.height(16.dp))
                LwButton(stringResource(R.string.receive_claimUsername), { nav.navigate("${R0.CLAIM}?from=&bonus=0") }, Modifier.fillMaxWidth(), BtnVariant.Secondary)
            } else Spinner()
        }
        LwButton(stringResource(R.string.receive_requestAmount), { c.flow.resetReceive(); nav.navigate(R0.RECEIVE_AMOUNT) }, Modifier.fillMaxWidth().padding(16.dp).testTag("request-amount"), BtnVariant.Theme)
    }
}

@Composable
fun ReceiveAmountScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val currencies by c.prefs.currencies.collectAsState()
    val rates by c.rates.rates.collectAsState()
    var code by rememberSaveable { mutableStateOf(currencies.selected.takeIf { it in currencies.active } ?: "SAT") }
    var raw by rememberSaveable { mutableStateOf("0") }
    var note by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val noWallet = stringResource(R.string.receive_noWallet)
    val sats = Currencies.parseToSats(raw, code, rates)
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(stringResource(R.string.receive_title), { nav.popBackStack() })
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountDisplay(keypadDisplay(raw, code), Currencies.unitLabel(code))
                if (code != "SAT") MutedText(stringResource(R.string.common_sats, Currencies.formatSats(sats ?: 0)))
                CurrencyToggle(currencies.active, code, { next -> raw = Currencies.inputFromSats(sats, next, rates); code = next })
            }
            OutlinedTextField(note, { note = it.take(140) }, Modifier.fillMaxWidth(), placeholder = { Txt(stringResource(R.string.receive_notePlaceholder), color = Lw.MutedFg) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = lwFieldColors())
            AmountKeypad(raw, { raw = it }, Currencies.keypadMode(code), Modifier.weight(1f).fillMaxWidth(), enabled = !loading)
            ErrorText(error, Modifier.fillMaxWidth())
        }
        LwButton(
            stringResource(if (loading) R.string.receive_creatingInvoice else R.string.common_continue),
            {
                val amount = sats ?: return@LwButton
                loading = true; error = null
                scope.launch {
                    runCatching {
                        val nwc = c.wallet.ensureWallet() ?: throw IllegalStateException(noWallet)
                        val inv = nwc.makeInvoice(amount * 1000, note.ifBlank { null })
                        c.flow.receive = FlowStore.Receive(amount, note.ifBlank { null }, inv.invoice, inv.paymentHash)
                    }.onSuccess { nav.navigate(R0.RECEIVE_INVOICE) }.onFailure { error = it.message ?: noWallet }
                    loading = false
                }
            },
            Modifier.fillMaxWidth().padding(16.dp).testTag("receive-continue"), BtnVariant.Theme, enabled = sats != null, loading = loading,
        )
    }
}

@Composable
fun ReceiveInvoiceScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val r = c.flow.receive
    val bolt11 = r.bolt11
    var nfcState by remember { mutableStateOf<String?>(null) }
    var nfcError by remember { mutableStateOf<String?>(null) }
    var pollFast by remember { mutableStateOf(false) }
    if (bolt11 == null || r.amountSats == null) { LaunchedEffect(Unit) { nav.navigate(R0.RECEIVE) { popUpTo(R0.RECEIVE) { inclusive = true } } }; return }

    fun paid(tx: Tx) {
        c.flow.receive = c.flow.receive.copy(paid = tx)
        nav.navigate(R0.RECEIVE_SUMMARY) { popUpTo(R0.HOME) }
    }
    LaunchedEffect(bolt11) { c.wallet.notices.filter { it.type == "payment_received" && it.tx.paymentHash == r.paymentHash }.collect { paid(it.tx) } }
    // Some wallets never emit the notification: poll lookup_invoice (fast after a card tap).
    LaunchedEffect(bolt11) {
        while (true) {
            delay(if (pollFast) 1000 else 4000)
            val hash = r.paymentHash ?: continue
            val tx = runCatching { c.wallet.client.value?.lookupInvoice(hash) }.getOrNull()
            if (tx?.settledAt != null) { paid(Tx.from(tx)); break }
        }
    }
    val charging = stringResource(R.string.receive_nfc_charging)
    val accepted = stringResource(R.string.receive_nfc_cardAccepted)
    val failedMsg = stringResource(R.string.receive_nfc_failed)
    DisposableEffect(bolt11) {
        NfcBus.consumer = { url ->
            scope.launch {
                nfcState = charging; nfcError = null
                runCatching { c.boltcardPay.payDisplayedInvoice(url, bolt11, r.amountSats) }
                    .onSuccess { nfcState = accepted; pollFast = true }
                    .onFailure { nfcState = null; nfcError = (it as? BoltcardPayException)?.message ?: failedMsg }
            }
        }
        onDispose { NfcBus.consumer = null }
    }
    val nfc = remember { NfcAdapter.getDefaultAdapter(context) }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.receive_invoiceTitle), { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Txt(Currencies.formatSats(r.amountSats), size = 36.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Txt("sats", Modifier.padding(bottom = 6.dp), size = 16.sp, color = Lw.MutedFg)
            }
            r.description?.let { MutedText(it, align = TextAlign.Center) }
            QrDisplay("lightning:$bolt11".uppercase(), null, copyValue = bolt11)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(14.dp, Lw.MutedFg); Spacer(Modifier.width(8.dp)); MutedText(stringResource(R.string.receive_waitingPayment))
            }
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Lw.Card).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                LwIcon(L.drawable.lucide_ic_nfc, size = 20.dp, tint = Lw.MutedFg)
                Spacer(Modifier.width(10.dp))
                Txt(
                    nfcState ?: when { nfc == null -> stringResource(R.string.receive_nfc_unsupported); !nfc.isEnabled -> stringResource(R.string.nfc_off); else -> stringResource(R.string.receive_nfc_scanning) },
                    Modifier.weight(1f), size = 13.sp, color = Lw.MutedFg,
                )
            }
            ErrorText(nfcError, Modifier.fillMaxWidth())
        }
    }
}

@Composable
fun ReceiveSummaryScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val currencies by c.prefs.currencies.collectAsState()
    val rates by c.rates.rates.collectAsState()
    var code by remember { mutableStateOf(currencies.selected) }
    val tx = c.flow.receive.paid
    if (tx == null) { LaunchedEffect(Unit) { nav.goHome() }; return }
    val title = stringResource(R.string.receive_summary_title)
    val rows = buildList {
        (tx.description ?: c.flow.receive.description)?.let { add(stringResource(R.string.receive_summary_note) to it) }
        add(stringResource(R.string.receive_summary_time) to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(tx.settledAt ?: System.currentTimeMillis())))
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 32.dp)) {
            PaymentReceiptCard(title, "settled", tx.amountSats.takeIf { it > 0 } ?: (c.flow.receive.amountSats ?: 0), code, currencies.active, rates, { code = it }, rows,
                listOf(stringResource(R.string.receive_summary_paymentHash) to tx.paymentHash.ifEmpty { null }, stringResource(R.string.receive_summary_preimage) to tx.preimage))
        }
        ReceiptFooter(
            onShare = { context.shareText("$title: ${Currencies.formatSats(tx.amountSats)} sats\n${tx.paymentHash}") },
            onActivity = { c.flow.resetReceive(); nav.navigate(R0.ACTIVITY) { popUpTo(R0.HOME) } },
            onDone = { c.flow.resetReceive(); nav.goHome() },
        )
    }
}
