package io.lawallet.wallet.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.app.AuthState
import io.lawallet.wallet.core.api.ActivationClaim
import io.lawallet.wallet.core.api.ActivationPreview
import io.lawallet.wallet.core.api.ApiException
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.AspectCard
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

/** activate-client.tsx — preview, inline auth (passkey / new key / existing account), claim, success. */
@Composable
fun ActivateScreen(tokenId: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val brand = LocalBrand.current
    val activity = androidx.activity.compose.LocalActivity.current!!
    val scope = rememberCoroutineScope()
    val auth by c.session.auth.collectAsState()
    var preview by remember { mutableStateOf<ActivationPreview?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var claim by remember { mutableStateOf<ActivationClaim?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var newKey by remember { mutableStateOf<Keys?>(null) }
    var saved by remember { mutableStateOf(false) }
    val invalid = stringResource(R.string.activate_error_invalidLink)
    val failed = stringResource(R.string.activate_failed)
    val cancelled = stringResource(R.string.auth_passkeyErrors_cancelled)
    val prfMsg = stringResource(R.string.auth_passkeyErrors_prf_unsupported)

    LaunchedEffect(tokenId) {
        runCatching { c.session.api.value.activationPreview(tokenId) }
            .onSuccess { preview = it }
            .onFailure { loadError = if (it is ApiException && it.status == 404) invalid else it.message }
    }

    suspend fun doClaim() {
        runCatching { c.session.api.value.claimActivation(tokenId) }
            .onSuccess {
                claim = it
                c.prefs.pendingActivation = null
                it.bonuses?.sats?.amountSats?.takeIf { _ -> it.bonuses.sats.granted }?.let { b -> c.prefs.bonusSats = b }
                c.scope.launch { runCatching { c.session.refreshMe() }; c.wallet.refreshBalance() }
            }
            .onFailure { error = it.message ?: failed }
    }

    fun withAuth(block: suspend () -> Unit) {
        busy = true; error = null
        scope.launch { runCatching { block() }.onFailure { error = passkeyError(it, cancelled, prfMsg) }; busy = false }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(null, { if (auth == AuthState.LoggedIn) nav.goHome() else nav.popBackStack() }, close = true)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp)) {
            val p = preview
            when {
                loadError != null -> StatusBlock(stringResource(R.string.activate_error_title), loadError!!)
                p == null -> Box(Modifier.padding(48.dp)) { Spinner() }
                claim != null -> {
                    Box(Modifier.size(80.dp).clip(CircleShape).background(brand.t400.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_check, size = 40.dp, tint = brand.t400) }
                    Txt(stringResource(R.string.activate_success_title), size = 24.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("activate-success"))
                    MutedText(claim?.card?.title?.let { it + " " + stringResource(R.string.activate_success_linkedNamed).trim() } ?: stringResource(R.string.activate_success_linked), align = TextAlign.Center)
                    claim?.bonuses?.sats?.amountSats?.let { MutedText(stringResource(R.string.activate_success_bonus, Currencies.formatSats(it)), align = TextAlign.Center) }
                }
                p.status == "CLAIMED" -> StatusBlock(stringResource(R.string.activate_claimed_title), stringResource(R.string.activate_claimed_body))
                p.status == "EXPIRED" -> StatusBlock(stringResource(R.string.activate_expired_title), stringResource(R.string.activate_expired_body))
                p.status == "REVOKED" -> StatusBlock(stringResource(R.string.activate_revoked_title), stringResource(R.string.activate_revoked_body))
                p.qrKind != "ONE_TIME" -> StatusBlock(stringResource(R.string.activate_unsupported_title), stringResource(R.string.activate_unsupported_body))
                else -> {
                    Txt(stringResource(R.string.activate_eyebrow), size = 15.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
                    AspectCard(p.card?.design?.imageUrl?.let { absolute(c.session.instanceUrl, it) }, Modifier.fillMaxWidth())
                    p.card?.title?.let { Txt(it, size = 20.sp, weight = FontWeight.SemiBold) }
                    if (auth != AuthState.LoggedIn) {
                        MutedText(stringResource(R.string.activate_inlineAuth_intro), align = TextAlign.Center)
                        newKey?.let { k ->
                            Txt(stringResource(R.string.activate_inlineAuth_newKeyTitle), weight = FontWeight.SemiBold)
                            MutedText(stringResource(R.string.activate_inlineAuth_newKeyBody), align = TextAlign.Center)
                            SecretKeyReveal(k.nsec, saved, { saved = it })
                        }
                    }
                    ErrorText(error, Modifier.fillMaxWidth())
                }
            }
        }
        val p = preview
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                claim != null -> {
                    if (claim?.needsLightningAddress == true) LwButton(stringResource(R.string.activate_success_claimAddress), { nav.navigate("${R0.CLAIM}?from=activate&bonus=${if (claim?.bonuses?.freeLightningAddress == true) 1 else 0}") { popUpTo(R0.HOME) } }, Modifier.fillMaxWidth(), BtnVariant.Theme)
                    LwButton(stringResource(R.string.activate_openWallet), { nav.goHome() }, Modifier.fillMaxWidth(), BtnVariant.Secondary)
                }
                p == null || p.status != "PENDING" || p.qrKind != "ONE_TIME" -> LwButton(stringResource(R.string.scan_backToWallet), { nav.goHome() }, Modifier.fillMaxWidth(), BtnVariant.Secondary)
                auth == AuthState.LoggedIn -> LwButton(stringResource(if (busy) R.string.activate_activating else R.string.activate_activate), { busy = true; error = null; scope.launch { doClaim(); busy = false } }, Modifier.fillMaxWidth().testTag("activate-submit"), BtnVariant.Theme, loading = busy)
                newKey != null -> LwButton(stringResource(if (busy) R.string.activate_inlineAuth_activating else R.string.activate_inlineAuth_createAndActivate), {
                    withAuth { c.session.loginWithSecret(newKey!!.secretHex, "nsec"); doClaim() }
                }, Modifier.fillMaxWidth(), BtnVariant.Theme, enabled = saved, loading = busy)
                else -> {
                    LwButton(stringResource(R.string.activate_inlineAuth_passkey), {
                        withAuth { val id = c.passkeys.register(activity, null, false); c.session.loginWithSecret(id.secretHex, "passkey"); doClaim() }
                    }, Modifier.fillMaxWidth(), BtnVariant.Theme, loading = busy, icon = L.drawable.lucide_ic_fingerprint_pattern)
                    LwButton(stringResource(R.string.activate_inlineAuth_nostr), { newKey = Keys.generate() }, Modifier.fillMaxWidth(), BtnVariant.Secondary, icon = L.drawable.lucide_ic_key_round)
                    LwButton(stringResource(R.string.activate_inlineAuth_haveAccount), {
                        c.prefs.pendingActivation = tokenId to System.currentTimeMillis()
                        nav.navigate(R0.LOGIN)
                    }, Modifier.fillMaxWidth(), BtnVariant.Ghost)
                }
            }
        }
    }
}

@Composable
private fun StatusBlock(title: String, body: String) {
    Column(Modifier.padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Txt(title, size = 22.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
        MutedText(body, align = TextAlign.Center)
    }
}

fun absolute(base: String, url: String) = if (url.startsWith("http")) url else base.trimEnd('/') + "/" + url.trimStart('/')
