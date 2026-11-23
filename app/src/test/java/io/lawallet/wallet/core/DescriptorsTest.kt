package io.lawallet.wallet.core

import io.lawallet.wallet.core.btc.Bip21
import io.lawallet.wallet.core.btc.BtcImport
import io.lawallet.wallet.core.btc.Chain
import io.lawallet.wallet.core.btc.DescriptorPair
import io.lawallet.wallet.core.btc.Descriptors
import io.lawallet.wallet.core.btc.ScriptType
import org.bitcoindevkit.Address
import org.bitcoindevkit.Amount
import org.bitcoindevkit.BumpFeeTxBuilder
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Persister
import org.bitcoindevkit.Psbt
import org.bitcoindevkit.SignersContainer
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.TxBuilder
import org.bitcoindevkit.UnconfirmedTx
import org.bitcoindevkit.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DescriptorsTest {
    private val abandon = ("abandon ".repeat(11) + "about").split(" ")
    private val zoo = ("zoo ".repeat(11) + "wrong").split(" ")
    private val legal = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")

    private fun first(p: DescriptorPair, chain: Chain = Chain.MAINNET) = Descriptors.address(p.external, 0, chain)

    @Test fun singleSigVectors() {
        // BIP-84/86/49/44 test vectors for "abandon … about".
        val p84 = Descriptors.fromPhrase(abandon, null, ScriptType.P2WPKH, Chain.MAINNET)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", first(p84))
        assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el", Descriptors.address(p84.internal, 0, Chain.MAINNET))
        assertEquals("bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr", first(Descriptors.fromPhrase(abandon, null, ScriptType.P2TR, Chain.MAINNET)))
        assertEquals("37VucYSaXLCAsxYyAPfbSi9eh4iEcbShgf", first(Descriptors.fromPhrase(abandon, null, ScriptType.P2SH_P2WPKH, Chain.MAINNET)))
        assertEquals("1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA", first(Descriptors.fromPhrase(abandon, null, ScriptType.P2PKH, Chain.MAINNET)))
        // Signet uses coin type 1.
        val signet = Descriptors.fromPhrase(abandon, null, ScriptType.P2WPKH, Chain.SIGNET)
        assertTrue(signet.external, "/84'/1'/0']tpub" in signet.external)
        assertEquals("tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl", first(signet, Chain.SIGNET))
        // A passphrase is a different wallet.
        assertNotEquals(first(p84), first(Descriptors.fromPhrase(abandon, "TREZOR", ScriptType.P2WPKH, Chain.MAINNET)))
    }

    @Test fun watchOnlyMatchesSeed() {
        val zpub = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        val w = Descriptors.fromXpub(zpub, ScriptType.P2WPKH, Chain.MAINNET)
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", first(w))
        assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el", Descriptors.address(w.internal, 0, Chain.MAINNET))
        // The same key as a descriptor, receive-only and multipath, with a zpub (SLIP-132, normalised).
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", first(Descriptors.fromDescriptor("wpkh([73c5da0a/84'/0'/0']$zpub/0/*)", Chain.MAINNET)))
        val multipath = Descriptors.fromDescriptor("wpkh([73c5da0a/84h/0h/0h]$zpub/<0;1>/*)", Chain.MAINNET)
        assertEquals("bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el", Descriptors.address(multipath.internal, 0, Chain.MAINNET))
        // The seed's own public descriptor round-trips (its checksum is checked).
        val seed = Descriptors.fromPhrase(abandon, null, ScriptType.P2WPKH, Chain.MAINNET)
        assertEquals(seed, Descriptors.fromDescriptor(seed.external, Chain.MAINNET))
        assertTrue(runCatching { Descriptors.fromDescriptor(seed.external.dropLast(1) + "q", Chain.MAINNET) }.isFailure)
    }

    @Test fun cosignerKeys() {
        val k = Descriptors.cosigner(abandon, null, Chain.SIGNET)
        assertTrue(k, Regex("^\\[73c5da0a/48'/1'/0'/2']tpub[1-9A-HJ-NP-Za-km-z]+$").matches(k))
        assertEquals(k to Chain.SIGNET, Descriptors.cosignerKey(" $k/<0;1>/* "))
        assertEquals(k to Chain.SIGNET, Descriptors.cosignerKey("$k/0/*"))
        assertNull(Descriptors.cosignerKey("nope"))
        // A Vpub (SLIP-132 for signet P2WSH) reads as the tpub it is.
        val tpub = k.substringAfter(']')
        val vpub = BtcImport.extendedKey(tpub)!!.xpub
        assertEquals(tpub, vpub)
    }

    @Test fun multisigSignsCombinesAndBumps() {
        val chain = Chain.SIGNET
        val seeds = listOf(abandon, zoo, legal)
        val keys = seeds.map { Descriptors.cosigner(it, null, chain) }
        val vault = Descriptors.multisig(2, keys, chain)
        assertEquals(2 to 3, Descriptors.quorum(vault.external))
        // sortedmulti: the cosigners' order doesn't change the addresses.
        assertEquals(first(vault, chain), first(Descriptors.multisig(2, keys.reversed(), chain), chain))
        assertTrue(first(vault, chain).startsWith("tb1q") && first(vault, chain).length == 62)
        // A seed that isn't a cosigner can't sign for it.
        assertNull(Descriptors.multisigSecret(vault, "legal winner thank year wave sausage worth useful legal winner thank year".split(" ").dropLast(1) + "yellow", "x", chain))

        val wallet = Wallet(Descriptor(vault.external, chain.kind), Descriptor(vault.internal, chain.kind), chain.bdk, Persister.newInMemory())
        fund(wallet, 100_000)
        val psbt = TxBuilder().addRecipient(dest(), Amount.fromSat(40_000u)).feeRate(FeeRate.fromSatPerVb(2u)).finish(wallet)
        val base = psbt.serialize()
        fun signedBy(seed: List<String>) = Psbt(base).also { p ->
            val signers = Descriptors.multisigSecret(vault, seed, null, chain)!!.map { SignersContainer.fromDescriptor(it) }
            assertFalse(wallet.signWithSigners(p, signers)) // one signature isn't enough to finalise
        }
        val combined = signedBy(abandon).combine(signedBy(legal))
        assertTrue(wallet.finalizePsbt(combined))
        val tx = combined.extractTx()
        assertTrue(tx.input().all { it.sequence == 0xFFFFFFFDu }) // RBF signalled
        assertEquals(40_000uL, tx.output().first { it.scriptPubkey.toBytes().contentEquals(dest().toBytes()) }.value.toSat())

        // Speed up: the replacement pays more and spends the same coin.
        wallet.applyUnconfirmedTxs(listOf(UnconfirmedTx(tx, 2u)))
        val bump = BumpFeeTxBuilder(tx.computeTxid(), FeeRate.fromSatPerVb(6u)).finish(wallet)
        assertTrue(bump.fee() > psbt.fee())
    }

    @Test fun singleSigSignsInOneGo() {
        val chain = Chain.SIGNET
        val pair = Descriptors.fromPhrase(abandon, null, ScriptType.P2TR, chain)
        val wallet = Wallet(Descriptor(pair.external, chain.kind), Descriptor(pair.internal, chain.kind), chain.bdk, Persister.newInMemory())
        fund(wallet, 50_000)
        // Max: drain everything to the destination, no change.
        val psbt = TxBuilder().drainWallet().drainTo(dest()).feeRate(FeeRate.fromSatPerVb(1u)).finish(wallet)
        val signers = Descriptors.secret(abandon, null, ScriptType.P2TR, chain).map { SignersContainer.fromDescriptor(it) }
        assertTrue(wallet.signWithSigners(psbt, signers))
        val tx = psbt.extractTx()
        assertEquals(1, tx.output().size)
        assertEquals(50_000uL, tx.output()[0].value.toSat() + psbt.fee())
    }

    @Test fun bip21() {
        val b = Bip21.parse("bitcoin:BC1QCR8TE4KR609GCAWUTMRZA0J4XV80JY8Z306FYU?amount=0.0005&label=Coffee%20shop&lightning=lnbc1")!!
        assertEquals("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu", b.address)
        assertEquals(50_000L, b.sats)
        assertEquals("Coffee shop", b.label)
        assertEquals("lnbc1", b.lightning)
        assertEquals("bitcoin:bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu?amount=0.0005&label=Coffee%20shop", b.copy(lightning = null).uri())
        assertEquals("BITCOIN:BC1QCR8TE4KR609GCAWUTMRZA0J4XV80JY8Z306FYU?amount=0.00000001", Bip21(b.address, 1).uri(qr = true))
        assertEquals("1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA", Bip21.parse("1LqBGSKuX5yYUonjxT5qGfpUsXKYYWeabA")!!.address)
        assertNull(Bip21.parse("bitcoin:bc1q?req-pop=x"))
        assertNull(Bip21.parse("bitcoin:bc1q?amount=1.123456789"))
        assertNull(Bip21.parse("lightning:lnbc1"))
        assertEquals(Chain.SIGNET, Descriptors.chainOf("tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl"))
        assertEquals(Chain.MAINNET, Descriptors.chainOf("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"))
        assertNull(Descriptors.chainOf("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyv"))
    }

    @Test fun scansRouteOnchain() = kotlinx.coroutines.runBlocking<Unit> {
        val router = io.lawallet.wallet.core.ln.ScanRouter(io.lawallet.wallet.core.ln.Lnurl(okhttp3.OkHttpClient()))
        suspend fun route(s: String) = router.route(s, emptySet())
        val onchain = route("bitcoin:tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl?amount=0.001") as io.lawallet.wallet.core.ln.ScanResult.Onchain
        assertEquals(100_000L, onchain.request.sats)
        assertTrue(route("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu") is io.lawallet.wallet.core.ln.ScanResult.Onchain)
        // A unified QR with a Lightning invoice pays over Lightning.
        val bolt11 = "lnbc10u1pj9rk5zpp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypqdqqcqzzsxqyz5vqsp5qqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqqqsyqcyq5rqwzqfqypq9qyyssq0"
        val unified = route("bitcoin:bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu?amount=0.00001&lightning=$bolt11")
        assertTrue(unified.toString(), unified !is io.lawallet.wallet.core.ln.ScanResult.Onchain || io.lawallet.wallet.core.ln.DestinationParser.runCatching { parse(bolt11) }.isFailure)
        // Wallet keys and descriptors go to onchain import, labelled by what they are.
        val zpub = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
        val key = route(zpub) as io.lawallet.wallet.core.ln.ScanResult.WalletKey
        assertEquals("zpub" to false, key.label to key.private)
        val xpub = BtcImport.extendedKey(zpub)!!.xpub
        assertEquals("descriptor", (route("wpkh($xpub/0/*)") as io.lawallet.wallet.core.ln.ScanResult.WalletKey).label)
        val xprv = "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi"
        assertTrue((route(xprv) as io.lawallet.wallet.core.ln.ScanResult.WalletKey).private)
        // Not an address: falls through to the Lightning parsers as before.
        assertTrue(route("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyv") is io.lawallet.wallet.core.ln.ScanResult.Error)
    }

    @Test fun scripthash() {
        // Electrum protocol docs example: P2PKH script of 1A1zP1eP5QGefi2DMPTfTL5SLmv7DivfNa.
        val script = "76a91462e907b15cbf27d5425399ebf6f0fb50ebb88f1888ac".chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertEquals("8b01df4e368ea28f8dc0423bcf7a4923e3a12d307c875e47a0cfbf90b5c39161", Descriptors.scripthash(script))
    }

    private fun dest() = Address("tb1q6rz28mcfaxtmd6v789l9rrlrusdprr9pqcpvkl", org.bitcoindevkit.Network.SIGNET).scriptPubkey()

    /** A made-up unconfirmed transaction paying [sats] to the wallet's first address. */
    private fun fund(w: Wallet, sats: Long) {
        val script = w.revealNextAddress(KeychainKind.EXTERNAL).address.scriptPubkey().toBytes()
        fun le(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * it)).toByte() }
        val raw = le(2, 4) + byteArrayOf(1) + ByteArray(32) { 0x11 } + le(0, 4) + byteArrayOf(0) + le(0xffffffffL, 4) +
            byteArrayOf(1) + le(sats, 8) + byteArrayOf(script.size.toByte()) + script + le(0, 4)
        w.applyUnconfirmedTxs(listOf(UnconfirmedTx(Transaction(raw), 1u)))
        assertEquals(sats.toULong(), w.balance().total.toSat())
    }
}
