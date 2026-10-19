package io.lawallet.wallet.ui.screens

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
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.shareText
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.theme.Lw
import java.text.DateFormat
import java.util.Calendar
import java.util.Date

@Composable
fun ActivityScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val recent by c.wallet.recent.collectAsState()
    var tab by remember { mutableStateOf("all") }
    var loading by remember { mutableStateOf(false) }
    var hasMore by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf(false) }
    val list = rememberLazyListState()
    LaunchedEffect(Unit) { loading = true; runCatching { c.wallet.page(null) }.onSuccess { hasMore = it.size >= 25 }.onFailure { error = true }; loading = false }
    LaunchedEffect(Unit) { c.wallet.notices.collect { c.wallet.refreshActivity() } }
    val atEnd by remember { derivedStateOf { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index?.let { it >= list.layoutInfo.totalItemsCount - 3 } ?: false } }
    LaunchedEffect(atEnd) {
        if (atEnd && hasMore && !loading && recent.isNotEmpty()) {
            loading = true
            runCatching { c.wallet.page(recent.minOf { it.createdAt } / 1000 - 1) }.onSuccess { hasMore = it.size >= 25 }
            loading = false
        }
    }
    val items = if (tab == "transfers") recent.filter { !it.incoming } else recent
    val groups = items.groupBy { dayLabel(it.createdAt) }
    val labels = DayLabels(stringResource(R.string.activity_today), stringResource(R.string.activity_yesterday))
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.activity_title), { nav.popBackStack() })
        Row(Modifier.padding(horizontal = 16.dp).clip(CircleShape).background(Lw.Card).padding(4.dp)) {
            listOf("all" to R.string.activity_all, "transfers" to R.string.activity_transfers).forEach { (k, l) ->
                Box(Modifier.clip(CircleShape).then(if (tab == k) Modifier.background(Lw.Background) else Modifier).clickable { tab = k }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Txt(stringResource(l), size = 12.sp, weight = FontWeight.SemiBold, color = if (tab == k) Lw.Foreground else Lw.MutedFg)
                }
            }
        }
        LazyColumn(Modifier.weight(1f), state = list) {
            groups.forEach { (day, txs) ->
                item(day.first) { Txt(labels.of(day), Modifier.padding(start = 16.dp, top = 20.dp, bottom = 8.dp), size = 12.sp, weight = FontWeight.Medium, color = Lw.MutedFg) }
                items(txs, key = { it.paymentHash + it.createdAt }) { tx -> TransactionRow(tx) { c.flow.activityDetail = tx; nav.navigate(R0.activity(tx.paymentHash)) } }
            }
            item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    when {
                        loading -> Spinner()
                        items.isEmpty() && error -> MutedText(stringResource(R.string.activity_loadFailed), align = TextAlign.Center)
                        items.isEmpty() -> MutedText(stringResource(R.string.activity_empty), align = TextAlign.Center)
                        !hasMore -> MutedText(stringResource(R.string.activity_end), align = TextAlign.Center, size = 12.sp)
                    }
                }
            }
        }
    }
}

private data class DayLabels(val today: String, val yesterday: String) {
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
    LaunchedEffect(hash) {
        runCatching { c.wallet.client.value?.lookupInvoice(hash) }.getOrNull()?.let { tx = Tx.from(it).let { l -> tx?.let { o -> l.copy(description = l.description ?: o.description) } ?: l } }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(null, { nav.popBackStack() }, close = true)
        val t = tx
        if (t == null) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) { Spinner() }
        else {
            val title = stringResource(when { t.state == "pending" -> R.string.activity_detail_pending; t.state == "failed" -> R.string.activity_detail_failed; t.incoming -> R.string.activity_detail_received; else -> R.string.activity_detail_sent })
            val rows = buildList {
                if (t.feesPaidSats > 0 || !t.incoming) add(stringResource(R.string.activity_detail_networkFee) to stringResource(R.string.common_sats, Currencies.formatSats(t.feesPaidSats)))
                t.description?.let { add(stringResource(R.string.activity_detail_note) to it) }
                add(stringResource(R.string.activity_detail_time) to DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(t.settledAt ?: t.createdAt)))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp)) {
                PaymentReceiptCard(title, t.state, t.amountSats, code, currencies.active, rates, { code = it }, rows,
                    listOf(stringResource(R.string.shared_receipt_text_paymentHash) to t.paymentHash.ifEmpty { null }, stringResource(R.string.shared_receipt_text_preimage) to t.preimage))
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
