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
    /** A transfer (or sweep) between the account's wallets that just went out: Home's wallet list plays it (no sent screen). */
    data class Transfer(val to: String, val sats: Long, val fee: Long, /** Null: the wallet in use (what Send pays from). */ val from: String? = null)
    var transfer: Transfer? = null
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
    /** One of the account's cards was just tapped on the phone: My Cards brings it to the front, then clears this. */
    val focusCard = MutableStateFlow<String?>(null)

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
        /** The wallet paying, when it isn't the one in use: a remote wallet id or `plugin:<id>` (Withdraw to active wallet). */
        val source: String? = null,
        val sourceLabel: String? = null,
        /** The destination is the wallet in use: its invoice comes from the open connection, no LaWallet call needed. */
        val toActive: Boolean = false,
    )

    data class SendResult(val amountSats: Long, val feesSats: Long, val preimage: String?, val paymentHash: String?, val to: String, val note: String?, val at: Long)

    data class Receive(val amountSats: Long? = null, val description: String? = null, val bolt11: String? = null, val paymentHash: String? = null, val paid: Tx? = null)

    /** ARS cash-out: the bank account picked on Send, then the amount, the exchange, the confirmed name and the locked order. */
    data class CashOut(
        val account: io.lawallet.wallet.core.api.BankAccount? = null,
        val amountArs: Double? = null,
        val provider: String? = null,
        val holderName: String = "",
        val order: io.lawallet.wallet.core.api.Swap? = null,
    )
    var cashOut = CashOut()

    /**
     * An onchain payment in progress: who pays, where to, how much ([max]: everything), the receiver's payjoin endpoint,
     * then the built tx and its id, and whether the payjoin happened ([joined]: null when it wasn't tried).
     */
    data class OnchainSend(
        val walletId: String,
        val address: String,
        val sats: Long? = null,
        val label: String? = null,
        val max: Boolean = false,
        val draft: OnchainDraft? = null,
        val txid: String? = null,
        val payjoin: String? = null,
        val joined: Boolean? = null,
    )
    var onchainSend: OnchainSend? = null
    /** Wallet material the main scanner found, for onchain import to start from (consumed there). */
    var onchainImport: String? = null
    /** A PSBT on its way between signers (base64), for the PSBT screen. */
    var psbt: String? = null

    var send = Send()
    var receive = Receive()
    var withdraw: WithdrawParams? = null
    var cashu: io.lawallet.wallet.core.ln.CashuToken? = null
    var activityDetail: Tx? = null

    fun resetSend() { send = Send() }
    fun resetReceive() { receive = Receive() }
}
