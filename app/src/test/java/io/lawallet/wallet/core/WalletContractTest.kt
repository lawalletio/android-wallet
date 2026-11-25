package io.lawallet.wallet.core

import io.lawallet.wallet.core.nwc.NwcClient
import io.lawallet.wallet.core.nwc.NwcException
import io.lawallet.wallet.core.nwc.NwcNotification
import io.lawallet.wallet.core.wallet.Wallet
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The typed calls every Wallet (NWC, plugin) inherits: method names, params and result decoding (docs/plugin-api.md). */
class WalletContractTest {
    private class Call(val method: String, val params: JsonObject, val timeoutMs: Long)

    /** Answers [answer] for every call and records it. */
    private class MemoryWallet(val answer: (Call) -> JsonElement) : Wallet {
        val calls = mutableListOf<Call>()
        override suspend fun request(method: String, params: JsonObject, timeoutMs: Long) = Call(method, params, timeoutMs).also { calls += it }.let(answer)
        override fun notifications(): Flow<NwcNotification> = emptyFlow()
        override val connected = MutableStateFlow(true)
        override val lastError = MutableStateFlow<String?>(null)
    }

    private fun json(s: String) = NwcClient.json.parseToJsonElement(s)

    @Test fun payInvoiceSendsTheAmountOnlyWhenGiven() = runTest {
        val w = MemoryWallet { json("""{"preimage":"ff","fees_paid":1000,"extra":1}""") }
        assertEquals(1000L, w.payInvoice("lnbc1x").feesPaid)
        w.payInvoice("lnbc1", amountMsat = 21_000)
        assertEquals("pay_invoice", w.calls[0].method)
        assertEquals(buildJsonObject { put("invoice", "lnbc1x") }, w.calls[0].params)
        assertEquals(buildJsonObject { put("invoice", "lnbc1"); put("amount", 21_000) }, w.calls[1].params)
        assertEquals(60_000L, w.calls[0].timeoutMs) // a payment may take a while to route
    }

    @Test fun otherCallsUseTheShortTimeoutAndTheirParams() = runTest {
        val w = MemoryWallet { c ->
            when (c.method) {
                "get_balance" -> json("""{"balance":21000}""")
                "list_transactions" -> json("""{"transactions":[{"type":"outgoing","payment_hash":"h","amount":1000}]}""")
                else -> json("""{"type":"incoming","amount":5000,"payment_hash":"p"}""")
            }
        }
        assertEquals(21_000L, w.getBalanceMsat())
        assertEquals("p", w.makeInvoice(5000, " ").paymentHash)
        w.makeInvoice(5000, "coffee")
        assertEquals("h", w.listTransactions(limit = 10, until = 99).single().paymentHash)
        w.listTransactions()
        assertEquals(listOf(20_000L), w.calls.map { it.timeoutMs }.distinct())
        assertEquals(buildJsonObject { put("amount", 5000) }, w.calls[1].params) // blank description left out
        assertEquals(buildJsonObject { put("amount", 5000); put("description", "coffee") }, w.calls[2].params)
        assertEquals(buildJsonObject { put("limit", 10); put("until", 99) }, w.calls[3].params)
        assertEquals(JsonObject(emptyMap()), w.calls[4].params)
        assertEquals(buildJsonObject { put("payment_hash", "x") }, w.also { it.lookupInvoice("x") }.calls.last().params)
    }

    @Test fun missingFieldsDecodeToDefaults() = runTest {
        val w = MemoryWallet { json("{}") }
        assertEquals(0L, w.getBalanceMsat())
        assertEquals("", w.payInvoice("lnbc1").preimage)
        assertEquals(emptyList<Any>(), w.listTransactions())
        assertNull(w.lookupInvoice("h").settledAt)
    }

    @Test fun errorsPassThrough() {
        val w = MemoryWallet { throw NwcException("INSUFFICIENT_BALANCE", "no") }
        assertEquals("INSUFFICIENT_BALANCE", assertThrows(NwcException::class.java) { runBlocking { w.payInvoice("lnbc1") } }.code)
    }

    // awaitSettled reads the wall clock for its deadline, so these run on real (short) time, not virtual time.

    @Test fun awaitSettledPollsUntilSettled() = runBlocking {
        var lookups = 0
        val w = MemoryWallet {
            lookups++
            when (lookups) {
                1 -> throw NwcException("NOT_FOUND", "not yet") // errors are retried, not fatal
                2 -> json("""{"payment_hash":"h"}""")
                else -> json("""{"payment_hash":"h","settled_at":1700000000}""")
            }
        }
        assertTrue(w.awaitSettled("h", timeoutMs = 5_000, everyMs = 10))
        assertEquals(3, lookups)
        assertTrue(w.calls.all { it.method == "lookup_invoice" && it.params == buildJsonObject { put("payment_hash", "h") } })
    }

    @Test fun awaitSettledGivesUpAtTheDeadline() = runBlocking {
        val w = MemoryWallet { json("""{"payment_hash":"h"}""") }
        val started = System.currentTimeMillis()
        assertFalse(w.awaitSettled("h", timeoutMs = 200, everyMs = 50))
        assertTrue(System.currentTimeMillis() - started in 200..2_000)
        assertTrue(w.calls.size in 2..5)
    }

    @Test fun defaultTimeouts() {
        assertEquals(60_000L, Wallet.defaultTimeout("pay_invoice"))
        assertEquals(20_000L, Wallet.defaultTimeout("get_balance"))
    }
}
