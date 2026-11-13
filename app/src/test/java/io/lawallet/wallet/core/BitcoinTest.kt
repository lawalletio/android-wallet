package io.lawallet.wallet.core

import io.lawallet.wallet.core.btc.Bip39
import io.lawallet.wallet.core.btc.BtcImport
import io.lawallet.wallet.core.btc.BtcInput
import io.lawallet.wallet.core.btc.PhraseProblem
import io.lawallet.wallet.core.btc.ScriptType
import io.lawallet.wallet.core.btc.Unsupported
import io.lawallet.wallet.core.btc.WatchKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class BitcoinTest {
    private val bip39 = Bip39(File("src/main/res/raw/bip39_english.txt").readLines())
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test fun bip39Vectors() {
        assertEquals("abandon ".repeat(11) + "about", bip39.fromEntropy(ByteArray(16)).joinToString(" "))
        assertEquals("legal winner thank year wave sausage worth useful legal winner thank yellow", bip39.fromEntropy(hex("7f".repeat(16))).joinToString(" "))
        assertEquals("zoo ".repeat(23) + "vote", bip39.fromEntropy(hex("ff".repeat(32))).joinToString(" "))
    }

    @Test fun bip39Checks() {
        repeat(20) { assertNull(bip39.problem(bip39.generate(if (it % 2 == 0) 12 else 24))) }
        assertEquals(PhraseProblem.CHECKSUM, bip39.problem(List(12) { "abandon" }))
        assertEquals(PhraseProblem.LENGTH, bip39.problem(List(11) { "abandon" }))
        assertEquals(PhraseProblem.UNKNOWN_WORD, bip39.problem(listOf("abandon", "bitcoinz")))
        assertEquals(listOf("abandon", "ability", "able", "about"), bip39.suggest("ab"))
    }

    @Test fun detects() {
        fun d(s: String) = BtcImport.detect(s, bip39)
        assertEquals(BtcInput.Phrase(("abandon ".repeat(11) + "about").split(" "), null), d("  Abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about "))
        assertEquals(PhraseProblem.UNKNOWN_WORD, (d("aban") as BtcInput.Phrase).problem)
        // BIP84 test vector account key.
        val zpub = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        assertEquals(BtcInput.WatchOnly(WatchKind.XPUB, zpub, ScriptType.P2WPKH), d(zpub))
        assertEquals(BtcInput.Rejected(Unsupported.INVALID), d("wpkh(" + zpub.dropLast(1) + "t)"))
        assertEquals(ScriptType.P2WPKH, (d("bitcoin:BC1QAR0SRRR7XFKVY5L643LYDNW9RE59GTZZWF5MDQ?amount=1") as BtcInput.WatchOnly).script)
        assertEquals(ScriptType.P2TR, (d("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr") as BtcInput.WatchOnly).script)
        assertEquals(ScriptType.P2PKH, (d("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN2") as BtcInput.WatchOnly).script)
        assertEquals(BtcInput.Rejected(Unsupported.TESTNET), d("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
        assertNull(d("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN3")) // bad checksum
        assertNull(d(""))
    }
}
