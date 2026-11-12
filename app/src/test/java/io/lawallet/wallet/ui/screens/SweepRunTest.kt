package io.lawallet.wallet.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SweepRunTest {
    @Test fun smallRemaindersStepDownBySats() {
        // 1 sat left over: 10 → 9 → 7 → 3 (steps of 1, 2, 4 sats)
        var amt = 10L; var step = 1L
        val tries = mutableListOf(amt)
        repeat(3) { amt = SweepRun.smaller(amt, step); step *= 2; tries += amt }
        assertEquals(listOf(10L, 9L, 7L, 3L), tries)
    }

    @Test fun bigAmountsStepDownByPercent() {
        // 0.5% then 1% of the amount, never less than the sat step.
        assertEquals(99_500L, SweepRun.smaller(100_000, 1))
        assertEquals(98_010L, SweepRun.smaller(99_000, 2))
    }

    @Test fun reserveIsOnePercentAtLeastOneSat() {
        assertEquals(1L, SweepRun.reserveFor(50))
        assertEquals(5_920L, SweepRun.reserveFor(591_929))
    }
}
