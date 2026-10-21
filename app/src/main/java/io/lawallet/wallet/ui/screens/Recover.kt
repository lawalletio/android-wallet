package io.lawallet.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import io.lawallet.wallet.R
import io.lawallet.wallet.core.api.AccountTransfer
import io.lawallet.wallet.core.api.RecoveredAccount
import io.lawallet.wallet.core.api.RecoverySide
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.LwCard
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.Instant
import java.util.Date
import com.composables.icons.lucide.R as L

/** `error.details.reason` on a RECOVER claim when the claimer has data and must agree to merge. */
const val RECOVERY_MERGE_REQUIRED = "RECOVERY_MERGE_REQUIRED"

/** "N cards · N wallets". */
@Composable
private fun counts(cards: Int, wallets: Int) =
    pluralStringResource(R.plurals.recovery_cards, cards, cards) + " · " + pluralStringResource(R.plurals.recovery_wallets, wallets, wallets)

/** recover-merge-dialog.tsx — a RECOVER claim into an account that already has data. */
@Composable
fun RecoverMergeDialog(existing: RecoverySide, incoming: RecoverySide, onConfirm: () -> Unit, onCancel: () -> Unit) {
    val brand = LocalBrand.current
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Txt(stringResource(R.string.activate_recover_merge_title), size = 20.sp, weight = FontWeight.SemiBold) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MutedText(stringResource(R.string.activate_recover_merge_body))
                MergeSide(stringResource(R.string.activate_recover_merge_yours), existing, Modifier)
                MergeSide(
                    stringResource(R.string.activate_recover_merge_incoming), incoming,
                    Modifier.border(1.dp, brand.t400.copy(alpha = 0.5f), RoundedCornerShape(16.dp)),
                )
                MutedText(stringResource(R.string.activate_recover_merge_keepsPrimary), size = 12.sp)
            }
        },
        confirmButton = {
            TextButton(onConfirm, Modifier.testTag("recover-merge-confirm")) {
                Txt(stringResource(R.string.activate_recover_merge_confirm), weight = FontWeight.SemiBold, color = brand.t400)
            }
        },
        dismissButton = { TextButton(onCancel) { Txt(stringResource(R.string.activate_recover_merge_cancel)) } },
        containerColor = Glass.Dialog,
    )
}

@Composable
private fun MergeSide(label: String, side: RecoverySide, modifier: Modifier) {
    LwCard(modifier.fillMaxWidth(), radius = 16.dp) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Txt(label.uppercase(), size = 11.sp, color = Lw.MutedFg, letterSpacing = 0.6.sp)
            if (side.lightningAddresses.isEmpty()) MutedText(stringResource(R.string.activate_recover_merge_noAddress))
            side.lightningAddresses.forEach { Txt(it, weight = FontWeight.Medium, maxLines = 1) }
            MutedText(counts(side.cards, side.remoteWallets), size = 13.sp)
        }
    }
}

/**
 * recovered-dialog.tsx, inline on the activation screen: what came over, with each wallet's live
 * balance read through the (now owned) `GET /api/remote-wallets/:id/balance`. A failed read shows
 * "Unavailable" for that wallet and the total sums the rest.
 */
@Composable
fun RecoveredContent(recovery: RecoveredAccount) {
    val c = LocalContainer.current
    val brand = LocalBrand.current
    // Absent = still loading; null = unavailable.
    val balances = remember { mutableStateMapOf<String, Long?>() }
    LaunchedEffect(recovery) {
        recovery.remoteWallets.forEach { w ->
            launch { balances[w.id] = runCatching { c.session.api.value.remoteWalletBalance(w.id) }.getOrNull() }
        }
    }
    val unavailable = stringResource(R.string.activate_recover_done_unavailable)
    val loaded = recovery.remoteWallets.mapNotNull { balances[it.id] }

    Box(Modifier.size(80.dp).clip(CircleShape).background(brand.t400.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
        LwIcon(L.drawable.lucide_ic_life_buoy, size = 40.dp, tint = brand.t400)
    }
    Txt(stringResource(R.string.activate_recover_done_title), size = 24.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("recover-success"))
    MutedText(
        stringResource(if (recovery.mergedIntoExisting) R.string.activate_recover_done_merged else R.string.activate_recover_done_body),
        align = TextAlign.Center,
    )
    LwCard(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Txt(stringResource(R.string.activate_recover_done_totalBalance).uppercase(), size = 11.sp, color = Lw.MutedFg, letterSpacing = 0.6.sp)
            Txt(
                if (recovery.remoteWallets.isNotEmpty() && loaded.isEmpty() && balances.size == recovery.remoteWallets.size) unavailable
                else Currencies.formatSats(loaded.sum()) + " sats",
                size = 28.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("recover-total"),
            )
        }
    }
    RecoveredSection(L.drawable.lucide_ic_zap, stringResource(R.string.activate_recover_done_addresses)) {
        if (recovery.lightningAddresses.isEmpty()) MutedText(stringResource(R.string.activate_recover_done_none))
        recovery.lightningAddresses.forEach { Txt(it, weight = FontWeight.Medium, maxLines = 1) }
    }
    RecoveredSection(L.drawable.lucide_ic_credit_card, stringResource(R.string.activate_recover_done_cards)) {
        if (recovery.cards.isEmpty()) MutedText(stringResource(R.string.activate_recover_done_none))
        recovery.cards.forEach { Txt(it.title ?: stringResource(R.string.activate_recover_done_untitledCard), weight = FontWeight.Medium, maxLines = 1) }
    }
    RecoveredSection(L.drawable.lucide_ic_wallet, stringResource(R.string.activate_recover_done_wallets)) {
        if (recovery.remoteWallets.isEmpty()) MutedText(stringResource(R.string.activate_recover_done_none))
        recovery.remoteWallets.forEach { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Txt(w.name, Modifier.weight(1f), weight = FontWeight.Medium, maxLines = 1)
                if (w.isDefault) {
                    Txt(
                        stringResource(R.string.activate_recover_done_defaultWallet), size = 11.sp, color = brand.t400,
                        modifier = Modifier.padding(horizontal = 8.dp).background(brand.t400.copy(alpha = 0.12f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
                Txt(
                    when {
                        !balances.containsKey(w.id) -> "…"
                        balances[w.id] == null -> unavailable
                        else -> Currencies.formatSats(balances[w.id]!!) + " sats"
                    },
                    color = Lw.MutedFg,
                )
            }
        }
    }
    if (recovery.vouchers > 0) MutedText(pluralStringResource(R.plurals.recovery_vouchers, recovery.vouchers, recovery.vouchers))
}

@Composable
private fun RecoveredSection(icon: Int, title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LwIcon(icon, size = 14.dp, tint = Lw.MutedFg)
            Box(Modifier.width(6.dp))
            Txt(title.uppercase(), size = 11.sp, color = Lw.MutedFg, letterSpacing = 0.6.sp)
        }
        LwCard(Modifier.fillMaxWidth(), radius = 16.dp) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        }
    }
}

/**
 * account-transferred-dialog.tsx — shown to the old owner after a card recovery moved their account
 * to another key, until they acknowledge it. Not dismissable by tapping outside or back.
 */
@Composable
fun AccountTransferredDialog(transfer: AccountTransfer) {
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val date = remember(transfer.transferredAt) {
        runCatching { DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date.from(Instant.parse(transfer.transferredAt))) }
            .getOrDefault(transfer.transferredAt)
    }
    val card = transfer.cardTitle ?: stringResource(R.string.settings_accountTransfer_unnamedCard)
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        icon = { LwIcon(L.drawable.lucide_ic_shield_alert, size = 28.dp, tint = Lw.Red) },
        title = { Txt(stringResource(R.string.settings_accountTransfer_title), size = 20.sp, weight = FontWeight.SemiBold, align = TextAlign.Center) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MutedText(stringResource(R.string.settings_accountTransfer_body, date, card))
                LwCard(Modifier.fillMaxWidth(), radius = 16.dp) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Txt(stringResource(R.string.settings_accountTransfer_moved).uppercase(), size = 11.sp, color = Lw.MutedFg, letterSpacing = 0.6.sp)
                        transfer.lightningAddresses.forEach { Txt(it, weight = FontWeight.Medium, maxLines = 1) }
                        MutedText(counts(transfer.cards, transfer.remoteWallets), size = 13.sp)
                    }
                }
                MutedText(stringResource(R.string.settings_accountTransfer_notYou), size = 12.sp)
                ErrorText(error)
            }
        },
        confirmButton = {
            TextButton({
                busy = true; error = null
                scope.launch {
                    runCatching { c.session.api.value.dismissAccountTransfer(); c.session.refreshMe() }
                        .onFailure { error = it.message }
                    busy = false
                }
            }, Modifier.testTag("account-transfer-dismiss"), enabled = !busy) {
                Txt(stringResource(R.string.settings_accountTransfer_dismiss), weight = FontWeight.SemiBold)
            }
        },
        containerColor = Glass.Dialog,
    )
}
