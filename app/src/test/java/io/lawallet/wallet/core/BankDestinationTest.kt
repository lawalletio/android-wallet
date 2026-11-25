package io.lawallet.wallet.core

import io.lawallet.wallet.core.api.BankOwner
import io.lawallet.wallet.core.api.Swap
import io.lawallet.wallet.core.ln.BankDestination
import io.lawallet.wallet.core.ln.BankDestination.Kind
import io.lawallet.wallet.core.ln.BankDestination.Phase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BankDestinationTest {
    @Test fun tellsCbuFromAliasFromEverythingElse() {
        assertEquals(Kind.Cbu, BankDestination.kind("0000003100010000000001"))
        assertEquals(Kind.Cbu, BankDestination.kind(" 0000003100010000000001 "))
        assertEquals(Kind.Alias, BankDestination.kind("mi.alias.mp"))
        assertEquals(Kind.Alias, BankDestination.kind("casa-perro-2"))
        assertNull(BankDestination.kind("00000031000100")) // a CBU being typed: no alias lookups on digits
        assertNull(BankDestination.kind("satoshi@lawallet.io"))
        assertNull(BankDestination.kind("ana")) // too short
        assertNull(BankDestination.kind("my_name")) // underscores: a Lightning user, never an alias
        assertNull(BankDestination.kind("a".repeat(21)))
    }

    @Test fun personShowsDniCompanyShowsCuit() {
        assertEquals("DNI 12345678", BankDestination.ownerId(BankOwner("F", "27123456784", "Ana")))
        assertEquals("CUIT 30712345671", BankDestination.ownerId(BankOwner("J", "30712345671", "Acme SA")))
        assertNull(BankDestination.ownerId(BankOwner("F", null, "Ana")))
    }

    @Test fun pendingSplitsByPaidAndDeadline() {
        val now = java.time.Instant.parse("2026-11-23T12:00:00Z").toEpochMilli()
        val s = Swap("s", "wapu", status = "PENDING", expiresAt = "2026-11-23T12:05:00Z")
        assertEquals(Phase.Awaiting, BankDestination.phase(s, now))
        assertEquals(Phase.Transferring, BankDestination.phase(s.copy(paidAt = "2026-11-23T12:01:00Z"), now))
        assertEquals(Phase.Expired, BankDestination.phase(s.copy(expiresAt = "2026-11-23T11:59:00Z"), now))
        assertEquals(Phase.Completed, BankDestination.phase(s.copy(status = "COMPLETED"), now))
        assertEquals(Phase.Failed, BankDestination.phase(s.copy(status = "FAILED"), now))
    }

    @Test fun rateIsWhatTheInvoiceBought() {
        assertEquals(100_000_000.0, BankDestination.arsPerBtc(Swap("s", "wapu", amountArs = 50_000.0, amountSats = 50_000))!!, 0.001)
        assertNull(BankDestination.arsPerBtc(Swap("s", "wapu", amountSats = 0)))
    }

    // regression: f99004c fix(activity): a delivered cash-out sits at the time the pesos landed
    @Test fun regression_f99004c_deliveredIsTheCompletedOrderLastUpdate() {
        val s = Swap("s", "wapu", status = "COMPLETED", createdAt = "2026-11-23T12:00:00Z", paidAt = "2026-11-23T12:01:00Z", updatedAt = "2026-11-23T12:20:00Z")
        assertEquals(java.time.Instant.parse("2026-11-23T12:20:00Z").toEpochMilli(), BankDestination.deliveredMs(s))
        assertNull(BankDestination.deliveredMs(s.copy(status = "PENDING"))) // paid, pesos not there yet
        assertNull(BankDestination.deliveredMs(s.copy(status = "FAILED")))
        assertNull(BankDestination.deliveredMs(s.copy(updatedAt = null)))
        assertNull(BankDestination.deliveredMs(s.copy(updatedAt = "yesterday")))
    }
}
