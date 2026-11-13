package io.lawallet.wallet.ui.screens

import kotlinx.serialization.builtins.ListSerializer
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.api.WalletAddress
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.GlassIconButton
import io.lawallet.wallet.core.ln.DestinationParser
import io.lawallet.wallet.ui.components.GroupDivider
import io.lawallet.wallet.ui.components.LwAlertDialog
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.ModalAction
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.SettingsGroup
import io.lawallet.wallet.ui.components.SettingsHeader
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.WalletMark
import io.lawallet.wallet.ui.components.copyToClipboard
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.sharedAddress
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.launch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.composables.icons.lucide.R as L

/**
 * The user's lightning addresses: the primary up top (Home's address pill expands into it), then every
 * address with where its payments land. Tap one to copy it, make it primary, pick the wallet it
 * receives into, or delete it. New ones go through the claim flow (it handles paid registration).
 */
@Composable
fun LightningAddressesScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings by c.session.settings.collectAsState()
    val me by c.session.me.collectAsState()
    val domain = settings?.domain ?: me?.lightningAddress?.substringAfter('@', "") ?: c.session.instanceHost
    // Last list seen, shown at once (offline too) while the server's answer comes in.
    val addressesSer = ListSerializer(WalletAddress.serializer())
    var addresses by remember { mutableStateOf(c.prefs.cached("addresses", addressesSer)) }
    var wallets by remember { mutableStateOf(c.prefs.cached("remote-wallets", remoteWalletsSer).orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var acting by remember { mutableStateOf<WalletAddress?>(null) }
    var picking by remember { mutableStateOf<WalletAddress?>(null) }
    var deleting by remember { mutableStateOf<WalletAddress?>(null) }
    var redirecting by remember { mutableStateOf<WalletAddress?>(null) }
    var busy by remember { mutableStateOf<String?>(null) } // the username being changed
    val copied = stringResource(R.string.common_copied)
    suspend fun load() {
        val api = c.session.api.value
        runCatching { api.addresses() }.onSuccess { addresses = it; error = null; c.prefs.setCached("addresses", addressesSer, it) }.onFailure { error = it.message }
        runCatching { api.remoteWallets() }.onSuccess { wallets = it; c.prefs.setCached("remote-wallets", remoteWalletsSer, it) }
    }
    // On every return too: an address added through the claim flow shows up when it pops back here.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scope.launch { load() } }
    // One change at a time: the row shows a spinner, then the list (and Home's pill, via me) refresh.
    fun change(a: WalletAddress, block: suspend () -> Unit) {
        if (busy != null) return
        busy = a.username
        scope.launch {
            runCatching { block() }.onFailure { context.toast(it.message ?: "") }
            load(); runCatching { c.session.refreshMe() }
            busy = null
        }
    }
    fun full(a: WalletAddress) = "${a.username}@$domain"

    Column(Modifier.fillMaxSize()) {
        SettingsHeader(stringResource(R.string.addresses_title), { nav.popBackStack() }, stringResource(R.string.common_back))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            // Hero: the primary, in the pill's shape so the expand reads as the pill growing.
            val primary = addresses?.firstOrNull { it.isPrimary } ?: addresses?.firstOrNull()
            val heroText = primary?.let(::full) ?: me?.lightningAddress
            Column(
                Modifier.fillMaxWidth().sharedAddress().glass(RoundedCornerShape(24.dp)).padding(20.dp).testTag("addresses-hero"),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.size(48.dp).glass(CircleShape, GlassLevel.Strong), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_at_sign, size = 22.dp) }
                Spacer(Modifier.height(12.dp))
                if (heroText != null) {
                    Txt(heroText.substringBefore('@'), size = 24.sp, weight = FontWeight.SemiBold, maxLines = 1)
                    Txt("@" + heroText.substringAfter('@'), size = 16.sp, weight = FontWeight.Medium, color = Lw.Foreground.copy(alpha = 0.6f), maxLines = 1)
                    Spacer(Modifier.height(14.dp))
                    LwButton(stringResource(R.string.common_copy), { context.copyToClipboard(heroText, copied) }, Modifier.testTag("addresses-copy"), BtnVariant.Secondary, height = 36.dp, icon = L.drawable.lucide_ic_copy)
                } else if (addresses == null) Spinner() else MutedText(stringResource(R.string.addresses_empty))
            }
            val list = addresses
            if (list == null && error == null) Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Spinner() }
            if (!list.isNullOrEmpty()) SettingsGroup(stringResource(R.string.addresses_all)) {
                list.forEachIndexed { i, a ->
                    if (i > 0) GroupDivider()
                    AddressRow(a, full(a), busy == a.username) { acting = a }
                }
            }
            ErrorText(error, Modifier.fillMaxWidth())
        }
        LwButton(
            stringResource(R.string.addresses_add), { nav.navigate("${R0.CLAIM}?from=addresses&bonus=0") },
            Modifier.fillMaxWidth().padding(16.dp).testTag("addresses-add"), BtnVariant.Theme, icon = L.drawable.lucide_ic_plus,
        )
    }

    acting?.let { a ->
        AddressActionsSheet(
            full(a), a.isPrimary, onDismiss = { acting = null },
            onCopy = { context.copyToClipboard(full(a), copied) },
            onPrimary = { change(a) { c.session.api.value.setPrimaryAddress(a.username) } },
            onReceiveInto = { picking = a },
            onRedirect = { redirecting = a },
            onDelete = { deleting = a },
        )
    }
    val invalid = stringResource(R.string.addresses_redirectInvalid)
    redirecting?.let { a ->
        TextPrompt(stringResource(R.string.addresses_redirect), a.redirect.orEmpty(), onDismiss = { redirecting = null }, saveLabel = stringResource(R.string.addresses_redirectSave), placeholder = "name@domain.com", maxLength = 128) { v ->
            val target = v.lowercase()
            // Pointing an address at itself would loop; the server also refuses wider cycles.
            if (!DestinationParser.isLightningAddress(target) || target == full(a)) { context.toast(invalid); return@TextPrompt }
            redirecting = null
            change(a) { c.session.api.value.redirectAddress(a.username, target) }
        }
    }
    picking?.let { a ->
        WalletPickerSheet(wallets.filter { it.status == "ACTIVE" }, a.remoteWalletId.takeIf { a.mode == "CUSTOM_NWC" }, onDismiss = { picking = null }) { w ->
            picking = null
            change(a) { c.session.api.value.routeAddressToWallet(a.username, w.id) }
        }
    }
    deleting?.let { a ->
        LwAlertDialog(
            onDismissRequest = { deleting = null },
            title = { Txt(stringResource(R.string.addresses_delete)) },
            text = { Txt(stringResource(R.string.addresses_deleteConfirm, full(a))) },
            confirmButton = { ModalAction(stringResource(R.string.addresses_delete), { deleting = null; change(a) { c.session.api.value.deleteAddress(a.username) } }, destructive = true) },
        )
    }
}

/** username@domain, where its payments go, and a Primary pill. */
@Composable
private fun AddressRow(a: WalletAddress, full: String, busy: Boolean, onClick: () -> Unit) {
    val brand = LocalBrand.current
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !busy, onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp).testTag("address-${a.username}"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(40.dp).glass(CircleShape, GlassLevel.Strong), contentAlignment = Alignment.Center) {
            LwIcon(L.drawable.lucide_ic_at_sign, size = 18.dp, tint = if (a.isPrimary) brand.t400 else Lw.MutedFg)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Txt(full, size = 15.sp, weight = FontWeight.SemiBold, maxLines = 1)
            Txt(
                when (a.mode) {
                    "CUSTOM_NWC" -> stringResource(R.string.addresses_receivesInto, a.remoteWalletName ?: "—")
                    "ALIAS", "PROXY_ALIAS" -> stringResource(R.string.addresses_forwardsTo, a.redirect ?: "—")
                    else -> stringResource(R.string.addresses_notReceiving)
                },
                size = 12.sp, color = if (a.mode == "IDLE") Lw.Orange else Lw.MutedFg, maxLines = 1,
            )
        }
        Spacer(Modifier.width(8.dp))
        when {
            busy -> Spinner(18.dp)
            a.isPrimary -> Txt(
                stringResource(R.string.remoteWallets_list_primary),
                Modifier.glass(CircleShape, GlassLevel.Thin, tint = brand.t400.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp),
                size = 11.sp, weight = FontWeight.SemiBold, color = brand.t400,
            )
            else -> LwIcon(L.drawable.lucide_ic_chevron_right, size = 18.dp, tint = Lw.MutedFg)
        }
    }
}

/** Same sheet style as a wallet's menu: the address up top, one row per action. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressActionsSheet(full: String, isPrimary: Boolean, onDismiss: () -> Unit, onCopy: () -> Unit, onPrimary: () -> Unit, onReceiveInto: () -> Unit, onRedirect: () -> Unit, onDelete: () -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val scope = rememberCoroutineScope()
    val brand = LocalBrand.current
    fun then(action: () -> Unit) { scope.launch { state.hide() }.invokeOnCompletion { onDismiss(); action() } }
    AppSheet(state, onDismiss, "address-menu") {
        Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 16.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Txt(full.substringBefore('@'), size = 19.sp, weight = FontWeight.SemiBold, maxLines = 1)
                Txt("@" + full.substringAfter('@'), size = 14.sp, color = Lw.MutedFg, maxLines = 1)
            }
            // Copy sits with the address it copies; the sheet stays open.
            GlassIconButton(L.drawable.lucide_ic_copy, onCopy, Modifier.testTag("address-menu-copy"), size = 40.dp, contentDescription = stringResource(R.string.common_copy))
        }
        Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(Color.White.copy(alpha = 0.08f)))
        if (!isPrimary) SheetAction(L.drawable.lucide_ic_circle_check, stringResource(R.string.addresses_setPrimary), brand.t400, "address-menu-primary") { then(onPrimary) }
        SheetAction(L.drawable.lucide_ic_wallet, stringResource(R.string.addresses_receiveInto), Lw.Foreground, "address-menu-wallet") { then(onReceiveInto) }
        SheetAction(L.drawable.lucide_ic_forward, stringResource(R.string.addresses_redirect), Lw.Foreground, "address-menu-redirect") { then(onRedirect) }
        SheetAction(L.drawable.lucide_ic_trash_2, stringResource(R.string.addresses_delete), Lw.Red, "address-menu-delete", Lw.Red) { then(onDelete) }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

/** Pick the remote wallet an address receives into; the current one is checked. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WalletPickerSheet(wallets: List<RemoteWallet>, currentId: String?, onDismiss: () -> Unit, onPick: (RemoteWallet) -> Unit) {
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val brand = LocalBrand.current
    AppSheet(state, onDismiss, "address-wallet-picker") {
        Txt(stringResource(R.string.addresses_pickWallet), Modifier.padding(horizontal = 24.dp, vertical = 12.dp), size = 17.sp, weight = FontWeight.SemiBold)
        if (wallets.isEmpty()) MutedText(stringResource(R.string.remoteWallets_list_emptyTitle), Modifier.padding(24.dp))
        wallets.forEach { w ->
            Row(
                Modifier.fillMaxWidth().clickable { onPick(w) }.padding(horizontal = 24.dp, vertical = 14.dp).testTag("pick-wallet-${w.name}"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).glass(RoundedCornerShape(12.dp), GlassLevel.Thin), contentAlignment = Alignment.Center) { WalletMark(w.provider == "lncurl", 20.dp) }
                Spacer(Modifier.width(14.dp))
                Txt(w.name, Modifier.weight(1f), size = 16.sp, weight = FontWeight.Medium, maxLines = 1)
                if (w.id == currentId) LwIcon(L.drawable.lucide_ic_check, size = 20.dp, tint = brand.t400)
            }
        }
        Spacer(Modifier.height(16.dp).navigationBarsPadding())
    }
}

/** The app's bottom-sheet chrome (glass, rounded top, slim handle), as on Home's wallet menu. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppSheet(state: androidx.compose.material3.SheetState, onDismiss: () -> Unit, tag: String, content: @Composable () -> Unit) =
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = state, containerColor = Glass.Dialog,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 10.dp, bottom = 6.dp).size(36.dp, 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) },
        modifier = Modifier.testTag(tag),
    ) { content() }
