package io.lawallet.wallet.ui.screens

import io.lawallet.wallet.ui.components.lwFieldColors
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
import io.lawallet.wallet.ui.components.copyToClipboard
import androidx.compose.foundation.clickable
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
import io.lawallet.wallet.ui.components.shareText
import io.lawallet.wallet.ui.components.qrBitmap
import io.lawallet.wallet.ui.theme.GlassLevel
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.border
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.ColumnScope
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
import io.lawallet.wallet.ui.components.PaymentReceipt
import androidx.compose.foundation.layout.PaddingValues
import io.lawallet.wallet.ui.components.QrDisplay
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.keypadDisplay
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import com.composables.icons.lucide.R as L

/**
 * Receive, as Home's pages: Onchain on the left, Lightning on the right, swiped the same way and in the same light
 * (the backdrop follows [ReceivePager]). Opens on the page Home was on.
 */
@Composable
fun ReceiveScreen(start: Int) {
    val nav = LocalNav.current
    val pager = androidx.compose.foundation.pager.rememberPagerState(initialPage = start) { 2 }
    DisposableEffect(pager) { ReceivePager.state = pager; onDispose { if (ReceivePager.state === pager) ReceivePager.state = null } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.receive_title), { nav.popBackStack() }, close = true)
        ReceiveTabs(pager)
        androidx.compose.foundation.pager.HorizontalPager(pager, Modifier.weight(1f).testTag("receive-pages"), beyondViewportPageCount = 1) { page ->
            Box(Modifier.fillMaxSize()) {
                PageWatermark(page, { pager.currentPage - page + pager.currentPageOffsetFraction }, { 0f })
                Column(Modifier.fillMaxSize()) { if (page == ONCHAIN) OnchainReceivePage() else LightningReceivePage() }
            }
        }
    }
}

/** Onchain | Lightning, segmented: the thumb slides and takes each page's tone 1:1 with the swipe; tap to go there. */
@Composable
private fun ReceiveTabs(pager: androidx.compose.foundation.pager.PagerState) {
    val scope = rememberCoroutineScope()
    val pos = { pager.currentPage + pager.currentPageOffsetFraction }
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.padding(horizontal = 16.dp, vertical = 4.dp).fillMaxWidth().height(52.dp).glass(CircleShape, GlassLevel.Thin).padding(4.dp).testTag("receive-tabs"),
    ) {
        val half = maxWidth / 2
        Box(Modifier.width(half).fillMaxHeight().graphicsLayer { translationX = pos().coerceIn(0f, 1f) * half.toPx() }.drawBehind {
            val tone = androidx.compose.ui.graphics.lerp(PAGE_TONES[ONCHAIN], PAGE_TONES[LIGHTNING], pos().coerceIn(0f, 1f))
            val r = androidx.compose.ui.geometry.CornerRadius(size.height / 2)
            drawRoundRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tone.copy(alpha = 0.42f), tone.copy(alpha = 0.22f))), cornerRadius = r)
            drawRoundRect(tone.copy(alpha = 0.7f), cornerRadius = r, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx()))
        })
        Row(Modifier.fillMaxSize()) {
            listOf(Triple(ONCHAIN, L.drawable.lucide_ic_bitcoin, R.string.receive_tab_onchain), Triple(LIGHTNING, L.drawable.lucide_ic_zap, R.string.receive_tab_lightning)).forEach { (page, icon, label) ->
                Row(
                    Modifier.weight(1f).fillMaxHeight().clip(CircleShape).clickable { scope.launch { pager.animateScrollToPage(page) } }
                        .graphicsLayer { alpha = 0.55f + 0.45f * HomePager.weight(page, pos()) }.testTag("receive-tab-$page"),
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    LwIcon(icon, size = 18.dp, tint = Lw.Foreground)
                    Spacer(Modifier.width(8.dp))
                    Txt(stringResource(label), size = 15.sp, weight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** A round action under the QR: a glass disc with its icon, the label under it. */
internal data class ReceiveAction(val icon: Int, val label: String, val tag: String, val loading: Boolean = false, val onClick: () -> Unit)

@Composable
internal fun ReceiveActions(actions: List<ReceiveAction>) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
    actions.forEach { a ->
        Column(Modifier.width(80.dp).clip(RoundedCornerShape(20.dp)).clickable(enabled = !a.loading, onClick = a.onClick).padding(vertical = 6.dp).testTag(a.tag), horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(56.dp).glass(CircleShape, GlassLevel.Regular), contentAlignment = Alignment.Center) {
                if (a.loading) Spinner(20.dp) else LwIcon(a.icon, size = 22.dp)
            }
            Spacer(Modifier.height(8.dp))
            Txt(a.label, size = 12.sp, weight = FontWeight.Medium, color = Lw.Foreground.copy(alpha = 0.85f), align = TextAlign.Center, maxLines = 2, lineHeight = 15.sp)
        }
    }
}

/**
 * The QR, as big as the screen allows (up to 340dp): white on a glass frame in the page's [tone], the network's [mark]
 * in the middle (error correction Q leaves room for it). Tap copies. A payment arriving flashes its amount over it.
 */
@Composable
internal fun BigQr(data: String?, tone: Color, mark: Int, credit: Long?, onTap: () -> Unit) {
    val bmp by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, data) {
        value = data?.let { d -> kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { qrBitmap(d, com.google.zxing.qrcode.decoder.ErrorCorrectionLevel.Q).asImageBitmap() } }
    }
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        val side = (maxWidth - 24.dp).coerceAtMost(340.dp)
        Box(
            Modifier.size(side).glass(RoundedCornerShape(36.dp), GlassLevel.Regular, tint = tone.copy(alpha = 0.10f))
                .border(1.dp, androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tone.copy(alpha = 0.55f), tone.copy(alpha = 0.10f))), RoundedCornerShape(36.dp)).padding(12.dp),
        ) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(26.dp)).background(Color.White).clickable(onClickLabel = stringResource(R.string.common_copy), onClick = onTap).padding(18.dp).testTag("qr"),
                contentAlignment = Alignment.Center,
            ) {
                val b = bmp
                if (b == null) Spinner(color = Color.Black)
                else {
                    androidx.compose.foundation.Image(b, null, Modifier.fillMaxSize(), filterQuality = androidx.compose.ui.graphics.FilterQuality.None)
                    Box(Modifier.fillMaxSize(0.2f).clip(CircleShape).background(Color.White).padding(5.dp).clip(CircleShape).background(tone), contentAlignment = Alignment.Center) {
                        LwIcon(mark, Modifier.fillMaxSize(0.56f), tint = Color.White)
                    }
                }
            }
        }
        androidx.compose.animation.AnimatedVisibility(credit != null, Modifier.padding(top = 20.dp), enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut()) {
            Txt("+" + Currencies.formatSats(credit ?: 0) + " sats", Modifier.clip(RoundedCornerShape(50)).background(Lw.Green).padding(horizontal = 16.dp, vertical = 8.dp), size = 18.sp, weight = FontWeight.SemiBold, color = Color.White)
        }
    }
}

/** The Lightning page: the account's address as a big QR and headline, Copy / Share / Amount under it. */
@Composable
private fun ColumnScope.LightningReceivePage() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val me by c.session.me.collectAsState()
    var credit by remember { mutableStateOf<Long?>(null) }
    val copied = stringResource(R.string.common_copied)
    LaunchedEffect(Unit) { runCatching { c.session.refreshMe() } }
    LaunchedEffect(Unit) {
        c.wallet.notices.filter { it.type == "payment_received" }.collect { credit = it.tx.amountSats; delay(2500); credit = null }
    }
    val address = c.wallet.shownAddress(me)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Spacer(Modifier.height(4.dp))
        if (address != null) {
            // Lowercase: an address gains nothing from QR alphanumeric mode ("@" is outside it), and some wallets reject an uppercased one.
            BigQr("lightning:$address", PAGE_TONES[LIGHTNING], L.drawable.lucide_ic_zap, credit) { context.copyToClipboard(address, copied) }
            AddressHero(address)
            ReceiveActions(listOf(
                ReceiveAction(L.drawable.lucide_ic_copy, stringResource(R.string.common_copy), "receive-copy") { context.copyToClipboard(address, copied) },
                ReceiveAction(L.drawable.lucide_ic_share_2, stringResource(R.string.common_share), "receive-share") { context.shareText(address) },
                ReceiveAction(L.drawable.lucide_ic_hash, stringResource(R.string.receive_action_amount), "request-amount") { c.flow.resetReceive(); nav.navigate(R0.RECEIVE_AMOUNT) },
            ))
        } else if (me != null) {
            Spacer(Modifier.height(40.dp))
            MutedText(stringResource(R.string.receive_noAddress), align = TextAlign.Center)
            LwButton(stringResource(R.string.receive_claimUsername), { nav.navigate("${R0.CLAIM}?from=&bonus=0") }, Modifier.fillMaxWidth(), BtnVariant.Secondary)
        } else { Spacer(Modifier.height(80.dp)); Spinner() }
        Spacer(Modifier.height(16.dp))
    }
}

/** The address as the headline under the QR: big username, @domain in the Lightning tone. Tap copies. */
@Composable
private fun AddressHero(address: String) {
    val context = LocalContext.current
    val copied = stringResource(R.string.common_copied)
    Column(
        Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClickLabel = stringResource(R.string.common_copy)) { context.copyToClipboard(address, copied) }
            .padding(horizontal = 12.dp, vertical = 4.dp).testTag("receive-address"),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Txt(stringResource(R.string.receive_yourAddress), size = 13.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Username leads and may ellipsize; the domain never truncates.
            Txt(address.substringBefore('@'), Modifier.weight(1f, fill = false), size = 26.sp, weight = FontWeight.SemiBold, maxLines = 1, letterSpacing = (-0.4).sp)
            Txt("@" + address.substringAfter('@'), size = 26.sp, weight = FontWeight.Medium, color = PAGE_TONES[LIGHTNING], maxLines = 1, letterSpacing = (-0.4).sp)
        }
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
            OutlinedTextField(note, { note = it.take(140) }, Modifier.fillMaxWidth(), enabled = !loading, placeholder = { Txt(stringResource(R.string.receive_notePlaceholder), color = Lw.MutedFg) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = lwFieldColors())
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
        // Until the invoice expires, and only while the app is visible (a payment made meanwhile shows on return).
        val expires = runCatching { io.lawallet.wallet.core.ln.Bolt11.decode(bolt11).expiresAtMs }.getOrDefault(Long.MAX_VALUE)
        while (System.currentTimeMillis() < expires) {
            delay(if (pollFast) 1000 else 4000)
            c.wallet.active.first { it }
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
            AmountDisplay(Currencies.formatSats(r.amountSats), "sats", size = 36.sp, unitSize = 16.sp, figureTag = "invoice-amount")
            r.description?.let { MutedText(it, align = TextAlign.Center) }
            QrDisplay("lightning:$bolt11".uppercase(), null, copyValue = bolt11)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(14.dp, Lw.MutedFg); Spacer(Modifier.width(8.dp)); MutedText(stringResource(R.string.receive_waitingPayment))
            }
            Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
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
        PaymentReceipt(title, "settled", tx.amountSats.takeIf { it > 0 } ?: (c.flow.receive.amountSats ?: 0), rows,
            listOf(stringResource(R.string.receive_summary_paymentHash) to tx.paymentHash.ifEmpty { null }, stringResource(R.string.receive_summary_preimage) to tx.preimage),
            PaddingValues(horizontal = 16.dp, vertical = 32.dp), onDone = { c.flow.resetReceive(); nav.goHome() })
    }
}
