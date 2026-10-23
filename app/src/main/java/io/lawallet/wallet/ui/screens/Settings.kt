package io.lawallet.wallet.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.os.LocaleListCompat
import androidx.fragment.app.FragmentActivity
import io.lawallet.wallet.BuildConfig
import io.lawallet.wallet.R
import io.lawallet.wallet.app.Diagnostics
import io.lawallet.wallet.app.Updater
import io.lawallet.wallet.core.api.OAuthGrant
import io.lawallet.wallet.core.api.PasskeyCredential
import io.lawallet.wallet.core.api.WalletCard
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.nostr.Nip19
import io.lawallet.wallet.service.PaymentSound
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.AspectCard
import io.lawallet.wallet.ui.components.BrandImage
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
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.copyToClipboard
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.launch
import java.util.Locale
import kotlin.math.roundToInt
import com.composables.icons.lucide.R as L

@Composable
private fun SettingsPage(title: String, content: @Composable () -> Unit) {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(title, { nav.popBackStack() }, stringResource(R.string.common_back))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) { content() }
    }
}

@Composable
fun lwSwitchColors() = SwitchDefaults.colors(checkedTrackColor = LocalBrand.current.t400, checkedThumbColor = Lw.Foreground, uncheckedTrackColor = Color.White.copy(alpha = 0.08f), uncheckedThumbColor = Lw.MutedFg, uncheckedBorderColor = Color.White.copy(alpha = 0.16f))

@Composable
fun SettingsScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val brand = LocalBrand.current
    var confirm by remember { mutableStateOf(false) }
    val bg by c.prefs.backgroundListener.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Txt(stringResource(R.string.settings_title), Modifier.padding(start = 20.dp, top = 24.dp, bottom = 16.dp), size = 28.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.4).sp)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            SettingsGroup(stringResource(R.string.settings_account)) {
                SettingsRow(stringResource(R.string.settings_myCards), { nav.navigate(R0.CARDS) }, Modifier.testTag("settings-cards"), icon = L.drawable.lucide_ic_credit_card)
                GroupDivider()
                SettingsRow(stringResource(R.string.settings_security), { nav.navigate(R0.SECURITY) }, Modifier.testTag("settings-security"), icon = L.drawable.lucide_ic_shield)
                GroupDivider()
                SettingsRow(stringResource(R.string.settings_remoteWallets), { nav.navigate(R0.REMOTE_WALLETS) }, Modifier.testTag("settings-wallets"), icon = L.drawable.lucide_ic_wallet)
            }
            SettingsGroup(stringResource(R.string.settings_preferences)) {
                SettingsRow(stringResource(R.string.settings_language), { nav.navigate(R0.LANGUAGE) }, icon = L.drawable.lucide_ic_languages)
                GroupDivider()
                SettingsRow(stringResource(R.string.settings_currencies), { nav.navigate(R0.CURRENCIES) }, Modifier.testTag("settings-currencies"), icon = L.drawable.lucide_ic_globe)
                GroupDivider()
                SettingsRow(stringResource(R.string.settings_paymentSound), { nav.navigate(R0.SOUND) }, icon = L.drawable.lucide_ic_volume_2)
                GroupDivider()
                SettingsRow(stringResource(R.string.bg_listener_title), { c.prefs.setBackgroundListener(!bg) }, icon = L.drawable.lucide_ic_bell) {
                    Switch(bg, { c.prefs.setBackgroundListener(it) }, colors = lwSwitchColors())
                }
                GroupDivider()
                SettingsRow(stringResource(R.string.instance_title), { nav.navigate(R0.INSTANCE) }, Modifier.testTag("settings-instance"), icon = L.drawable.lucide_ic_server, value = c.session.instanceHost)
            }
            SettingsGroup(stringResource(R.string.settings_support)) {
                SettingsRow(stringResource(R.string.settings_helpCenter), { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://docs.lawallet.io"))) }, icon = L.drawable.lucide_ic_info, external = true)
                GroupDivider()
                SettingsRow(stringResource(R.string.settings_reportBug), { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lawalletio/android-wallet/issues"))) }, icon = L.drawable.lucide_ic_circle_alert, external = true)
                GroupDivider()
                SettingsRow(stringResource(R.string.diag_title), { nav.navigate(R0.DIAGNOSTICS) }, Modifier.testTag("settings-diagnostics"), icon = L.drawable.lucide_ic_activity)
                GroupDivider()
                SettingsRow(stringResource(R.string.update_title), { nav.navigate(R0.UPDATES) }, Modifier.testTag("settings-updates"), icon = L.drawable.lucide_ic_download, value = "v${BuildConfig.VERSION_NAME}")
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BrandImage(brand.isotype, "isotype.svg", Modifier.size(32.dp))
                Txt(brand.name ?: stringResource(R.string.settings_communityBy), size = 13.sp, color = Lw.MutedFg)
                Txt(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), size = 12.sp, color = Lw.MutedFg)
            }
            SettingsGroup(stringResource(R.string.settings_dangerZone)) {
                SettingsRow(stringResource(R.string.settings_removeWallet), { confirm = true }, Modifier.testTag("settings-remove"), labelColor = Lw.Red, icon = L.drawable.lucide_ic_log_out)
            }
        }
    }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false },
        text = { Txt(stringResource(R.string.settings_removeWalletConfirm)) },
        confirmButton = { TextButton({ confirm = false; c.session.logout() }, Modifier.testTag("confirm-remove")) { Txt(stringResource(R.string.settings_removeWallet), color = Lw.Red) } },
        dismissButton = { TextButton({ confirm = false }) { Txt(stringResource(R.string.common_cancel)) } },
        containerColor = Glass.Dialog,
    )
}

@Composable
fun CardsScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf<List<WalletCard>?>(null) }
    var failed by remember { mutableStateOf(false) }
    val fallback = stringResource(R.string.settings_cardsScreen_fallbackName)
    val updateFailed = stringResource(R.string.settings_cardsScreen_updateFailed)
    val enabledFmt = stringResource(R.string.settings_cardsScreen_enabledToast)
    val disabledFmt = stringResource(R.string.settings_cardsScreen_disabledToast)
    val linkedFmt = stringResource(R.string.settings_cardsScreen_linkedToast)
    suspend fun load() { runCatching { c.session.api.value.cards() }.onSuccess { cards = it; failed = false }.onFailure { failed = true } }
    LaunchedEffect(Unit) { load() }
    SettingsPage(stringResource(R.string.settings_myCards)) {
        val list = cards
        when {
            list == null && failed -> Column { Txt(stringResource(R.string.settings_cardsScreen_errorTitle), weight = FontWeight.SemiBold); MutedText(stringResource(R.string.settings_cardsScreen_tryAgain)) }
            list == null -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Spinner() }
            list.isEmpty() -> Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                LwIcon(L.drawable.lucide_ic_credit_card, size = 40.dp, tint = Lw.MutedFg)
                Spacer(Modifier.height(12.dp))
                Txt(stringResource(R.string.settings_cardsScreen_emptyTitle), weight = FontWeight.SemiBold)
                MutedText(stringResource(R.string.settings_cardsScreen_emptyBody))
            }
            else -> list.forEach { card ->
                val name = card.title ?: fallback
                val status = when { card.blocked -> R.string.settings_cardsScreen_status_Blocked; card.disabled -> R.string.settings_cardsScreen_status_Disabled; else -> R.string.settings_cardsScreen_status_Active }
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(16.dp).testTag("card-${card.id}"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    AspectCard(card.design?.imageUrl?.let { absolute(c.session.instanceUrl, it) }, Modifier.fillMaxWidth())
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Txt(name, size = 16.sp, weight = FontWeight.SemiBold)
                            Txt(stringResource(status), size = 12.sp, color = if (card.blocked || card.disabled) Lw.MutedFg else Lw.Green)
                        }
                        Txt(stringResource(R.string.settings_cardsScreen_tapPayments), size = 12.sp, color = Lw.MutedFg)
                        Spacer(Modifier.width(8.dp))
                        Switch(!card.disabled, { on ->
                            scope.launch {
                                runCatching { c.session.api.value.setCardEnabled(card.id, on) }
                                    .onSuccess { updated -> cards = cards?.map { if (it.id == card.id) updated else it }; context.toast((if (on) enabledFmt else disabledFmt).format(name)) }
                                    .onFailure { context.toast(it.message ?: updateFailed) }
                            }
                        }, enabled = !card.blocked, colors = lwSwitchColors())
                    }
                    if (card.remoteWalletId == null || card.remoteWalletId != card.defaultRemoteWalletId) LwButton(stringResource(R.string.settings_cardsScreen_linkToWallet), {
                        scope.launch {
                            runCatching { c.session.api.value.linkCardToDefaultWallet(card.id) }
                                .onSuccess { context.toast(linkedFmt.format(name)); load() }
                                .onFailure { context.toast(it.message ?: updateFailed) }
                        }
                    }, Modifier.fillMaxWidth(), BtnVariant.Secondary, height = 40.dp, enabled = !card.blocked)
                }
            }
        }
    }
}

/** Gate the nsec reveal behind the device lock (biometric or PIN) when one is set up. */
private fun unlock(activity: FragmentActivity, title: String, onOk: () -> Unit) {
    val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) { onOk(); return }
    BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onOk()
    }).authenticate(BiometricPrompt.PromptInfo.Builder().setTitle(title).setAllowedAuthenticators(auth).build())
}

@Composable
fun SecurityScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val activity = androidx.activity.compose.LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val signer by c.session.signer.collectAsState()
    var role by remember { mutableStateOf<String?>(null) }
    var revealed by remember { mutableStateOf<String?>(null) }
    var passkeys by remember { mutableStateOf<List<PasskeyCredential>?>(null) }
    var grants by remember { mutableStateOf<List<OAuthGrant>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<PasskeyCredential?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var deleteUnsupported by remember { mutableStateOf(false) }
    val deletedMsg = stringResource(R.string.security_delete_done)
    val deleteFailed = stringResource(R.string.security_delete_failed)
    val npubCopied = stringResource(R.string.settings_securityScreen_npubCopied)
    val added = stringResource(R.string.settings_passkeysScreen_added)
    val addFailed = stringResource(R.string.settings_passkeysScreen_addFailed)
    val unlockTitle = stringResource(R.string.backup_auth_title)
    val method = c.session.loginMethod
    val me by c.session.me.collectAsState()
    // Optimistic: shows the switched value while the PUT runs, reverts on failure.
    var recoveryOverride by remember { mutableStateOf<Boolean?>(null) }
    val recoveryOn = stringResource(R.string.settings_securityScreen_cardRecovery_enabled)
    val recoveryOff = stringResource(R.string.settings_securityScreen_cardRecovery_disabled)
    val recoveryFailed = stringResource(R.string.settings_securityScreen_cardRecovery_failed)
    LaunchedEffect(Unit) {
        role = runCatching { c.session.api.value.jwtInfo().role }.getOrNull()
        passkeys = runCatching { c.session.api.value.passkeys() }.getOrNull()
        grants = runCatching { c.session.api.value.oauthGrants() }.getOrNull()
    }
    SettingsPage(stringResource(R.string.settings_security)) {
        val npub = signer?.pubkey?.let { Nip19.npub(it) }
        SettingsGroup {
            Column(Modifier.fillMaxWidth().clickable(enabled = npub != null) { context.copyToClipboard(npub!!, npubCopied) }.padding(16.dp)) {
                Txt(stringResource(R.string.settings_securityScreen_publicKey), size = 12.sp, color = Lw.MutedFg)
                Txt(npub ?: stringResource(R.string.settings_securityScreen_notSignedIn), size = 13.sp, mono = true, modifier = Modifier.testTag("npub"))
            }
            GroupDivider()
            SettingsRow(stringResource(R.string.settings_securityScreen_role), null, value = role?.let { stringResource(roleLabel(it)) } ?: "—")
            GroupDivider()
            SettingsRow(stringResource(R.string.settings_securityScreen_loginMethod), null, value = when (method) {
                "passkey" -> stringResource(R.string.settings_securityScreen_methods_passkey)
                "amber" -> "Amber (NIP-55)"
                "nsec" -> stringResource(R.string.settings_securityScreen_methods_nsec)
                else -> stringResource(R.string.settings_securityScreen_unknown)
            })
        }
        if (method == "nsec" || method == "passkey") SettingsGroup(stringResource(R.string.settings_securityScreen_secretKey)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MutedText(stringResource(if (method == "passkey") R.string.settings_securityScreen_passkeyKeyHint else R.string.settings_securityScreen_nsecKeyHint), size = 13.sp)
                val secret = revealed
                if (secret == null) LwButton(stringResource(R.string.settings_securityScreen_revealSecret), {
                    unlock(activity, unlockTitle) { revealed = c.session.secretHex()?.let { Nip19.nsec(it) } }
                }, Modifier.fillMaxWidth().testTag("reveal-secret"), BtnVariant.Secondary, icon = L.drawable.lucide_ic_key_round)
                else SecretKeyReveal(secret, null, null)
            }
        }
        SettingsGroup(stringResource(R.string.settings_securityScreen_passkeys)) {
            val list = passkeys
            if (list.isNullOrEmpty()) MutedText(stringResource(R.string.settings_passkeysScreen_empty), Modifier.padding(16.dp), size = 13.sp)
            list?.forEachIndexed { i, pk ->
                if (i > 0) GroupDivider()
                SettingsRow(pk.label ?: stringResource(R.string.settings_passkeysScreen_untitled, pk.id.take(6)), { editing = pk }, icon = L.drawable.lucide_ic_fingerprint_pattern,
                    value = if (pk.backedUp) stringResource(R.string.settings_passkeysScreen_synced) else null)
            }
            GroupDivider()
            SettingsRow(stringResource(if (busy) R.string.settings_passkeysScreen_waiting else R.string.settings_passkeysScreen_add), {
                if (busy) return@SettingsRow
                busy = true
                scope.launch {
                    runCatching { c.passkeys.register(activity, "Android · ${android.os.Build.MODEL}", authenticated = true) }
                        .onSuccess { context.toast(added); passkeys = runCatching { c.session.api.value.passkeys() }.getOrNull() }
                        .onFailure { context.toast(it.message ?: addFailed) }
                    busy = false
                }
            }, icon = L.drawable.lucide_ic_plus)
        }
        SettingsGroup(stringResource(R.string.settings_securityScreen_connectedApps)) {
            val list = grants
            if (list.isNullOrEmpty()) MutedText("—", Modifier.padding(16.dp))
            list?.forEachIndexed { i, g ->
                if (i > 0) GroupDivider()
                SettingsRow(g.clientName, null, value = g.spendLimitSats?.let { stringResource(R.string.common_sats, Currencies.formatSats(it)) }) {
                    TextButton({ scope.launch { runCatching { c.session.api.value.revokeGrant(g.id) }.onSuccess { grants = grants?.filter { it.id != g.id } } } }) { Txt("Revoke", size = 13.sp, color = Lw.Red) }
                }
            }
        }
        SettingsGroup(stringResource(R.string.settings_securityScreen_device)) {
            SettingsRow(stringResource(R.string.settings_securityScreen_removeDevice), { confirmRemove = true }, labelColor = Lw.Red, icon = L.drawable.lucide_ic_log_out)
        }
        // Absent on servers without card recovery.
        me?.cardRecoveryEnabled?.let { stored ->
            val enabled = recoveryOverride ?: stored
            SettingsGroup(stringResource(R.string.settings_securityScreen_cardRecovery_title)) {
                SettingsRow(stringResource(R.string.settings_securityScreen_cardRecovery_toggle), null, icon = L.drawable.lucide_ic_life_buoy) {
                    Switch(enabled, { on ->
                        recoveryOverride = on
                        scope.launch {
                            runCatching { c.session.api.value.setCardRecovery(on); c.session.refreshMe() }
                                .onSuccess { context.toast(if (on) recoveryOn else recoveryOff) }
                                .onFailure { context.toast(it.message ?: recoveryFailed) }
                            recoveryOverride = null
                        }
                    }, Modifier.testTag("card-recovery-toggle"), enabled = recoveryOverride == null, colors = lwSwitchColors())
                }
                MutedText(stringResource(R.string.settings_securityScreen_cardRecovery_hint), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), size = 13.sp)
            }
        }
        SettingsGroup(stringResource(R.string.settings_dangerZone)) {
            SettingsRow(stringResource(R.string.security_delete_account), { confirmDelete = true }, Modifier.testTag("delete-account"), labelColor = Lw.Red, icon = L.drawable.lucide_ic_trash_2)
        }
    }
    // Modal while the request runs (back is ignored), so the screen can't be left with the account gone but the session kept.
    if (confirmDelete) AlertDialog(
        onDismissRequest = { if (!deleting) confirmDelete = false },
        title = { Txt(stringResource(R.string.security_delete_account), weight = FontWeight.SemiBold) },
        text = { Txt(stringResource(R.string.security_delete_confirm)) },
        confirmButton = {
            TextButton({
                deleting = true
                scope.launch {
                    runCatching { c.session.api.value.deleteAccount() }
                        .onSuccess { ok -> if (ok) { context.toast(deletedMsg); c.session.logout() } else deleteUnsupported = true }
                        .onFailure { context.toast(it.message ?: deleteFailed) }
                    deleting = false
                    confirmDelete = false
                }
            }, Modifier.testTag("confirm-delete-account"), enabled = !deleting) { Txt(stringResource(R.string.security_delete_account), color = Lw.Red) }
        },
        dismissButton = { TextButton({ confirmDelete = false }, enabled = !deleting) { Txt(stringResource(R.string.common_cancel)) } },
        containerColor = Glass.Dialog,
    )
    if (deleteUnsupported) AlertDialog(
        onDismissRequest = { deleteUnsupported = false },
        text = { Txt(stringResource(R.string.security_delete_unsupported)) },
        confirmButton = {
            TextButton({
                deleteUnsupported = false
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lawalletio/android-wallet/blob/main/PRIVACY.md#account-deletion")))
            }) { Txt(stringResource(R.string.security_delete_steps)) }
        },
        dismissButton = { TextButton({ deleteUnsupported = false }) { Txt(stringResource(R.string.common_cancel)) } },
        containerColor = Glass.Dialog,
    )
    editing?.let { pk -> PasskeyDialog(pk, passkeys?.size ?: 0, onDone = { changed -> editing = null; if (changed) scope.launch { passkeys = runCatching { c.session.api.value.passkeys() }.getOrNull() } }) }
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        text = { Txt(stringResource(R.string.settings_securityScreen_removeConfirm)) },
        confirmButton = { TextButton({ confirmRemove = false; c.session.logout() }) { Txt(stringResource(R.string.settings_removeWallet), color = Lw.Red) } },
        dismissButton = { TextButton({ confirmRemove = false }) { Txt(stringResource(R.string.common_cancel)) } },
        containerColor = Glass.Dialog,
    )
}

private fun roleLabel(role: String) = when (role) {
    "ADMIN" -> R.string.settings_securityScreen_roles_ADMIN
    "OPERATOR" -> R.string.settings_securityScreen_roles_OPERATOR
    "VIEWER" -> R.string.settings_securityScreen_roles_VIEWER
    else -> R.string.settings_securityScreen_roles_USER
}

@Composable
private fun PasskeyDialog(pk: PasskeyCredential, total: Int, onDone: (Boolean) -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var label by remember { mutableStateOf(pk.label ?: "") }
    var deleting by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = { onDone(false) },
        title = { Txt(stringResource(if (deleting) (if (total <= 1) R.string.settings_passkeysScreen_deleteOnlyTitle else R.string.settings_passkeysScreen_deleteTitle) else R.string.settings_passkeysScreen_manage), size = 16.sp, weight = FontWeight.SemiBold) },
        text = {
            if (deleting) Txt(stringResource(if (total <= 1) R.string.settings_passkeysScreen_deleteOnlyBody else R.string.settings_passkeysScreen_deleteBody), color = Lw.MutedFg)
            else OutlinedTextField(label, { label = it.take(64) }, label = { Txt(stringResource(R.string.settings_passkeysScreen_name)) }, singleLine = true, colors = lwFieldColors())
        },
        confirmButton = {
            if (deleting) TextButton({ scope.launch { runCatching { c.session.api.value.deletePasskey(pk.id) }.onSuccess { onDone(true) }.onFailure { context.toast(it.message ?: "") } } }) { Txt(stringResource(R.string.settings_passkeysScreen_deleteConfirm), color = Lw.Red) }
            else TextButton({ scope.launch { runCatching { c.session.api.value.renamePasskey(pk.id, label) }.onSuccess { onDone(true) }.onFailure { context.toast(it.message ?: "") } } }, enabled = label.isNotBlank()) { Txt(stringResource(R.string.settings_passkeysScreen_saveName)) }
        },
        dismissButton = {
            if (!deleting) TextButton({ deleting = true }) { Txt(stringResource(R.string.settings_passkeysScreen_delete), color = Lw.Red) }
            else TextButton({ onDone(false) }) { Txt(stringResource(R.string.common_cancel)) }
        },
        containerColor = Glass.Dialog,
    )
}

@Composable
fun CurrenciesScreen() {
    val c = LocalContainer.current
    val state by c.prefs.currencies.collectAsState()
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun update(active: List<String>) {
        val next = state.copy(active = active, selected = state.selected.takeIf { it in active } ?: "SAT", localEdit = true)
        c.prefs.setCurrencies(next)
        scope.launch { runCatching { c.session.api.value.setCurrencyPrefs(io.lawallet.wallet.core.api.CurrencyPrefs(next.active, next.selected)) } }
    }
    SettingsPage(stringResource(R.string.settings_currenciesScreen_title)) {
        SettingsGroup(stringResource(R.string.settings_currenciesScreen_activeAssets)) {
            val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
            var dragging by remember { mutableStateOf<String?>(null) }
            var dragY by remember { mutableStateOf(0f) }
            state.active.forEachIndexed { i, code ->
                if (i > 0) GroupDivider()
                Row(
                    Modifier.fillMaxWidth().height(56.dp).then(if (dragging == code) Modifier.offset { IntOffset(0, dragY.roundToInt()) }.background(Color.White.copy(alpha = 0.10f)) else Modifier).padding(horizontal = 12.dp).testTag("active-$code"),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (code == "SAT") LwIcon(L.drawable.lucide_ic_lock, size = 18.dp, tint = Lw.MutedFg)
                    else Box(Modifier.size(28.dp).pointerInput(state.active) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { dragging = code; dragY = 0f },
                            onDragEnd = {
                                val from = state.active.indexOf(code)
                                val to = (from + (dragY / rowPx).roundToInt()).coerceIn(1, state.active.size - 1)
                                if (to != from) update(state.active.toMutableList().apply { add(to, removeAt(from)) })
                                dragging = null; dragY = 0f
                            },
                            onDragCancel = { dragging = null; dragY = 0f },
                        ) { _, d -> dragY += d.y }
                    }, contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_grip_vertical, size = 18.dp, tint = Lw.MutedFg) }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Txt(code, size = 15.sp, weight = FontWeight.SemiBold)
                        Txt(if (code == "SAT") stringResource(R.string.settings_currenciesScreen_alwaysActive) else Currencies.displayName(code), size = 12.sp, color = Lw.MutedFg)
                    }
                    if (code != "SAT") Box(Modifier.size(28.dp).clip(CircleShape).background(Lw.Red.copy(alpha = 0.15f)).clickable { update(state.active - code) }.testTag("remove-$code"), contentAlignment = Alignment.Center) {
                        LwIcon(L.drawable.lucide_ic_minus, size = 16.dp, tint = Lw.Red)
                    }
                }
            }
        }
        MutedText(stringResource(R.string.settings_currenciesScreen_hint), size = 12.sp)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Txt(stringResource(R.string.settings_currenciesScreen_searchPlaceholder), color = Lw.MutedFg) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = lwFieldColors(),
            leadingIcon = { LwIcon(L.drawable.lucide_ic_search, size = 18.dp, tint = Lw.MutedFg) })
        SettingsGroup(stringResource(R.string.settings_currenciesScreen_available)) {
            val available = Currencies.CATALOG.filter { it !in state.active }.filter { query.isBlank() || it.contains(query, true) || Currencies.displayName(it).contains(query, true) }
            if (available.isEmpty()) MutedText(stringResource(R.string.settings_currenciesScreen_noMatch), Modifier.padding(16.dp))
            available.forEachIndexed { i, code ->
                if (i > 0) GroupDivider()
                SettingsRow("$code · ${Currencies.displayName(code)}", { update(state.active + code) }, Modifier.testTag("add-$code")) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Lw.Green.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_plus, size = 16.dp, tint = Lw.Green) }
                }
            }
        }
    }
}

@Composable
fun LanguageScreen() {
    val current = AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').ifEmpty { "auto" }
    var selected by remember { mutableStateOf(current) }
    val deviceLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    SettingsPage(stringResource(R.string.settings_language)) {
        SettingsGroup {
            listOf("auto" to stringResource(R.string.settings_languageScreen_automatic), "en" to "English", "es" to "Español").forEachIndexed { i, (tag, label) ->
                if (i > 0) GroupDivider()
                SettingsRow(label, {
                    selected = tag
                    AppCompatDelegate.setApplicationLocales(if (tag == "auto") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
                }, value = if (tag == "auto") deviceLocale.displayLanguage else null) {
                    RadioButton(selected == tag, null, colors = RadioButtonDefaults.colors(selectedColor = LocalBrand.current.t400))
                }
            }
        }
    }
}

@Composable
fun PaymentSoundScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val sound by c.prefs.paymentSound.collectAsState()
    SettingsPage(stringResource(R.string.settings_paymentSound)) {
        SettingsGroup(stringResource(R.string.settings_paymentSoundScreen_successful)) {
            listOf(
                "chime" to (R.string.settings_paymentSoundScreen_sounds_chime_label to R.string.settings_paymentSoundScreen_sounds_chime_description),
                "bananero" to (R.string.settings_paymentSoundScreen_sounds_bananero_label to R.string.settings_paymentSoundScreen_sounds_bananero_description),
                "off" to (R.string.settings_paymentSoundScreen_sounds_off_label to R.string.settings_paymentSoundScreen_sounds_off_description),
            ).forEachIndexed { i, (k, labels) ->
                if (i > 0) GroupDivider()
                Row(Modifier.fillMaxWidth().clickable { c.prefs.setPaymentSound(k); PaymentSound.play(context, k) }.padding(horizontal = 16.dp, vertical = 12.dp).testTag("sound-$k"), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Txt(stringResource(labels.first), size = 16.sp, weight = FontWeight.Medium)
                        Txt(stringResource(labels.second), size = 12.sp, color = Lw.MutedFg)
                    }
                    RadioButton(sound == k, null, colors = RadioButtonDefaults.colors(selectedColor = LocalBrand.current.t400))
                }
            }
        }
    }
}

@Composable
fun InstanceScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(c.session.instanceUrl) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.instance_invalid)
    val switched = stringResource(R.string.instance_switched)
    fun switch(target: String) {
        loading = true; error = null
        scope.launch {
            runCatching { c.session.switchInstance(target) }
                .onSuccess { context.toast(switched.format(it)); nav.popBackStack() }
                .onFailure { error = invalid + (it.message?.let { m -> "\n$m" } ?: "") }
            loading = false
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        SettingsHeader(stringResource(R.string.instance_title), { nav.popBackStack() }, stringResource(R.string.common_back))
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MutedText(stringResource(R.string.instance_description))
            SettingsGroup { SettingsRow(stringResource(R.string.instance_current), null, value = c.session.instanceUrl) }
            OutlinedTextField(url, { url = it.trim(); error = null }, Modifier.fillMaxWidth().testTag("instance-input"), label = { Txt(stringResource(R.string.instance_url)) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = lwFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            ErrorText(error, Modifier.fillMaxWidth())
            if (c.session.instanceUrl != BuildConfig.DEFAULT_INSTANCE) LwButton(stringResource(R.string.instance_reset, BuildConfig.DEFAULT_INSTANCE.removePrefix("https://")), { url = BuildConfig.DEFAULT_INSTANCE; switch(BuildConfig.DEFAULT_INSTANCE) }, Modifier.fillMaxWidth(), BtnVariant.Ghost)
        }
        LwButton(stringResource(R.string.instance_save), { switch(url) }, Modifier.fillMaxWidth().padding(16.dp).testTag("instance-save"), BtnVariant.Theme, enabled = url.isNotBlank(), loading = loading)
    }
}

@Composable
fun DiagnosticsScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val reports by c.prefs.crashReports.collectAsState()
    val exits = remember { Diagnostics.exits(c.prefs).asReversed() }
    val copied = stringResource(R.string.diag_copied)
    SettingsPage(stringResource(R.string.diag_title)) {
        if (Diagnostics.available) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsGroup {
                SettingsRow(stringResource(R.string.diag_crash_reports), { c.prefs.setCrashReports(!reports) }, Modifier.testTag("diag-reports"), icon = L.drawable.lucide_ic_bug) {
                    Switch(reports, { c.prefs.setCrashReports(it) }, enabled = !BuildConfig.BETA, colors = lwSwitchColors())
                }
            }
            MutedText(stringResource(if (BuildConfig.BETA) R.string.diag_crash_reports_beta else R.string.diag_crash_reports_body), Modifier.padding(horizontal = 6.dp), size = 12.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsGroup(stringResource(R.string.diag_exits)) {
                when {
                    android.os.Build.VERSION.SDK_INT < 30 -> MutedText(stringResource(R.string.diag_exits_unsupported), Modifier.padding(16.dp))
                    exits.isEmpty() -> MutedText(stringResource(R.string.diag_exits_empty), Modifier.padding(16.dp))
                    else -> exits.forEachIndexed { i, e ->
                        if (i > 0) GroupDivider()
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp).testTag("exit-$i")) {
                            Txt(e.reason.replace('_', ' ').uppercase(), size = 14.sp, weight = FontWeight.SemiBold, color = if (e.abnormal) Lw.Red else Lw.Foreground)
                            Txt(Diagnostics.summary(e), size = 12.sp, color = Lw.MutedFg)
                        }
                    }
                }
            }
            MutedText(stringResource(R.string.diag_exits_hint), Modifier.padding(horizontal = 6.dp), size = 12.sp)
        }
        LwButton(stringResource(R.string.diag_copy), { context.copyToClipboard(Diagnostics.report(c.prefs), copied) }, Modifier.fillMaxWidth().testTag("diag-copy"), BtnVariant.Secondary)
    }
}

@Composable
fun UpdatesScreen() {
    val c = LocalContainer.current
    val activity = androidx.activity.compose.LocalActivity.current
    val scope = rememberCoroutineScope()
    val state by c.updater.state.collectAsState()
    LaunchedEffect(Unit) { if (state is Updater.State.Idle || state is Updater.State.Failed) c.updater.checkNow() }
    SettingsPage(stringResource(R.string.update_title)) {
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(16.dp).testTag("update-status"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt(stringResource(R.string.update_installed, BuildConfig.VERSION_NAME), size = 13.sp, color = Lw.MutedFg)
            when (val s = state) {
                Updater.State.Idle, Updater.State.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spinner(16.dp); Txt(stringResource(R.string.update_checking))
                }
                Updater.State.UpToDate -> Txt(stringResource(R.string.update_latest), weight = FontWeight.Medium)
                is Updater.State.Available -> {
                    Txt(if (s.via == Updater.Via.PLAY) stringResource(R.string.update_available_play) else stringResource(R.string.update_available, s.version), weight = FontWeight.Medium)
                    val label = when (s.via) { Updater.Via.PLAY -> R.string.update_play; Updater.Via.DOWNLOAD -> R.string.update_download; Updater.Via.RELEASE_PAGE -> R.string.update_page }
                    LwButton(stringResource(label), { activity?.let { c.updater.start(it, c.scope) } }, Modifier.fillMaxWidth().testTag("update-start"), BtnVariant.Theme, icon = L.drawable.lucide_ic_download)
                }
                is Updater.State.Downloading -> {
                    Txt(stringResource(R.string.update_downloading, (s.progress * 100).roundToInt()))
                    androidx.compose.material3.LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = LocalBrand.current.t400, trackColor = Color.White.copy(alpha = 0.08f))
                }
                Updater.State.Installing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spinner(16.dp); Txt(stringResource(R.string.update_installing))
                }
                is Updater.State.Failed -> ErrorText(stringResource(R.string.update_failed, s.reason))
            }
        }
        val busy = state is Updater.State.Checking || state is Updater.State.Downloading || state is Updater.State.Installing
        LwButton(stringResource(R.string.update_check), { scope.launch { c.updater.checkNow() } }, Modifier.fillMaxWidth().testTag("update-check"), BtnVariant.Secondary, enabled = !busy)
    }
}
