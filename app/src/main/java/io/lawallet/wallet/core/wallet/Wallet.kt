package io.lawallet.wallet.core.wallet

import io.lawallet.wallet.core.nwc.NwcClient
import io.lawallet.wallet.core.nwc.NwcNotification
import io.lawallet.wallet.core.nwc.NwcTransaction
import io.lawallet.wallet.core.nwc.PayResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A wallet the app can spend from and receive into. The ABI is NIP-47 (method names, params, result JSON, error codes
 * via [io.lawallet.wallet.core.nwc.NwcException]), so an NWC connection, a JS plugin and a native module all answer the
 * same [request]. The typed calls below are shared by every kind. Contract: docs/plugins.md.
 */
interface Wallet {
    /** One NIP-47 call. Throws [io.lawallet.wallet.core.nwc.NwcException] with the NIP-47 error code. */
    suspend fun request(method: String, params: JsonObject = JsonObject(emptyMap()), timeoutMs: Long = defaultTimeout(method)): JsonElement

    /** payment_received / payment_sent, live. Missed ones come from [listTransactions] catch-up. */
    fun notifications(): Flow<NwcNotification>

    /** The transport is up (relay socket, plugin session). A reconnect triggers a catch-up. */
    val connected: StateFlow<Boolean>
    val lastError: StateFlow<String?>

    suspend fun getBalanceMsat(): Long = decode<BalanceResult>(request("get_balance")).balance

    suspend fun payInvoice(invoice: String, amountMsat: Long? = null): PayResult =
        decode(request("pay_invoice", buildJsonObject {
            put("invoice", invoice)
            if (amountMsat != null) put("amount", amountMsat)
        }))

    suspend fun makeInvoice(amountMsat: Long, description: String?): NwcTransaction =
        decode(request("make_invoice", buildJsonObject {
            put("amount", amountMsat)
            if (!description.isNullOrBlank()) put("description", description)
        }))

    suspend fun lookupInvoice(paymentHash: String): NwcTransaction =
        decode(request("lookup_invoice", buildJsonObject { put("payment_hash", paymentHash) }))

    suspend fun listTransactions(limit: Int? = null, from: Long? = null, until: Long? = null, type: String? = null): List<NwcTransaction> =
        decode<TxList>(request("list_transactions", buildJsonObject {
            if (limit != null) put("limit", limit)
            if (from != null) put("from", from)
            if (until != null) put("until", until)
            if (type != null) put("type", type)
        })).transactions

    companion object {
        /** A payment may take a while to route; anything else a live wallet answers in seconds. */
        fun defaultTimeout(method: String): Long = if (method == "pay_invoice") 60_000 else 20_000
    }
}

private inline fun <reified T> decode(raw: JsonElement): T = NwcClient.json.decodeFromJsonElement(kotlinx.serialization.serializer<T>(), raw)

@Serializable private data class BalanceResult(val balance: Long = 0)
@Serializable private data class TxList(val transactions: List<NwcTransaction> = emptyList())
