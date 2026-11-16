package io.lawallet.wallet.app

import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.ln.Destination
import io.lawallet.wallet.core.ln.PayMetadata
import io.lawallet.wallet.core.ln.WithdrawParams
import kotlinx.coroutines.flow.MutableStateFlow

/** In-memory hand-off between flow steps (wallet-flow-store.ts) — deliberately not persisted. */
class FlowStore {
    /** Set after adding a remote wallet: Home opens its wallet list on return, showing the new one. */
    var revealWallets = false
    /** A remote wallet just added: the list it lands in plays its entrance (consumed by the first to show it). */
    var newWalletId: String? = null
    /**
     * A wallet being added from Home: its card shows at once under [key] (status PENDING) while LaWallet sets it up,
     * then [created] takes its place under the same key. Cleared once Home closes the list or leaves.
     */
    data class PendingWallet(val key: String, val name: String, val lncurl: Boolean, val selected: Boolean, val created: RemoteWallet? = null, val askName: Boolean = false, val renamed: Boolean = false)
    val pendingWallet = MutableStateFlow<PendingWallet?>(null)
    /** Same, for the Onchain page after adding or backing up an onchain wallet. */
    var revealOnchain = false

    data class Send(
        val destination: Destination? = null,
        val recipientLabel: String? = null,
        val meta: PayMetadata? = null,
        val amountSats: Long? = null,
        val comment: String? = null,
        val bolt11: String? = null,
        val result: SendResult? = null,
        /** Tapped from an NFC tag / POS rather than scanned or typed. */
        val viaNfc: Boolean = false,
    )

    data class SendResult(val amountSats: Long, val feesSats: Long, val preimage: String?, val paymentHash: String?, val to: String, val note: String?, val at: Long)

    data class Receive(val amountSats: Long? = null, val description: String? = null, val bolt11: String? = null, val paymentHash: String? = null, val paid: Tx? = null)

    var send = Send()
    var receive = Receive()
    var withdraw: WithdrawParams? = null
    var activityDetail: Tx? = null

    fun resetSend() { send = Send() }
    fun resetReceive() { receive = Receive() }
}
