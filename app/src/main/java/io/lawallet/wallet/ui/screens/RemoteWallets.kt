package io.lawallet.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.app.Tx
import io.lawallet.wallet.core.api.ForwardDestination
import io.lawallet.wallet.core.api.ReceiveAction
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.api.WalletNotification
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.nwc.NwcClient
import io.lawallet.wallet.core.nwc.NwcUri
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.GroupDivider
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.SettingsGroup
import io.lawallet.wallet.ui.components.SettingsHeader
import io.lawallet.wallet.ui.components.SettingsRow
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.TransactionRow
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.copyToClipboard
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

@Composable
fun RemoteWalletsScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var wallets by remember { mutableStateOf<List<RemoteWallet>?>(null) }
    var failed by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf(false) }
    val nowPrimary = stringResource(R.string.remoteWallets_list_nowPrimary)
    val enabledFmt = stringResource(R.string.remoteWallets_list_enabledToast)
    val disabledFmt = stringResource(R.string.remoteWallets_list_disabledToast)
    val updateFailed = stringResource(R.string.remoteWallets_list_updateFailed)
    suspend fun load() { runCatching { c.session.api.value.remoteWallets() }.onSuccess { wallets = it; failed = false }.onFailure { failed = true } }
    suspend fun changed() { load(); runCatching { c.session.refreshMe() } }
    LaunchedEffect(Unit) { load() }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(stringResource(R.string.settings_remoteWallets), { nav.popBackStack() }, stringResource(R.string.common_back))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val list = wallets
            when {
                list == null && failed -> Column { Txt(stringResource(R.string.remoteWallets_list_errorTitle), weight = FontWeight.SemiBold); MutedText(stringResource(R.string.remoteWallets_list_tryAgain)) }
                list == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Spinner() }
                list.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    LwIcon(L.drawable.lucide_ic_wallet, size = 40.dp, tint = Lw.MutedFg)
                    Spacer(Modifier.height(12.dp))
                    Txt(stringResource(R.string.remoteWallets_list_emptyTitle), weight = FontWeight.SemiBold)
                    MutedText(stringResource(R.string.remoteWallets_list_emptyBody))
                }
                else -> SettingsGroup {
                    list.forEachIndexed { i, w ->
                        if (i > 0) GroupDivider()
                        Row(Modifier.fillMaxWidth().clickable { nav.navigate(R0.remoteWallet(w.id)) }.padding(16.dp).testTag("wallet-${w.name}"), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Txt(w.name, size = 16.sp, weight = FontWeight.Medium, maxLines = 1)
                                    if (w.isDefault) { Spacer(Modifier.width(8.dp)); Pill(stringResource(R.string.remoteWallets_list_primary), LocalBrand.current.t400) }
                                }
                                Spacer(Modifier.height(6.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    when (w.status) {
                                        "DEAD" -> Pill(stringResource(R.string.rw_dead), Lw.Red)
                                        "DISABLED" -> Pill(stringResource(R.string.remoteWallets_paused), Lw.MutedFg)
                                        else -> Pill(stringResource(R.string.remoteWallets_live), Lw.Green)
                                    }
                                    Pill(if (w.provider == "lncurl") "LNCurl" else w.type, Lw.NwcPurple)
                                }
                            }
                            if (!w.isDefault && w.status == "ACTIVE") TextButton({
                                scope.launch { runCatching { c.session.api.value.updateRemoteWallet(w.id, isDefault = true) }.onSuccess { context.toast(nowPrimary.format(w.name)); changed() }.onFailure { context.toast(it.message ?: updateFailed) } }
                            }) { Txt(stringResource(R.string.remoteWallets_list_setPrimary), size = 13.sp) }
                            if (w.status == "ACTIVE" || w.status == "DISABLED") Switch(w.status == "ACTIVE", { on ->
                                scope.launch {
                                    runCatching { c.session.api.value.updateRemoteWallet(w.id, status = if (on) "ACTIVE" else "DISABLED") }
                                        .onSuccess { context.toast((if (on) enabledFmt else disabledFmt).format(w.name)); changed() }
                                        .onFailure { context.toast(it.message ?: updateFailed) }
                                }
                            }, colors = lwSwitchColors())
                        }
                    }
                }
            }
        }
        LwButton(stringResource(R.string.rw_add), { adding = true }, Modifier.fillMaxWidth().padding(16.dp).testTag("add-wallet"), BtnVariant.Theme, icon = L.drawable.lucide_ic_plus)
    }
    if (adding) AddWalletDialog(onDismiss = { adding = false }, onCreated = { adding = false; scope.launch { changed() } })
}

@Composable
private fun Pill(text: String, color: androidx.compose.ui.graphics.Color) =
    Txt(text, Modifier.glass(CircleShape, GlassLevel.Thin, tint = color.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp), size = 11.sp, weight = FontWeight.SemiBold, color = color)

@Composable
private fun AddWalletDialog(onDismiss: () -> Unit, onCreated: () -> Unit) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val settings by c.session.settings.collectAsState()
    var name by remember { mutableStateOf("") }
    var conn by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf("SEND_RECEIVE") }
    var primary by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Glass.Dialog,
        title = { Txt(stringResource(R.string.rw_add_title), size = 16.sp, weight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(64) }, Modifier.fillMaxWidth().testTag("wallet-name"), label = { Txt(stringResource(R.string.rw_name)) }, singleLine = true, colors = lwFieldColors())
                OutlinedTextField(conn, { conn = it.trim(); error = null }, Modifier.fillMaxWidth().testTag("wallet-nwc"), placeholder = { Txt(stringResource(R.string.rw_connection_hint), color = Lw.MutedFg) }, colors = lwFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), maxLines = 3)
                Txt(stringResource(R.string.rw_mode), size = 12.sp, color = Lw.MutedFg)
                listOf("SEND_RECEIVE" to R.string.rw_mode_send_receive, "RECEIVE" to R.string.rw_mode_receive).forEach { (k, l) ->
                    Row(Modifier.fillMaxWidth().clickable { mode = k }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(mode == k, { mode = k }, colors = RadioButtonDefaults.colors(selectedColor = LocalBrand.current.t400)); Txt(stringResource(l))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) { Txt(stringResource(R.string.rw_make_primary), Modifier.weight(1f)); Switch(primary, { primary = it }, colors = lwSwitchColors()) }
                ErrorText(error)
                if (settings?.lncurlEnabled == true) LwButton(stringResource(R.string.rw_create_lncurl), {
                    busy = true
                    scope.launch {
                        runCatching { c.session.api.value.createLncurlWallet(name.ifBlank { null }, primary) }.onSuccess { onCreated() }.onFailure { error = it.message }
                        busy = false
                    }
                }, Modifier.fillMaxWidth().testTag("create-lncurl"), BtnVariant.Secondary, enabled = !busy, icon = L.drawable.lucide_ic_zap)
            }
        },
        confirmButton = {
            TextButton({
                if (runCatching { NwcUri.parse(conn) }.isFailure) { error = "Invalid NWC connection string"; return@TextButton }
                busy = true
                scope.launch {
                    runCatching { c.session.api.value.createNwcWallet(name.ifBlank { "Wallet" }, conn, mode, primary) }.onSuccess { onCreated() }.onFailure { error = it.message }
                    busy = false
                }
            }, enabled = !busy && conn.isNotBlank(), modifier = Modifier.testTag("wallet-connect")) { Txt(stringResource(R.string.rw_connect)) }
        },
        dismissButton = { TextButton(onDismiss) { Txt(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun RemoteWalletDetailScreen(id: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var wallet by remember { mutableStateOf<RemoteWallet?>(null) }
    var notFound by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf<Long?>(null) }
    var connection by remember { mutableStateOf<String?>(null) }
    var showConn by remember { mutableStateOf(false) }
    var txs by remember { mutableStateOf<List<Tx>?>(null) }
    var action by remember { mutableStateOf<ReceiveAction?>(null) }
    var notifications by remember { mutableStateOf<List<WalletNotification>>(emptyList()) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var editingPlan by remember { mutableStateOf(false) }
    var addingHook by remember { mutableStateOf(false) }
    val copied = stringResource(R.string.common_copied)
    val planSaved = stringResource(R.string.remoteWallets_forwarding_planSaved)
    suspend fun load() {
        val api = c.session.api.value
        runCatching { api.remoteWallet(id) }.onSuccess { wallet = it }.onFailure { notFound = true; return }
        balance = runCatching { api.remoteWalletBalance(id) }.getOrNull()
        connection = runCatching { api.remoteWalletConnectionString(id) }.getOrNull()
        action = runCatching { api.receiveAction(id) }.getOrNull()
        notifications = runCatching { api.remoteWalletNotifications(id) }.getOrDefault(emptyList())
    }
    LaunchedEffect(id) {
        load()
        connection?.let { cs ->
            val client = runCatching { NwcClient(NwcUri.parse(cs), c.http, c.scope) }.getOrNull()
            txs = runCatching { client?.listTransactions(limit = 20)?.map(Tx::from) }.getOrNull() ?: emptyList()
            client?.close()
        }
    }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(wallet?.name ?: stringResource(R.string.remoteWallets_detail_title), { nav.popBackStack() }, stringResource(R.string.common_back))
        val w = wallet
        if (w == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (notFound) MutedText(stringResource(R.string.remoteWallets_detail_notFound)) else Spinner() }; return@Column }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp)).padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Txt(stringResource(R.string.remote_wallet_balance), size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
                Txt(balance?.let { Currencies.formatSats(it) } ?: "—", size = 36.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("wallet-balance"))
                Txt("sats", size = 14.sp, color = Lw.MutedFg)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (w.isDefault) Pill(stringResource(R.string.remoteWallets_list_primary), LocalBrand.current.t400)
                    Pill(w.status, if (w.status == "ACTIVE") Lw.Green else Lw.MutedFg)
                    w.provider?.let { Pill(if (it == "lncurl") "LNCurl" else it, Lw.NwcPurple) }
                }
            }
            SettingsGroup {
                if (!w.isDefault && w.status == "ACTIVE") { SettingsRow(stringResource(R.string.remoteWallets_list_setPrimary), { scope.launch { runCatching { c.session.api.value.updateRemoteWallet(id, isDefault = true) }; load(); runCatching { c.session.refreshMe() } } }, icon = L.drawable.lucide_ic_check); GroupDivider() }
                SettingsRow(stringResource(R.string.rw_rename), { renaming = true }, icon = L.drawable.lucide_ic_pencil)
                GroupDivider()
                if (w.status == "ACTIVE" || w.status == "DISABLED") {
                    SettingsRow(stringResource(if (w.status == "ACTIVE") R.string.remoteWallets_pause else R.string.remoteWallets_resume), {
                        scope.launch { runCatching { c.session.api.value.updateRemoteWallet(id, status = if (w.status == "ACTIVE") "DISABLED" else "ACTIVE") }; load(); runCatching { c.session.refreshMe() } }
                    }, icon = L.drawable.lucide_ic_refresh_cw)
                    GroupDivider()
                }
                SettingsRow(stringResource(R.string.rw_delete), { deleting = true }, labelColor = Lw.Red, icon = L.drawable.lucide_ic_trash_2)
            }
            connection?.let { cs ->
                SettingsGroup(stringResource(R.string.remote_wallet_connection)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt(if (showConn) cs else cs.take(36) + "••••••••••••", size = 11.sp, mono = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LwButton(stringResource(R.string.remote_wallet_reveal), { showConn = !showConn }, Modifier.weight(1f), BtnVariant.Secondary, height = 36.dp, icon = L.drawable.lucide_ic_eye)
                            LwButton(stringResource(R.string.common_copy), { context.copyToClipboard(cs, copied) }, Modifier.weight(1f), BtnVariant.Secondary, height = 36.dp, icon = L.drawable.lucide_ic_copy)
                        }
                    }
                }
            }
            w.receiveCapabilities?.let { caps ->
                SettingsGroup(stringResource(R.string.remoteWallets_protocols_label)) {
                    SettingsRow("LUD-21 (verify)", null, value = stringResource(if (w.status == "ACTIVE") R.string.shared_protocol_supported else R.string.shared_protocol_notSupported))
                    GroupDivider()
                    SettingsRow("NIP-57 (zaps)", null, value = stringResource(if (caps.nip57) R.string.shared_protocol_supported else R.string.shared_protocol_notSupported))
                    caps.reason?.let { MutedText(it, Modifier.padding(16.dp), size = 12.sp) }
                }
            }
            action?.let { a ->
                SettingsGroup(stringResource(R.string.remoteWallets_forwarding_title)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        MutedText(stringResource(R.string.remoteWallets_forwarding_subtitle), size = 12.sp)
                        if (!a.eligible) MutedText(a.reason ?: stringResource(R.string.remoteWallets_forwarding_unavailable), size = 12.sp)
                        val rev = a.revision
                        if (rev == null) Txt(stringResource(R.string.remoteWallets_forwarding_noPlan), weight = FontWeight.Medium)
                        else {
                            Row { Txt(stringResource(R.string.remoteWallets_forwarding_pendingBalance), Modifier.weight(1f), color = Lw.MutedFg, size = 13.sp); Txt(stringResource(R.string.common_sats, Currencies.formatSats(a.pendingAmountMsats / 1000)), size = 13.sp) }
                            Row { Txt(stringResource(R.string.remoteWallets_forwarding_entryFee), Modifier.weight(1f), color = Lw.MutedFg, size = 13.sp); Txt("${rev.feeBps / 100.0}% + ${rev.baseFeeSats.toLong()} sats", size = 13.sp) }
                            rev.destinations.forEach { d -> Row { Txt(d.address, Modifier.weight(1f), size = 13.sp, maxLines = 1); Txt("${d.allocationBps / 100.0}%", size = 13.sp, color = Lw.MutedFg) } }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Txt(stringResource(if (a.enabled) R.string.remoteWallets_live else R.string.remoteWallets_paused), Modifier.weight(1f))
                                Switch(a.enabled, { on -> scope.launch { runCatching { c.session.api.value.toggleReceiveAction(id, on) }.onFailure { context.toast(it.message ?: "") }; load() } }, colors = lwSwitchColors())
                            }
                        }
                        if (a.eligible) LwButton(stringResource(if (rev == null) R.string.remoteWallets_forwarding_configure else R.string.remoteWallets_forwarding_editPlan), { editingPlan = true }, Modifier.fillMaxWidth(), BtnVariant.Secondary, height = 40.dp)
                    }
                }
            }
            SettingsGroup(stringResource(R.string.notifications_title)) {
                if (notifications.isEmpty()) MutedText(stringResource(R.string.notifications_empty), Modifier.padding(16.dp), size = 13.sp)
                notifications.forEachIndexed { i, n ->
                    if (i > 0) GroupDivider()
                    SettingsRow(n.name.ifBlank { n.channel }, null, value = n.channel) {
                        Switch(n.enabled, { on -> scope.launch { runCatching { c.session.api.value.setNotificationEnabled(id, n.id, on) }; load() } }, colors = lwSwitchColors())
                    }
                }
                GroupDivider()
                SettingsRow(stringResource(R.string.notifications_add_webhook), { addingHook = true }, icon = L.drawable.lucide_ic_plus)
            }
            SettingsGroup(stringResource(R.string.rw_recent_tx)) {
                when {
                    txs == null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner() }
                    txs!!.isEmpty() -> MutedText(stringResource(R.string.activity_empty), Modifier.padding(16.dp))
                    else -> txs!!.forEach { TransactionRow(it) { c.flow.activityDetail = it; nav.navigate(R0.activity(it.paymentHash)) } }
                }
            }
        }
    }
    if (renaming) TextPrompt(stringResource(R.string.rw_rename), wallet?.name ?: "", onDismiss = { renaming = false }) { v ->
        renaming = false; scope.launch { runCatching { c.session.api.value.updateRemoteWallet(id, name = v) }.onFailure { context.toast(it.message ?: "") }; load() }
    }
    if (deleting) AlertDialog(
        onDismissRequest = { deleting = false }, containerColor = Glass.Dialog,
        text = { Txt(stringResource(R.string.rw_delete_confirm)) },
        confirmButton = { TextButton({
            deleting = false
            scope.launch {
                runCatching { c.session.api.value.deleteRemoteWallet(id, permanent = wallet?.status != "ACTIVE") }
                    .onSuccess { runCatching { c.session.refreshMe() }; nav.popBackStack() }.onFailure { context.toast(it.message ?: "") }
            }
        }) { Txt(stringResource(R.string.rw_delete), color = Lw.Red) } },
        dismissButton = { TextButton({ deleting = false }) { Txt(stringResource(R.string.common_cancel)) } },
    )
    if (editingPlan) ForwardPlanDialog(action, onDismiss = { editingPlan = false }) { fee, base, dests ->
        editingPlan = false
        scope.launch { runCatching { c.session.api.value.saveReceiveAction(id, fee, base, dests) }.onSuccess { context.toast(planSaved) }.onFailure { context.toast(it.message ?: "") }; load() }
    }
    if (addingHook) WebhookDialog(onDismiss = { addingHook = false }) { name, action2, url ->
        addingHook = false
        scope.launch { runCatching { c.session.api.value.createWebhookNotification(id, name, action2, url) }.onFailure { context.toast(it.message ?: "") }; load() }
    }
}

@Composable
private fun TextPrompt(title: String, initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Glass.Dialog, title = { Txt(title, size = 16.sp, weight = FontWeight.SemiBold) },
        text = { OutlinedTextField(v, { v = it.take(64) }, singleLine = true, colors = lwFieldColors()) },
        confirmButton = { TextButton({ onSave(v.trim()) }, enabled = v.isNotBlank()) { Txt(stringResource(R.string.common_done)) } },
        dismissButton = { TextButton(onDismiss) { Txt(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun ForwardPlanDialog(current: ReceiveAction?, onDismiss: () -> Unit, onSave: (Int, Long, List<ForwardDestination>) -> Unit) {
    var fee by remember { mutableStateOf((current?.revision?.feeBps ?: 0).toString()) }
    var base by remember { mutableStateOf((current?.revision?.baseFeeSats?.toLong() ?: 0).toString()) }
    val rows = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(current?.revision?.destinations?.map { it.address to (it.allocationBps / 100.0).toString() } ?: listOf("" to "100")) } }
    val total = rows.sumOf { it.second.toDoubleOrNull() ?: 0.0 }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Glass.Dialog,
        title = { Txt(stringResource(R.string.remoteWallets_forwarding_configTitle), size = 16.sp, weight = FontWeight.SemiBold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MutedText(stringResource(R.string.remoteWallets_forwarding_configBody), size = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(fee, { fee = it.filter(Char::isDigit).take(4) }, Modifier.weight(1f), label = { Txt(stringResource(R.string.rw_forward_fee_bps), size = 11.sp) }, singleLine = true, colors = lwFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                    OutlinedTextField(base, { base = it.filter(Char::isDigit).take(9) }, Modifier.weight(1f), label = { Txt(stringResource(R.string.rw_forward_base), size = 11.sp) }, singleLine = true, colors = lwFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
                rows.forEachIndexed { i, (addr, pct) ->
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(addr, { rows[i] = it.trim() to pct }, Modifier.weight(2f), label = { Txt(stringResource(R.string.rw_forward_address), size = 11.sp) }, singleLine = true, colors = lwFieldColors())
                        OutlinedTextField(pct, { rows[i] = addr to it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) }, Modifier.weight(1f), label = { Txt(stringResource(R.string.rw_forward_split), size = 11.sp) }, singleLine = true, colors = lwFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                        if (rows.size > 1) Box(Modifier.size(28.dp).clickable { rows.removeAt(i) }, contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_x, size = 16.dp, tint = Lw.MutedFg) }
                    }
                }
                if (rows.size < 20) TextButton({ rows.add("" to "0") }) { Txt(stringResource(R.string.remoteWallets_forwarding_addDestination)) }
                if (kotlin.math.abs(total - 100) > 0.001) ErrorText("Σ ${"%.2f".format(total)}% ≠ 100%")
            }
        },
        confirmButton = {
            TextButton({
                onSave(fee.toIntOrNull() ?: 0, base.toLongOrNull() ?: 0, rows.map { ForwardDestination(it.first, ((it.second.toDoubleOrNull() ?: 0.0) * 100).toInt()) })
            }, enabled = kotlin.math.abs(total - 100) < 0.001 && rows.all { it.first.contains('@') }) { Txt(stringResource(R.string.remoteWallets_forwarding_saveAndEnable)) }
        },
        dismissButton = { TextButton(onDismiss) { Txt(stringResource(R.string.common_cancel)) } })
}

@Composable
private fun WebhookDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("https://") }
    var action by remember { mutableStateOf("RECEIVED") }
    AlertDialog(onDismissRequest = onDismiss, containerColor = Glass.Dialog,
        title = { Txt(stringResource(R.string.notifications_add_webhook), size = 16.sp, weight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it.take(64) }, label = { Txt(stringResource(R.string.notification_name)) }, singleLine = true, colors = lwFieldColors())
                OutlinedTextField(url, { url = it.trim() }, label = { Txt(stringResource(R.string.webhook_url)) }, singleLine = true, colors = lwFieldColors(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                listOf("RECEIVED" to R.string.notification_received, "FORWARDED" to R.string.notification_forwarded).forEach { (k, l) ->
                    Row(Modifier.clickable { action = k }, verticalAlignment = Alignment.CenterVertically) { RadioButton(action == k, { action = k }); Txt(stringResource(l)) }
                }
            }
        },
        confirmButton = { TextButton({ onSave(name.ifBlank { "Webhook" }, action, url) }, enabled = url.startsWith("https://") && url.length > 10) { Txt(stringResource(R.string.common_done)) } },
        dismissButton = { TextButton(onDismiss) { Txt(stringResource(R.string.common_cancel)) } })
}
