package io.lawallet.wallet.ui.screens

import io.lawallet.wallet.ui.components.lwFieldColors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.lawallet.wallet.R
import io.lawallet.wallet.core.nostr.Bech32
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.nostr.Nip04
import io.lawallet.wallet.core.nostr.NostrEvent
import io.lawallet.wallet.core.nostr.toHex
import io.lawallet.wallet.core.nwc.RelayPool
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.SettingsHeader
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.theme.Lw
import kotlinx.coroutines.launch

private const val FEEDBACK_NPUB = "npub1ek4262k6nv3l9axh403w2e66pzl2pqhhnjrhgv2wpzx2akak285scxv4pd"
private val FEEDBACK_RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net")

/** NIP-04 DM (kind 4) to the team, signed with the user's key. Succeeds if any relay accepts it. */
private suspend fun sendFeedback(c: io.lawallet.wallet.app.AppContainer, message: String) {
    val keys = Keys.fromHex(c.session.secretHex() ?: error("no local key"))
    val to = Bech32.decodeBytes(FEEDBACK_NPUB).second.toHex()
    val event = NostrEvent.sign(keys, 4, Nip04.encrypt(message, keys, to), listOf(listOf("p", to)))
    RelayPool.publishAny(c.http, FEEDBACK_RELAYS, event)
}

@Composable
fun FeedbackScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val sent = stringResource(R.string.feedback_sent)
    val failed = stringResource(R.string.feedback_failed)
    Column(Modifier.fillMaxSize().imePadding()) {
        SettingsHeader(stringResource(R.string.feedback_title), { nav.popBackStack() })
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MutedText(stringResource(R.string.feedback_description))
            OutlinedTextField(text, { text = it; error = null }, Modifier.fillMaxWidth().weight(1f).testTag("feedback-input"), enabled = !loading, placeholder = { Txt(stringResource(R.string.feedback_placeholder), color = Lw.MutedFg) }, shape = RoundedCornerShape(12.dp), colors = lwFieldColors())
            ErrorText(error, Modifier.fillMaxWidth())
        }
        LwButton(stringResource(R.string.feedback_send), {
            loading = true; error = null
            scope.launch {
                runCatching { sendFeedback(c, text.trim()) }
                    .onSuccess { context.toast(sent); nav.popBackStack() }
                    .onFailure { error = failed }
                loading = false
            }
        }, Modifier.fillMaxWidth().padding(16.dp).testTag("feedback-send"), BtnVariant.Theme, enabled = text.isNotBlank(), loading = loading)
    }
}
