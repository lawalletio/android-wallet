package io.lawallet.wallet.ui.screens

import io.lawallet.wallet.ui.components.SettingsPage
import io.lawallet.wallet.ui.components.lwFieldColors
import io.lawallet.wallet.ui.components.toastError
import io.lawallet.wallet.ui.components.ConfirmDialog
import io.lawallet.wallet.ui.components.DialogTitle
import io.lawallet.wallet.ui.components.SettingsSwitchRow
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import io.lawallet.wallet.ui.components.LwAlertDialog
import io.lawallet.wallet.ui.components.ModalAction
import io.lawallet.wallet.ui.components.autoFocus
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import io.lawallet.wallet.R
import io.lawallet.wallet.app.AppLock
import io.lawallet.wallet.core.api.OAuthGrant
import io.lawallet.wallet.core.api.PasskeyCredential
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.nostr.Nip19
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.GroupDivider
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.SettingsGroup
import io.lawallet.wallet.ui.components.SettingsRow
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.copyToClipboard
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.theme.Lw
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

@Composable
fun SecurityScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val activity = androidx.activity.compose.LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val signer by c.session.signer.collectAsState()
    var role by remember { mutableStateOf(c.prefs.cached("role", String.serializer())) }
    var revealed by remember { mutableStateOf<String?>(null) }
    val passkeysSer = ListSerializer(PasskeyCredential.serializer())
    val grantsSer = ListSerializer(OAuthGrant.serializer())
    var passkeys by remember { mutableStateOf(c.prefs.cached("passkeys", passkeysSer)) }
    var grants by remember { mutableStateOf(c.prefs.cached("grants", grantsSer)) }
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
        // Keep the cached values when offline.
        runCatching { c.session.api.value.jwtInfo().role }.onSuccess { role = it; if (it != null) c.prefs.setCached("role", String.serializer(), it) }
        runCatching { c.session.api.value.passkeys() }.onSuccess { passkeys = it; c.prefs.setCached("passkeys", passkeysSer, it) }
        runCatching { c.session.api.value.oauthGrants() }.onSuccess { grants = it; c.prefs.setCached("grants", grantsSer, it) }
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
                "bunker" -> stringResource(R.string.bunker_method)
                "nsec" -> stringResource(R.string.settings_securityScreen_methods_nsec)
                else -> stringResource(R.string.settings_securityScreen_unknown)
            })
        }
        if (method == "nsec" || method == "passkey") SettingsGroup(stringResource(R.string.settings_securityScreen_secretKey)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MutedText(stringResource(if (method == "passkey") R.string.settings_securityScreen_passkeyKeyHint else R.string.settings_securityScreen_nsecKeyHint), size = 13.sp)
                val secret = revealed
                if (secret == null) LwButton(stringResource(R.string.settings_securityScreen_revealSecret), {
                    scope.launch { if (AppLock.authenticate(activity, unlockTitle)) revealed = c.session.secretHex()?.let { Nip19.nsec(it) } }
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
                        .onFailure { context.toastError(it, addFailed) }
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
                SettingsSwitchRow(stringResource(R.string.settings_securityScreen_cardRecovery_toggle), enabled, { on ->
                        recoveryOverride = on
                        scope.launch {
                            runCatching { c.session.api.value.setCardRecovery(on); c.session.refreshMe() }
                                .onSuccess { context.toast(if (on) recoveryOn else recoveryOff) }
                                .onFailure { context.toastError(it, recoveryFailed) }
                            recoveryOverride = null
                        }
                }, icon = L.drawable.lucide_ic_life_buoy, enabled = recoveryOverride == null, switchModifier = Modifier.testTag("card-recovery-toggle"))
                MutedText(stringResource(R.string.settings_securityScreen_cardRecovery_hint), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), size = 13.sp)
            }
        }
        SettingsGroup(stringResource(R.string.settings_dangerZone)) {
            SettingsRow(stringResource(R.string.security_delete_account), { confirmDelete = true }, Modifier.testTag("delete-account"), labelColor = Lw.Red, icon = L.drawable.lucide_ic_trash_2)
        }
    }
    // Modal while the request runs (back is ignored), so the screen can't be left with the account gone but the session kept.
    if (confirmDelete) LwAlertDialog(
        onDismissRequest = { if (!deleting) confirmDelete = false },
        title = { DialogTitle(stringResource(R.string.security_delete_account)) },
        text = { Txt(stringResource(R.string.security_delete_confirm)) },
        confirmButton = {
            ModalAction(stringResource(R.string.security_delete_account), {
                deleting = true
                scope.launch {
                    runCatching { c.session.api.value.deleteAccount() }
                        .onSuccess { ok -> if (ok) { context.toast(deletedMsg); c.session.logout() } else deleteUnsupported = true }
                        .onFailure { context.toastError(it, deleteFailed) }
                    deleting = false
                    confirmDelete = false
                }
            }, Modifier.testTag("confirm-delete-account"), enabled = !deleting, destructive = true)
        },
    )
    if (deleteUnsupported) LwAlertDialog(
        onDismissRequest = { deleteUnsupported = false },
        text = { Txt(stringResource(R.string.security_delete_unsupported)) },
        confirmButton = {
            ModalAction(stringResource(R.string.security_delete_steps), {
                deleteUnsupported = false
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/lawalletio/android-wallet/blob/main/PRIVACY.md#account-deletion")))
            })
        },
    )
    editing?.let { pk -> PasskeyDialog(pk, passkeys?.size ?: 0, onDone = { changed -> editing = null; if (changed) scope.launch { passkeys = runCatching { c.session.api.value.passkeys() }.getOrNull() } }) }
    if (confirmRemove) ConfirmDialog(
        stringResource(R.string.settings_securityScreen_removeConfirm), stringResource(R.string.settings_removeWallet), { confirmRemove = false; c.session.logout() }, { confirmRemove = false },
        destructive = true,
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
    var saving by remember { mutableStateOf(false) }
    // Runs [call]; the field and actions stay locked until it answers.
    fun submit(call: suspend () -> Unit) {
        if (saving) return
        saving = true
        scope.launch { runCatching { call() }.onSuccess { onDone(true) }.onFailure { saving = false; context.toastError(it) } }
    }
    LwAlertDialog(
        onDismissRequest = { if (!saving) onDone(false) },
        title = { DialogTitle(stringResource(if (deleting) (if (total <= 1) R.string.settings_passkeysScreen_deleteOnlyTitle else R.string.settings_passkeysScreen_deleteTitle) else R.string.settings_passkeysScreen_manage)) },
        text = {
            if (deleting) Txt(stringResource(if (total <= 1) R.string.settings_passkeysScreen_deleteOnlyBody else R.string.settings_passkeysScreen_deleteBody), color = Lw.MutedFg)
            else OutlinedTextField(label, { label = it.take(64) }, Modifier.autoFocus(), enabled = !saving, label = { Txt(stringResource(R.string.settings_passkeysScreen_name)) }, singleLine = true, colors = lwFieldColors())
        },
        confirmButton = {
            if (deleting) ModalAction(stringResource(R.string.settings_passkeysScreen_deleteConfirm), { submit { c.session.api.value.deletePasskey(pk.id) } }, loading = saving, destructive = true)
            else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                ModalAction(stringResource(R.string.settings_passkeysScreen_saveName), { submit { c.session.api.value.renamePasskey(pk.id, label) } }, enabled = label.isNotBlank(), loading = saving)
                // Deleting is a second step (confirmed above), so it stays a quiet link under the main action.
                TextButton({ deleting = true }, Modifier.padding(top = 4.dp), enabled = !saving) { Txt(stringResource(R.string.settings_passkeysScreen_delete), color = Lw.Red) }
            }
        },
    )
}
