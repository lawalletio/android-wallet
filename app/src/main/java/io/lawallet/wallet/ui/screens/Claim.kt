package io.lawallet.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.core.api.ApiException
import io.lawallet.wallet.core.api.RegistrationInvoice
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.QrDisplay
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

private val USERNAME = Regex("^[a-z0-9]+$")

/** claim-address-screen.tsx + use-new-address-flow.ts: username → (402 → pay → verify → claim) → success. */
@Composable
fun ClaimUsernameScreen(from: String?, bonus: Boolean) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val brand = LocalBrand.current
    val scope = rememberCoroutineScope()
    val settings by c.session.settings.collectAsState()
    var username by rememberSaveable { mutableStateOf("") }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("username") } // username | payment | success
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var invoice by remember { mutableStateOf<RegistrationInvoice?>(null) }
    var claimed by remember { mutableStateOf<String?>(null) }
    val domain = settings?.domain ?: stringResource(R.string.claim_yourDomain)
    val fromActivate = from == "activate"
    val charsetErr = stringResource(R.string.claim_errors_charset)
    val maxErr = stringResource(R.string.claim_errors_maxLength)
    val createFailed = stringResource(R.string.claim_errors_createFailed)
    val noPayment = stringResource(R.string.claim_errors_noPaymentYet)
    val validation = when { username.isEmpty() -> null; username.length > 16 -> maxErr; !USERNAME.matches(username) -> charsetErr; else -> null }

    LaunchedEffect(username) {
        available = null
        if (username.isEmpty() || validation != null) return@LaunchedEffect
        delay(300)
        checking = true
        available = runCatching { c.session.api.value.checkUsername(username).available }.getOrNull()
        checking = false
    }

    suspend fun finish(address: String?) {
        claimed = address ?: "$username@$domain"
        step = "success"
        runCatching { c.session.refreshMe() }
    }

    suspend fun claimPaid(inv: RegistrationInvoice, preimage: String) {
        runCatching { c.session.api.value.claimInvoice(inv.id, preimage) }
            .onSuccess { finish(it.lightningAddress) }
            .onFailure { if (it.message?.contains("already been claimed", true) == true) finish(null) else error = it.message }
    }

    // Poll LUD-21 verify while the payment step is up.
    LaunchedEffect(invoice) {
        val inv = invoice ?: return@LaunchedEffect
        val verify = inv.verify ?: return@LaunchedEffect
        c.lnurl.pollVerify(verify, timeoutMs = 600_000)?.let { claimPaid(inv, it) }
    }

    androidx.activity.compose.BackHandler(step == "payment") { step = "username"; invoice = null }
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(stringResource(if (step == "payment") R.string.claim_paymentTitle else R.string.claim_headerTitle), if (fromActivate && step == "username") null else ({ if (step == "payment") { step = "username"; invoice = null } else nav.popBackStack() }), close = step != "payment")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (step) {
                "username" -> {
                    Txt(stringResource(R.string.claim_heading), size = 24.sp, weight = FontWeight.SemiBold)
                    MutedText(stringResource(when { fromActivate && bonus -> R.string.claim_introActivateFree; fromActivate -> R.string.claim_introActivate; else -> R.string.claim_intro }))
                    if (bonus && c.prefs.bonusSats > 0) MutedText(stringResource(R.string.claim_cardBonus, Currencies.formatSats(c.prefs.bonusSats)))
                    Txt(stringResource(R.string.claim_username), size = 14.sp, weight = FontWeight.Medium)
                    OutlinedTextField(
                        username, { username = it.lowercase().trim(); error = null }, Modifier.fillMaxWidth().testTag("username-input"),
                        singleLine = true, shape = RoundedCornerShape(12.dp), colors = lwFieldColors(),
                        suffix = { Txt("@$domain", color = Lw.MutedFg) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    )
                    when {
                        validation != null -> ErrorText(validation)
                        checking -> MutedText(stringResource(R.string.claim_checkingAvailability), size = 12.sp)
                        available == false -> ErrorText(stringResource(R.string.claim_taken))
                        available == true -> Txt(stringResource(R.string.claim_available), size = 12.sp, color = Lw.Green)
                        else -> MutedText(stringResource(R.string.claim_usernameHint), size = 12.sp)
                    }
                    ErrorText(error, Modifier.fillMaxWidth())
                }
                "payment" -> {
                    val inv = invoice
                    if (inv == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner() }
                    else {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            MutedText(stringResource(R.string.claim_toClaim) + " $username@$domain")
                            Txt(stringResource(R.string.claim_payAmount, Currencies.formatSats(inv.amountSats)), size = 24.sp, weight = FontWeight.SemiBold)
                            QrDisplay("lightning:${inv.bolt11}".uppercase(), null, copyValue = inv.bolt11)
                            Row(verticalAlignment = Alignment.CenterVertically) { Spinner(14.dp, Lw.MutedFg); Spacer(Modifier.width(8.dp)); MutedText(stringResource(R.string.claim_waitingPayment)) }
                            ErrorText(error, Modifier.fillMaxWidth())
                            LwButton(stringResource(R.string.claim_payWithWallet), {
                                scope.launch {
                                    loading = true
                                    runCatching { c.wallet.freshClient()?.payInvoice(inv.bolt11) ?: error("no wallet") }
                                        .onSuccess { if (it.preimage.isNotEmpty()) claimPaid(inv, it.preimage) }
                                        .onFailure { error = it.message }
                                    loading = false
                                }
                            }, Modifier.fillMaxWidth(), BtnVariant.Theme, loading = loading, enabled = c.wallet.hasWallet)
                            LwButton(stringResource(R.string.claim_checkNow), {
                                scope.launch {
                                    val (settled, pre) = runCatching { c.lnurl.verifyOnce(inv.verify ?: "") }.getOrDefault(false to null)
                                    if (settled && pre != null) claimPaid(inv, pre) else error = noPayment
                                }
                            }, Modifier.fillMaxWidth(), BtnVariant.Secondary)
                        }
                    }
                }
                else -> Column(Modifier.fillMaxWidth().padding(top = 32.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.size(80.dp).clip(CircleShape).background(brand.t400.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_check, size = 40.dp, tint = brand.t400) }
                    Txt(stringResource(R.string.claim_claimedTitle), size = 24.sp, weight = FontWeight.SemiBold)
                    Txt(claimed ?: "", size = 18.sp, weight = FontWeight.SemiBold, modifier = Modifier.testTag("claimed-address"))
                    MutedText(stringResource(R.string.claim_claimedBody), align = TextAlign.Center)
                }
            }
        }
        when (step) {
            "username" -> LwButton(
                stringResource(if (loading) R.string.claim_claiming else R.string.claim_claimAddress),
                {
                    loading = true; error = null
                    scope.launch {
                        try {
                            val addr = c.session.api.value.createAddress(username)
                            finish("${addr.username}@$domain")
                        } catch (e: ApiException) {
                            if (e.status == 402) {
                                step = "payment"
                                runCatching { c.session.api.value.createAddressInvoice(username) }
                                    .onSuccess { inv -> if (inv.free) runCatching { finish("${c.session.api.value.createAddress(username).username}@$domain") }.onFailure { error = it.message; step = "username" } else invoice = inv }
                                    .onFailure { error = it.message; step = "username" }
                            } else error = e.message ?: createFailed
                        } catch (e: Exception) {
                            error = e.message ?: createFailed
                        }
                        loading = false
                    }
                },
                Modifier.fillMaxWidth().padding(16.dp).testTag("claim-submit"), BtnVariant.Theme, enabled = username.isNotEmpty() && validation == null && available != false, loading = loading,
            )
            "success" -> LwButton(stringResource(R.string.claim_goToWallet), { nav.goHome() }, Modifier.fillMaxWidth().padding(16.dp).testTag("claim-done"))
            else -> Spacer(Modifier.height(16.dp))
        }
    }
}
