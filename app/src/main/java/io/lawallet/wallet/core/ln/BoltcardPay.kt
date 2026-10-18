package io.lawallet.wallet.core.ln

class BoltcardPayException(message: String, val code: String? = null, val limitSats: Long? = null) : Exception(message)

/**
 * Charge the invoice already on screen from a tapped Bolt card (LNURL-withdraw).
 * Port of boltcard-pay.ts: resolve the card URL, then GET callback?k1&pr=<displayed bolt11>.
 */
class BoltcardPay(private val lnurl: Lnurl) {
    suspend fun payDisplayedInvoice(cardUrl: String, bolt11: String, amountSats: Long) {
        if (bolt11.isBlank() || amountSats <= 0) throw BoltcardPayException("There is no invoice to charge.", "noInvoice")
        val resolved = try {
            lnurl.resolve(cardUrl)
        } catch (e: LnurlException) {
            if (e.code == "noAmount" || e.code == "belowOneSat") throw BoltcardPayException("This card cannot pay right now.", "cannotPay")
            throw BoltcardPayException(e.message ?: "Could not reach the card.", null)
        } catch (e: Exception) {
            throw BoltcardPayException("Could not reach the card.", "unreachable")
        }
        val params = (resolved as? LnurlResolved.Withdraw)?.params
            ?: throw BoltcardPayException("This tag is not a BoltCard.", "notBoltcard")
        if (amountSats > params.maxSats) throw BoltcardPayException(
            "This card can pay up to ${params.maxSats} sats. This invoice is $amountSats sats.", "aboveMax", params.maxSats)
        if (amountSats < params.minSats) throw BoltcardPayException(
            "This card's minimum is ${params.minSats} sats. This invoice is $amountSats sats.", "belowMin", params.minSats)
        try {
            lnurl.submitWithdraw(params.callback, params.k1, bolt11)
        } catch (e: LnurlException) {
            throw BoltcardPayException(e.message ?: "The card payment was rejected.", e.code)
        } catch (e: Exception) {
            throw BoltcardPayException("The card payment was rejected.", "rejected")
        }
    }
}
