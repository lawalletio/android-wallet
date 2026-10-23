package io.lawallet.wallet.core

import io.lawallet.wallet.core.diag.Trail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** The flight recorder writes redacted lines, keeps exception messages out of crashes and hands the last run over. */
class TrailTest {
    @Test
    fun redactsRotatesAndRecordsCrashesWithoutMessages() {
        val dir = Files.createTempDirectory("trail").toFile()
        Trail.open(dir, "start pid=111")
        Trail.add("nwc", "pay_invoice failed for alice@lawallet.ar lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3", error = true)
        Trail.crash(Thread.currentThread(), IllegalStateException("secret nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"))
        Trail.flush()

        val run = Trail.tail(previous = false, 50).joinToString("\n")
        assertTrue(run, run.contains("app: start pid=111"))
        assertTrue(run, run.contains("E nwc: pay_invoice failed for [redacted] [redacted]"))
        assertTrue(run, run.contains("E crash: java.lang.IllegalStateException on "))
        assertTrue(run, run.contains("at io.lawallet.wallet.core.TrailTest"))
        assertFalse(run, run.contains("nsec1") || run.contains("alice@") || run.contains("lnbc2500"))

        Trail.open(dir, "start pid=222")
        assertEquals("app: start pid=111", Trail.previousHeader()?.substringAfter(' ')?.substringAfter(' '))
        Trail.flush()
        assertTrue(Trail.tail(previous = false, 5).single().endsWith("app: start pid=222"))
    }
}
