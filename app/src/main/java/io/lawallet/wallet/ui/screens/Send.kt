package io.lawallet.wallet.ui.screens

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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.lawallet.wallet.R
import io.lawallet.wallet.app.FlowStore
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.ln.Bolt11
import io.lawallet.wallet.core.ln.Destination
import io.lawallet.wallet.core.ln.DestinationException
import io.lawallet.wallet.core.ln.DestinationParser
import io.lawallet.wallet.core.store.Contact
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
import io.lawallet.wallet.ui.components.ReceiptFooter
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.SwipeButton
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.keypadDisplay
import io.lawallet.wallet.ui.components.shareText
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date
import com.composables.icons.lucide.R as L

private val SUGGESTED_DOMAINS = listOf("lacrypta.ar", "lawallet.io", "walletofsatoshi.com", "blink.sv", "strike.me", "getalby.com", "ln.tips", "stacker.news", "primal.net", "fountain.fm", "coinos.io", "bitrefill.me", "zbd.gg", "npub.cash")

private fun domainAvatar(domain: String) = "https://raw.githubusercontent.com/lawalletio/static/main/public/img/domains/${if (domain == "lawallet.io") "lawallet.ar" else domain}.png"

/** Resolves a parsed destination into the send flow; returns the next route. */
suspend fun startSend(c: io.lawallet.wallet.app.AppContainer, dest: Destination): String {
    c.flow.send = FlowStore.Send(destination = dest)
    return when (dest) {
        is Destination.Invoice -> {
            c.flow.send = c.flow.send.copy(bolt11 = dest.bolt11, amountSats = dest.amountSats, recipientLabel = dest.description)
            if (dest.amountSats != null) R0.SEND_PREVIEW else R0.SEND_AMOUNT
        }
        is Destination.LnurlPay -> {
            val meta = c.lnurl.payMetadata(dest.lnurlpUrl)
            c.flow.send = c.flow.send.copy(meta = meta, recipientLabel = dest.address ?: meta.identifier ?: java.net.URI(dest.lnurlpUrl).host)
            if (meta.minSendableMsat == meta.maxSendableMsat) {
                c.flow.send = c.flow.send.copy(amountSats = meta.minSendableMsat / 1000)
                R0.SEND_PREVIEW
            } else R0.SEND_AMOUNT
        }
        is Destination.Npub -> throw DestinationException("unsupported", "Paying a Nostr profile is not supported yet")
    }
}

@Composable
fun SendRecipientScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    var input by rememberSaveable { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val contacts = remember { c.prefs.contacts }
    val invalid = stringResource(R.string.send_invalidRecipient)
    fun submit(value: String = input) {
        loading = true; error = null
        scope.launch {
            runCatching { startSend(c, DestinationParser.parse(value)) }
                .onSuccess { nav.navigate(it) }
                .onFailure { error = it.message ?: invalid }
            loading = false
        }
    }
    val user = input.substringBefore('@')
    val typedDomain = input.substringAfter('@', "")
    val suggestions = if (user.isNotBlank() && !input.startsWith("ln", true) && user.all { it.isLetterOrDigit() || it in "._-" })
        SUGGESTED_DOMAINS.filter { it.startsWith(typedDomain, true) && "$user@$it" != input }.take(6) else emptyList()
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(stringResource(R.string.send_title), { nav.popBackStack() }, close = true)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Txt(stringResource(R.string.send_to), size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
            OutlinedTextField(
                input, { input = it; error = null }, Modifier.fillMaxWidth().testTag("recipient-input"),
                placeholder = { Txt("satoshi@lawallet.io", color = Lw.MutedFg) },
                singleLine = true, shape = RoundedCornerShape(12.dp), colors = lwFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { if (input.isNotBlank()) submit() }),
                trailingIcon = {
                    Box(Modifier.size(40.dp).clickable { nav.navigate(R0.SCAN) }, contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_scan_qr_code, size = 20.dp, tint = Lw.MutedFg) }
                },
            )
            ErrorText(error, Modifier.fillMaxWidth())
            if (suggestions.isNotEmpty()) AddressGroup(stringResource(R.string.shared_addressInput_suggestions), suggestions.map { "$user@$it" to domainAvatar(it) }) { input = it; submit(it) }
            if (contacts.isNotEmpty() && input.isBlank()) AddressGroup(stringResource(R.string.shared_addressInput_saved), contacts.map { it.address to (it.picture ?: domainAvatar(it.address.substringAfter('@'))) }) { input = it; submit(it) }
        }
        LwButton(stringResource(R.string.common_continue), { submit() }, Modifier.fillMaxWidth().padding(16.dp).testTag("recipient-continue"), BtnVariant.Theme, enabled = input.isNotBlank(), loading = loading)
    }
}

@Composable
private fun AddressGroup(title: String, items: List<Pair<String, String>>, onPick: (String) -> Unit) {
    Column {
        Txt(title, Modifier.padding(start = 6.dp, bottom = 8.dp), size = 13.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp))) {
            items.forEach { (address, avatar) ->
                Row(Modifier.fillMaxWidth().clickable { onPick(address) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    AsyncImage(avatar, null, Modifier.size(32.dp).glass(CircleShape, GlassLevel.Strong), contentScale = ContentScale.Crop)
                    Spacer(Modifier.width(12.dp))
                    Txt(address, size = 14.sp, maxLines = 1)
                }
            }
        }
    }
}

/** Recipient card used by amount + preview steps. */
@Composable
private fun RecipientCard(s: FlowStore.Send) {
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(40.dp).glass(CircleShape, GlassLevel.Strong), contentAlignment = Alignment.Center) {
            val img = s.meta?.imageDataUri
            if (img != null) AsyncImage(img, null, Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
            else LwIcon(if (s.destination is Destination.Invoice) L.drawable.lucide_ic_zap else L.drawable.lucide_ic_at_sign, size = 18.dp, tint = Lw.MutedFg)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(stringResource(if (s.destination is Destination.Invoice) R.string.send_kind_invoice else R.string.send_kind_address), size = 12.sp, color = Lw.MutedFg)
            Txt(s.recipientLabel ?: (s.bolt11?.take(18)?.plus("…") ?: ""), size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
fun SendAmountScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val s = c.flow.send
    val currencies by c.prefs.currencies.collectAsState()
    val rates by c.rates.rates.collectAsState()
    var code by rememberSaveable { mutableStateOf(currencies.selected.takeIf { it in currencies.active } ?: "SAT") }
    var raw by rememberSaveable { mutableStateOf(Currencies.inputFromSats(s.amountSats, code, rates)) }
    var note by rememberSaveable { mutableStateOf(s.comment ?: "") }
    if (s.destination == null) { LaunchedEffect(Unit) { nav.navigate(R0.SEND) { popUpTo(R0.SEND) { inclusive = true } } }; return }
    val sats = Currencies.parseToSats(raw, code, rates)
    val meta = s.meta
    val outOfRange = meta != null && sats != null && (sats * 1000 < meta.minSendableMsat || sats * 1000 > meta.maxSendableMsat)
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(stringResource(R.string.send_amountTitle), { nav.popBackStack() })
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RecipientCard(s)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountDisplay(keypadDisplay(raw, code), Currencies.unitLabel(code))
                if (code != "SAT") MutedText(stringResource(R.string.common_sats, Currencies.formatSats(sats ?: 0)))
                CurrencyToggle(currencies.active, code, { next -> raw = Currencies.inputFromSats(sats, next, rates); code = next })
                if (outOfRange) ErrorText("${(meta!!.minSendableMsat + 999) / 1000} – ${meta.maxSendableMsat / 1000} sats")
            }
            if ((meta?.commentAllowed ?: 0) > 0) OutlinedTextField(
                note, { note = it.take(meta!!.commentAllowed) }, Modifier.fillMaxWidth(),
                placeholder = { Txt(stringResource(R.string.send_amount_notePlaceholder), color = Lw.MutedFg) },
                supportingText = { Txt(stringResource(R.string.send_amount_noteLeft, (meta!!.commentAllowed - note.length).toString()), size = 12.sp, color = Lw.MutedFg) },
                singleLine = true, shape = RoundedCornerShape(12.dp), colors = lwFieldColors(),
            )
            AmountKeypad(raw, { raw = it }, Currencies.keypadMode(code), Modifier.weight(1f).fillMaxWidth())
        }
        LwButton(stringResource(R.string.common_continue), {
            c.flow.send = s.copy(amountSats = sats, comment = note.ifBlank { null })
            nav.navigate(R0.SEND_PREVIEW)
        }, Modifier.fillMaxWidth().padding(16.dp).testTag("amount-continue"), BtnVariant.Theme, enabled = sats != null && !outOfRange)
    }
}

@Composable
fun SendPreviewScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val s = c.flow.send
    val currencies by c.prefs.currencies.collectAsState()
    val rates by c.rates.rates.collectAsState()
    var paying by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val noWallet = stringResource(R.string.send_preview_noWallet)
    val failed = stringResource(R.string.send_preview_paymentFailed)
    val amount = s.amountSats
    if (s.destination == null || amount == null) { LaunchedEffect(Unit) { nav.navigate(R0.SEND) { popUpTo(R0.SEND) { inclusive = true } } }; return }

    fun pay() {
        paying = true; error = null
        scope.launch {
            runCatching {
                val nwc = c.wallet.freshClient() ?: throw IllegalStateException(noWallet)
                var sentComment = s.comment
                val (invoice, amountMsat) = when (val d = s.destination) {
                    is Destination.Invoice -> d.bolt11 to (if (d.amountSats == null) amount * 1000 else null)
                    is Destination.LnurlPay -> c.lnurl.requestInvoice(d.lnurlpUrl, amount, s.comment).also { sentComment = it.comment }.paymentRequest to null
                    else -> throw IllegalStateException(failed)
                }
                val res = nwc.payInvoice(invoice, amountMsat)
                val hash = runCatching { Bolt11.decode(invoice).paymentHash }.getOrNull()
                c.flow.send = s.copy(result = FlowStore.SendResult(amount, (res.feesPaid ?: 0) / 1000, res.preimage.ifEmpty { null }, hash, s.recipientLabel ?: "", sentComment, System.currentTimeMillis()))
                (s.destination as? Destination.LnurlPay)?.address?.let { addr ->
                    c.prefs.contacts = listOf(Contact(addr, null, s.meta?.imageDataUri?.takeIf { it.length < 4000 }, System.currentTimeMillis())) + c.prefs.contacts.filter { it.address != addr }
                }
            }.onSuccess {
                done = true
                c.scope.launch { c.wallet.refreshBalance(); c.wallet.refreshActivity() }
                nav.navigate(R0.SEND_SUMMARY) { popUpTo(R0.HOME) }
            }.onFailure { error = it.message ?: failed; paying = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.send_previewTitle), { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp)).padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(stringResource(R.string.send_preview_sending), size = 14.sp, color = Lw.MutedFg)
                Row(verticalAlignment = Alignment.Bottom) {
                    Txt(Currencies.formatSats(amount), size = 60.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("preview-amount"))
                    Spacer(Modifier.width(8.dp))
                    Txt("sats", Modifier.padding(bottom = 10.dp), size = 18.sp, color = Lw.MutedFg)
                }
            }
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PreviewRow(stringResource(R.string.send_to), s.recipientLabel ?: "—")
                PreviewRow(stringResource(R.string.send_preview_type), stringResource(if (s.destination is Destination.Invoice) R.string.send_kind_invoice else R.string.send_kind_address))
                s.comment?.let { PreviewRow(stringResource(R.string.send_note), it) }
            }
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(stringResource(R.string.send_preview_quote), size = 15.sp, weight = FontWeight.SemiBold)
                PreviewRow(stringResource(R.string.send_preview_amount), stringResource(R.string.common_sats, Currencies.formatSats(amount)))
                PreviewRow(stringResource(R.string.send_networkFee), stringResource(R.string.send_preview_feeNotQuoted))
                PreviewRow(stringResource(R.string.send_preview_total), stringResource(R.string.send_preview_plusFee, Currencies.formatSats(amount)))
                val fiat = currencies.active.filter { it != "SAT" }
                if (fiat.isNotEmpty()) {
                    Txt(stringResource(R.string.send_preview_equivalents), size = 12.sp, color = Lw.MutedFg)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        fiat.take(3).forEach { cc ->
                            Column(Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Lw.Background).padding(10.dp)) {
                                Txt(cc, size = 11.sp, color = Lw.MutedFg)
                                Txt(Currencies.format(amount, cc, rates), size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1)
                            }
                        }
                    }
                }
            }
            ErrorText(error, Modifier.fillMaxWidth())
        }
        SwipeButton(stringResource(R.string.send_preview_swipeToPay), ::pay, loading = paying, done = done, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun PreviewRow(k: String, v: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(k, size = 14.sp, color = Lw.MutedFg)
        Txt(v, Modifier.weight(1f), size = 14.sp, align = TextAlign.End, maxLines = 2)
    }
}

@Composable
fun SendSummaryScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val currencies by c.prefs.currencies.collectAsState()
    val rates by c.rates.rates.collectAsState()
    var code by remember { mutableStateOf(currencies.selected) }
    val r = c.flow.send.result
    if (r == null) { LaunchedEffect(Unit) { nav.goHome() }; return }
    val rows = buildList {
        add(stringResource(R.string.send_to) to r.to)
        add(stringResource(R.string.send_networkFee) to stringResource(R.string.common_sats, Currencies.formatSats(r.feesSats)))
        r.note?.let { add(stringResource(R.string.send_note) to it) }
        add(stringResource(R.string.send_summary_time) to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(r.at)))
    }
    val title = stringResource(R.string.send_summary_title)
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 32.dp)) {
            PaymentReceiptCard(title, "settled", r.amountSats, code, currencies.active, rates, { code = it }, rows,
                listOf(stringResource(R.string.send_summary_paymentHash) to r.paymentHash, stringResource(R.string.send_summary_preimage) to r.preimage))
        }
        ReceiptFooter(
            onShare = { context.shareText("$title: ${Currencies.formatSats(r.amountSats)} sats → ${r.to}\n${r.paymentHash ?: ""}") },
            onActivity = { c.flow.resetSend(); nav.navigate(R0.ACTIVITY) { popUpTo(R0.HOME) } },
            onDone = { c.flow.resetSend(); nav.goHome() },
        )
    }
}
