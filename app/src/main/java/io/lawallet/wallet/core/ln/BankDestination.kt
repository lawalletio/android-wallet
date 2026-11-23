package io.lawallet.wallet.core.ln

import io.lawallet.wallet.core.api.BankOwner
import io.lawallet.wallet.core.api.Swap
import java.time.Instant

/** Argentine bank destinations for ARS cash-out: same rules as lawallet-nwc's `createSwapSchema`. */
object BankDestination {
    enum class Kind { Cbu, Alias }

    private val CBU = Regex("^\\d{22}$")
    private val ALIAS = Regex("^[a-zA-Z0-9.-]{6,20}$")

    /** A 22-digit CBU/CVU, an alias (6–20 of letters, digits, dots, dashes), or neither. No check digit, as on the server. */
    fun kind(input: String): Kind? {
        val s = input.trim()
        return when {
            CBU.matches(s) -> Kind.Cbu
            ALIAS.matches(s) && !s.all { it.isDigit() } -> Kind.Alias
            else -> null
        }
    }

    /** Grouped the way banks print it: bank and branch block (8 digits), then the account block (14). */
    fun formatCbu(cbu: String) = if (cbu.length == 22) "${cbu.take(8)} ${cbu.drop(8)}" else cbu

    /** A person's CUIT shows as their DNI (its middle 8 digits); companies keep the CUIT. */
    fun ownerId(owner: BankOwner): String? {
        val cuit = owner.cuit?.filter { it.isDigit() } ?: return null
        return if (owner.type == "F" && cuit.length == 11) "DNI ${cuit.substring(2, 10)}" else "CUIT $cuit"
    }

    enum class Phase { Awaiting, Transferring, Completed, Failed, Expired, Canceled }

    /** The server only says PENDING; paid and the deadline split it (swap-shared.tsx). */
    fun phase(swap: Swap, nowMs: Long = System.currentTimeMillis()): Phase = when (swap.status) {
        "COMPLETED" -> Phase.Completed
        "FAILED" -> Phase.Failed
        "EXPIRED" -> Phase.Expired
        "CANCELED" -> Phase.Canceled
        else -> when {
            swap.paidAt != null -> Phase.Transferring
            epochMs(swap.expiresAt)?.let { it < nowMs } == true -> Phase.Expired
            else -> Phase.Awaiting
        }
    }

    /** ARS per BTC the order actually paid: amountArs over the invoice's sats. */
    fun arsPerBtc(swap: Swap): Double? = swap.amountSats.takeIf { it > 0 }?.let { swap.amountArs * 100_000_000.0 / it }

    fun epochMs(iso: String?): Long? = iso?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
}
