package io.lawallet.wallet.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextAlign
import kotlinx.coroutines.delay
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

/** The login steps after the signer is reached; shown full-screen as soon as one starts. */
private enum class BunkerStep { Connect, Key, Approve, SignIn, Done }

/**
 * NIP-46 login. Tab 0: paste or scan the signer's bunker:// (camera on by default).
 * Tab 1: show our nostrconnect:// for the signer to scan, and wait for it.
 * Once a step starts (Connect tapped, or the signer answered the QR) the form gives way to [BunkerProgress].
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
    var step by remember { mutableStateOf<BunkerStep?>(null) }
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
        error = null; authOpened = false
        val open = client.onAuthUrl
        client.onAuthUrl = { open(it); authOpened = true }
        client.onProgress = { method, done ->
            when {
                method == "connect" && !done -> step = BunkerStep.Connect
                method == "get_public_key" && !done -> step = BunkerStep.Key
                method == "sign_event" -> step = if (done) BunkerStep.SignIn else BunkerStep.Approve
            }
        }
        job = scope.launch {
            var handedOff = false
            try {
                handshake()
                step = BunkerStep.Key
                c.session.loginWithBunker(Nip46Signer(client, client.getPublicKey()))
                handedOff = true
                step = BunkerStep.Done
                delay(900) // let the success state land before leaving
                nav.afterAuth(c)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (step == null) step = BunkerStep.Connect
                error = e.message ?: failed
            } finally {
                if (!handedOff) client.close()
            }
        }
    }

    fun connectBunker(raw: String) {
        val uri = runCatching { BunkerUri.parse(raw) }.getOrNull()
        if (uri == null) { error = invalid; return }
        scanning = false
        val client = c.session.newBunkerClient(uri.relays).also { it.remotePubkey = uri.remotePubkey }
        step = BunkerStep.Connect
        start(client) { client.connect(uri.secret) }
    }

    /** Back to the form: stop whatever is running; the QR tab gets a fresh code. */
    fun reset() {
        job?.cancel(); job = null
        step = null; error = null
        if (tab == 1) attempt++
    }

    // Show-QR tab: a fresh client key + secret per attempt, listening until a signer answers.
    LaunchedEffect(tab, attempt) {
        job?.cancel(); job = null
        step = null; error = null
        if (tab != 1) return@LaunchedEffect
        val keys = Keys.generate()
        val secret = Nip46Client.newSecret()
        connectUri = NostrConnectUri(keys.pubkey, relays, secret, Nip46Client.PERMS, "LaWallet").toString()
        val client = c.session.newBunkerClient(relays, keys)
        start(client) { client.awaitConnect(secret) }
    }

    androidx.activity.compose.BackHandler(step != null && step != BunkerStep.Done) { reset() }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(stringResource(R.string.bunker_title), { if (step != null && step != BunkerStep.Done) reset() else nav.popBackStack() }, stringResource(R.string.common_back))
        AnimatedContent(
            step != null, Modifier.weight(1f),
            transitionSpec = { (fadeIn(tween(300)) + slideInVertically(tween(300)) { it / 12 }) togetherWith fadeOut(tween(200)) },
            label = "bunker-screen",
        ) { inProgress ->
            if (inProgress) BunkerProgress(step ?: BunkerStep.Connect, error, authOpened, onRetry = {
                if (tab == 0) connectBunker(input) else reset()
            }, onCancel = { reset() })
            else Column(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    MutedText(stringResource(R.string.bunker_subtitle))
                    TabSwitch(listOf(stringResource(R.string.bunker_tab_paste), stringResource(R.string.bunker_tab_show)), tab) { tab = it; scanning = it == 0 }
                    if (tab == 0) {
                        if (scanning) QrCamera(false, { error = it; scanning = false }) { raw ->
                            if (!scanning) return@QrCamera
                            scanning = false; input = raw.trim()
                            connectBunker(input)
                        }
                        OutlinedTextField(
                            input, { input = it.trim(); error = null },
                            Modifier.fillMaxWidth().testTag("bunker-input"),
                            label = { Txt(stringResource(R.string.bunker_uri_label)) },
                            placeholder = { Txt("bunker://…", color = Lw.MutedFg) }, colors = lwFieldColors(), shape = RoundedCornerShape(12.dp),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), maxLines = 3,
                            trailingIcon = {
                                Box(Modifier.size(40.dp).clickable { scanning = !scanning; error = null }.testTag("bunker-scan"), contentAlignment = Alignment.Center) {
                                    LwIcon(if (scanning) L.drawable.lucide_ic_x else L.drawable.lucide_ic_scan_qr_code, size = 20.dp, tint = Lw.MutedFg, contentDescription = stringResource(R.string.shared_nav_scan))
                                }
                            },
                        )
                        ErrorText(error, Modifier.fillMaxWidth())
                    } else connectUri?.let { uri ->
                        QrDisplay(uri, stringResource(R.string.bunker_show_caption), modifier = Modifier.fillMaxWidth())
                        // Amber & co. on this same phone register nostrconnect://; hand the URI over directly.
                        val intent = remember(uri) { Intent(Intent.ACTION_VIEW, Uri.parse(uri)) }
                        if (remember(uri) { intent.resolveActivity(context.packageManager) != null }) LwButton(
                            stringResource(R.string.bunker_open_signer), { runCatching { context.startActivity(intent) } },
                            Modifier.fillMaxWidth(), BtnVariant.Secondary, icon = L.drawable.lucide_ic_smartphone,
                        )
                        WaitingRow()
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
                }
                if (tab == 0) LwButton(
                    stringResource(R.string.bunker_connect), { connectBunker(input) },
                    Modifier.fillMaxWidth().padding(16.dp).testTag("bunker-connect"), BtnVariant.Theme,
                    enabled = input.isNotBlank(), icon = L.drawable.lucide_ic_plug,
                )
            }
        }
    }
}

/** Two-segment glass switch; the selected segment takes the brand tint. */
@Composable
internal fun TabSwitch(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
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

/** QR tab, before anything is detected: a quiet "waiting for your signer" line. */
@Composable
private fun WaitingRow() {
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), GlassLevel.Thin).padding(16.dp).testTag("bunker-status"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) { Spinner(22.dp) }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Txt(stringResource(R.string.bunker_waiting), size = 15.sp, weight = FontWeight.Medium)
            MutedText(stringResource(R.string.bunker_waiting_scan), size = 13.sp)
        }
    }
}

/** Full-screen login progress: pulsing hero, current step title, and the step list with spinners and checks. */
@Composable
private fun BunkerProgress(step: BunkerStep, error: String?, authOpened: Boolean, onRetry: () -> Unit, onCancel: () -> Unit) {
    val brand = LocalBrand.current
    val failed = error != null
    val done = step == BunkerStep.Done
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).testTag("bunker-progress"), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(24.dp))
        // Hero: rings pulse outward while working; the icon swaps to a check (or an x) with a pop.
        val pulse = rememberInfiniteTransition(label = "pulse")
        val t by pulse.animateFloat(0f, 1f, infiniteRepeatable(tween(1800, easing = LinearEasing)), label = "t")
        val accent = when { failed -> Lw.Red; done -> Lw.Green; else -> brand.t400 }
        Box(Modifier.size(180.dp), contentAlignment = Alignment.Center) {
            if (!failed && !done) listOf(0f, 0.5f).forEach { offset ->
                val p = (t + offset) % 1f
                Box(Modifier.size(96.dp).graphicsLayer { scaleX = 1f + p * 0.85f; scaleY = 1f + p * 0.85f; alpha = (1f - p) * 0.5f }.border(2.dp, accent, CircleShape))
            }
            AnimatedContent(
                when { failed -> 2; done -> 1; else -> 0 },
                transitionSpec = { (scaleIn(spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMediumLow), initialScale = 0.6f) + fadeIn()) togetherWith fadeOut(tween(150)) },
                label = "hero",
            ) { state ->
                Box(Modifier.size(96.dp).glass(CircleShape, GlassLevel.Strong, tint = accent.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) {
                    LwIcon(when (state) { 2 -> L.drawable.lucide_ic_x; 1 -> L.drawable.lucide_ic_check; else -> L.drawable.lucide_ic_radio_tower }, size = 40.dp, tint = if (state == 0) Lw.Foreground else accent)
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        AnimatedContent(
            if (failed) -1 else step.ordinal,
            transitionSpec = { (fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 3 }) togetherWith (fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 3 }) },
            label = "title",
        ) { s ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Txt(stringResource(if (s < 0) R.string.bunker_failed else stepTitle(BunkerStep.entries[s])), size = 22.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
                val hint = when {
                    s < 0 -> null
                    authOpened && s <= BunkerStep.Approve.ordinal -> stringResource(R.string.bunker_auth_url)
                    else -> stepHint(BunkerStep.entries[s])?.let { stringResource(it) }
                }
                if (hint != null) MutedText(hint, align = TextAlign.Center)
            }
        }
        if (error != null) { Spacer(Modifier.height(8.dp)); ErrorText(error, Modifier.fillMaxWidth()) }
        Spacer(Modifier.height(24.dp))
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), GlassLevel.Thin).padding(vertical = 6.dp)) {
            listOf(BunkerStep.Connect, BunkerStep.Key, BunkerStep.Approve, BunkerStep.SignIn).forEach { s ->
                val state = when {
                    step.ordinal > s.ordinal -> StepState.Done
                    step == s -> if (failed) StepState.Failed else StepState.Active
                    else -> StepState.Pending
                }
                StepRow(stringResource(stepTitle(s)), state)
            }
        }
        Spacer(Modifier.weight(1f).height(24.dp))
        if (failed) {
            LwButton(stringResource(R.string.bunker_retry), onRetry, Modifier.fillMaxWidth().testTag("bunker-retry"), BtnVariant.Theme, icon = L.drawable.lucide_ic_refresh_cw)
            Spacer(Modifier.height(8.dp))
            LwButton(stringResource(R.string.common_back), onCancel, Modifier.fillMaxWidth(), BtnVariant.Ghost)
        } else if (!done) LwButton(stringResource(R.string.common_cancel), onCancel, Modifier.fillMaxWidth().testTag("bunker-cancel"), BtnVariant.Ghost)
        Spacer(Modifier.height(16.dp))
    }
}

private enum class StepState { Pending, Active, Done, Failed }

@Composable
private fun StepRow(label: String, state: StepState) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(26.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(state, transitionSpec = { (scaleIn(spring(dampingRatio = 0.55f), initialScale = 0.4f) + fadeIn()) togetherWith fadeOut(tween(120)) }, label = "step") { st ->
                when (st) {
                    StepState.Active -> Spinner(20.dp)
                    StepState.Done -> Box(Modifier.size(24.dp).background(Lw.Green.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_check, size = 14.dp, tint = Lw.Green) }
                    StepState.Failed -> Box(Modifier.size(24.dp).background(Lw.Red.copy(alpha = 0.22f), CircleShape), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_x, size = 14.dp, tint = Lw.Red) }
                    StepState.Pending -> Box(Modifier.size(10.dp).border(1.5.dp, Lw.MutedFg.copy(alpha = 0.5f), CircleShape))
                }
            }
        }
        val color by animateColorAsState(if (state == StepState.Pending) Lw.MutedFg else Lw.Foreground, label = "label")
        Txt(label, Modifier.weight(1f), size = 15.sp, weight = if (state == StepState.Active) FontWeight.Medium else FontWeight.Normal, color = color)
    }
}

private fun stepTitle(s: BunkerStep) = when (s) {
    BunkerStep.Connect -> R.string.bunker_step_connect
    BunkerStep.Key -> R.string.bunker_step_key
    BunkerStep.Approve -> R.string.bunker_step_approve
    BunkerStep.SignIn -> R.string.bunker_step_signin
    BunkerStep.Done -> R.string.bunker_done
}

private fun stepHint(s: BunkerStep) = when (s) {
    BunkerStep.Connect -> R.string.bunker_hint_connect
    BunkerStep.Approve -> R.string.bunker_hint_approve
    else -> null
}
