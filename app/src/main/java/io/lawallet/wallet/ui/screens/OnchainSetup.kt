@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.lawallet.wallet.ui.screens

import android.content.ClipboardManager
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.BuildConfig
import io.lawallet.wallet.R
import io.lawallet.wallet.app.OnchainWallet
import io.lawallet.wallet.core.btc.Chain
import io.lawallet.wallet.core.btc.Descriptors
import io.lawallet.wallet.core.btc.Electrum
import io.lawallet.wallet.core.btc.WatchKind
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.DialogTitle
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.FieldCaption
import io.lawallet.wallet.ui.components.GlassIconButton
import io.lawallet.wallet.ui.components.GroupDivider
import io.lawallet.wallet.ui.components.LwBottomSheet
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.ModalAction
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.SettingsGroup
import io.lawallet.wallet.ui.components.SettingsHeader
import io.lawallet.wallet.ui.components.SettingsPage
import io.lawallet.wallet.ui.components.SettingsRow
import io.lawallet.wallet.ui.components.SettingsSwitchRow
import io.lawallet.wallet.ui.components.TextPrompt
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.lwFieldColors
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.theme.Lw
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.R as L

/*
 * Setting up onchain: a multisig vault (this app as coordinator, Sparrow-style: cosigner keys in, a wsh(sortedmulti)
 * descriptor out) and the onchain settings (Electrum server per network, signet).
 */

/** A cosigner in the vault being built: its key with origin, and where it came from (a seed here, or pasted). */
private data class Cosigner(val key: String, val seedId: String? = null, val label: String? = null)

@Composable
fun CreateMultisigScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val wallets by c.onchain.wallets.collectAsState()
    val defaultName = stringResource(R.string.onchain_ms_defaultName)
    var name by rememberSaveable { mutableStateOf("") }
    var chain by rememberSaveable { mutableStateOf(Chain.MAINNET) }
    val keys = remember { mutableStateListOf<Cosigner>() }
    var threshold by rememberSaveable { mutableStateOf(2) }
    var addingKey by remember { mutableStateOf(false) }
    var pickingSeed by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val seeds = wallets.filter { it.seed && it.chain == chain && it.script != null }
    val n = keys.size
    val m = threshold.coerceIn(1, n.coerceAtLeast(1))
    val dup = stringResource(R.string.onchain_ms_keyDup)
    val unreadable = stringResource(R.string.onchain_import_invalid)
    val onePhoneKey = stringResource(R.string.onchain_ms_onePhoneKey)
    fun add(k: Cosigner): Boolean {
        if (keys.any { it.key.substringAfter(']') == k.key.substringAfter(']') }) { error = dup; return false }
        keys.add(k); error = null
        if (keys.size == 2 && threshold > 2) threshold = 2
        return true
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        SettingsHeader(stringResource(R.string.onchain_ms_title), { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            NameField(name, { name = it }, defaultName)
            NetworkPicker(chain) { if (it != chain) { chain = it; keys.clear() } }
            SettingsGroup(stringResource(R.string.onchain_ms_cosigners)) {
                keys.forEachIndexed { i, k ->
                    if (i > 0) GroupDivider()
                    val fp = Regex("^\\[([0-9a-fA-F]{8})").find(k.key)?.groupValues?.get(1)
                    SettingsRow(
                        k.label ?: stringResource(R.string.onchain_ms_cosignerN, i + 1), null, Modifier.testTag("ms-key-$i"),
                        icon = if (k.seedId != null) L.drawable.lucide_ic_smartphone else L.drawable.lucide_ic_key_round,
                        subtitle = (fp?.let { "$it · " } ?: "") + k.key.substringAfter(']').let { it.take(8) + "…" + it.takeLast(6) },
                    ) { GlassIconButton(L.drawable.lucide_ic_x, { keys.removeAt(i) }, size = 32.dp) }
                }
                if (keys.isNotEmpty()) GroupDivider()
                SettingsRow(stringResource(R.string.onchain_ms_addKey), { addingKey = true }, Modifier.testTag("ms-add-key"), icon = L.drawable.lucide_ic_plus)
                if (seeds.isNotEmpty() && keys.none { it.seedId != null }) {
                    GroupDivider()
                    SettingsRow(stringResource(R.string.onchain_ms_thisPhone), { pickingSeed = true }, Modifier.testTag("ms-add-phone"), icon = L.drawable.lucide_ic_smartphone, subtitle = stringResource(R.string.onchain_ms_thisPhone_body))
                }
            }
            if (seeds.isEmpty()) MutedText(stringResource(R.string.onchain_ms_needSeed), Modifier.padding(horizontal = 4.dp), size = 12.sp)
            if (n >= 2) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldCaption(stringResource(R.string.onchain_ms_quorum))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(L.drawable.lucide_ic_minus, { threshold = (m - 1).coerceAtLeast(1) }, Modifier.testTag("ms-minus"))
                    Txt(stringResource(R.string.onchain_ms_of, m, n), Modifier.weight(1f), size = 22.sp, weight = FontWeight.SemiBold, align = androidx.compose.ui.text.style.TextAlign.Center)
                    GlassIconButton(L.drawable.lucide_ic_plus, { threshold = (m + 1).coerceAtMost(n) }, Modifier.testTag("ms-plus"))
                }
                MutedText(stringResource(R.string.onchain_ms_quorum_body, m, n, n - m), Modifier.padding(horizontal = 4.dp), size = 12.sp)
            }
            Row(Modifier.padding(horizontal = 4.dp)) {
                LwIcon(L.drawable.lucide_ic_info, Modifier.padding(top = 2.dp), size = 15.dp, tint = Lw.MutedFg)
                Spacer(Modifier.width(8.dp))
                MutedText(stringResource(R.string.onchain_ms_importHint), size = 12.sp)
            }
            ErrorText(error)
        }
        LwButton(stringResource(R.string.onchain_ms_create), {
            busy = true; error = null
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        val d = Descriptors.multisig(m, keys.map { it.key }, chain)
                        c.onchain.add(OnchainWallet(name = name.trim().ifBlank { defaultName }, watch = WatchKind.MULTISIG, source = d.external, chain = chain, descriptors = d, signer = keys.firstNotNullOfOrNull { it.seedId }))
                    }
                }.onSuccess { w ->
                    // To the vault: its descriptor is what everyone needs to back up.
                    nav.navigate(R0.onchainWallet(w.id)) { popUpTo(R0.HOME) }
                }.onFailure { error = unreadable }
                busy = false
            }
        }, Modifier.fillMaxWidth().padding(16.dp).testTag("ms-create"), BtnVariant.Theme, enabled = n >= 2 && !busy, loading = busy, icon = L.drawable.lucide_ic_users)
    }
    if (pickingSeed) LwBottomSheet({ pickingSeed = false }, "ms-seed-sheet") {
        seeds.forEach { s ->
            SettingsRow(s.name, {
                pickingSeed = false
                scope.launch {
                    val key = withContext(Dispatchers.IO) { c.onchain.secret(s.id)?.let { (words, pass) -> Descriptors.cosigner(words, pass, chain) } }
                    if (key == null) error = unreadable
                    else if (keys.any { it.seedId != null }) error = onePhoneKey
                    else add(Cosigner(key, s.id, s.name))
                }
            }, leading = { OnchainMark(s, 36) }, subtitle = onchainSubtitle(s))
        }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
    if (addingKey) CosignerKeySheet(chain, { addingKey = false }) { key -> if (add(Cosigner(key))) addingKey = false }
}

/** A cosigner's key, pasted or scanned (`[fingerprint/48'/c'/0'/2']xpub`, or a bare xpub/Zpub/Vpub). */
@Composable
private fun CosignerKeySheet(chain: Chain, onDismiss: () -> Unit, onKey: (String) -> Unit) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    val parsed = remember(text) { Descriptors.cosignerKey(text) }
    val wrongNet = parsed != null && parsed.second != chain
    LwBottomSheet(onDismiss, "ms-key-sheet") {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp).imePadding(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            DialogTitle(stringResource(R.string.onchain_ms_addKey))
            MutedText(stringResource(R.string.onchain_ms_keyBody), size = 13.sp)
            if (scanning) QrCamera({ scanning = false }) { raw -> if (scanning) { scanning = false; text = raw.trim() } }
            OutlinedTextField(
                text, { text = it.trim() }, Modifier.fillMaxWidth().testTag("ms-key-field"),
                placeholder = { Txt(stringResource(R.string.onchain_ms_keyField), color = Lw.MutedFg, size = 13.sp) },
                minLines = 3, maxLines = 5, colors = lwFieldColors(), shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 13.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LwButton(stringResource(R.string.onchain_import_paste), {
                    context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let { text = it }
                }, Modifier.weight(1f), BtnVariant.Secondary, height = 42.dp, icon = L.drawable.lucide_ic_clipboard_paste)
                LwButton(stringResource(if (scanning) R.string.common_cancel else R.string.shared_nav_scan), { scanning = !scanning }, Modifier.weight(1f),
                    BtnVariant.Secondary, height = 42.dp, icon = if (scanning) L.drawable.lucide_ic_x else L.drawable.lucide_ic_scan_qr_code)
            }
            if (text.length > 20 && parsed == null) ErrorText(stringResource(R.string.onchain_ms_keyInvalid))
            if (wrongNet) ErrorText(stringResource(R.string.onchain_ms_wrongNet))
            ModalAction(stringResource(R.string.onchain_ms_addKey), { parsed?.let { onKey(it.first) } }, Modifier.testTag("ms-key-add"), enabled = parsed != null && !wrongNet)
        }
    }
}

@Composable
fun OnchainSettingsScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val wallets by c.onchain.wallets.collectAsState()
    val signet by c.onchain.signet.collectAsState()
    var editing by remember { mutableStateOf<Chain?>(null) }
    val badUrl = stringResource(if (BuildConfig.DEBUG) R.string.onchain_settings_invalidUrlDebug else R.string.onchain_settings_invalidUrl)
    val chains = Chain.entries.filter { it == Chain.MAINNET || signet || wallets.any { w -> w.chain == it } }
    SettingsPage(stringResource(R.string.onchain_settings_title)) {
        SettingsGroup(stringResource(R.string.onchain_settings_server)) {
            chains.forEachIndexed { i, chain ->
                if (i > 0) GroupDivider()
                val url by c.onchain.electrum(chain).collectAsState()
                val online by c.onchainWallets.connected(chain).collectAsState()
                val tip by c.onchainWallets.tip(chain).collectAsState()
                SettingsRow(
                    stringResource(if (chain == Chain.MAINNET) R.string.onchain_settings_mainnet else R.string.onchain_signet), { editing = chain }, Modifier.testTag("electrum-${chain.name}"),
                    icon = L.drawable.lucide_ic_server, subtitle = url.removePrefix("ssl://") + " · " + (if (online && tip != null) stringResource(R.string.onchain_settings_ok, tip!!) else stringResource(R.string.onchain_server_offline)),
                )
                if (url != chain.electrum) {
                    GroupDivider()
                    SettingsRow(stringResource(R.string.onchain_settings_reset, chain.electrum.removePrefix("ssl://")), { c.onchain.setElectrum(chain, null) }, Modifier.testTag("electrum-reset-${chain.name}"), icon = L.drawable.lucide_ic_rotate_ccw)
                }
            }
        }
        MutedText(stringResource(R.string.onchain_settings_serverBody), Modifier.padding(horizontal = 6.dp), size = 12.sp)
        SettingsGroup {
            SettingsSwitchRow(stringResource(R.string.onchain_settings_signet), signet, { c.onchain.setSignet(it) }, Modifier.testTag("onchain-signet-toggle"), icon = L.drawable.lucide_ic_flask_conical)
        }
        MutedText(stringResource(R.string.onchain_settings_signetBody), Modifier.padding(horizontal = 6.dp), size = 12.sp)
    }
    editing?.let { chain ->
        val current by c.onchain.electrum(chain).collectAsState()
        TextPrompt(stringResource(R.string.onchain_settings_server), current, { editing = null }, saveLabel = stringResource(R.string.onchain_settings_save), placeholder = chain.electrum, maxLength = 200) { url ->
            // TLS only, except debug builds against a local server (tcp://10.0.2.2:50001 from the emulator).
            val ok = runCatching { Electrum.parse(url) }.getOrNull()?.let { it.first == "ssl" || BuildConfig.DEBUG } == true
            if (!ok) context.toast(badUrl) else { c.onchain.setElectrum(chain, url.takeIf { it != chain.electrum }); editing = null }
        }
    }
}
