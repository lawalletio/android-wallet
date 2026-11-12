package io.lawallet.wallet.ui.screens

import com.composables.icons.lucide.R as L
import kotlinx.coroutines.launch
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.core.ln.splitPaymentDescription
import io.lawallet.wallet.core.ln.DestinationParser
import coil3.compose.AsyncImage
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.layout.ContentScale
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Stable
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.app.Tx
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.PaymentReceiptCard
import io.lawallet.wallet.ui.components.ReceiptFooter
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.TransactionRow
import io.lawallet.wallet.ui.components.TransactionGroup
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.shareText
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.glass
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun ActivityScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val recent by c.wallet.recent.collectAsState()
    var tab by remember { mutableStateOf("all") }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { c.wallet.notices.collect { c.wallet.refreshActivity() } }
    val paging = rememberActivityPaging(list, firstPage = true)
    val items = if (tab == "transfers") recent.filter { !it.incoming } else recent
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.activity_title), { nav.popBackStack() })
        Row(Modifier.padding(horizontal = 16.dp).glass(CircleShape, GlassLevel.Thin).padding(4.dp)) {
            listOf("all" to R.string.activity_all, "transfers" to R.string.activity_transfers).forEach { (k, l) ->
                Box(Modifier.clip(CircleShape).then(if (tab == k) Modifier.background(Lw.Background) else Modifier).clickable { tab = k }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Txt(stringResource(l), size = 12.sp, weight = FontWeight.SemiBold, color = if (tab == k) Lw.Foreground else Lw.MutedFg)
                }
            }
        }
        val labels = rememberDayLabels()
        LazyColumn(Modifier.weight(1f), state = list) {
            activityList(items, labels, paging) { tx -> c.flow.activityDetail = tx; nav.navigate(R0.activity(tx.paymentHash)) }
        }
    }
}

/** Older pages load as the list nears its end (25 per page); [enabled] gates it (e.g. Home's docked sheet). */
@Stable
internal class ActivityPaging { var loading by mutableStateOf(false); var hasMore by mutableStateOf(true); var error by mutableStateOf(false) }

@Composable
internal fun rememberActivityPaging(list: LazyListState, firstPage: Boolean, enabled: () -> Boolean = { true }): ActivityPaging {
    val c = LocalContainer.current
    val recent by c.wallet.recent.collectAsState()
    val p = remember { ActivityPaging() }
    if (firstPage) LaunchedEffect(Unit) { p.loading = true; runCatching { c.wallet.page(null) }.onSuccess { p.hasMore = it.size >= 25 }.onFailure { p.error = true }; p.loading = false }
    val atEnd by remember { derivedStateOf { enabled() && (list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 3 } ?: false) } }
    LaunchedEffect(atEnd) {
        if (atEnd && p.hasMore && !p.loading && recent.isNotEmpty()) {
            p.loading = true
            runCatching { c.wallet.page(recent.minOf { it.createdAt } / 1000 - 1) }.onSuccess { p.hasMore = it.size >= 25 }
            p.loading = false
        }
    }
    return p
}

@Composable
internal fun rememberDayLabels() = DayLabels(stringResource(R.string.activity_today), stringResource(R.string.activity_yesterday))

/** Day-grouped transaction rows plus the paging footer; shared by the Activity screen and Home's sheet. */
internal fun LazyListScope.activityList(items: List<Tx>, labels: DayLabels, paging: ActivityPaging, onOpen: (Tx) -> Unit) {
    items.groupBy { dayLabel(it.createdAt) }.forEach { (day, txs) ->
        item(day.first) { Txt(labels.of(day), Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp), size = 12.sp, weight = FontWeight.Medium, color = Lw.MutedFg) }
        groupRuns(txs).forEach { run ->
            val first = run.first()
            if (run.size == 1) item(first.paymentHash + first.createdAt) { TransactionRow(first) { onOpen(first) } }
            else item("group-" + first.paymentHash + first.createdAt) { TransactionGroup(run, onOpen) }
        }
    }
    item("footer") {
        Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
            when {
                paging.loading -> Spinner()
                items.isEmpty() && paging.error -> MutedText(stringResource(R.string.activity_loadFailed), align = TextAlign.Center)
                items.isEmpty() -> MutedText(stringResource(R.string.activity_empty), align = TextAlign.Center)
                !paging.hasMore -> MutedText(stringResource(R.string.activity_end), align = TextAlign.Center, size = 12.sp)
            }
        }
    }
}

/**
 * Consecutive settled payments with the same direction and description (e.g. a stream of
 * "LNCurl hosting fee") collapse into one run; everything else stays a run of one.
 */
internal fun groupRuns(txs: List<Tx>): List<List<Tx>> {
    fun key(tx: Tx) = tx.description?.takeIf { it.isNotBlank() && tx.state != "pending" && tx.state != "failed" }?.let { tx.incoming to it }
    val out = mutableListOf<MutableList<Tx>>()
    for (tx in txs) {
        val prev = out.lastOrNull()?.last()
        if (prev != null && key(tx) != null && key(prev) == key(tx)) out.last() += tx else out += mutableListOf(tx)
    }
    return out
}

internal data class DayLabels(val today: String, val yesterday: String) {
    fun of(day: Pair<Long, Int>): String = when (day.second) {
        0 -> today
        1 -> yesterday
        in 2..6 -> java.text.SimpleDateFormat("EEEE", java.util.Locale.getDefault()).format(Date(day.first)).replaceFirstChar { it.uppercase() }
        else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(day.first))
    }
}

/** (startOfDayMs, daysAgo) */
private fun dayLabel(ms: Long): Pair<Long, Int> {
    val cal = Calendar.getInstance().apply { timeInMillis = ms; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    val today = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
    return cal.timeInMillis to ((today.timeInMillis - cal.timeInMillis) / 86_400_000L).toInt()
}

@Composable
fun ActivityDetailScreen(hash: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val currencies by c.prefs.currencies.collectAsState()
    val rates by c.rates.rates.collectAsState()
    var tx by remember { mutableStateOf(c.flow.activityDetail?.takeIf { it.paymentHash == hash } ?: c.wallet.findTx(hash)) }
    var code by remember { mutableStateOf(currencies.selected) }
    val destinations by c.prefs.paymentDestinations.collectAsState()
    val scope = rememberCoroutineScope()
    var opening by remember { mutableStateOf(false) }
    // Tapping the recipient starts a fresh send to the same address.
    fun payAgain(address: String) {
        opening = true
        scope.launch {
            val next = runCatching { startSend(c, DestinationParser.parse(address)) }.getOrDefault(R0.SEND)
            opening = false
            nav.navigate(next)
        }
    }
    LaunchedEffect(hash) {
        runCatching { c.wallet.client.value?.lookupInvoice(hash) }.getOrNull()?.let { tx = Tx.from(it).let { l -> tx?.let { o -> l.copy(description = l.description ?: o.description) } ?: l } }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(null, { nav.popBackStack() }, close = true)
        val t = tx
        if (t == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Spinner() }
        else {
            val title = stringResource(when { t.state == "pending" -> R.string.activity_detail_pending; t.state == "failed" -> R.string.activity_detail_failed; t.incoming -> R.string.activity_detail_received; else -> R.string.activity_detail_sent })
            val dest = if (t.incoming) null else destinations[t.paymentHash]
            val (label, describedComment) = splitPaymentDescription(t.description)
            // Sends from before destinations were saved only carry the address in the description.
            val recipient = dest?.address ?: label.takeIf { !t.incoming && DestinationParser.isLightningAddress(it) }
            val toLabel = stringResource(R.string.activity_detail_to)
            val rows = buildList {
                if (!t.incoming) (recipient ?: label.ifEmpty { null })?.let { add(toLabel to it) }
                if (t.feesPaidSats > 0 || !t.incoming) add(stringResource(R.string.activity_detail_networkFee) to stringResource(R.string.common_sats, Currencies.formatSats(t.feesPaidSats)))
                (if (t.incoming) describedComment ?: label.ifEmpty { null } else dest?.comment ?: describedComment)?.let { add(stringResource(R.string.activity_detail_note) to it) }
                add(stringResource(R.string.activity_detail_time) to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t.settledAt ?: t.createdAt)))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                PaymentReceiptCard(title, t.state, t.amountSats, code, currencies.active, rates, { code = it }, rows,
                    listOf(stringResource(R.string.shared_receipt_text_paymentHash) to t.paymentHash.ifEmpty { null }, stringResource(R.string.shared_receipt_text_preimage) to t.preimage),
                    if (recipient == null) emptyMap() else mapOf(toLabel to { RecipientChip(recipient, c.avatars.of(recipient), opening) { payAgain(recipient) } }))
            }
            val shareTitle = stringResource(R.string.shared_receipt_text_title)
            ReceiptFooter(
                onShare = { context.shareText("$shareTitle\n$title: ${Currencies.formatSats(t.amountSats)} sats\n${rows.joinToString("\n") { "${it.first}: ${it.second}" }}\nHash: ${t.paymentHash}") },
                onActivity = { nav.navigate(R0.ACTIVITY) { popUpTo(R0.HOME) } },
                onDone = { nav.goHome() },
            )
        }
    }
}

@Composable
private fun RecipientChip(address: String, avatar: String, loading: Boolean, onClick: () -> Unit) {
    val desc = stringResource(R.string.activity_detail_sendAgain, address)
    Row(
        Modifier.clip(CircleShape).border(1.dp, Lw.Border, CircleShape).clickable(enabled = !loading, onClick = onClick)
            .semantics { contentDescription = desc }.padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp).testTag("recipient-chip"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        AsyncImage(avatar, null, Modifier.size(24.dp).clip(CircleShape), contentScale = ContentScale.Crop)
        Txt(address, Modifier.weight(1f, fill = false), size = 14.sp, maxLines = 1)
        if (loading) Spinner(size = 14.dp) else LwIcon(L.drawable.lucide_ic_send, size = 14.dp, tint = Lw.MutedFg)
    }
}
