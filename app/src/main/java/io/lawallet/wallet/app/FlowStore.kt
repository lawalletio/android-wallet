package io.lawallet.wallet.app

import io.lawallet.wallet.core.ln.Destination
import io.lawallet.wallet.core.ln.PayMetadata
import io.lawallet.wallet.core.ln.WithdrawParams

/** In-memory hand-off between flow steps (wallet-flow-store.ts) — deliberately not persisted. */
class FlowStore {
    data class Send(
        val destination: Destination? = null,
        val recipientLabel: String? = null,
        val meta: PayMetadata? = null,
        val amountSats: Long? = null,
        val comment: String? = null,
        val bolt11: String? = null,
        val result: SendResult? = null,
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
