package io.lawallet.wallet.app

import org.junit.Assert.assertEquals
import org.junit.Test

class UniqueNameTest {
    @Test fun freeNameIsKept() = assertEquals("marce", uniqueName("marce", setOf("Alby")))
    @Test fun takenNameGetsTwo() = assertEquals("marce 2", uniqueName("marce", setOf("marce")))
    @Test fun skipsTakenNumbers() = assertEquals("marce 4", uniqueName("marce", setOf("marce", "marce 2", "marce 3")))
    @Test fun fillsTheFirstGap() = assertEquals("marce 2", uniqueName("marce", setOf("marce", "marce 3")))
}
