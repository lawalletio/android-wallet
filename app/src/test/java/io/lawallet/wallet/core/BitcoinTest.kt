package io.lawallet.wallet.core

import io.lawallet.wallet.app.OnchainTx
import io.lawallet.wallet.core.btc.Base58
import io.lawallet.wallet.core.btc.Bip39
import io.lawallet.wallet.core.btc.Chain
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
        assertEquals(BtcInput.WatchOnly(WatchKind.ADDRESS, "tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx", ScriptType.P2WPKH, Chain.SIGNET), d("tb1qw508d6qejxtdg4y5r3zarvary0c5xw7kxpjzsx"))
        // Signet/testnet keys and multisig descriptors are wallets now; a lone Zpub is a cosigner, not a wallet.
        fun reversion(key: String, v: Int) = Base58.encodeCheck(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()) + Base58.decodeCheck(key)!!.copyOfRange(4, 78))
        val vpub = reversion(zpub, 0x045F1CF6)
        assertEquals(BtcInput.WatchOnly(WatchKind.XPUB, vpub, ScriptType.P2WPKH, Chain.SIGNET), d(vpub))
        assertEquals(WatchKind.MULTISIG, (d("wsh(sortedmulti(2,[73c5da0a/48'/1'/0'/2']$vpub/0/*,$vpub/0/*))") as BtcInput.WatchOnly).kind)
        assertEquals(BtcInput.Rejected(Unsupported.MULTISIG), d(reversion(zpub, 0x02AA7ED3)))
        // A zprv is a spending wallet now; a Zprv (one multisig cosigner's private key) still isn't one.
        assertEquals(BtcInput.PrivateKey(reversion(zpub, 0x04B2430C), ScriptType.P2WPKH), d(reversion(zpub, 0x04B2430C)))
        assertEquals(BtcInput.Rejected(Unsupported.PRIVATE_KEY), d(reversion(zpub, 0x02AA7A99)))
        // Normalising keeps the key material: a zpub becomes the xpub of the same key.
        assertEquals(BtcImport.extendedKey(zpub)!!.xpub, BtcImport.extendedKey(reversion(zpub, 0x0488B21E))!!.xpub)
        assertEquals(reversion(zpub, 0x0488B21E), BtcImport.extendedKey(zpub)!!.xpub)
        assertNull(d("1BvBMSEYstWetqTFn5Au4m4GFg7xJaNVN3")) // bad checksum
        assertNull(d(""))
    }

    @Test fun bip39SuggestAndProblemEdges() {
        assertEquals(emptyList<String>(), bip39.suggest(""))
        assertEquals(listOf("zebra", "zero", "zone", "zoo"), bip39.suggest("z", max = 10))
        assertEquals(listOf("abandon"), bip39.suggest("ab", max = 1))
        assertEquals(emptyList<String>(), bip39.suggest("xyz"))
        assertEquals(PhraseProblem.UNKNOWN_WORD, bip39.problem(List(11) { "abandon" } + "Abandon")) // case matters: the list is lowercase
        assertEquals(PhraseProblem.LENGTH, bip39.problem(emptyList()))
        assertEquals(null, bip39.problem(("abandon ".repeat(11) + "about").split(" ")))
    }

    @Test fun sendAmount() {
        fun tx(delta: Long, fee: Long?, paid: Long? = null) = OnchainTx("t", delta, fee, null, 1, 0, null, false, paid)
        assertEquals(2500, tx(-2641, 141).amount)              // to someone else: delta less the fee
        assertEquals(2500, tx(-141, 141, paid = 2500).amount)  // to our own address: delta is just the fee
        assertEquals(2500, tx(2500, null).amount)              // received
    }

    @Test fun payjoinSendCountsOnlyOurSide() {
        // We spent a 100 000 coin to pay 30 000; the receiver added a 264 087 coin, so their output holds 294 087.
        val amount = { paidFee: Pair<Long?, Long?> -> OnchainTx("t", -30_417, paidFee.second, null, 1, 0, null, false, paidFee.first).amount }
        assertEquals(30_000L to 417L, OnchainTx.sendShare(-30_417, 294_087, 363_670, 100_000, fee = 417, asked = 30_000))
        // Not noted at send (an older build, a restored wallet): their input shows in the fee BDK fetched.
        assertEquals(30_000, amount(OnchainTx.sendShare(-30_417, 294_087, 363_670, 100_000, fee = 417, asked = null)))
        assertEquals(30_417, amount(OnchainTx.sendShare(-30_417, 294_087, 363_670, 100_000, fee = null, asked = null)))
        // A plain send keeps its outputs to others.
        assertEquals(2500L to 141L, OnchainTx.sendShare(-2641, 2500, 9859, 10_000, fee = 141, asked = null))
    }
}
