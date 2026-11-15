package io.lawallet.wallet.core

import io.lawallet.wallet.app.Tx
import io.lawallet.wallet.app.mergeActivity
import io.lawallet.wallet.app.paymentKey
import io.lawallet.wallet.core.nwc.NwcTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** Issue #19: one payment = one key, whichever path reported it. */
class PaymentKeyTest {
    @Test fun samePaymentFromEverySourceSharesOneKey() {
        val notified = Tx.from(NwcTransaction(type = "incoming", paymentHash = "abc", amount = 1000, createdAt = 1, settledAt = 2))
        val catchUpRow = Tx.from(NwcTransaction(paymentHash = "abc", amount = 1000, createdAt = 1, settledAt = 2, state = "settled"))
        assertEquals(paymentKey(notified), paymentKey(catchUpRow))
    }

    @Test fun selfPaymentKeepsBothSidesButRefreshesCollapse() {
        val sent = Tx.from(NwcTransaction(type = "outgoing", paymentHash = "abc", amount = 1000, createdAt = 2, settledAt = 2, state = "settled"))
        val received = Tx.from(NwcTransaction(type = "incoming", paymentHash = "abc", amount = 1000, createdAt = 1, settledAt = 2, state = "settled"))
        val merged = mergeActivity(listOf(sent), listOf(received))
        assertEquals(listOf("outgoing", "incoming"), merged.map { it.type })
        // The same side seen again (pending, then settled) stays one row, the newer report winning.
        val pending = sent.copy(state = "pending", settledAt = null)
        assertEquals(listOf(sent, received), mergeActivity(listOf(sent), listOf(pending, received)))
    }

    @Test fun hashlessPaymentsDontCollide() {
        val a = Tx.from(NwcTransaction(amount = 1000, createdAt = 1))
        val b = Tx.from(NwcTransaction(amount = 2000, createdAt = 5))
        assertNotEquals(paymentKey(a), paymentKey(b))
        assertNotEquals("", paymentKey(a))
    }
}
