@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.lawallet.wallet.ui.screens

import io.lawallet.wallet.ui.components.LoadingBox
import io.lawallet.wallet.ui.components.AmountDisplay
import io.lawallet.wallet.ui.components.FieldCaption
import io.lawallet.wallet.ui.components.Pill
import io.lawallet.wallet.ui.components.TabSwitch
import io.lawallet.wallet.ui.components.TextPrompt
import io.lawallet.wallet.ui.components.lwFieldColors
import io.lawallet.wallet.ui.components.SettingsSwitchRow
import android.content.ClipboardManager
import android.os.Build
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import io.lawallet.wallet.R
import androidx.compose.foundation.layout.navigationBarsPadding
import io.lawallet.wallet.ui.components.DialogTitle
import io.lawallet.wallet.ui.components.QrDisplay
import io.lawallet.wallet.ui.components.LwBottomSheet
import io.lawallet.wallet.app.AppContainer
import io.lawallet.wallet.app.OnchainWallet
import io.lawallet.wallet.core.btc.Bip39
import io.lawallet.wallet.core.btc.BtcImport
import io.lawallet.wallet.core.btc.BtcInput
import io.lawallet.wallet.core.btc.Chain
import io.lawallet.wallet.core.btc.Descriptors
import io.lawallet.wallet.core.btc.PhraseProblem
import io.lawallet.wallet.core.btc.ScriptType
import io.lawallet.wallet.core.btc.Unsupported
import io.lawallet.wallet.core.btc.WatchKind
import io.lawallet.wallet.core.fx.Currencies
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
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.copyToClipboard
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.R as L

/*
 * Onchain wallets, BlueWallet-style: Add (create or import) → a new wallet shows its recovery phrase and
 * asks for three of its words back; Import takes whatever the user has (phrase, xpub/ypub/zpub, descriptor,
 * address) and says what it found as they type. Standard BIP39 + BIP44/49/84/86, so wallets move freely
 * between LaWallet and other wallets. UI only: no addresses are derived and nothing is spent yet.
 */

/** Back to Home with the Onchain deck open, showing what just changed. */
private fun NavHostController.backToOnchain(c: AppContainer) {
    c.flow.revealOnchain = true
    if (!popBackStack(R0.HOME, inclusive = false)) goHome()
}

@Composable
fun AddOnchainWalletScreen() {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(stringResource(R.string.onchain_add_title), { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OptionCard(L.drawable.lucide_ic_sparkles, stringResource(R.string.onchain_add_create_title), stringResource(R.string.onchain_add_create_body), "onchain-add-create") { nav.navigate(R0.ONCHAIN_CREATE) }
            OptionCard(L.drawable.lucide_ic_import, stringResource(R.string.onchain_add_import_title), stringResource(R.string.onchain_add_import_body), "onchain-add-import") { nav.navigate(R0.ONCHAIN_IMPORT) }
            OptionCard(L.drawable.lucide_ic_users, stringResource(R.string.onchain_add_multisig_title), stringResource(R.string.onchain_add_multisig_body), "onchain-add-multisig") { nav.navigate(R0.ONCHAIN_MULTISIG) }
            Row(Modifier.padding(start = 6.dp, end = 6.dp, top = 8.dp)) {
                LwIcon(L.drawable.lucide_ic_shield_check, Modifier.padding(top = 2.dp), size = 16.dp, tint = Lw.MutedFg)
                Spacer(Modifier.width(10.dp))
                MutedText(stringResource(R.string.onchain_add_note), size = 12.sp)
            }
        }
    }
}

@Composable
private fun OptionCard(icon: Int, title: String, body: String, tag: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).clickable(onClick = onClick).padding(16.dp).testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(48.dp).clip(RoundedCornerShape(16.dp)).background(Brush.linearGradient(listOf(Lw.Bitcoin.copy(alpha = 0.32f), Lw.Bitcoin.copy(alpha = 0.08f)))),
            contentAlignment = Alignment.Center,
        ) { LwIcon(icon, size = 22.dp, tint = Lw.Bitcoin) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(title, size = 16.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.height(3.dp))
            MutedText(body, size = 13.sp)
        }
        Spacer(Modifier.width(8.dp))
        LwIcon(L.drawable.lucide_ic_chevron_right, size = 18.dp, tint = Lw.MutedFg)
    }
}

/** BIP44/49/84/86 as plain choices: name, address look, standard path. [locked] shows the one an import dictates. */
@Composable
private fun ScriptPicker(selected: ScriptType, onSelect: (ScriptType) -> Unit, locked: Boolean = false, chain: Chain = Chain.MAINNET, options: List<ScriptType> = ScriptType.entries) {
    SettingsGroup(stringResource(R.string.onchain_script)) {
        (if (locked) listOf(selected) else options).forEachIndexed { i, t ->
            if (i > 0) GroupDivider(inset = 14.dp)
            SettingsRow(
                t.label, if (locked) null else ({ onSelect(t) }), Modifier.testTag("script-${t.name}"), subtitle = "BIP${t.purpose} · ${t.prefix(chain)} · ${t.path(chain)}",
                trailing = if (locked) null else ({
                    if (t == ScriptType.P2WPKH) Pill(stringResource(R.string.onchain_recommended), Lw.Bitcoin)
                    RadioButton(t == selected, { onSelect(t) }, colors = RadioButtonDefaults.colors(selectedColor = Lw.Bitcoin, unselectedColor = Lw.MutedFg))
                }),
            )
        }
    }
}

/** Advanced, folded away: the BIP39 passphrase ("25th word"). */
@Composable
private fun PassphraseSection(on: Boolean, onToggle: (Boolean) -> Unit, value: String, onValue: (String) -> Unit) {
    var open by rememberSaveable { mutableStateOf(on) }
    Column(Modifier.animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.clip(CircleShape).clickable { open = !open }.padding(horizontal = 6.dp, vertical = 6.dp).testTag("onchain-advanced"), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.onchain_advanced), size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
            LwIcon(if (open) L.drawable.lucide_ic_chevron_up else L.drawable.lucide_ic_chevron_down, size = 16.dp, tint = Lw.MutedFg)
        }
        if (open) SettingsGroup {
            SettingsSwitchRow(stringResource(R.string.onchain_passphrase), on, onToggle, icon = L.drawable.lucide_ic_lock)
            if (on) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Txt(stringResource(R.string.onchain_passphrase_body), size = 12.sp, color = Lw.Orange, lineHeight = 17.sp)
                OutlinedTextField(
                    value, onValue, Modifier.fillMaxWidth().testTag("onchain-passphrase"), singleLine = true, colors = lwFieldColors(), shape = RoundedCornerShape(16.dp),
                    visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                )
            }
        }
    }
}

/** Mainnet or signet, offered only once signet is on in Settings → Onchain. */
@Composable
internal fun NetworkPicker(chain: Chain, onChain: (Chain) -> Unit) {
    val c = LocalContainer.current
    val signet by c.onchain.signet.collectAsState()
    if (!signet && chain == Chain.MAINNET) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        FieldCaption(stringResource(R.string.onchain_network))
        TabSwitch(listOf(stringResource(R.string.onchain_settings_mainnet), stringResource(R.string.onchain_signet)), chain.ordinal) { onChain(Chain.entries[it]) }
        if (chain == Chain.SIGNET) MutedText(stringResource(R.string.onchain_signet_note), Modifier.padding(horizontal = 4.dp), size = 12.sp)
    }
}

@Composable
internal fun NameField(name: String, onName: (String) -> Unit, placeholder: String) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    FieldCaption(stringResource(R.string.onchain_name))
    OutlinedTextField(
        name, { onName(it.take(40)) }, Modifier.fillMaxWidth().testTag("onchain-name"),
        placeholder = { Txt(placeholder, color = Lw.MutedFg) }, singleLine = true, colors = lwFieldColors(), shape = RoundedCornerShape(16.dp),
    )
}

@Composable
fun CreateOnchainWalletScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val defaultName = stringResource(R.string.onchain_name_default)
    var name by rememberSaveable { mutableStateOf("") }
    var script by rememberSaveable { mutableStateOf(ScriptType.P2WPKH) }
    var chain by rememberSaveable { mutableStateOf(Chain.MAINNET) }
    var words by rememberSaveable { mutableIntStateOf(12) }
    var usePass by rememberSaveable { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().imePadding()) {
        SettingsHeader(stringResource(R.string.onchain_new_title), { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            NameField(name, { name = it }, defaultName)
            NetworkPicker(chain) { chain = it }
            ScriptPicker(script, { script = it }, chain = chain)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FieldCaption(stringResource(R.string.onchain_words))
                TabSwitch(listOf(12, 24).map { pluralStringResource(R.plurals.onchain_word_count, it, it) }, if (words == 12) 0 else 1) { words = if (it == 0) 12 else 24 }
            }
            PassphraseSection(usePass, { usePass = it }, pass, { pass = it })
        }
        LwButton(
            stringResource(R.string.onchain_create_cta), {
                busy = true
                scope.launch {
                    val phrase = withContext(Dispatchers.Default) { c.bip39.generate(words) }
                    val secret = phrase.joinToString(" ") + if (usePass) "\n$pass" else ""
                    val w = withContext(Dispatchers.IO) {
                        val d = Descriptors.fromPhrase(phrase, pass.takeIf { usePass }, script, chain)
                        c.onchain.add(OnchainWallet(name = name.trim().ifBlank { defaultName }, script = script, words = words, passphrase = usePass, backedUp = false, chain = chain, descriptors = d), secret)
                    }
                    // Straight to the phrase: Add and Create leave the back stack, so Back from it lands on Home.
                    nav.navigate(R0.onchainBackup(w.id)) { popUpTo(R0.HOME) }
                }
            },
            Modifier.fillMaxWidth().padding(16.dp).testTag("onchain-create"), BtnVariant.Theme,
            enabled = !busy && (!usePass || pass.isNotEmpty()), loading = busy, icon = L.drawable.lucide_ic_sparkles,
        )
    }
}

/** Keeps the phrase out of screenshots, screen recordings and the recents thumbnail while it's shown. */
@Composable
private fun SecureWindow() {
    val window = LocalActivity.current?.window
    DisposableEffect(window) {
        window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
}

/** The phrase (blurred until tapped); a wallet that isn't backed up yet then asks for three of its words back. */
@Composable
fun OnchainBackupScreen(id: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val wallets by c.onchain.wallets.collectAsState()
    val w = wallets.find { it.id == id }
    var secret by remember { mutableStateOf<Pair<List<String>, String?>?>(null) }
    LaunchedEffect(id) { secret = withContext(Dispatchers.IO) { c.onchain.secret(id) } }
    SecureWindow()
    // Fixed on entry: confirming flips backedUp, and the screen shouldn't switch modes under the user.
    val fresh = remember { w?.backedUp == false }
    var verifying by rememberSaveable { mutableStateOf(false) }
    BackHandler(verifying) { verifying = false }
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(
            stringResource(if (verifying) R.string.onchain_verify_title else R.string.onchain_backup_title),
            { if (verifying) verifying = false else nav.popBackStack() },
        )
        val s = secret
        if (w == null || s == null) { LoadingBox(Modifier.fillMaxSize()); return@Column }
        AnimatedContent(
            verifying,
            transitionSpec = {
                val dir = if (targetState) 1 else -1
                (slideInHorizontally(tween(320)) { dir * it / 4 } + fadeIn(tween(220, 80))).togetherWith(slideOutHorizontally(tween(260)) { -dir * it / 4 } + fadeOut(tween(120)))
            },
            label = "backupStep",
        ) { v ->
            if (!v) PhraseStep(s.first, s.second != null, fresh, onWritten = { verifying = true }, onLater = { nav.backToOnchain(c) }, onDone = { nav.popBackStack() })
            else VerifyStep(s.first, c.bip39) {
                c.onchain.update(id) { it.copy(backedUp = true) }
                nav.backToOnchain(c)
            }
        }
    }
}

@Composable
private fun PhraseStep(words: List<String>, passphrase: Boolean, fresh: Boolean, onWritten: () -> Unit, onLater: () -> Unit, onDone: () -> Unit) {
    var revealed by rememberSaveable { mutableStateOf(false) }
    val sensitiveGate = rememberSensitiveGate()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), GlassLevel.Thin, tint = Lw.Bitcoin.copy(alpha = 0.08f)).padding(14.dp)) {
                LwIcon(L.drawable.lucide_ic_shield_alert, size = 20.dp, tint = Lw.Bitcoin)
                Spacer(Modifier.width(12.dp))
                Txt(stringResource(R.string.onchain_backup_body), size = 13.sp, lineHeight = 19.sp)
            }
            Box {
                // Column-major, like the paper backup cards: 1–6 down the left, 7–12 down the right.
                val half = words.size / 2
                Column(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp)).padding(12.dp)
                        .then(if (revealed) Modifier else if (Build.VERSION.SDK_INT >= 31) Modifier.blur(14.dp) else Modifier.alpha(0f))
                        .testTag("onchain-words"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    repeat(half) { r ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WordChip(r + 1, words[r])
                            WordChip(r + half + 1, words[r + half])
                        }
                    }
                }
                if (!revealed) Box(
                    Modifier.matchParentSize().clip(RoundedCornerShape(24.dp)).background(Color.Black.copy(alpha = 0.25f)).clickable { sensitiveGate { revealed = true } }.testTag("onchain-reveal"),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        LwIcon(L.drawable.lucide_ic_eye, size = 26.dp)
                        Spacer(Modifier.height(8.dp))
                        Txt(stringResource(R.string.onchain_backup_reveal), size = 15.sp, weight = FontWeight.SemiBold)
                    }
                }
            }
            if (passphrase) Row(Modifier.padding(horizontal = 6.dp)) {
                LwIcon(L.drawable.lucide_ic_lock, Modifier.padding(top = 2.dp), size = 15.dp, tint = Lw.Orange)
                Spacer(Modifier.width(10.dp))
                Txt(stringResource(R.string.onchain_backup_passphrase), size = 13.sp, color = Lw.Orange, lineHeight = 18.sp)
            }
        }
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (fresh) {
                LwButton(stringResource(R.string.onchain_backup_written), onWritten, Modifier.fillMaxWidth().testTag("onchain-written"), BtnVariant.Theme, enabled = revealed)
                LwButton(stringResource(R.string.onchain_backup_later), onLater, Modifier.fillMaxWidth().testTag("onchain-later"), BtnVariant.Ghost, height = 44.dp)
            } else LwButton(stringResource(R.string.common_done), onDone, Modifier.fillMaxWidth().testTag("onchain-phrase-done"), BtnVariant.Secondary)
        }
    }
}

@Composable
private fun RowScope.WordChip(n: Int, word: String) =
    Row(Modifier.weight(1f).glass(RoundedCornerShape(14.dp), GlassLevel.Thin).padding(horizontal = 12.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt("$n", Modifier.width(24.dp), size = 12.sp, color = Lw.MutedFg)
        Txt(word, size = 16.sp, weight = FontWeight.Medium)
    }

/** Three random positions, three candidates each (the right word + two that aren't in the phrase). */
@Composable
private fun VerifyStep(words: List<String>, bip39: Bip39, onVerified: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val context = LocalContext.current
    val confirmed = stringResource(R.string.onchain_verify_done)
    val positions = remember { words.indices.shuffled().take(3).sorted() }
    val options = remember { val others = bip39.words - words.toSet(); positions.associateWith { p -> (others.shuffled().take(2) + words[p]).shuffled() } }
    val picked = remember { mutableStateMapOf<Int, String>() }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
            MutedText(stringResource(R.string.onchain_verify_body))
            positions.forEach { p ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FieldCaption(stringResource(R.string.onchain_verify_word, p + 1))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.getValue(p).forEach { opt ->
                            val on = picked[p] == opt
                            val wrong = on && opt != words[p]
                            val tint = when { wrong -> Lw.Red; on -> Lw.Bitcoin; else -> null }
                            Box(
                                Modifier.weight(1f).height(48.dp)
                                    .glass(RoundedCornerShape(14.dp), if (on) GlassLevel.Strong else GlassLevel.Thin, tint = tint?.copy(alpha = 0.16f))
                                    .border(1.dp, tint?.copy(alpha = 0.6f) ?: Color.Transparent, RoundedCornerShape(14.dp))
                                    .clickable {
                                        picked[p] = opt
                                        haptics.performHapticFeedback(if (opt == words[p]) HapticFeedbackType.Confirm else HapticFeedbackType.Reject)
                                    }.testTag("verify-$p-$opt"),
                                contentAlignment = Alignment.Center,
                            ) { Txt(opt, size = 15.sp, weight = FontWeight.Medium, color = if (wrong) Lw.Red else Lw.Foreground) }
                        }
                    }
                    if (picked[p] != null && picked[p] != words[p]) Txt(stringResource(R.string.onchain_verify_wrong), Modifier.padding(start = 4.dp), size = 12.sp, color = Lw.Red)
                }
            }
        }
        LwButton(
            stringResource(R.string.common_done), { context.toast(confirmed); onVerified() },
            Modifier.fillMaxWidth().padding(16.dp).testTag("onchain-verify"), BtnVariant.Theme,
            enabled = positions.all { picked[it] == words[it] }, icon = L.drawable.lucide_ic_check,
        )
    }
}

@Composable
fun ImportOnchainWalletScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val wallets by c.onchain.wallets.collectAsState()
    // Opened by the main scanner with a key or descriptor: start from it.
    var text by remember { mutableStateOf(c.flow.onchainImport.orEmpty().let { TextFieldValue(it, TextRange(it.length)) }.also { c.flow.onchainImport = null }) }
    var scanning by remember { mutableStateOf(false) }
    var name by rememberSaveable { mutableStateOf("") }
    var script by remember { mutableStateOf<ScriptType?>(null) } // the user's pick, over what the input implies
    var usePass by rememberSaveable { mutableStateOf(false) }
    var pass by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var phraseChain by rememberSaveable { mutableStateOf(Chain.MAINNET) }
    val signet by c.onchain.signet.collectAsState()
    val bip39 = c.bip39
    val detected = remember(text.text) { BtcImport.detect(text.text, bip39) }
    LaunchedEffect(detected?.javaClass, (detected as? BtcInput.WatchOnly)?.value) { script = null; error = null }
    // The last word is still being typed until a space follows it: no "unknown word" for it yet, suggestions instead.
    val typing = text.text.isNotEmpty() && !text.text.last().isWhitespace()
    val phrase = detected as? BtcInput.Phrase
    // Signet material needs signet turned on (Settings → Onchain): until then it reads as unsupported.
    val watch = (detected as? BtcInput.WatchOnly)?.takeIf { it.chain == Chain.MAINNET || signet }
    val priv = (detected as? BtcInput.PrivateKey)?.takeIf { it.chain == Chain.MAINNET || signet }
    // A keystore export: its accounts (pick one), or its vault; a cosigner key alone belongs in a multisig vault.
    val export = (detected as? BtcInput.Export)?.export?.takeIf { (it.chain == Chain.MAINNET || signet) && (it.accounts.isNotEmpty() || it.multisig != null) }
    val ready = (phrase != null && phrase.problem == null) || watch != null || priv != null || export != null
    val chain = watch?.chain ?: priv?.chain ?: export?.chain ?: phraseChain
    val effective = script?.takeIf { export == null || it in export.accounts } ?: watch?.script ?: priv?.script
        ?: export?.accounts?.keys?.let { k -> ScriptType.entries.firstOrNull { it in k } } ?: ScriptType.P2WPKH
    val exists = stringResource(R.string.onchain_import_exists)
    val unreadable = stringResource(R.string.onchain_import_invalid)
    // Keystore files: Coldcard's SD card exports, Electrum/Sparrow/Specter wallet files, BSMS, Bitcoin Core dumps.
    val openFile = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            val read = withContext(Dispatchers.IO) { runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().take(256 * 1024).toByteArray().decodeToString() } }.getOrNull() }
            if (read == null) error = unreadable else text = TextFieldValue(read.trim(), TextRange(read.trim().length))
        }
    }
    val defaultName = stringResource(if (watch != null) R.string.onchain_name_watch else R.string.onchain_name_imported)

    fun complete(word: String) {
        val t = text.text
        val start = t.indexOfLast { it.isWhitespace() } + 1
        val next = t.substring(0, start) + word + " "
        text = TextFieldValue(next, TextRange(next.length))
    }

    Column(Modifier.fillMaxSize().imePadding()) {
        SettingsHeader(stringResource(R.string.onchain_import_title), { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            if (scanning) QrReader({ error = it; scanning = false }) { raw ->
                if (!scanning) return@QrReader
                scanning = false
                val read = io.lawallet.wallet.core.btc.SeedQr.read(raw, bip39)?.first?.joinToString(" ") ?: raw.trim()
                text = TextFieldValue(read, TextRange(read.length))
            }
            OutlinedTextField(
                text, { text = it }, Modifier.fillMaxWidth().testTag("onchain-import-field"),
                placeholder = { Txt(stringResource(R.string.onchain_import_field), color = Lw.MutedFg) },
                minLines = 3, maxLines = 6, colors = lwFieldColors(), shape = RoundedCornerShape(20.dp),
                // Password-class input: keyboards don't learn or suggest a recovery phrase. Our own chips suggest instead.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 16.sp, lineHeight = 24.sp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LwButton(stringResource(R.string.onchain_import_paste), {
                    context.getSystemService(ClipboardManager::class.java).primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.trim()?.let {
                        text = TextFieldValue(it, TextRange(it.length))
                    }
                }, Modifier.weight(1f).testTag("onchain-paste"), BtnVariant.Secondary, height = 42.dp, icon = L.drawable.lucide_ic_clipboard_paste)
                LwButton(stringResource(R.string.onchain_psbt_file), { openFile.launch(arrayOf("*/*")) }, Modifier.weight(1f).testTag("onchain-file"),
                    BtnVariant.Secondary, height = 42.dp, icon = L.drawable.lucide_ic_folder_open)
                LwButton(stringResource(if (scanning) R.string.common_cancel else R.string.scan_short), { scanning = !scanning }, Modifier.weight(1f).testTag("onchain-scan"),
                    BtnVariant.Secondary, height = 42.dp, icon = if (scanning) L.drawable.lucide_ic_x else L.drawable.lucide_ic_scan_qr_code)
            }
            // What the field holds, live.
            when (val d = detected) {
                null -> if (text.text.trim().length > 8) Detected(L.drawable.lucide_ic_circle_alert, stringResource(R.string.onchain_reject_invalid), Lw.Red)
                is BtcInput.Phrase -> {
                    val unknown = d.words.withIndex().firstOrNull { (i, w) -> !bip39.isWord(w) && !(typing && i == d.words.lastIndex) }?.value
                    val count = pluralStringResource(R.plurals.onchain_word_count, d.words.size, d.words.size)
                    when {
                        d.problem == null -> Detected(L.drawable.lucide_ic_circle_check, stringResource(R.string.onchain_detect_phrase, d.words.size) + " · BIP39", Lw.Green)
                        unknown != null -> Detected(L.drawable.lucide_ic_circle_alert, stringResource(R.string.onchain_detect_unknown, unknown), Lw.Red)
                        d.problem == PhraseProblem.CHECKSUM && !typing -> Detected(L.drawable.lucide_ic_circle_alert, stringResource(R.string.onchain_detect_checksum), Lw.Red)
                        d.words.size > 24 -> Detected(L.drawable.lucide_ic_circle_alert, stringResource(R.string.onchain_detect_length), Lw.Red)
                        else -> Detected(L.drawable.lucide_ic_list_ordered, "$count · " + stringResource(R.string.onchain_detect_length), Lw.MutedFg)
                    }
                    val last = d.words.last()
                    val suggestions = if (typing) bip39.suggest(last).filter { it != last || bip39.suggest(last).size > 1 } else emptyList()
                    if (suggestions.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        suggestions.forEach { s ->
                            Txt(s, Modifier.glass(CircleShape, GlassLevel.Regular).clickable { complete(s) }.padding(horizontal = 16.dp, vertical = 9.dp).testTag("bip39-$s"), size = 15.sp, weight = FontWeight.Medium)
                        }
                    }
                }
                is BtcInput.WatchOnly -> if (watch == null) Detected(L.drawable.lucide_ic_circle_alert, stringResource(R.string.onchain_reject_testnet), Lw.Red) else {
                    val kind = stringResource(when (d.kind) { WatchKind.XPUB -> R.string.onchain_kind_xpub; WatchKind.DESCRIPTOR -> R.string.onchain_kind_descriptor; WatchKind.ADDRESS -> R.string.onchain_kind_address; WatchKind.MULTISIG -> R.string.onchain_kind_multisig })
                    val what = if (d.kind == WatchKind.MULTISIG) Descriptors.quorum(BtcImport.normalizeKeys(d.value))?.let { (m, n) -> stringResource(R.string.onchain_multisig_label, m, n) } ?: "" else d.script?.label ?: stringResource(R.string.onchain_address)
                    val net = if (d.chain == Chain.SIGNET) " · " + stringResource(R.string.onchain_signet) else ""
                    Detected(if (d.kind == WatchKind.MULTISIG) L.drawable.lucide_ic_users else L.drawable.lucide_ic_eye, "$kind · $what$net", Lw.Green)
                    MutedText(stringResource(if (d.kind == WatchKind.MULTISIG) R.string.onchain_detect_multisig else R.string.onchain_detect_watch), Modifier.padding(horizontal = 4.dp), size = 13.sp)
                }
                is BtcInput.Export -> when {
                    export == null && d.export.cosigner != null && d.export.accounts.isEmpty() && d.export.multisig == null ->
                        Detected(L.drawable.lucide_ic_users, stringResource(R.string.onchain_reject_multisig), Lw.Orange)
                    export == null -> Detected(L.drawable.lucide_ic_circle_alert, stringResource(R.string.onchain_reject_testnet), Lw.Red)
                    else -> {
                        val what = if (export.multisig != null) Descriptors.quorum(BtcImport.normalizeKeys(export.multisig))?.let { (m, n) -> stringResource(R.string.onchain_multisig_label, m, n) }.orEmpty()
                            else pluralStringResource(R.plurals.onchain_export_accounts, export.accounts.size, export.accounts.size)
                        val net = if (export.chain == Chain.SIGNET) " · " + stringResource(R.string.onchain_signet) else ""
                        Detected(if (export.multisig != null) L.drawable.lucide_ic_users else L.drawable.lucide_ic_file_check, stringResource(R.string.onchain_kind_export, export.source) + " · " + what + net, Lw.Green)
                        MutedText(stringResource(if (export.multisig != null) R.string.onchain_detect_multisig else R.string.onchain_detect_watch), Modifier.padding(horizontal = 4.dp), size = 13.sp)
                    }
                }
                is BtcInput.PrivateKey -> if (priv == null) Detected(L.drawable.lucide_ic_circle_alert, stringResource(R.string.onchain_reject_testnet), Lw.Red) else {
                    val net = if (d.chain == Chain.SIGNET) " · " + stringResource(R.string.onchain_signet) else ""
                    Detected(L.drawable.lucide_ic_key_round, stringResource(R.string.onchain_kind_xprv) + " · " + effective.label + net, Lw.Green)
                    MutedText(stringResource(R.string.onchain_detect_private), Modifier.padding(horizontal = 4.dp), size = 13.sp)
                }
                is BtcInput.Rejected -> Detected(
                    L.drawable.lucide_ic_circle_alert,
                    stringResource(when (d.reason) { Unsupported.PRIVATE_KEY -> R.string.onchain_reject_private; Unsupported.TESTNET -> R.string.onchain_reject_testnet; Unsupported.MULTISIG -> R.string.onchain_reject_multisig; Unsupported.INVALID -> R.string.onchain_reject_invalid }),
                    Lw.Red,
                )
            }
            if (ready) {
                // A phrase or an xpub doesn't say which accounts it was used with (BlueWallet scans; here the user says).
                // ypub/zpub, descriptors and addresses do, so theirs is shown, not offered.
                val pickable = phrase != null || (watch?.value ?: priv?.value)?.let { it.startsWith("xpub") || it.startsWith("tpub") || it.startsWith("xprv") || it.startsWith("tprv") } == true
                if (phrase != null) NetworkPicker(phraseChain) { phraseChain = it }
                if (export != null && export.multisig == null) ScriptPicker(effective, { script = it }, locked = export.accounts.size < 2, chain = chain, options = ScriptType.entries.filter { it in export.accounts })
                else if (export == null && watch?.kind != WatchKind.MULTISIG && (watch?.kind != WatchKind.ADDRESS || watch.script != null)) {
                    ScriptPicker(effective, { script = it }, locked = !pickable, chain = chain)
                    if (pickable) MutedText(stringResource(R.string.onchain_import_script_hint), Modifier.padding(horizontal = 4.dp), size = 12.sp)
                }
                NameField(name, { name = it }, defaultName)
                if (phrase != null) PassphraseSection(usePass, { usePass = it }, pass, { pass = it })
            }
            ErrorText(error)
        }
        LwButton(
            stringResource(R.string.onchain_import_cta), {
                val label = name.trim().ifBlank { defaultName }
                busy = true; error = null
                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching {
                            val passphrase = pass.takeIf { usePass && it.isNotEmpty() }
                            val d = when {
                                phrase != null -> Descriptors.fromPhrase(phrase.words, passphrase, effective, chain)
                                priv != null -> Descriptors.fromXprv(priv.value, effective, chain)
                                export != null -> Descriptors.fromDescriptor(export.multisig ?: export.accounts.getValue(effective), chain)
                                watch!!.kind == WatchKind.ADDRESS -> null
                                watch.kind == WatchKind.XPUB -> Descriptors.fromXpub(watch.value, effective, chain)
                                else -> Descriptors.fromDescriptor(watch.value, chain)
                            }
                            if (d != null && wallets.any { it.descriptors == d } || watch != null && wallets.any { it.source == watch.value }) return@runCatching false
                            if (export != null) {
                                val multi = export.multisig != null
                                // A vault holding one of this phone's seeds' keys signs with it.
                                val signer = if (!multi || d == null) null else wallets.filter { it.seed && !it.keyOnly && it.chain == chain }
                                    .firstOrNull { s -> c.onchain.secret(s.id)?.let { (words, p) -> Descriptors.multisigSecret(d, words, p, chain) } != null }?.id
                                c.onchain.add(OnchainWallet(
                                    name = label, script = if (multi) null else effective, watch = if (multi) WatchKind.MULTISIG else WatchKind.DESCRIPTOR,
                                    source = d!!.external, chain = chain, descriptors = d, signer = signer,
                                ))
                            } else if (priv != null) {
                                // Kept like a phrase: Keystore-wrapped, out of the record. Its owner already has it (it was imported).
                                c.onchain.add(OnchainWallet(name = label, script = effective, chain = chain, descriptors = d), io.lawallet.wallet.app.OnchainStore.KEY + priv.value)
                            } else if (phrase != null) {
                                // An imported phrase is already written down somewhere: no backup step.
                                val w = OnchainWallet(name = label, script = effective, words = phrase.words.size, passphrase = passphrase != null, chain = chain, descriptors = d)
                                c.onchain.add(w, phrase.words.joinToString(" ") + if (passphrase != null) "\n$passphrase" else "")
                            } else {
                                // A vault holding one of this phone's seeds' keys signs with it.
                                val signer = if (watch!!.kind != WatchKind.MULTISIG || d == null) null else wallets.filter { it.seed && it.chain == chain }
                                    .firstOrNull { s -> c.onchain.secret(s.id)?.let { (words, p) -> Descriptors.multisigSecret(d, words, p, chain) } != null }?.id
                                c.onchain.add(OnchainWallet(
                                    name = label, script = if (watch.kind == WatchKind.ADDRESS || watch.kind == WatchKind.MULTISIG) watch.script else effective,
                                    watch = watch.kind, source = watch.value, chain = chain, descriptors = d, signer = signer,
                                ))
                            }
                            true
                        }
                    }
                    busy = false
                    result.onSuccess { if (it) nav.backToOnchain(c) else error = exists }.onFailure { error = unreadable }
                }
            },
            Modifier.fillMaxWidth().padding(16.dp).testTag("onchain-import"), BtnVariant.Theme, enabled = ready && !busy, loading = busy, icon = L.drawable.lucide_ic_import,
        )
    }
}

@Composable
private fun Detected(icon: Int, text: String, color: Color) =
    Row(
        Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), GlassLevel.Thin, tint = color.copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 12.dp).testTag("onchain-detected"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LwIcon(icon, size = 18.dp, tint = color)
        Spacer(Modifier.width(10.dp))
        Txt(text, size = 14.sp, weight = FontWeight.Medium, lineHeight = 19.sp)
    }

@Composable
fun OnchainWalletDetailScreen(id: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val wallets by c.onchain.wallets.collectAsState()
    val states by c.onchainWallets.states.collectAsState()
    val w = wallets.find { it.id == id }
    var renaming by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var showing by remember { mutableStateOf<Pair<String, String>?>(null) }
    var secret by remember { mutableStateOf(false) }
    val sensitiveGate = rememberSensitiveGate()
    val privateTitle = stringResource(R.string.onchain_privateKey)
    val scope = rememberCoroutineScope()
    val copied = stringResource(R.string.common_copied)
    if (w == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    LaunchedEffect(id) { runCatching { c.onchainWallets.refresh(id) } }
    val state = states[id]
    val online by c.onchainWallets.connected(w.chain).collectAsState()
    val accent = if (w.chain == Chain.SIGNET) Lw.Signet else Lw.Bitcoin
    val cosignerTitle = stringResource(R.string.onchain_cosigner)
    val descriptorTitle = stringResource(R.string.onchain_descriptor)
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(w.name, { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(28.dp), tint = accent.copy(alpha = 0.06f)).padding(vertical = 22.dp, horizontal = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                OnchainMark(w, 52)
                Spacer(Modifier.height(12.dp))
                Txt(stringResource(R.string.remote_wallet_balance), size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
                AmountDisplay(state?.balance?.let { Currencies.formatSats(it.total) } ?: "—", "sats", size = 36.sp, unitSize = 14.sp, figureTag = "onchain-wallet-balance")
                state?.balance?.pending?.takeIf { it != 0L }?.let { p ->
                    Txt(stringResource(R.string.onchain_balance_pending, Currencies.formatSats(p)), size = 13.sp, color = Lw.MutedFg)
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (w.isPrimary) Pill(stringResource(R.string.remoteWallets_list_primary), Lw.Bitcoin)
                    if (w.chain == Chain.SIGNET) Pill(stringResource(R.string.onchain_signet), Lw.Signet)
                    if (w.multisig) w.quorum?.let { (m, n) -> Pill(stringResource(R.string.onchain_multisig_label, m, n), Lw.Bitcoin) }
                    if (w.watchOnly) Pill(stringResource(R.string.onchain_watchOnly), Lw.MutedFg)
                    if (!w.backedUp) Pill(stringResource(R.string.onchain_notBackedUp), Lw.Orange)
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    LwButton(stringResource(R.string.home_receive), { nav.navigate(R0.onchainReceive(id)) }, Modifier.weight(1f).testTag("onchain-detail-receive"), BtnVariant.Secondary, height = 42.dp, icon = L.drawable.lucide_ic_arrow_down_left)
                    if (w.descriptors != null) LwButton(stringResource(R.string.home_send), {
                        c.flow.onchainSend = null
                        nav.navigate(R0.onchainSend(id))
                    }, Modifier.weight(1f).testTag("onchain-detail-send"), BtnVariant.Secondary, height = 42.dp, icon = L.drawable.lucide_ic_arrow_up_right)
                }
            }
            if (!w.backedUp) Row(
                Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), tint = Lw.Orange.copy(alpha = 0.10f)).clickable { nav.navigate(R0.onchainBackup(id)) }.padding(14.dp).testTag("onchain-detail-backup"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LwIcon(L.drawable.lucide_ic_shield_alert, size = 20.dp, tint = Lw.Orange)
                Spacer(Modifier.width(12.dp))
                Txt(stringResource(R.string.onchain_backup_warning), Modifier.weight(1f), size = 13.sp, lineHeight = 18.sp)
                Spacer(Modifier.width(8.dp))
                LwIcon(L.drawable.lucide_ic_chevron_right, size = 18.dp, tint = Lw.MutedFg)
            }
            SettingsGroup(stringResource(R.string.onchain_details)) {
                SettingsRow(stringResource(R.string.onchain_type), null, icon = L.drawable.lucide_ic_layers, value = if (w.multisig) "P2WSH" else w.script?.label ?: stringResource(R.string.onchain_address))
                w.path?.let { GroupDivider(); SettingsRow(stringResource(R.string.onchain_path), null, icon = L.drawable.lucide_ic_hash, value = it) }
                if (w.seed && !w.keyOnly) { GroupDivider(); SettingsRow(stringResource(R.string.onchain_backup_title), { nav.navigate(R0.onchainBackup(id)) }, icon = L.drawable.lucide_ic_key_round, value = phraseSummary(w)) }
                if (w.keyOnly) {
                    GroupDivider()
                    SettingsRow(stringResource(R.string.onchain_privateKey), {
                        sensitiveGate {
                            scope.launch { withContext(Dispatchers.IO) { c.onchain.privateKey(id) }?.let { showing = privateTitle to it; secret = true } }
                        }
                    }, icon = L.drawable.lucide_ic_key_round, value = "••••••", modifier = Modifier.testTag("onchain-private-key"))
                }
                if (w.multisig) {
                    GroupDivider()
                    SettingsRow(stringResource(R.string.onchain_signsWith), null, icon = L.drawable.lucide_ic_key_round,
                        value = w.signer?.let { s -> wallets.find { it.id == s }?.name } ?: stringResource(R.string.onchain_noKeyHere))
                }
                if (w.watch == WatchKind.ADDRESS || w.watch == WatchKind.XPUB) w.source?.let { src ->
                    GroupDivider()
                    val kind = stringResource(if (w.watch == WatchKind.ADDRESS) R.string.onchain_kind_address else R.string.onchain_kind_xpub)
                    SettingsRow(kind, { context.copyToClipboard(src, copied) }, icon = L.drawable.lucide_ic_eye, value = src.take(8) + "…" + src.takeLast(6)) {
                        LwIcon(L.drawable.lucide_ic_copy, size = 18.dp, tint = Lw.MutedFg)
                    }
                }
                GroupDivider()
                SettingsRow(stringResource(R.string.onchain_server), { nav.navigate(R0.ONCHAIN_SETTINGS) }, icon = L.drawable.lucide_ic_server,
                    value = stringResource(if (state?.syncing == true) R.string.onchain_server_syncing else if (online) R.string.onchain_server_online else R.string.onchain_server_offline))
            }
            if (w.descriptors != null) SettingsGroup {
                SettingsRow(descriptorTitle, { showing = descriptorTitle to w.descriptors.external }, icon = L.drawable.lucide_ic_file_text, subtitle = stringResource(R.string.onchain_descriptor_body), modifier = Modifier.testTag("onchain-descriptor"))
                if (w.seed && !w.keyOnly) {
                    GroupDivider()
                    SettingsRow(cosignerTitle, {
                        scope.launch {
                            withContext(Dispatchers.IO) { c.onchain.secret(id)?.let { (words, pass) -> Descriptors.cosigner(words, pass, w.chain) } }?.let { showing = cosignerTitle to it }
                        }
                    }, icon = L.drawable.lucide_ic_users, subtitle = stringResource(R.string.onchain_cosigner_body), modifier = Modifier.testTag("onchain-cosigner"))
                }
                if (w.multisig || w.watchOnly) {
                    GroupDivider()
                    SettingsRow(stringResource(R.string.onchain_psbt_open), { c.flow.psbt = null; nav.navigate(R0.onchainPsbt(id)) }, icon = L.drawable.lucide_ic_file_pen_line, modifier = Modifier.testTag("onchain-psbt-open"))
                }
            }
            SettingsGroup {
                if (!w.isPrimary) { SettingsRow(stringResource(R.string.remoteWallets_list_setPrimary), { c.onchain.setPrimary(id) }, icon = L.drawable.lucide_ic_check); GroupDivider() }
                SettingsRow(stringResource(R.string.rw_rename), { renaming = true }, icon = L.drawable.lucide_ic_pencil)
            }
            SettingsGroup { SettingsRow(stringResource(R.string.onchain_remove), { removing = true }, labelColor = Lw.Red, icon = L.drawable.lucide_ic_trash_2) }
        }
    }
    showing?.let { (title, value) ->
        // A private key on screen: kept out of screenshots and the recents thumbnail, like the phrase.
        if (secret) SecureWindow()
        LwBottomSheet({ showing = null; secret = false }, "onchain-export-sheet") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                DialogTitle(title)
                QrDisplay(value, null)
                Txt(value, Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), GlassLevel.Thin).padding(12.dp), size = 12.sp, mono = true, lineHeight = 17.sp)
            }
        }
    }
    if (renaming) TextPrompt(stringResource(R.string.rw_rename), w.name, onDismiss = { renaming = false }) { v ->
        renaming = false
        c.onchain.update(id) { it.copy(name = v) }
    }
    if (removing) RemoveOnchainDialog(w, { removing = false }) { removing = false; c.onchain.remove(id); nav.popBackStack() }
}
