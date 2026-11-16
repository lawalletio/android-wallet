package io.lawallet.wallet.core

import io.lawallet.wallet.core.diag.FrameStats
import io.lawallet.wallet.core.diag.jsonLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameStatsTest {
    @Test fun countsJankAgainstEachFramesDeadline() {
        val s = FrameStats()
        repeat(90) { s.add(5f, 3f, 2f, 8.3f) }
        repeat(9) { s.add(12f, 10f, 2f, 8.3f) }
        s.add(800f, 790f, 5f, 8.3f)
        val m = s.summary("enter_")
        assertEquals(100, m["enter_frames"])
        assertEquals(10, m["enter_janky"])
        assertEquals(10.0, m["enter_jank_pct"])
        assertEquals(1, m["enter_frozen"])
        assertEquals(5.0, m["enter_p50"])
        assertEquals(12.0, m["enter_p99"])
        assertEquals(800.0, m["enter_max"])
        assertEquals(2.0, m["enter_gpu_p90"])
    }

    @Test fun noGpuTimingBeforeApi31LeavesItOut() {
        val s = FrameStats().apply { add(5f, 4f, -1f, 16.6f) }
        assertTrue("gpu_p90" !in s.summary())
        assertTrue(FrameStats().summary().isEmpty())
    }

    @Test fun growsPastItsInitialCapacity() {
        val s = FrameStats()
        repeat(10_000) { s.add(it % 20f, 1f, 1f, 16.6f) }
        assertEquals(10_000, s.summary()["frames"])
    }

    @Test fun jsonLineEscapesStringsAndKeepsNumbers() =
        assertEquals("""{"t":"screen","route":"a/\"b\"\\c","ms":12,"p90":7.5,"ok":true}""",
            jsonLine(mapOf("t" to "screen", "route" to "a/\"b\"\\c", "ms" to 12L, "p90" to 7.5, "ok" to true, "skip" to null)))
}
