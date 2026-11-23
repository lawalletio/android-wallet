package io.lawallet.wallet.ui.screens

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import io.lawallet.wallet.R
import io.lawallet.wallet.app.FlowStore
import io.lawallet.wallet.core.api.ApiException
import io.lawallet.wallet.core.api.BankAccount
import io.lawallet.wallet.core.api.Swap
import io.lawallet.wallet.core.api.SwapQuote
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.ln.BankDestination
import io.lawallet.wallet.core.ln.BankDestination.Phase
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.awaitSettled
import io.lawallet.wallet.ui.components.AmountDisplay
import io.lawallet.wallet.ui.components.AmountKeypad
import io.lawallet.wallet.ui.components.BankAvatar
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.KeyValueRow
import io.lawallet.wallet.ui.components.LoadingBox
import io.lawallet.wallet.ui.components.LwBottomSheet
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.Pill
import io.lawallet.wallet.ui.components.ProviderLogo
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.SwipeButton
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.copyToClipboard
import io.lawallet.wallet.ui.components.keypadDisplay
import io.lawallet.wallet.ui.components.providerName
import io.lawallet.wallet.ui.components.toastError
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.text.DateFormat
import java.util.Date
import kotlin.math.ceil
import com.composables.icons.lucide.R as L

/** Pesos as banks print them: whole, grouped. */
internal fun ars(v: Double) = Currencies.formatSats(Math.round(v))

/** A BTC price as pesos per sat (≈ 1.06): 2 to 4 decimals, the way the rates differ. */
internal fun arsPerSat(arsPerBtc: Double): String = java.text.DecimalFormat("#,##0.00##").format(arsPerBtc / 100_000_000)

private sealed interface Lookup {
    data object Idle : Lookup
    data object Loading : Lookup
    data object Busy : Lookup
    /** The lookup service is down: the typed CBU/alias can still be paid, its holder typed by hand. */
    data class Unverified(val account: BankAccount) : Lookup
    data class Found(val account: BankAccount) : Lookup
}

/** Starts a cash-out to [account]; [name] is the holder the user saw (saved recipient or lookup). */
internal fun startCashOut(c: io.lawallet.wallet.app.AppContainer, account: BankAccount, name: String?): String {
    c.flow.cashOut = FlowStore.CashOut(account, holderName = name ?: account.owners.firstOrNull()?.name.orEmpty())
    return R0.CASHOUT_AMOUNT
}

/**
 * Bank accounts under Send's field: saved ones that match at once, and a CBU/CVU or alias resolved to its owner and
 * bank. A 22-digit CBU resolves right away; an alias waits until typing stops (it may still become `alias@domain`),
 * and never once there's an `@`.
 */
@Composable
internal fun BankSuggestions(input: String, enabled: Boolean, onPick: (BankAccount, String?) -> Unit) {
    val c = LocalContainer.current
    val q = input.trim()
    val kind = BankDestination.kind(q)
    val all by c.swaps.recipients.collectAsState()
    val saved = remember(q, all) { c.swaps.matching(q).take(4) }
    var menuFor by remember { mutableStateOf<io.lawallet.wallet.app.BankRecipient?>(null) }
    menuFor?.let { r -> SavedRecipientSheet(r, { menuFor = null }) { c.swaps.forget(r) } }
    val known = saved.any { it.account.cvu == q || it.account.alias.equals(q, true) }
    var lookup by remember { mutableStateOf<Lookup>(Lookup.Idle) }
    LaunchedEffect(q) {
        lookup = Lookup.Idle
        if (kind == null || '@' in q || known) return@LaunchedEffect
        if (!c.swaps.lookupEnabled) {
            if (kind == BankDestination.Kind.Cbu) lookup = Lookup.Found(BankAccount(q))
            return@LaunchedEffect
        }
        if (kind == BankDestination.Kind.Alias) delay(800)
        lookup = Lookup.Loading
        val typed = if (kind == BankDestination.Kind.Cbu) BankAccount(q) else BankAccount("", alias = q.lowercase())
        val found = try {
            c.swaps.lookup(q)
        } catch (e: ApiException) {
            // 503: the lookup service (gorilla-api) is down, which says nothing about the account.
            lookup = if (e.status == 429) Lookup.Busy else Lookup.Unverified(typed)
            return@LaunchedEffect
        } catch (_: IOException) { lookup = Lookup.Unverified(typed); return@LaunchedEffect }
        // An unknown CBU can still be paid (the name is typed next); an unknown alias is most likely a Lightning user.
        lookup = when {
            found != null -> Lookup.Found(found)
            kind == BankDestination.Kind.Cbu -> Lookup.Found(typed)
            else -> Lookup.Idle
        }
    }
    if (saved.isEmpty() && lookup == Lookup.Idle) return
    Column {
        Txt(stringResource(R.string.swap_bankAccounts), Modifier.padding(start = 6.dp, bottom = 8.dp), size = 13.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).animateContentSize().testTag("bank-suggestions")) {
            saved.forEach { r -> BankRow(r.account, r.holderName, enabled = enabled, onLongClick = { menuFor = r }) { onPick(r.account, r.holderName) } }
            when (val l = lookup) {
                is Lookup.Found -> if (saved.none { it.account.cvu == l.account.cvu }) BankRow(l.account, l.account.owners.firstOrNull()?.name, expanded = true, enabled = enabled) { onPick(l.account, null) }
                Lookup.Loading -> StatusRow(stringResource(R.string.cashOut_lookingUp), loading = true)
                Lookup.Busy -> StatusRow(stringResource(R.string.swap_lookupBusy))
                is Lookup.Unverified -> BankRow(l.account, null, expanded = true, enabled = enabled, note = stringResource(R.string.swap_unverified)) { onPick(l.account, null) }
                Lookup.Idle -> {}
            }
        }
    }
}

@Composable
private fun StatusRow(text: String, loading: Boolean = false) = Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
    Box(Modifier.size(40.dp).glass(CircleShape, GlassLevel.Strong), contentAlignment = Alignment.Center) {
        if (loading) Spinner(18.dp, Lw.MutedFg) else LwIcon(L.drawable.lucide_ic_landmark, size = 18.dp, tint = Lw.MutedFg)
    }
    Spacer(Modifier.width(12.dp))
    MutedText(text)
}

/** One bank account: logo, holder, bank and ID, then the alias or CBU. */
@Composable
private fun BankRow(account: BankAccount, name: String?, expanded: Boolean = false, enabled: Boolean = true, note: String? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    val owner = account.owners.firstOrNull()
    val haptics = LocalHapticFeedback.current
    Row(Modifier.fillMaxWidth().combinedClickable(enabled = enabled, onLongClick = onLongClick?.let { { haptics.performHapticFeedback(HapticFeedbackType.LongPress); it() } }, onClick = onClick).padding(horizontal = 16.dp, vertical = if (expanded) 16.dp else 12.dp).testTag("bank-row"), verticalAlignment = Alignment.CenterVertically) {
        BankAvatar(account.icon, if (expanded) 52.dp else 40.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(name?.ifBlank { null } ?: stringResource(R.string.swap_bankAccount), size = if (expanded) 16.sp else 14.sp, weight = FontWeight.SemiBold, maxLines = 1)
            val meta = listOfNotNull(account.bank, owner?.let(BankDestination::ownerId)).joinToString(" · ")
            if (meta.isNotEmpty()) Txt(meta, size = 12.sp, color = Lw.MutedFg, maxLines = 1)
            if (note != null) Txt(note, size = 12.sp, color = Lw.Orange, maxLines = 1)
            Row(verticalAlignment = Alignment.CenterVertically) {
                LwIcon(L.drawable.lucide_ic_landmark, size = 12.dp, tint = Lw.Gold)
                Spacer(Modifier.width(4.dp))
                Txt(account.alias ?: BankDestination.formatCbu(account.cvu), size = 13.sp, color = Lw.MutedFg, maxLines = 1)
            }
        }
        LwIcon(L.drawable.lucide_ic_chevron_right, size = 18.dp, tint = Lw.MutedFg)
    }
}

/** A saved bank account's menu (long press on Send): take it off the list. */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SavedRecipientSheet(r: io.lawallet.wallet.app.BankRecipient, onDismiss: () -> Unit, onRemove: () -> Unit) {
    val state = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    LwBottomSheet(onDismiss, "bank-recipient-menu", state) {
        BankRow(r.account, r.holderName, expanded = true, enabled = false) {}
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        SheetAction(L.drawable.lucide_ic_trash_2, stringResource(R.string.swap_removeSaved), Lw.Red, "bank-recipient-remove", Lw.Red) {
            onRemove()
            scope.launch { state.hide() }.invokeOnCompletion { onDismiss() }
        }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

/** Compact "who gets the pesos" card at the top of each step. */
@Composable
private fun RecipientStrip(account: BankAccount, name: String) = Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
    BankAvatar(account.icon)
    Spacer(Modifier.width(12.dp))
    Column(Modifier.weight(1f)) {
        Txt(stringResource(R.string.swap_bankAccount), size = 12.sp, color = Lw.MutedFg)
        Txt(name.ifBlank { account.alias ?: BankDestination.formatCbu(account.cvu) }, size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
        if (name.isNotBlank()) Txt(listOfNotNull(account.bank, account.alias ?: BankDestination.formatCbu(account.cvu)).joinToString(" · "), size = 13.sp, color = Lw.MutedFg, maxLines = 1)
    }
}

// ---------- amount ----------

@Composable
fun CashOutAmountScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val f = c.flow.cashOut
    val account = f.account
    if (account == null) { LaunchedEffect(Unit) { nav.goHome() }; return }
    val rates by c.rates.rates.collectAsState()
    val balance by c.wallet.balance.collectAsState()
    var raw by rememberSaveable { mutableStateOf(f.amountArs?.toLong()?.toString() ?: "0") }
    var picking by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf<String?>(null) }
    var createError by remember { mutableStateOf<String?>(null) }
    val createFailed = stringResource(R.string.cashOut_createFailed)
    val scope = rememberCoroutineScope()
    val amount = raw.toLongOrNull()?.takeIf { it > 0 }
    // Yadio's rate: close enough to warn before asking the exchanges, which quote their own.
    val approx = amount?.let { a -> rates?.get("ARS")?.takeIf { it > 0 }?.let { ceil(a / it * 100_000_000).toLong() } }
    val over = approx != null && balance != null && approx > balance!!
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(stringResource(R.string.swap_amountTitle), { nav.popBackStack() })
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            RecipientStrip(account, f.holderName)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AmountDisplay(keypadDisplay(raw, "SAT"), "ARS", figureTag = "cashout-amount")
                MutedText(if (approx != null) stringResource(R.string.swap_approxSats, Currencies.formatSats(approx)) else " ")
                if (balance != null) MutedText("${stringResource(R.string.claim_available)}: ${stringResource(R.string.common_sats, Currencies.formatSats(balance!!))}", size = 13.sp)
                if (over) ErrorText(stringResource(R.string.send_preview_notEnoughBalance))
            }
            AmountKeypad(raw, { raw = it }, Currencies.keypadMode("SAT"), Modifier.weight(1f).fillMaxWidth())
        }
        LwButton(stringResource(R.string.cashOut_continue), { picking = true }, Modifier.fillMaxWidth().padding(16.dp).testTag("cashout-amount-continue"), BtnVariant.Theme, enabled = amount != null && !over)
    }
    // Picking an exchange locks its rate: the order is created here and the review shows who gets the pesos.
    if (picking && amount != null) ProviderSheet(amount.toDouble(), account, creating, createError, { if (creating == null) { picking = false; createError = null } }) { provider ->
        creating = provider; createError = null
        scope.launch {
            runCatching { c.swaps.create(provider, amount.toDouble(), account, f.holderName.ifBlank { null }) }
                .onSuccess {
                    c.flow.cashOut = f.copy(amountArs = amount.toDouble(), provider = provider, order = it)
                    picking = false
                    nav.navigate(R0.CASHOUT_REVIEW)
                }
                .onFailure { createError = it.message ?: createFailed }
            creating = null
        }
    }
}

/** Why [q] can't take this order, or null when it can. */
@Composable
private fun unusable(q: SwapQuote, amountArs: Double, account: BankAccount): String? = when {
    q.error != null || q.sats == null -> stringResource(R.string.cashOut_unavailable)
    q.minArs != null && amountArs < q.minArs -> stringResource(R.string.cashOut_minimum, ars(q.minArs))
    q.provider == "bullbitcoin" && account.cvu.length != 22 -> stringResource(R.string.cashOut_needsCbu)
    else -> null
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
/** Each exchange's live quote for [amountArs] (refreshed every 30 s while open): sats, rate, how long it takes. */
@Composable
private fun ProviderSheet(amountArs: Double, account: BankAccount, creating: String?, error: String?, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    val c = LocalContainer.current
    val quotes by produceState<List<SwapQuote>?>(null, amountArs) {
        while (true) {
            value = runCatching { c.swaps.quote(amountArs) }.getOrElse { if (value == null) emptyList() else value }
            delay(30_000)
        }
    }
    LwBottomSheet(onDismiss, tag = "cashout-providers") {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt(stringResource(R.string.swap_chooseProvider), size = 20.sp, weight = FontWeight.SemiBold)
            MutedText(stringResource(R.string.swap_chooseProviderBody), size = 13.sp)
            Row(verticalAlignment = Alignment.Bottom) {
                Txt(ars(amountArs), size = 28.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.width(6.dp))
                Txt("ARS", Modifier.padding(bottom = 4.dp), size = 14.sp, color = Lw.MutedFg)
            }
            val list = quotes
            // Sized for two exchanges from the start: the sheet opens at its full height instead of growing under the fold.
            Column(Modifier.fillMaxWidth().heightIn(min = 236.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { when {
                list == null -> Row(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                    Spinner(18.dp, Lw.MutedFg); Spacer(Modifier.width(10.dp)); MutedText(stringResource(R.string.cashOut_quoting))
                }
                list.isEmpty() -> MutedText(stringResource(R.string.cashOut_noProviders), Modifier.padding(vertical = 24.dp))
                else -> {
                    val reasons = list.map { unusable(it, amountArs, account) }
                    val best = list.filterIndexed { i, _ -> reasons[i] == null }.minByOrNull { it.sats!! }?.provider
                    list.forEachIndexed { i, q -> ProviderRow(q, reasons[i], q.provider == best && list.size > 1, i, creating) { onPick(q.provider) } }
                }
            } }
            ErrorText(error, Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun ProviderRow(q: SwapQuote, reason: String?, best: Boolean, index: Int, creating: String?, onClick: () -> Unit) {
    // Rows land one after another, rising into place.
    val enter = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(index * 70L); enter.animateTo(1f, tween(280, easing = FastOutSlowInEasing)) }
    val usable = reason == null
    Row(
        Modifier.fillMaxWidth().graphicsLayer { alpha = enter.value * (if (usable) 1f else 0.5f); translationY = (1f - enter.value) * 24.dp.toPx() }
            .glass(RoundedCornerShape(20.dp), tint = if (best) Lw.Green.copy(alpha = 0.06f) else null)
            .then(if (best) Modifier.border(1.dp, Lw.Green.copy(alpha = 0.35f), RoundedCornerShape(20.dp)) else Modifier)
            .clickable(enabled = usable && creating == null, onClick = onClick).padding(16.dp).testTag("provider-${q.provider}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ProviderLogo(q.provider, 44.dp)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(providerName(q.provider), size = 16.sp, weight = FontWeight.SemiBold)
                if (best) { Spacer(Modifier.width(8.dp)); Pill(stringResource(R.string.swap_bestRate), Lw.Green) }
            }
            q.arsPerBtc?.let { Txt(stringResource(R.string.swap_rate, arsPerSat(it)), size = 12.sp, color = Lw.MutedFg) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                LwIcon(L.drawable.lucide_ic_clock, size = 12.dp, tint = Lw.MutedFg)
                Spacer(Modifier.width(4.dp))
                Txt(stringResource(if (q.provider == "bullbitcoin") R.string.swap_eta_bullbitcoin else R.string.swap_eta_wapu), size = 12.sp, color = Lw.MutedFg, maxLines = 1)
            }
            if (reason != null) Txt(reason, size = 12.sp, color = Lw.Orange)
        }
        if (creating == q.provider) Spinner(22.dp)
        else if (q.sats != null) Column(horizontalAlignment = Alignment.End) {
            Txt(Currencies.formatSats(q.sats), size = 17.sp, weight = FontWeight.SemiBold)
            Txt("sats", size = 12.sp, color = Lw.MutedFg)
        }
    }
}

/** Who gets the pesos: bank logo, holder, bank and ID, CBU and alias. */
@Composable
private fun RecipientCard(account: BankAccount, name: String?) = Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
    val owner = account.owners.firstOrNull()
    Row(verticalAlignment = Alignment.CenterVertically) {
        BankAvatar(account.icon, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(stringResource(R.string.swap_recipientTitle), size = 12.sp, color = Lw.MutedFg)
            Txt(name?.ifBlank { null } ?: owner?.name ?: stringResource(R.string.swap_bankAccount), size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
            val meta = listOfNotNull(account.bank?.ifBlank { null }, owner?.let(BankDestination::ownerId)).joinToString(" · ")
            if (meta.isNotEmpty()) Txt(meta, size = 12.sp, color = Lw.MutedFg, maxLines = 1)
        }
    }
    if (account.cvu.length == 22) KeyValueRow(stringResource(R.string.swap_cbu), BankDestination.formatCbu(account.cvu))
    account.alias?.let { KeyValueRow(stringResource(R.string.swap_alias), it) }
}

@Composable
private fun ProviderInline(provider: String) = Row(verticalAlignment = Alignment.CenterVertically) {
    ProviderLogo(provider, 22.dp)
    Spacer(Modifier.width(6.dp))
    Txt(providerName(provider), size = 14.sp)
}

// ---------- review ----------

@Composable
fun CashOutReviewScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val f = c.flow.cashOut
    val order = f.order
    val account = f.account
    if (order == null || account == null) { LaunchedEffect(Unit) { nav.goHome() }; return }
    val slideToPay by c.prefs.slideToPay.collectAsState()
    val balance by c.wallet.balance.collectAsState()
    var paying by remember { mutableStateOf(false) }
    var done by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.cashOut_paymentFailed)
    val paymentGate = rememberPaymentGate()
    val deadline = BankDestination.epochMs(order.expiresAt)
    val now by produceState(System.currentTimeMillis(), deadline) { while (deadline != null) { delay(1000); value = System.currentTimeMillis() } }
    val leftMs = deadline?.minus(now)
    val expired = leftMs != null && leftMs <= 0
    val short = balance?.let { order.amountSats > it } == true && !paying && !done
    // Backing out of an unpaid order releases it; a paid one is the status screen's.
    fun leave() {
        if (!done) c.scope.launch { c.swaps.cancel(order.id) }
        c.flow.cashOut = f.copy(order = null)
        nav.popBackStack()
    }
    BackHandler(!paying) { leave() }
    fun pay() {
        paying = true; error = null
        scope.launch {
            if (!paymentGate(order.amountSats)) { paying = false; return@launch }
            runCatching { c.swaps.pay(order) }
                .onSuccess {
                    done = true
                    delay(450) // the swipe lands on its check before the screen moves on
                    c.flow.cashOut = FlowStore.CashOut()
                    nav.navigate(R0.cashOutOrder(order.id)) { popUpTo(R0.HOME) }
                }
                .onFailure { error = it.message ?: failed; paying = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.swap_reviewTitle), { if (!paying) leave() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp)).padding(vertical = 24.dp, horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                ExchangeFlow(order.provider, account.icon, active = !done)
                Spacer(Modifier.height(18.dp))
                Txt(stringResource(R.string.cashOut_theyReceive), size = 14.sp, color = Lw.MutedFg)
                AmountDisplay(ars(order.amountArs), "ARS", figureTag = "cashout-review-amount")
                MutedText("${stringResource(R.string.cashOut_youPay)} ${stringResource(R.string.common_sats, Currencies.formatSats(order.amountSats))}")
                if (leftMs != null) {
                    Spacer(Modifier.height(10.dp))
                    if (expired) Txt(stringResource(R.string.swap_rateExpired), size = 13.sp, color = Lw.Red)
                    else Txt(stringResource(R.string.swap_lockedFor, "%d:%02d".format(leftMs / 60_000, leftMs / 1000 % 60)), size = 13.sp, color = if (leftMs < 60_000) Lw.Orange else Lw.MutedFg, modifier = Modifier.testTag("cashout-countdown"))
                }
            }
            RecipientCard(account, order.holderName ?: f.holderName)
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                KeyValueRow(stringResource(R.string.cashOut_via), providerName(order.provider)) { ProviderInline(order.provider) }
                BankDestination.arsPerBtc(order)?.let { KeyValueRow(stringResource(R.string.swap_rateLabel), stringResource(R.string.swap_rate, arsPerSat(it))) }
                KeyValueRow(stringResource(R.string.send_networkFee), stringResource(R.string.send_preview_feeNotQuoted))
            }
            ErrorText(error, Modifier.fillMaxWidth())
            if (expired && !paying && !done) LwButton(stringResource(R.string.swap_newQuote), { leave() }, Modifier.fillMaxWidth(), BtnVariant.Secondary)
        }
        val label = stringResource(if (short) R.string.send_preview_notEnoughBalance else R.string.swap_slideToSend)
        if (slideToPay) SwipeButton(label, ::pay, loading = paying, done = done, modifier = Modifier.padding(16.dp), enabled = !expired && !short)
        else Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LwButton(stringResource(R.string.common_cancel), { leave() }, Modifier.weight(1f), BtnVariant.Secondary, enabled = !paying && !done)
            LwButton(if (short) label else stringResource(R.string.cashOut_pay, Currencies.formatSats(order.amountSats)), ::pay, Modifier.weight(1f).testTag("cashout-pay"), BtnVariant.Theme, enabled = !expired && !done && !short, loading = paying)
        }
    }
}

/** Sats → exchange → bank, with chevrons drifting toward the bank while [active]. */
@Composable
private fun ExchangeFlow(provider: String, bankIcon: String?, active: Boolean) {
    val t = if (active) rememberInfiniteTransition(label = "flow").animateFloat(0f, 1f, infiniteRepeatable(tween(1300, easing = LinearEasing)), label = "drift") else null
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(44.dp).glass(CircleShape, GlassLevel.Strong, tint = Lw.Gold.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_zap, size = 20.dp, tint = Lw.Gold) }
        Chevrons(t)
        ProviderLogo(provider, 44.dp)
        Chevrons(t)
        BankAvatar(bankIcon, 44.dp)
    }
}

@Composable
private fun Chevrons(t: androidx.compose.runtime.State<Float>?) = Box(Modifier.width(44.dp).height(20.dp), contentAlignment = Alignment.Center) {
    LwIcon(L.drawable.lucide_ic_chevrons_right, Modifier.graphicsLayer {
        val v = t?.value ?: 0.5f
        translationX = (v - 0.5f) * 14.dp.toPx(); alpha = if (t == null) 0.6f else 0.35f + 0.65f * (1f - kotlin.math.abs(v - 0.5f) * 2f)
    }, size = 18.dp, tint = Lw.MutedFg)
}

// ---------- order status ----------

/** A cash-out order, live: where the pesos are, the rate it got, who gets them, its receipt. Also a swap's activity detail. */
@Composable
fun CashOutOrderScreen(id: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val swaps by c.swaps.swaps.collectAsState()
    val swap = swaps.firstOrNull { it.id == id }
    DisposableEffect(id) {
        c.swaps.watching.value = id
        onDispose { if (c.swaps.watching.value == id) c.swaps.watching.value = null }
    }
    LaunchedEffect(id) { nav.awaitSettled(); runCatching { c.swaps.refresh(id) } }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(null, { if (!nav.popBackStack()) nav.goHome() }, close = true)
        if (swap == null) { LoadingBox(Modifier.weight(1f)); return@Column }
        val pending = swap.status == "PENDING"
        val now by produceState(System.currentTimeMillis(), pending) { while (pending) { delay(1000); value = System.currentTimeMillis() } }
        val phase = BankDestination.phase(swap, now)
        val recipient = c.swaps.recipientFor(swap.destination)
        var busy by remember { mutableStateOf(false) }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                StatusHero(phase, swap.provider)
                Spacer(Modifier.height(16.dp))
                Txt(stringResource(phaseTitle(phase)), size = 22.sp, weight = FontWeight.SemiBold, align = TextAlign.Center, modifier = Modifier.testTag("cashout-status"))
                Spacer(Modifier.height(6.dp))
                MutedText(stringResource(phaseBody(phase)), align = TextAlign.Center, size = 13.sp)
                Spacer(Modifier.height(14.dp))
                AmountDisplay(ars(swap.amountArs), "ARS")
                MutedText(stringResource(R.string.common_sats, Currencies.formatSats(swap.amountSats)))
            }
            Timeline(swap, phase)
            RecipientCard(recipient?.account ?: BankAccount(swap.destination.takeIf { it.length == 22 }.orEmpty(), alias = swap.destination.takeIf { it.length != 22 }), swap.holderName ?: recipient?.holderName)
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(stringResource(R.string.swap_details), size = 15.sp, weight = FontWeight.SemiBold)
                KeyValueRow(stringResource(R.string.cashOut_via), providerName(swap.provider)) { ProviderInline(swap.provider) }
                BankDestination.arsPerBtc(swap)?.let { KeyValueRow(stringResource(R.string.swap_rateLabel), stringResource(R.string.swap_rate, arsPerSat(it))) }
                KeyValueRow(stringResource(R.string.cashOut_youPay), stringResource(R.string.common_sats, Currencies.formatSats(swap.amountSats)))
                val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
                BankDestination.epochMs(swap.createdAt)?.let { KeyValueRow(stringResource(R.string.swap_created), fmt.format(Date(it))) }
                BankDestination.epochMs(swap.paidAt)?.let { KeyValueRow(stringResource(R.string.swap_paid), fmt.format(Date(it))) }
                BankDestination.deliveredMs(swap)?.let { KeyValueRow(stringResource(R.string.swap_delivered), fmt.format(Date(it))) }
                val orderRef = swap.externalId ?: swap.id
                CopyRow(stringResource(R.string.swap_order), orderRef)
                swap.paymentHash?.let { CopyRow(stringResource(R.string.swap_paymentHash), it) }
            }
            if (phase == Phase.Completed && (swap.receiptUrl != null || swap.provider == "bullbitcoin")) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val receiptFailed = stringResource(R.string.swap_receiptFailed)
                fun receipt(share: Boolean) { busy = true; scope.launch { runCatching { openReceipt(context, c, swap, share) }.onFailure { context.toastError(it, receiptFailed) }; busy = false } }
                LwButton(stringResource(R.string.cashOut_receipt), { receipt(false) }, Modifier.weight(1f).testTag("cashout-receipt"), BtnVariant.Secondary, enabled = !busy, leading = { LwIcon(L.drawable.lucide_ic_receipt, size = 18.dp) })
                LwButton(stringResource(R.string.swap_shareReceipt), { receipt(true) }, Modifier.weight(1f), BtnVariant.Secondary, enabled = !busy, leading = { LwIcon(L.drawable.lucide_ic_share_2, size = 18.dp) })
            }
            if (phase == Phase.Awaiting) LwButton(stringResource(R.string.swap_cancel), { busy = true; scope.launch { c.swaps.cancel(swap.id); busy = false } }, Modifier.fillMaxWidth(), BtnVariant.Ghost, loading = busy)
            Spacer(Modifier.height(8.dp))
        }
        LwButton(stringResource(R.string.cashOut_done), { if (!nav.popBackStack()) nav.goHome() }, Modifier.fillMaxWidth().padding(16.dp).testTag("cashout-done"), BtnVariant.Theme)
    }
}

private fun phaseTitle(p: Phase) = when (p) {
    Phase.Awaiting -> R.string.swap_awaiting
    Phase.Transferring -> R.string.cashOut_status_PENDING
    Phase.Completed -> R.string.cashOut_status_COMPLETED
    Phase.Failed -> R.string.cashOut_status_FAILED
    Phase.Expired -> R.string.cashOut_status_EXPIRED
    Phase.Canceled -> R.string.cashOut_status_CANCELED
}

private fun phaseBody(p: Phase) = when (p) {
    Phase.Awaiting, Phase.Transferring -> R.string.cashOut_pendingBody
    Phase.Completed -> R.string.cashOut_completedBody
    Phase.Failed -> R.string.cashOut_failedBody
    Phase.Expired, Phase.Canceled -> R.string.cashOut_expiredBody
}

@Composable
private fun CopyRow(key: String, value: String) {
    val context = LocalContext.current
    val copied = stringResource(R.string.common_copied)
    Row(Modifier.fillMaxWidth().clickable { context.copyToClipboard(value, copied) }, verticalAlignment = Alignment.CenterVertically) {
        Txt(key, size = 14.sp, color = Lw.MutedFg)
        Spacer(Modifier.width(16.dp))
        Txt(if (value.length > 20) value.take(8) + "…" + value.takeLast(8) else value, Modifier.weight(1f), size = 14.sp, maxLines = 1, align = TextAlign.End)
        Spacer(Modifier.width(6.dp))
        LwIcon(L.drawable.lucide_ic_copy, size = 14.dp, tint = Lw.MutedFg)
    }
}

/**
 * The order's state as one mark: an orbiting arc around the exchange while it moves, a check that springs in (with
 * rings and a buzz) when the pesos land live, an X when it stopped.
 */
@Composable
private fun StatusHero(phase: Phase, provider: String) {
    val brand = LocalBrand.current
    val haptics = LocalHapticFeedback.current
    // Landing while on screen is celebrated; an order opened already done just shows its check.
    val landed = remember { Animatable(if (phase == Phase.Completed) 1f else 0f) }
    LaunchedEffect(phase) {
        if (phase == Phase.Completed && landed.value < 1f) {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            landed.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
        }
    }
    Box(Modifier.size(128.dp), contentAlignment = Alignment.Center) {
        when (phase) {
            Phase.Awaiting, Phase.Transferring -> {
                val spin = rememberInfiniteTransition(label = "orbit")
                val turn by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "turn")
                val glow by spin.animateFloat(0.25f, 0.6f, infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), androidx.compose.animation.core.RepeatMode.Reverse), label = "glow")
                val color = if (phase == Phase.Transferring) brand.t400 else Lw.Gold
                Box(Modifier.size(112.dp).drawBehind {
                    drawCircle(color.copy(alpha = glow * 0.25f), radius = size.minDimension / 2)
                    val stroke = 4.dp.toPx()
                    drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, style = Stroke(stroke), topLeft = Offset(stroke, stroke), size = Size(size.width - stroke * 2, size.height - stroke * 2))
                    drawArc(color, turn, 90f, false, style = Stroke(stroke, cap = androidx.compose.ui.graphics.StrokeCap.Round), topLeft = Offset(stroke, stroke), size = Size(size.width - stroke * 2, size.height - stroke * 2))
                }, contentAlignment = Alignment.Center) { ProviderLogo(provider, 64.dp) }
            }
            Phase.Completed -> {
                // Two rings ripple out as the check lands.
                Box(Modifier.size(128.dp).drawBehind {
                    val v = landed.value
                    for (i in 0..1) {
                        val p = ((v - i * 0.15f) / 0.85f).coerceIn(0f, 1f)
                        if (p in 0.001f..0.999f) drawCircle(Lw.Green.copy(alpha = (1f - p) * 0.5f), radius = size.minDimension / 2 * (0.55f + p * 0.45f), style = Stroke(3.dp.toPx()))
                    }
                })
                Box(Modifier.size(88.dp).graphicsLayer { scaleX = 0.4f + 0.6f * landed.value; scaleY = scaleX; alpha = landed.value.coerceIn(0f, 1f) }.clip(CircleShape).background(Lw.Green), contentAlignment = Alignment.Center) {
                    LwIcon(L.drawable.lucide_ic_check, size = 44.dp, tint = Lw.Background)
                }
            }
            else -> Box(Modifier.size(88.dp).glass(CircleShape, GlassLevel.Strong, tint = (if (phase == Phase.Failed) Lw.Red else Lw.MutedFg).copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                LwIcon(L.drawable.lucide_ic_x, size = 40.dp, tint = if (phase == Phase.Failed) Lw.Red else Lw.MutedFg)
            }
        }
    }
}

/** Order created → Lightning payment → the exchange's bank transfer → pesos delivered; the line fills as it goes. */
@Composable
private fun Timeline(swap: Swap, phase: Phase) {
    val brand = LocalBrand.current
    // Steps done; the one after them is live (or where it stopped).
    val done = when (phase) {
        Phase.Awaiting -> 1
        Phase.Transferring -> 2
        Phase.Completed -> 4
        Phase.Failed -> 2
        Phase.Expired, Phase.Canceled -> 1
    }
    val stopped = phase == Phase.Failed || phase == Phase.Expired || phase == Phase.Canceled
    val fill by animateFloatAsState(done.toFloat(), tween(700, easing = FastOutSlowInEasing), label = "timeline")
    val steps = listOf(
        stringResource(R.string.swap_step_created) to null,
        stringResource(R.string.swap_step_lightning) to null,
        stringResource(R.string.swap_step_bank, providerName(swap.provider)) to listOfNotNull(swap.providerStatus, stringResource(if (swap.provider == "bullbitcoin") R.string.swap_eta_bullbitcoin else R.string.swap_eta_wapu).takeIf { phase == Phase.Transferring }).joinToString(" · ").ifEmpty { null },
        stringResource(R.string.swap_step_delivered) to null,
    )
    val pulse = rememberInfiniteTransition(label = "step").animateFloat(0.4f, 1f, infiniteRepeatable(tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse")
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(horizontal = 16.dp, vertical = 14.dp)) {
        steps.forEachIndexed { i, (title, sub) ->
            val isDone = i < done
            val isLive = i == done && !stopped
            val isStop = i == done && stopped
            Row(Modifier.fillMaxWidth().height(if (sub != null) 64.dp else 52.dp)) {
                // Dot plus the connector to the next step, filled up to [fill].
                Box(Modifier.width(28.dp).fillMaxSize().drawBehind {
                    if (i < steps.lastIndex) {
                        val x = size.width / 2
                        val top = 26.dp.toPx(); val w = 2.dp.toPx()
                        drawRoundRect(Color.White.copy(alpha = 0.1f), Offset(x - w / 2, top), Size(w, size.height - top + 4.dp.toPx()), CornerRadius(w))
                        val p = (fill - i).coerceIn(0f, 1f)
                        if (p > 0f) drawRoundRect(Lw.Green, Offset(x - w / 2, top), Size(w, (size.height - top + 4.dp.toPx()) * p), CornerRadius(w))
                    }
                }, contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.padding(top = 4.dp).size(20.dp).graphicsLayer { if (isLive) { alpha = pulse.value } }.clip(CircleShape).background(
                        when { isDone -> Lw.Green; isLive -> brand.t400; isStop -> if (phase == Phase.Failed) Lw.Red else Lw.MutedFg; else -> Color.White.copy(alpha = 0.08f) },
                    ), contentAlignment = Alignment.Center) {
                        if (isDone) LwIcon(L.drawable.lucide_ic_check, size = 12.dp, tint = Lw.Background)
                        else if (isStop) LwIcon(L.drawable.lucide_ic_x, size = 12.dp, tint = Lw.Background)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.padding(top = 4.dp)) {
                    Txt(title, size = 14.sp, weight = if (isLive) FontWeight.SemiBold else FontWeight.Medium, color = if (isDone || isLive) Lw.Foreground else Lw.MutedFg)
                    if (sub != null && (isLive || isDone || isStop)) Txt(sub, size = 12.sp, color = Lw.MutedFg, maxLines = 2)
                }
            }
        }
    }
}

/**
 * Wapu's receipt is the bank's transfer image (a URL: opened in place, downloaded to share); Bull Bitcoin's is a PDF
 * LaWallet renders. Shared files go through the FileProvider.
 */
private suspend fun openReceipt(context: Context, c: io.lawallet.wallet.app.AppContainer, swap: Swap, share: Boolean) {
    val url = swap.receiptUrl
    if (url != null && !share) { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))); return }
    val (bytes, type) = if (url != null) withContext(Dispatchers.IO) {
        c.http.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { res ->
            if (!res.isSuccessful) throw IOException("HTTP ${res.code}")
            res.body.bytes() to (res.header("content-type")?.substringBefore(';')?.takeIf { it.startsWith("image/") } ?: "image/jpeg")
        }
    } else c.swaps.receipt(swap.id, share) to "application/pdf"
    val ext = when (type) { "application/pdf" -> "pdf"; "image/png" -> "png"; "image/webp" -> "webp"; else -> "jpg" }
    val uri = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "shared/comprobante.$ext").apply { parentFile?.mkdirs(); writeBytes(bytes) }
        FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    }
    val intent = if (share) Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, uri).apply { clipData = ClipData.newRawUri(null, uri) }
    else Intent(Intent.ACTION_VIEW).setDataAndType(uri, type)
    context.startActivity(Intent.createChooser(intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
}

// ---------- home ----------

/** A cash-out still moving, pinned above Home's activity until the pesos land. */
@Composable
internal fun PendingSwapCard(swap: Swap, onClick: () -> Unit) {
    val c = LocalContainer.current
    val brand = LocalBrand.current
    val icon = c.swaps.recipientFor(swap.destination)?.account?.icon
    val sweep = rememberInfiniteTransition(label = "pending").animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1600, easing = LinearEasing)), label = "sweep")
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).glass(RoundedCornerShape(20.dp), GlassLevel.Regular, tint = brand.t400.copy(alpha = 0.06f))
            .clickable(onClick = onClick).testTag("pending-swap"),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            BankAvatar(icon, badge = true)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Txt(swap.holderName ?: stringResource(R.string.swap_bankAccount), size = 14.sp, weight = FontWeight.Medium, maxLines = 1)
                Txt(stringResource(R.string.cashOut_status_PENDING) + " · " + providerName(swap.provider), size = 12.sp, color = Lw.MutedFg, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Txt("ARS ${ars(swap.amountArs)}", size = 14.sp, weight = FontWeight.SemiBold)
                Txt("−${Currencies.formatSats(swap.amountSats)} sats", size = 12.sp, color = Lw.MutedFg)
            }
        }
        // A light sweeping along the bottom edge: still on its way.
        Box(Modifier.fillMaxWidth().height(2.dp).drawBehind {
            val x = sweep.value * size.width
            drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, brand.t400, Color.Transparent), startX = x - size.width * 0.25f, endX = x + size.width * 0.25f))
        })
    }
}
