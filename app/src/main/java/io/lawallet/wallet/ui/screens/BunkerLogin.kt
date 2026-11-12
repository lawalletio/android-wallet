package io.lawallet.wallet.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.core.auth.BunkerUri
import io.lawallet.wallet.core.auth.Nip46Client
import io.lawallet.wallet.core.auth.Nip46Signer
import io.lawallet.wallet.core.auth.NostrConnectUri
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.afterAuth
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.QrDisplay
import io.lawallet.wallet.ui.components.SettingsGroup
import io.lawallet.wallet.ui.components.SettingsHeader
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

private enum class BunkerPhase { Idle, Waiting, SigningIn, Error }

/**
 * NIP-46 login. Tab 0: paste or scan the signer's bunker:// (camera on by default).
 * Tab 1: show our nostrconnect:// for the signer to scan, and wait for it.
 */
@Composable
fun BunkerLoginScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var input by rememberSaveable { mutableStateOf("") }
    var scanning by remember { mutableStateOf(true) }
    var phase by remember { mutableStateOf(BunkerPhase.Idle) }
    var error by remember { mutableStateOf<String?>(null) }
    var authOpened by remember { mutableStateOf(false) }
    var job by remember { mutableStateOf<Job?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var connectUri by remember { mutableStateOf<String?>(null) }
    // Some signers (Amber) answer only on their own configured relays, so the QR's list is editable and remembered.
    var relays by remember { mutableStateOf(c.prefs.bunkerRelays ?: Nip46Client.DEFAULT_RELAYS) }
    var newRelay by rememberSaveable { mutableStateOf("") }
    fun setRelays(list: List<String>) { relays = list; c.prefs.bunkerRelays = list; attempt++ }
    val failed = stringResource(R.string.auth_loginFailed)
    val invalid = stringResource(R.string.bunker_invalid)

    /** Runs [handshake], then get_public_key and the app login; closes the client unless the session took it. */
    fun start(client: Nip46Client, handshake: suspend () -> Unit) {
        job?.cancel()
        phase = BunkerPhase.Waiting; error = null; authOpened = false
        val open = client.onAuthUrl
        client.onAuthUrl = { open(it); authOpened = true }
        job = scope.launch {
            var handedOff = false
            try {
                handshake()
                phase = BunkerPhase.SigningIn
                c.session.loginWithBunker(Nip46Signer(client, client.getPublicKey()))
                handedOff = true
                nav.afterAuth(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                phase = BunkerPhase.Error; error = e.message ?: failed
            } finally {
                if (!handedOff) client.close()
            }
        }
    }

    fun connectBunker(raw: String) {
        val uri = runCatching { BunkerUri.parse(raw) }.getOrNull()
        if (uri == null) { phase = BunkerPhase.Error; error = invalid; return }
        scanning = false
        val client = c.session.newBunkerClient(uri.relays).also { it.remotePubkey = uri.remotePubkey }
        start(client) { client.connect(uri.secret) }
    }

    // Show-QR tab: a fresh client key + secret per attempt, listening until a signer answers.
    LaunchedEffect(tab, attempt) {
        job?.cancel(); job = null
        phase = BunkerPhase.Idle; error = null
        if (tab != 1) return@LaunchedEffect
        val keys = Keys.generate()
        val secret = Nip46Client.newSecret()
        connectUri = NostrConnectUri(keys.pubkey, relays, secret, Nip46Client.PERMS, "LaWallet").toString()
        val client = c.session.newBunkerClient(relays, keys)
        start(client) { client.awaitConnect(secret) }
    }

    val busy = phase == BunkerPhase.Waiting || phase == BunkerPhase.SigningIn
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(stringResource(R.string.bunker_title), { nav.popBackStack() }, stringResource(R.string.common_back))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MutedText(stringResource(R.string.bunker_subtitle))
            TabSwitch(listOf(stringResource(R.string.bunker_tab_paste), stringResource(R.string.bunker_tab_show)), tab) { tab = it; scanning = it == 0 }
            if (tab == 0) {
                if (scanning && !busy) QrCamera(false, { error = it; scanning = false }) { raw ->
                    if (!scanning) return@QrCamera
                    scanning = false; input = raw.trim()
                    connectBunker(input)
                }
                OutlinedTextField(
                    input, { input = it.trim(); if (phase == BunkerPhase.Error) { phase = BunkerPhase.Idle; error = null } },
                    Modifier.fillMaxWidth().testTag("bunker-input"), enabled = !busy,
                    label = { Txt(stringResource(R.string.bunker_uri_label)) },
                    placeholder = { Txt("bunker://…", color = Lw.MutedFg) }, colors = lwFieldColors(), shape = RoundedCornerShape(12.dp),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), maxLines = 3,
                    trailingIcon = {
                        Box(Modifier.size(40.dp).clickable(enabled = !busy) { scanning = !scanning; error = null }.testTag("bunker-scan"), contentAlignment = Alignment.Center) {
                            LwIcon(if (scanning) L.drawable.lucide_ic_x else L.drawable.lucide_ic_scan_qr_code, size = 20.dp, tint = Lw.MutedFg, contentDescription = stringResource(R.string.shared_nav_scan))
                        }
                    },
                )
            } else connectUri?.let { uri ->
                QrDisplay(uri, stringResource(R.string.bunker_show_caption), modifier = Modifier.fillMaxWidth())
                // Amber & co. on this same phone register nostrconnect://; hand the URI over directly.
                val intent = remember(uri) { Intent(Intent.ACTION_VIEW, Uri.parse(uri)) }
                if (remember(uri) { intent.resolveActivity(context.packageManager) != null }) LwButton(
                    stringResource(R.string.bunker_open_signer), { runCatching { context.startActivity(intent) } },
                    Modifier.fillMaxWidth(), BtnVariant.Secondary, icon = L.drawable.lucide_ic_smartphone,
                )
                SettingsGroup(stringResource(R.string.bunker_relays)) {
                    relays.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = Color.White.copy(alpha = 0.06f))
                        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Txt(r.removePrefix("wss://"), Modifier.weight(1f).padding(vertical = 14.dp), size = 14.sp, maxLines = 1)
                            if (relays.size > 1) Box(Modifier.size(44.dp).clickable { setRelays(relays - r) }.testTag("bunker-relay-remove"), contentAlignment = Alignment.Center) {
                                LwIcon(L.drawable.lucide_ic_x, size = 18.dp, tint = Lw.MutedFg, contentDescription = stringResource(R.string.bunker_relay_remove))
                            }
                        }
                    }
                }
                val relayUrl = newRelay.trim().trimEnd('/').let { if (it.isNotEmpty() && "://" !in it) "wss://$it" else it }
                val relayValid = Regex("^wss?://[^\\s/?#]+\\.[^\\s?#]+$").matches(relayUrl) && relayUrl !in relays
                OutlinedTextField(
                    newRelay, { newRelay = it.trim() }, Modifier.fillMaxWidth().testTag("bunker-relay-input"),
                    placeholder = { Txt(stringResource(R.string.bunker_relay_add), color = Lw.MutedFg) }, singleLine = true,
                    colors = lwFieldColors(), shape = RoundedCornerShape(12.dp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        Box(Modifier.size(40.dp).clickable(enabled = relayValid) { setRelays(relays + relayUrl); newRelay = "" }.testTag("bunker-relay-add"), contentAlignment = Alignment.Center) {
                            LwIcon(L.drawable.lucide_ic_plus, size = 20.dp, tint = if (relayValid) Lw.Foreground else Lw.MutedFg, contentDescription = stringResource(R.string.bunker_relay_add))
                        }
                    },
                )
                MutedText(stringResource(R.string.bunker_relays_hint), size = 13.sp)
            }
            StatusCard(phase, authOpened, tab)
            ErrorText(error, Modifier.fillMaxWidth())
        }
        when {
            tab == 0 -> LwButton(
                stringResource(if (phase == BunkerPhase.Error) R.string.bunker_retry else R.string.bunker_connect),
                { connectBunker(input) },
                Modifier.fillMaxWidth().padding(16.dp).testTag("bunker-connect"), BtnVariant.Theme,
                enabled = input.isNotBlank(), loading = busy, icon = L.drawable.lucide_ic_plug,
            )
            phase == BunkerPhase.Error -> LwButton(
                stringResource(R.string.bunker_retry), { attempt++ },
                Modifier.fillMaxWidth().padding(16.dp).testTag("bunker-retry"), BtnVariant.Theme, icon = L.drawable.lucide_ic_refresh_cw,
            )
        }
    }
}

/** Two-segment glass switch; the selected segment takes the brand tint. */
@Composable
private fun TabSwitch(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().glass(CircleShape, GlassLevel.Thin).padding(4.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(40.dp)
                    .then(if (on) Modifier.glass(CircleShape, GlassLevel.Strong, tint = LocalBrand.current.t400.copy(alpha = 0.25f)) else Modifier)
                    .clickable { onSelect(i) }.testTag("bunker-tab-$i"),
                contentAlignment = Alignment.Center,
            ) { Txt(label, size = 14.sp, weight = FontWeight.Medium, color = if (on) Lw.Foreground else Lw.MutedFg) }
        }
    }
}

/** Waiting / signing-in feedback; nothing while idle or failed (the error text covers that). */
@Composable
private fun StatusCard(phase: BunkerPhase, authOpened: Boolean, tab: Int) {
    if (phase != BunkerPhase.Waiting && phase != BunkerPhase.SigningIn) return
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), GlassLevel.Thin).padding(16.dp).testTag("bunker-status"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (phase == BunkerPhase.SigningIn) Box(Modifier.size(28.dp).glass(CircleShape, GlassLevel.Thin, tint = Lw.Green.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
            LwIcon(L.drawable.lucide_ic_check, size = 16.dp, tint = Lw.Green)
        } else Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Spinner(22.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(stringResource(if (phase == BunkerPhase.SigningIn) R.string.bunker_connected else R.string.bunker_waiting), size = 15.sp, weight = FontWeight.Medium)
            MutedText(
                stringResource(when {
                    phase == BunkerPhase.SigningIn -> R.string.bunker_signing_in
                    authOpened -> R.string.bunker_auth_url
                    tab == 1 -> R.string.bunker_waiting_scan
                    else -> R.string.bunker_waiting_approve
                }),
                size = 13.sp,
            )
        }
    }
}
