package io.lawallet.wallet.ui.screens

import androidx.compose.runtime.snapshots.SnapshotStateMap
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.builtins.MapSerializer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.horizontalScroll
import androidx.compose.runtime.key
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
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
import io.lawallet.wallet.ui.components.LwAlertDialog
import io.lawallet.wallet.ui.components.ModalAction
import androidx.compose.foundation.layout.heightIn
import io.lawallet.wallet.ui.components.GlassIconButton
import io.lawallet.wallet.ui.components.autoFocus
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.app.AppContainer
import io.lawallet.wallet.app.Tx
import io.lawallet.wallet.core.api.ForwardDestination
import io.lawallet.wallet.core.api.ReceiveAction
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.api.WalletNotification
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.nwc.NwcClient
import io.lawallet.wallet.core.nwc.NwcUri
import io.lawallet.wallet.ui.LocalContainer
import kotlinx.serialization.builtins.ListSerializer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.GroupDivider
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.WalletMark
import androidx.compose.ui.draw.alpha
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
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

internal val remoteWalletsSer = ListSerializer(RemoteWallet.serializer())

/**
 * Refreshes the remote wallets (cached under "remote-wallets", passed to [onList]) then each ACTIVE one's
 * balance into [balances] (null = unavailable). Offline, balances load for the cached list. False if the list failed.
 */
/**
 * There's always a primary while any wallet is active: when it's paused or deleted, the most recently
 * primary wallet that's still active takes over (else the first active one). Returns the list as it now stands.
 */
internal suspend fun ensurePrimary(c: AppContainer, list: List<RemoteWallet>): List<RemoteWallet> {
    list.firstOrNull { it.isDefault && it.status == "ACTIVE" }?.let { c.prefs.notePrimary(it.id); return list }
    val pick = nextPrimary(list, c.prefs.primaryHistory) ?: return list
    runCatching { c.session.api.value.updateRemoteWallet(pick.id, isDefault = true) }.getOrElse { return list }
    c.prefs.notePrimary(pick.id)
    runCatching { c.session.refreshMe() } // the app's wallet connection follows the new primary
    return list.map { it.copy(isDefault = it.id == pick.id) }
}

/** Who takes over from a primary that's gone: the latest past primary still active, else the first active wallet. */
internal fun nextPrimary(list: List<RemoteWallet>, history: List<String>): RemoteWallet? {
    val active = list.filter { it.status == "ACTIVE" }
    return history.firstNotNullOfOrNull { id -> active.find { it.id == id } } ?: active.firstOrNull()
}

/** Re-reads the list and fixes the primary: after a wallet is paused or deleted outside a list screen. */
internal suspend fun ensurePrimary(c: AppContainer) {
    runCatching { ensurePrimary(c, c.session.api.value.remoteWallets()) }.onSuccess { c.prefs.setCached("remote-wallets", remoteWalletsSer, it) }
}

internal suspend fun fetchRemoteWallets(c: AppContainer, onList: (List<RemoteWallet>) -> Unit, balances: MutableMap<String, Long?>): Boolean {
    val list = runCatching { ensurePrimary(c, c.session.api.value.remoteWallets()) }.onSuccess { onList(it); c.prefs.setCached("remote-wallets", remoteWalletsSer, it) }.getOrNull()
    coroutineScope {
        (list ?: c.prefs.cached("remote-wallets", remoteWalletsSer)).orEmpty().filter { it.status == "ACTIVE" }.forEach { w ->
            launch { readWalletBalance(c, w.id, balances) }
        }
    }
    return list != null
}

private val walletBalancesSer = MapSerializer(String.serializer(), Long.serializer())
internal fun cachedWalletBalances(c: AppContainer): Map<String, Long> = c.prefs.cached("rw-balances", walletBalancesSer).orEmpty()
internal fun saveWalletBalance(c: AppContainer, id: String, sats: Long) = c.prefs.setCached("rw-balances", walletBalancesSer, cachedWalletBalances(c) + (id to sats))

/** Each remote wallet's balance (missing = loading, null = unavailable), filled from the last known figures so the
 *  cards show at once while live reads come in. */
@Composable
internal fun rememberWalletBalances(): SnapshotStateMap<String, Long?> {
    val c = LocalContainer.current
    return remember { mutableStateMapOf<String, Long?>().apply { putAll(cachedWalletBalances(c)) } }
}

/** A live read of one wallet's balance into [balances] and the cache; a failure keeps the last figure if there is one. */
internal suspend fun readWalletBalance(c: AppContainer, id: String, balances: MutableMap<String, Long?>) {
    runCatching { c.session.api.value.remoteWalletBalance(id) }
        .onSuccess { balances[id] = it; saveWalletBalance(c, id, it) }
        .onFailure { if (balances[id] == null) balances[id] = null }
}

@Composable
fun RemoteWalletsScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    var wallets by remember { mutableStateOf(c.prefs.cached("remote-wallets", remoteWalletsSer)) }
    val balances = rememberWalletBalances()
    var failed by remember { mutableStateOf(false) }
    val newId = rememberNewWallet()
    LaunchedEffect(Unit) { failed = !fetchRemoteWallets(c, { wallets = it }, balances) }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(stringResource(R.string.settings_remoteWallets), { nav.popBackStack() }, stringResource(R.string.common_back))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val list = wallets
            when {
                list == null && failed -> Column { Txt(stringResource(R.string.remoteWallets_list_errorTitle), weight = FontWeight.SemiBold); MutedText(stringResource(R.string.remoteWallets_list_tryAgain)) }
                list == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Spinner() }
                list.isEmpty() -> Column(Modifier.fillMaxWidth().padding(vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.size(72.dp).glass(CircleShape), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_wallet, size = 32.dp, tint = Lw.MutedFg) }
                    Spacer(Modifier.height(16.dp))
                    Txt(stringResource(R.string.remoteWallets_list_emptyTitle), size = 18.sp, weight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    MutedText(stringResource(R.string.remoteWallets_list_emptyBody))
                }
                else -> {
                    val active = list.filter { it.status == "ACTIVE" }
                    val total = active.mapNotNull { balances[it.id] }.sum()
                    val totalReady = active.all { balances.containsKey(it.id) }
                    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp)).padding(vertical = 24.dp, horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Txt(stringResource(R.string.rw_total_balance), size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.Bottom) {
                            Txt(if (totalReady) Currencies.formatSats(total) else "—", size = 40.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("wallets-total"))
                            Spacer(Modifier.width(6.dp))
                            Txt("sats", Modifier.padding(bottom = 7.dp), size = 16.sp, color = Lw.MutedFg)
                        }
                        Spacer(Modifier.height(4.dp))
                        MutedText(pluralStringResource(R.plurals.rw_wallet_count, list.size, list.size), size = 13.sp)
                    }
                    list.sortedWith(compareByDescending<RemoteWallet> { it.isDefault }.thenBy { it.status != "ACTIVE" }).forEach { w ->
                        key(w.id) {
                            NewWalletEntrance(w.id == newId, RoundedCornerShape(24.dp)) {
                                WalletCard(w, if (w.status == "ACTIVE") balances[w.id] else null, loading = w.status == "ACTIVE" && !balances.containsKey(w.id)) { nav.navigate(R0.remoteWallet(w.id)) }
                            }
                        }
                    }
                }
            }
        }
        LwButton(stringResource(R.string.rw_add), { nav.navigate(R0.REMOTE_WALLET_ADD) }, Modifier.fillMaxWidth().padding(16.dp).testTag("add-wallet"), BtnVariant.Theme, icon = L.drawable.lucide_ic_plus)
    }
}

/** The wallet just added (consumed once: coming back later doesn't replay its entrance). */
@Composable
internal fun rememberNewWallet(): String? {
    val c = LocalContainer.current
    return remember { c.flow.newWalletId.also { c.flow.newWalletId = null } }
}

/**
 * A just-added wallet's card: once [start] returns (the screen has landed), it rises in from below on a soft spring,
 * growing into place, while a brand-coloured halo blooms behind it and fades.
 */
@Composable
internal fun NewWalletEntrance(isNew: Boolean, shape: Shape, start: suspend () -> Unit = { delay(320) }, content: @Composable () -> Unit) {
    if (!isNew) return content()
    val rise = remember { Animatable(0f) }
    val halo = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        start()
        launch { rise.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = Spring.StiffnessMediumLow)) }
        halo.animateTo(1f, tween(320, easing = FastOutSlowInEasing))
        halo.animateTo(0f, tween(1100, delayMillis = 450, easing = LinearOutSlowInEasing))
    }
    val tint = LocalBrand.current.t400
    Box(
        Modifier
            .graphicsLayer {
                val p = rise.value
                alpha = p.coerceIn(0f, 1f)
                translationY = (1f - p) * 56.dp.toPx()
                scaleX = 0.9f + 0.1f * p; scaleY = scaleX
            }
            .drawBehind {
                if (halo.value <= 0f) return@drawBehind
                val grow = 6.dp.toPx() * halo.value
                val outline = shape.createOutline(Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, this)
                translate(-grow, -grow) { drawOutline(outline, tint.copy(alpha = 0.28f * halo.value)) }
            },
    ) { content() }
}

@Composable
private fun WalletCard(w: RemoteWallet, balance: Long?, loading: Boolean, onClick: () -> Unit) {
    val brand = LocalBrand.current
    val lncurl = w.provider == "lncurl"
    val accent = if (lncurl) Lw.LncurlGreen else Lw.NwcPurple
    val dim = w.status != "ACTIVE"
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), tint = if (w.isDefault) brand.t400.copy(alpha = 0.08f) else null)
            .clickable(onClick = onClick).padding(16.dp).testTag("wallet-${w.name}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(44.dp).clip(CircleShape).background(accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            WalletMark(lncurl, 20.dp, Modifier.alpha(if (dim) 0.45f else 1f))
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(w.name, size = 16.sp, weight = FontWeight.Medium, maxLines = 1, color = if (dim) Lw.MutedFg else Lw.Foreground)
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (w.isDefault) Pill(stringResource(R.string.remoteWallets_list_primary), brand.t400)
                when (w.status) {
                    "DEAD" -> Pill(stringResource(R.string.rw_dead), Lw.Red)
                    "DISABLED" -> Pill(stringResource(R.string.remoteWallets_paused), Lw.MutedFg)
                    else -> Pill(if (lncurl) "LNCurl" else "NWC", accent)
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(horizontalAlignment = Alignment.End) {
            when {
                loading -> Spinner()
                balance != null -> {
                    Txt(Currencies.formatSats(balance), size = 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("wallet-balance-${w.name}"))
                    Txt("sats", size = 12.sp, color = Lw.MutedFg)
                }
                else -> Txt("—", size = 18.sp, color = Lw.MutedFg)
            }
        }
        Spacer(Modifier.width(4.dp))
        LwIcon(L.drawable.lucide_ic_chevron_right, size = 18.dp, tint = Lw.MutedFg)
    }
}

@Composable
internal fun Pill(text: String, color: androidx.compose.ui.graphics.Color) =
    Txt(text, Modifier.glass(CircleShape, GlassLevel.Thin, tint = color.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp), size = 11.sp, weight = FontWeight.SemiBold, color = color)

/** Add a remote wallet: pick a source first (import NWC / disposable LNCurl), then fill its form. */
@Composable
fun AddRemoteWalletScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val settings by c.session.settings.collectAsState()
    var step by remember { mutableStateOf<String?>(null) } // null = choose, "nwc", "lncurl"
    var name by remember { mutableStateOf("") }
    var conn by remember { mutableStateOf("") }
    // Primary is opt-in, except for the first wallet (it has to be the primary). Cached list first, then the server's.
    var primary by remember { mutableStateOf(c.prefs.cached("remote-wallets", remoteWalletsSer).isNullOrEmpty()) }
    var primaryTouched by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { c.session.api.value.remoteWallets() }.onSuccess { if (!primaryTouched) primary = it.isEmpty() } }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var scanning by remember { mutableStateOf(false) }
    val invalid = "Invalid NWC connection string"
    // Name is optional: default to the URI's lud16, else its first relay host.
    val derivedName = remember(conn) { runCatching { NwcUri.parse(conn) }.getOrNull()?.let { it.lud16 ?: it.relays.firstOrNull()?.substringAfter("://")?.substringBefore('/') } ?: "Wallet" }
    val back = { if (step != null) { step = null; error = null; scanning = false } else nav.popBackStack() }
    androidx.activity.compose.BackHandler(step != null) { back() }
    fun submit(create: suspend () -> RemoteWallet) {
        busy = true; error = null
        scope.launch {
            runCatching { create() }.onSuccess { created ->
                // Back where we came from (Home's wallet list or Remote wallets) while the button still spins: the list
                // already holds the new wallet (cache) and plays its entrance. Busy stays on: this screen is leaving.
                val others = c.prefs.cached("remote-wallets", remoteWalletsSer).orEmpty().filter { it.id != created.id }
                c.prefs.setCached("remote-wallets", remoteWalletsSer, others.map { if (created.isDefault) it.copy(isDefault = false) else it } + created)
                c.flow.newWalletId = created.id
                c.scope.launch { runCatching { c.session.refreshMe() } }
                if (nav.previousBackStackEntry?.destination?.route == R0.HOME) c.flow.revealWallets = true
                if (!nav.popBackStack()) nav.goHome()
            }.onFailure { error = it.message; busy = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(stringResource(when (step) { "nwc" -> R.string.rw_import_nwc; "lncurl" -> R.string.rw_create_disposable; else -> R.string.rw_add_title }), { back() }, stringResource(R.string.common_back))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (step) {
                null -> {
                    MutedText(stringResource(R.string.rw_add_subtitle))
                    WalletMark(lncurl = false, size = 96.dp, modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
                    LwButton(stringResource(R.string.rw_import_nwc), { step = "nwc"; scanning = true }, Modifier.fillMaxWidth().testTag("add-nwc"), BtnVariant.Theme, height = 60.dp, icon = L.drawable.lucide_ic_link)
                    if (settings?.lncurlEnabled == true) LwButton(stringResource(R.string.rw_create_disposable), { step = "lncurl" }, Modifier.fillMaxWidth().testTag("add-lncurl"), BtnVariant.Secondary, height = 60.dp, icon = L.drawable.lucide_ic_zap)
                }
                "nwc" -> {
                    if (scanning) QrCamera(false, { error = it; scanning = false }) { raw ->
                        if (!scanning) return@QrCamera
                        scanning = false; conn = raw.trim()
                        error = if (runCatching { NwcUri.parse(conn) }.isFailure) invalid else null
                    }
                    OutlinedTextField(conn, { conn = it.trim(); error = null }, Modifier.fillMaxWidth().testTag("wallet-nwc"), enabled = !busy, label = { Txt(stringResource(R.string.rw_connection)) }, placeholder = { Txt(stringResource(R.string.rw_connection_hint), color = Lw.MutedFg) }, colors = lwFieldColors(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), maxLines = 3,
                        trailingIcon = { Box(Modifier.size(40.dp).clickable(enabled = !busy) { scanning = !scanning; error = null }.testTag("wallet-scan"), contentAlignment = Alignment.Center) { LwIcon(if (scanning) L.drawable.lucide_ic_x else L.drawable.lucide_ic_scan_qr_code, size = 20.dp, tint = Lw.MutedFg, contentDescription = stringResource(R.string.shared_nav_scan)) } })
                    OutlinedTextField(name, { name = it.take(64) }, Modifier.fillMaxWidth().testTag("wallet-name"), enabled = !busy, label = { Txt(stringResource(R.string.rw_name_optional)) }, placeholder = { Txt(derivedName, color = Lw.MutedFg) }, singleLine = true, colors = lwFieldColors())
                    SettingsGroup { SettingsRow(stringResource(R.string.rw_make_primary), { if (!busy) { primaryTouched = true; primary = !primary } }) { Switch(primary, { primaryTouched = true; primary = it }, enabled = !busy, colors = lwSwitchColors()) } }
                }
                "lncurl" -> {
                    MutedText(stringResource(R.string.rw_lncurl_body))
                    val focus = remember { FocusRequester() }
                    LaunchedEffect(Unit) { focus.requestFocus() }
                    OutlinedTextField(name, { name = it.take(64) }, Modifier.fillMaxWidth().focusRequester(focus).testTag("wallet-name"), enabled = !busy, label = { Txt(stringResource(R.string.rw_name)) }, singleLine = true, colors = lwFieldColors())
                    SettingsGroup { SettingsRow(stringResource(R.string.rw_make_primary), { if (!busy) { primaryTouched = true; primary = !primary } }) { Switch(primary, { primaryTouched = true; primary = it }, enabled = !busy, colors = lwSwitchColors()) } }
                }
            }
            ErrorText(error)
        }
        when (step) {
            "nwc" -> LwButton(stringResource(R.string.rw_connect), {
                if (runCatching { NwcUri.parse(conn) }.isFailure) error = invalid
                else submit { c.session.api.value.createNwcWallet(name.ifBlank { derivedName }, conn, "SEND_RECEIVE", primary) }
            }, Modifier.fillMaxWidth().padding(16.dp).testTag("wallet-connect"), BtnVariant.Theme, enabled = !busy && conn.isNotBlank(), loading = busy)
            "lncurl" -> LwButton(stringResource(R.string.rw_create), { submit { c.session.api.value.createLncurlWallet(name.ifBlank { null }, primary) } },
                Modifier.fillMaxWidth().padding(16.dp).testTag("create-lncurl"), BtnVariant.Theme, enabled = !busy, loading = busy, icon = L.drawable.lucide_ic_zap)
        }
    }
}

/** A receive protocol as a small round icon: lit when supported, dimmed when not; a tap says which and why. */
@Composable
private fun ProtocolBadge(@DrawableRes icon: Int, name: String, supported: Boolean, reason: String?) {
    val context = LocalContext.current
    val status = stringResource(if (supported) R.string.shared_protocol_supported else R.string.shared_protocol_notSupported)
    val tint = if (supported) Lw.Green else Lw.MutedFg
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(tint.copy(alpha = if (supported) 0.16f else 0.08f))
            .clickable(role = Role.Button) { context.toast(listOfNotNull("$name · $status", reason?.takeIf { !supported }).joinToString("\n")) }
            .semantics { contentDescription = "$name: $status" },
        contentAlignment = Alignment.Center,
    ) { LwIcon(icon, size = 14.dp, tint = tint.copy(alpha = if (supported) 1f else 0.6f)) }
}

/** Top-right status button: green "Live" with a pause icon, or muted "Paused" with a play icon; a tap flips it. */
@Composable
private fun StatusToggle(live: Boolean, busy: Boolean, onClick: () -> Unit) {
    val tint = if (live) Lw.Green else Lw.MutedFg
    Row(
        Modifier.height(40.dp).clip(CircleShape).background(tint.copy(alpha = 0.14f)).border(1.dp, tint.copy(alpha = 0.35f), CircleShape)
            .clickable(enabled = !busy, role = Role.Button, onClick = onClick).padding(start = 12.dp, end = 16.dp).testTag("wallet-status-toggle"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) Spinner(18.dp, tint) else LwIcon(if (live) L.drawable.lucide_ic_pause else L.drawable.lucide_ic_play, size = 18.dp, tint = tint)
        Spacer(Modifier.width(8.dp))
        Txt(stringResource(if (live) R.string.remoteWallets_live else R.string.remoteWallets_paused), size = 15.sp, weight = FontWeight.SemiBold, color = tint)
    }
}

@Composable
fun RemoteWalletDetailScreen(id: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var wallet by remember { mutableStateOf<RemoteWallet?>(null) }
    var notFound by remember { mutableStateOf(false) }
    var balance by remember { mutableStateOf(cachedWalletBalances(c)[id]) }
    var connection by remember { mutableStateOf<String?>(null) }
    var showConn by remember { mutableStateOf(false) }
    val sensitiveGate = rememberSensitiveGate()
    var toggling by remember { mutableStateOf(false) }
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
        runCatching { api.remoteWalletBalance(id) }.onSuccess { balance = it; saveWalletBalance(c, id, it) } // a failure keeps the cached figure
        connection = runCatching { api.remoteWalletConnectionString(id) }.getOrNull()
        action = runCatching { api.receiveAction(id) }.getOrNull()
        notifications = runCatching { api.remoteWalletNotifications(id) }.getOrDefault(emptyList())
    }
    LaunchedEffect(id) { load() }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(wallet?.name ?: stringResource(R.string.remoteWallets_detail_title), { nav.popBackStack() }, stringResource(R.string.common_back),
            titleAction = if (wallet == null) null else ({ GlassIconButton(L.drawable.lucide_ic_pencil, { renaming = true }, Modifier.testTag("wallet-rename"), size = 36.dp, contentDescription = stringResource(R.string.rw_rename)) }),
        ) {
            // Status as a toggle: says what it is (Live / Paused), the icon says what a tap does (pause / play).
            val w = wallet
            if (w != null && (w.status == "ACTIVE" || w.status == "DISABLED")) StatusToggle(w.status == "ACTIVE", toggling) {
                toggling = true
                scope.launch {
                    runCatching { c.session.api.value.updateRemoteWallet(id, status = if (w.status == "ACTIVE") "DISABLED" else "ACTIVE") }.onFailure { context.toast(it.message ?: "") }
                    ensurePrimary(c); load(); runCatching { c.session.refreshMe() }
                    toggling = false
                }
            }
        }
        val w = wallet
        if (w == null) { Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { if (notFound) MutedText(stringResource(R.string.remoteWallets_detail_notFound)) else Spinner() }; return@Column }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Box(Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp))) {
                Column(Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Txt(stringResource(R.string.remote_wallet_balance), size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
                    Txt(balance?.let { Currencies.formatSats(it) } ?: "—", size = 36.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("wallet-balance"))
                    Txt("sats", size = 14.sp, color = Lw.MutedFg)
                    val dead = w.status != "ACTIVE" && w.status != "DISABLED" // e.g. DEAD: nothing to toggle
                    if (w.isDefault || dead) {
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (w.isDefault) Pill(stringResource(R.string.remoteWallets_list_primary), LocalBrand.current.t400)
                            if (dead) Pill(w.status, Lw.Red)
                        }
                    }
                }
                // What kind of wallet it is (LNCurl or NWC), as its mark in the corner.
                val lncurl = w.provider == "lncurl"
                Box(
                    Modifier.align(Alignment.TopStart).padding(14.dp).size(34.dp).clip(CircleShape).background((if (lncurl) Lw.LncurlGreen else Lw.NwcPurple).copy(alpha = 0.16f))
                        .semantics { contentDescription = if (lncurl) "LNCurl" else "NWC" }.testTag("wallet-kind"),
                    contentAlignment = Alignment.Center,
                ) { WalletMark(lncurl, 16.dp) }
                w.receiveCapabilities?.let { caps ->
                    Row(Modifier.align(Alignment.BottomEnd).padding(12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ProtocolBadge(L.drawable.lucide_ic_badge_check, "LUD-21 (verify)", w.status == "ACTIVE", null)
                        ProtocolBadge(L.drawable.lucide_ic_zap, "NIP-57 (zaps)", caps.nip57, caps.reason)
                    }
                }
            }
            if (!w.isDefault && w.status == "ACTIVE") SettingsGroup {
                SettingsRow(stringResource(R.string.remoteWallets_list_setPrimary), { scope.launch { runCatching { c.session.api.value.updateRemoteWallet(id, isDefault = true) }.onSuccess { c.prefs.notePrimary(id) }; load(); runCatching { c.session.refreshMe() } } }, icon = L.drawable.lucide_ic_check)
            }
            connection?.let { cs ->
                SettingsGroup(stringResource(R.string.remote_wallet_connection)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Txt(if (showConn) cs else cs.take(36) + "••••••••••••", size = 11.sp, mono = true)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            LwButton(stringResource(R.string.remote_wallet_reveal), { if (showConn) showConn = false else sensitiveGate { showConn = true } }, Modifier.weight(1f), BtnVariant.Secondary, height = 36.dp, icon = L.drawable.lucide_ic_eye)
                            LwButton(stringResource(R.string.common_copy), { sensitiveGate { context.copyToClipboard(cs, copied) } }, Modifier.weight(1f), BtnVariant.Secondary, height = 36.dp, icon = L.drawable.lucide_ic_copy)
                        }
                    }
                }
            }
            connection?.let { cs -> runCatching { NwcUri.parse(cs).relays }.getOrNull() }?.takeIf { it.isNotEmpty() }?.let { relays ->
                // Labels in one row, scrolling sideways; a tap copies the relay.
                Column {
                    Txt(stringResource(R.string.bunker_relays), Modifier.padding(start = 6.dp, bottom = 8.dp), size = 13.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).testTag("wallet-relays"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        relays.forEach { r ->
                            Row(Modifier.clip(CircleShape).glass(CircleShape, GlassLevel.Thin).clickable { context.copyToClipboard(r, copied) }.padding(start = 10.dp, end = 12.dp, top = 7.dp, bottom = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                LwIcon(L.drawable.lucide_ic_radio_tower, size = 14.dp, tint = Lw.MutedFg)
                                Spacer(Modifier.width(6.dp))
                                Txt(r.removePrefix("wss://").removePrefix("ws://").trimEnd('/'), size = 13.sp, maxLines = 1)
                            }
                        }
                    }
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
            SettingsGroup { SettingsRow(stringResource(R.string.rw_delete), { deleting = true }, labelColor = Lw.Red, icon = L.drawable.lucide_ic_trash_2) }
        }
    }
    if (renaming) TextPrompt(stringResource(R.string.rw_rename), wallet?.name ?: "", onDismiss = { renaming = false }) { v ->
        renaming = false; scope.launch { runCatching { c.session.api.value.updateRemoteWallet(id, name = v) }.onFailure { context.toast(it.message ?: "") }; load() }
    }
    if (deleting) LwAlertDialog(
        onDismissRequest = { deleting = false },
        containerColor = Glass.Dialog,
        text = { Txt(stringResource(R.string.rw_delete_confirm)) },
        confirmButton = { ModalAction(stringResource(R.string.rw_delete), {
            deleting = false
            scope.launch {
                runCatching { c.session.api.value.deleteRemoteWallet(id, permanent = wallet?.status != "ACTIVE") }
                    .onSuccess { ensurePrimary(c); runCatching { c.session.refreshMe() }; nav.popBackStack() }.onFailure { context.toast(it.message ?: "") }
            }
        }, destructive = true) },
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
internal fun TextPrompt(title: String, initial: String, onDismiss: () -> Unit, saveLabel: String = title, placeholder: String? = null, maxLength: Int = 64, onSave: (String) -> Unit) {
    // Opens focused with the current text selected: type to replace, Done (or the button) to save.
    var v by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val save = { if (v.text.isNotBlank()) onSave(v.text.trim()) }
    LwAlertDialog(
        onDismissRequest = onDismiss, containerColor = Glass.Dialog,
        title = { Txt(title, size = 19.sp, weight = FontWeight.SemiBold) },
        text = {
            OutlinedTextField(
                v, { v = it.copy(text = it.text.take(maxLength)) }, Modifier.fillMaxWidth().heightIn(min = 60.dp).autoFocus().testTag("prompt-field"),
                placeholder = placeholder?.let { p -> { Txt(p, color = Lw.MutedFg) } },
                singleLine = true, colors = lwFieldColors(), shape = RoundedCornerShape(16.dp),
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 18.sp),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save() }),
            )
        },
        confirmButton = { ModalAction(saveLabel, save, Modifier.testTag("prompt-save"), enabled = v.text.isNotBlank()) },
    )
}

@Composable
private fun ForwardPlanDialog(current: ReceiveAction?, onDismiss: () -> Unit, onSave: (Int, Long, List<ForwardDestination>) -> Unit) {
    var fee by remember { mutableStateOf((current?.revision?.feeBps ?: 0).toString()) }
    var base by remember { mutableStateOf((current?.revision?.baseFeeSats?.toLong() ?: 0).toString()) }
    val rows = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(current?.revision?.destinations?.map { it.address to (it.allocationBps / 100.0).toString() } ?: listOf("" to "100")) } }
    val total = rows.sumOf { it.second.toDoubleOrNull() ?: 0.0 }
    LwAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Glass.Dialog,
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
            ModalAction(stringResource(R.string.remoteWallets_forwarding_saveAndEnable), {
                onSave(fee.toIntOrNull() ?: 0, base.toLongOrNull() ?: 0, rows.map { ForwardDestination(it.first, ((it.second.toDoubleOrNull() ?: 0.0) * 100).toInt()) })
            }, enabled = kotlin.math.abs(total - 100) < 0.001 && rows.all { it.first.contains('@') })
        },
    )
}

@Composable
private fun WebhookDialog(onDismiss: () -> Unit, onSave: (String, String, String) -> Unit) {
    var name by remember { mutableStateOf("") }
    var host by remember { mutableStateOf("") } // everything after the fixed https://
    var action by remember { mutableStateOf("RECEIVED") }
    val valid = host.contains('.') && host.none(Char::isWhitespace)
    val save = { if (valid) onSave(name.trim().ifBlank { "Webhook" }, action, "https://$host") }
    val shape = RoundedCornerShape(16.dp)
    LwAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Glass.Dialog,
        title = { Txt(stringResource(R.string.notifications_add_webhook), size = 19.sp, weight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldCaption(stringResource(R.string.notification_name))
                    OutlinedTextField(
                        name, { name = it.take(64) }, Modifier.fillMaxWidth().autoFocus().testTag("webhook-name"),
                        placeholder = { Txt("Webhook", color = Lw.MutedFg) }, singleLine = true, shape = shape, colors = lwFieldColors(),
                        leadingIcon = { LwIcon(L.drawable.lucide_ic_tag, size = 18.dp, tint = Lw.MutedFg) },
                        keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Next),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldCaption(stringResource(R.string.webhook_url_short))
                    OutlinedTextField(
                        host, { host = it.trim().removePrefix("https://").removePrefix("http://").take(512) }, Modifier.fillMaxWidth().testTag("webhook-url"),
                        placeholder = { Txt("example.com/hook", color = Lw.MutedFg) }, singleLine = true, shape = shape, colors = lwFieldColors(),
                        leadingIcon = { LwIcon(L.drawable.lucide_ic_link, size = 18.dp, tint = Lw.MutedFg) },
                        prefix = { Txt("https://", color = Lw.MutedFg) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                        keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save() }),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldCaption(stringResource(R.string.webhook_trigger))
                    TabSwitch(listOf(stringResource(R.string.webhook_on_received), stringResource(R.string.webhook_on_forwarded)), if (action == "RECEIVED") 0 else 1) {
                        action = if (it == 0) "RECEIVED" else "FORWARDED"
                    }
                }
            }
        },
        confirmButton = { ModalAction(stringResource(R.string.notifications_add_webhook), save, Modifier.testTag("webhook-save"), enabled = valid) },
    )
}

/** Small muted caption above a field, like the Add wallet form's. */
@Composable
internal fun FieldCaption(text: String) = Txt(text, Modifier.padding(start = 4.dp), size = 13.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
