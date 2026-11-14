package io.lawallet.wallet.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SendClipboardTest {
    // BOLT-11 spec vector, expires at (1496314658 + 60) s.
    private val coffee = "lnbc2500u1pvjluezsp5zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zyg3zygspp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdq5xysxxatsyp3k7enxv4jsxqzpu9qrsgquk0rl77nj30yxdy8j9vdx85fkpmdla2087ne0xh8nhedh8w27kyke0lp53ut353s06fv3qfegext0eh0ymjpf39tuven09sam30g4vgpfna3rh"

    @Test fun payableClips() {
        assertTrue(isPayableClip("leon@nostrwot.com"))
        assertTrue(isPayableClip(coffee, now = 0))
        assertTrue(isPayableClip("lightning:$coffee", now = 0))
        assertFalse(isPayableClip(coffee, now = (1496314658L + 61) * 1000)) // expired
        assertFalse(isPayableClip("hello world"))
        assertFalse(isPayableClip("lnbc1garbage"))
        assertFalse(isPayableClip("npub1abc"))
    }
}
