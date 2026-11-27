package io.lawallet.wallet.ui.screens

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.app.OnchainWallet
import io.lawallet.wallet.core.btc.Chain
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.DialogTitle
import io.lawallet.wallet.ui.components.LwAlertDialog
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.ModalAction
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import com.composables.icons.lucide.R as L

/*
 * Paying on the other network: a code or recipient that only the other Home page (Bitcoin onchain ⇄ Lightning) can
 * pay, or an onchain wallet on another chain. Says what it is, and one button switches over and carries on.
 */

/** Home's two payment pages. */
enum class PayNetwork(val page: Int, val tone: Color, @DrawableRes val icon: Int, @StringRes val label: Int, @StringRes val title: Int) {
    ONCHAIN(0, Lw.Bitcoin, L.drawable.lucide_ic_bitcoin, R.string.network_onchain, R.string.network_onchain_title),
    LIGHTNING(1, Lw.Lightning, L.drawable.lucide_ic_zap, R.string.network_lightning, R.string.network_lightning_title);

    val other get() = if (this == ONCHAIN) LIGHTNING else ONCHAIN

    companion object {
        /** The payment page Home is on (or was last), or null on one that pays neither (USDT). */
        fun current(): PayNetwork? = entries.firstOrNull { it.page == HomePager.page }
    }
}

/** Switches Home to [to]'s page, for when the user comes back from paying there. */
fun switchHome(to: PayNetwork) = HomePager.show(to.page)

internal fun chainTone(chain: Chain) = if (chain == Chain.SIGNET) Lw.Signet else Lw.Bitcoin

@Composable
internal fun chainName(chain: Chain) = stringResource(if (chain == Chain.SIGNET) R.string.onchain_signet else R.string.onchain_settings_mainnet)

/** Buttons inside take [tone] as their brand colour. */
@Composable
private fun Toned(tone: Color, content: @Composable () -> Unit) {
    val b = LocalBrand.current
    CompositionLocalProvider(LocalBrand provides b.copy(t200 = lerp(tone, Color.Black, 0.45f), t300 = lerp(tone, Color.Black, 0.25f), t400 = tone), content = content)
}

@Composable
private fun Badge(@DrawableRes icon: Int, tone: Color, size: Dp, modifier: Modifier = Modifier) =
    Box(modifier.size(size).glass(CircleShape, GlassLevel.Thin, tint = tone.copy(alpha = 0.20f)), contentAlignment = Alignment.Center) {
        LwIcon(icon, size = size * 0.48f, tint = tone)
    }

/** A scanned code for the other network: where you are → where it pays, and a switch that pays it there. */
@Composable
fun NetworkSwitchDialog(to: PayNetwork, onSwitch: () -> Unit, onDismiss: () -> Unit) = LwAlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
        Toned(to.tone) {
            ModalAction(stringResource(R.string.network_switch, stringResource(to.label)), onSwitch, Modifier.testTag("network-switch"))
        }
    },
    modifier = Modifier.testTag("network-dialog"),
    icon = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Badge(to.other.icon, to.other.tone, 44.dp, Modifier.alpha(0.55f))
            LwIcon(L.drawable.lucide_ic_arrow_right, size = 20.dp, tint = Lw.MutedFg)
            Badge(to.icon, to.tone, 64.dp)
        }
    },
    title = { DialogTitle(stringResource(to.title)) },
    text = {
        Txt(stringResource(if (to == PayNetwork.ONCHAIN) R.string.network_scan_onchain else R.string.network_scan_lightning), Modifier.fillMaxWidth(), color = Lw.MutedFg, align = TextAlign.Center)
    },
)

/**
 * A scanned address on another [chain] than the selected wallet ([current]): offers [payer], a wallet on that chain
 * (or adding one, when there's none), instead of paying from a wallet the user didn't pick.
 */
@Composable
fun WalletSwitchDialog(chain: Chain, current: OnchainWallet, payer: OnchainWallet?, onSwitch: () -> Unit, onDismiss: () -> Unit) = LwAlertDialog(
    onDismissRequest = onDismiss,
    confirmButton = {
        Toned(chainTone(chain)) {
            ModalAction(if (payer != null) stringResource(R.string.onchain_send_otherChain_use, payer.name) else stringResource(R.string.onchain_add_title), onSwitch, Modifier.testTag("wallet-switch"))
        }
    },
    modifier = Modifier.testTag("wallet-dialog"),
    icon = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Badge(L.drawable.lucide_ic_wallet, chainTone(current.chain), 44.dp, Modifier.alpha(0.55f))
            LwIcon(L.drawable.lucide_ic_arrow_right, size = 20.dp, tint = Lw.MutedFg)
            Badge(L.drawable.lucide_ic_wallet, chainTone(chain), 64.dp)
        }
    },
    title = { DialogTitle(stringResource(R.string.onchain_send_otherChain_title, chainName(chain))) },
    text = {
        Txt(
            if (payer != null) stringResource(R.string.onchain_send_otherChain_body, current.name, chainName(current.chain), chainName(chain))
            else stringResource(R.string.onchain_send_otherChain_none, chainName(chain)),
            Modifier.fillMaxWidth(), color = Lw.MutedFg, align = TextAlign.Center,
        )
    },
)

/**
 * Inline, under a recipient field: what's wrong ([title], [body]) and the one [action] that fixes it, in [tone].
 * Shared by the network switch and an onchain wallet on another chain.
 */
@Composable
fun SwitchCard(@DrawableRes icon: Int, tone: Color, title: String, body: String, action: String, onClick: () -> Unit, modifier: Modifier = Modifier) =
    Column(
        modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp), GlassLevel.Thin, tint = tone.copy(alpha = 0.10f)).padding(14.dp).testTag("network-card"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Badge(icon, tone, 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Txt(title, size = 15.sp, weight = FontWeight.SemiBold)
                MutedText(body, size = 13.sp)
            }
        }
        Toned(tone) {
            LwButton(action, onClick, Modifier.fillMaxWidth().testTag("network-card-action"), BtnVariant.Theme, height = 42.dp, icon = L.drawable.lucide_ic_arrow_left_right)
        }
    }

/** [SwitchCard] for a recipient only [to] can pay; [body] defaults to the typed-recipient wording. */
@Composable
fun NetworkSwitchCard(to: PayNetwork, onSwitch: () -> Unit, body: String? = null) = SwitchCard(
    to.icon, to.tone, stringResource(to.title),
    body ?: stringResource(if (to == PayNetwork.ONCHAIN) R.string.network_send_onchain else R.string.network_send_lightning),
    stringResource(R.string.network_switch, stringResource(to.label)), onSwitch,
)
