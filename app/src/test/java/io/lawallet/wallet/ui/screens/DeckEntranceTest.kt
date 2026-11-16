package io.lawallet.wallet.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The open deck: every card at rest, however long the list (equal gaps). */
class DeckEntranceTest {
    @Test fun everyCardLandsWhenTheDeckIsOpen() = (0..60).forEach { d -> assertEquals("card $d", 1f, deckEntrance(d, 1f), 0f) }

    @Test fun nearerCardsLead() {
        assertTrue(deckEntrance(0, 0.5f) > deckEntrance(3, 0.5f))
        assertEquals(0f, deckEntrance(0, 0f), 0f)
    }
}
