package io.lawallet.wallet.ui.screens

import io.lawallet.wallet.app.Tx
import org.junit.Assert.assertEquals
import org.junit.Test

class ActivitySearchTest {
    private fun tx(sats: Long, desc: String?, type: String = "outgoing") = Tx(type, sats, 0, desc, "h$sats", null, null, 0, "settled")
    private val all = listOf(tx(21, "Coffee"), tx(21_000, "Rent split", "incoming"), tx(1_000_000, null))
    private fun shown() = all.filter(ActivitySearch::matches).map { it.amountSats }

    @Test fun filters() = with(ActivitySearch) {
        reset(); maxSats = 1_000_000
        assertEquals(listOf(21L, 21_000L, 1_000_000L), shown())
        query = "coffee"; assertEquals(listOf(21L), shown())
        query = "21,000"; assertEquals(listOf(21_000L), shown())
        query = ""; received = false; assertEquals(listOf(21L, 1_000_000L), shown())
        received = true; sent = false; assertEquals(listOf(21_000L), shown())
        sent = true; sizeOn = true; size = 0.5f..1f // log midpoint ≈ 1,000 sats
        assertEquals(listOf(21_000L, 1_000_000L), shown())
        reset()
    }

    // regression: 5306075 fix(activity): search finds swap holders and saved recipients, not only descriptions
    @Test fun regression_5306075_searchFindsRowLabels() = with(ActivitySearch) {
        reset(); maxSats = 1_000_000
        val cashOut = tx(17_800, null)
        query = "ana"
        assertEquals(false, matches(cashOut))
        assertEquals(true, matches(cashOut, listOf("Ana Pérez", "ana.mp", "Mercado Pago")))
        query = "MERCADO"
        assertEquals(true, matches(cashOut, listOf("Ana Pérez", "Mercado Pago")))
        query = "beto"
        assertEquals(false, matches(cashOut, listOf("Ana Pérez")))
        reset()
    }
}
