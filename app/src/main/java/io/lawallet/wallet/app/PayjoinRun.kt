package io.lawallet.wallet.app

import io.lawallet.wallet.core.btc.Payjoin

/**
 * A payjoin as it happens, for the screen that shows it. [step] is the one in progress (or [Step.DONE]); [flow] the
 * money, the original's until the receiver's proposal is accepted. On a fallback [failedAt] says where it stopped and
 * [why] in the user's terms, and the original went out instead ([joined] false).
 */
data class PayjoinRun(
    val step: Step = Step.SIGN,
    val flow: Payjoin.Flow? = null,
    val relay: String? = null,
    val directory: String? = null,
    /** When the receiver's time to answer runs out (epoch ms), from the moment the offer is out. */
    val deadline: Long? = null,
    val txid: String? = null,
    val joined: Boolean? = null,
    val extraFee: Long = 0,
    val failedAt: Step? = null,
    val why: Why? = null,
    /** The send itself failed: nothing went out. */
    val error: Throwable? = null,
) {
    enum class Step { SIGN, SEND, WAIT, CHECK, BROADCAST, DONE }
    enum class Why { TIMEOUT, DECLINED, OFFLINE, CHECKS }

    val finished get() = step == Step.DONE || error != null

    companion object {
        /** A failed payjoin in the user's terms (never the receiver's own words). */
        fun why(e: Throwable): Why = when {
            e is Payjoin.Rejected && e.message == "timeout" -> Why.TIMEOUT
            e is Payjoin.Rejected && e.message.orEmpty().startsWith("receiver:") -> Why.DECLINED
            e is java.io.IOException || (e is Payjoin.Rejected && e.message.orEmpty().startsWith("http")) -> Why.OFFLINE
            else -> Why.CHECKS
        }
    }
}
