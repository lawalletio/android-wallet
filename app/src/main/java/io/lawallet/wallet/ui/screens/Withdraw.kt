package io.lawallet.wallet.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import io.lawallet.wallet.R
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.service.PaymentSound
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.AmountDisplay
import io.lawallet.wallet.ui.components.AmountKeypad
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.keypadDisplay
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** withdraw-screen.tsx — LUD-03: mint an invoice via NWC, hand it to the voucher's callback, watch it settle. */
@Composable
fun WithdrawScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val p = c.flow.withdraw
    if (p == null) { LaunchedEffect(Unit) { nav.goHome() }; return }
    val client by c.wallet.client.collectAsState()
    val fixed = p.minSats == p.maxSats
    var raw by rememberSaveable { mutableStateOf(p.maxSats.toString()) }
    var phase by remember { mutableStateOf("form") } // form | claiming | won | pending
    var error by remember { mutableStateOf<String?>(null) }
    val noWallet = stringResource(R.string.withdraw_noWallet)
    val amount = raw.toLongOrNull() ?: 0
    val valid = amount in p.minSats..p.maxSats && amount > 0

    if (phase != "form") {
        val chest by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.treasure_chest))
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Txt(stringResource(if (phase == "claiming") R.string.withdraw_celebration_eyebrowClaiming else R.string.withdraw_celebration_eyebrowClaimed), size = 15.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
            LottieAnimation(chest, Modifier.size(260.dp), isPlaying = true, iterations = if (phase == "claiming") Int.MAX_VALUE else 1)
            Txt(
                stringResource(when (phase) { "claiming" -> R.string.withdraw_celebration_opening; "won" -> R.string.withdraw_celebration_won; else -> R.string.withdraw_celebration_onItsWay }),
                size = 28.sp, weight = FontWeight.SemiBold, align = TextAlign.Center, modifier = Modifier.testTag("withdraw-phase-$phase"),
            )
            MutedText(
                when (phase) {
                    "claiming" -> stringResource(R.string.withdraw_celebration_calling, Currencies.formatSats(amount))
                    "won" -> stringResource(R.string.withdraw_celebration_srLanded, Currencies.formatSats(amount))
                    else -> stringResource(R.string.withdraw_celebration_pendingBody)
                }, align = TextAlign.Center,
            )
            if (phase != "claiming") LwButton(stringResource(R.string.withdraw_celebration_collect), { c.flow.withdraw = null; nav.goHome() }, Modifier.fillMaxWidth().padding(top = 32.dp), BtnVariant.Theme)
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.withdraw_title), { nav.popBackStack() }, close = true)
        Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(16.dp)) {
                Txt(stringResource(R.string.withdraw_from), size = 12.sp, color = Lw.MutedFg)
                Txt(p.defaultDescription.ifBlank { stringResource(R.string.withdraw_voucher) }, size = 16.sp, weight = FontWeight.SemiBold)
                Txt(p.host, size = 12.sp, color = Lw.MutedFg)
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                AmountDisplay(keypadDisplay(raw, "SAT"), "sats")
                MutedText(if (fixed) stringResource(R.string.withdraw_fixedAmount) else stringResource(R.string.withdraw_range, Currencies.formatSats(p.minSats), Currencies.formatSats(p.maxSats)))
            }
            if (client == null) Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(16.dp)) {
                Txt(stringResource(R.string.withdraw_noWallet), weight = FontWeight.Medium)
                MutedText(stringResource(R.string.withdraw_noWalletBody), size = 12.sp)
                LwButton(stringResource(R.string.withdraw_manageWallets), { nav.navigate(R0.REMOTE_WALLETS) }, Modifier.padding(top = 12.dp), BtnVariant.Secondary, height = 36.dp)
            }
            if (!fixed) AmountKeypad(raw, { raw = it }, Currencies.keypadMode("SAT"), Modifier.weight(1f).fillMaxWidth()) else Box(Modifier.weight(1f))
            ErrorText(error, Modifier.fillMaxWidth())
        }
        LwButton(
            if (valid) stringResource(R.string.withdraw_ctaAmount, Currencies.formatSats(amount)) else stringResource(R.string.withdraw_cta),
            {
                error = null
                scope.launch {
                    runCatching {
                        val nwc = c.wallet.ensureWallet() ?: throw IllegalStateException(noWallet)
                        val inv = nwc.makeInvoice(amount * 1000, p.defaultDescription.ifBlank { "LNURL withdraw" })
                        phase = "claiming"
                        c.lnurl.submitWithdraw(p.callback, p.k1, inv.invoice ?: error("no invoice"))
                        val deadline = System.currentTimeMillis() + 45_000
                        var settled = false
                        while (!settled && System.currentTimeMillis() < deadline) {
                            delay(3000)
                            settled = runCatching { nwc.lookupInvoice(inv.paymentHash).settledAt != null }.getOrDefault(false)
                        }
                        settled
                    }.onSuccess { settled ->
                        phase = if (settled) "won" else "pending"
                        PaymentSound.play(context, "chime")
                        c.scope.launch { c.wallet.refreshBalance(); c.wallet.refreshActivity() }
                    }.onFailure { error = it.message; phase = "form" }
                }
            },
            Modifier.fillMaxWidth().padding(16.dp).testTag("withdraw-cta"), BtnVariant.Theme, enabled = valid,
        )
    }
}
