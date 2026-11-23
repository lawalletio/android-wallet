package io.lawallet.wallet.core

import com.sparrowwallet.hummingbird.UREncoder
import com.sparrowwallet.hummingbird.registry.CryptoAccount
import com.sparrowwallet.hummingbird.registry.CryptoHDKey
import com.sparrowwallet.hummingbird.registry.CryptoKeypath
import com.sparrowwallet.hummingbird.registry.CryptoOutput
import com.sparrowwallet.hummingbird.registry.ScriptExpression
import com.sparrowwallet.hummingbird.registry.pathcomponent.IndexPathComponent
import io.lawallet.wallet.core.btc.Base58
import io.lawallet.wallet.core.btc.Bip39
import io.lawallet.wallet.core.btc.BtcImport
import io.lawallet.wallet.core.btc.BtcInput
import io.lawallet.wallet.core.btc.Chain
import io.lawallet.wallet.core.btc.Descriptors
import io.lawallet.wallet.core.btc.KeystoreExport
import io.lawallet.wallet.core.btc.Keystores
import io.lawallet.wallet.core.btc.ScriptType
import io.lawallet.wallet.core.btc.UrAssembler
import io.lawallet.wallet.core.ln.Lnurl
import io.lawallet.wallet.core.ln.ScanResult
import io.lawallet.wallet.core.ln.ScanRouter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Keystore exports Sparrow imports from, each checked against the wallet's known first address. */
class KeystoresTest {
    // BIP-84 test vector account ("abandon … about"): fingerprint 73c5da0a, first address bc1qcr8t…
    private val zpub = "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs"
    private val xpub = BtcImport.extendedKey(zpub)!!.xpub
    private val first84 = "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"
    private val abandon = ("abandon ".repeat(11) + "about").split(" ")
    private val zoo = ("zoo ".repeat(11) + "wrong").split(" ")

    private fun firstOf(desc: String, chain: Chain = Chain.MAINNET) = Descriptors.address(Descriptors.fromDescriptor(desc, chain).external, 0, chain)
    private fun single(e: KeystoreExport?, script: ScriptType = ScriptType.P2WPKH) = firstOf(e!!.accounts.getValue(script))

    // A 2-of-2 signet vault over two seeds' BIP-48 keys, and its first address.
    private val cosigners = listOf(abandon, zoo).map { Descriptors.cosigner(it, null, Chain.SIGNET) }
    private val vaultFirst = Descriptors.address(Descriptors.multisig(2, cosigners, Chain.SIGNET).external, 0, Chain.SIGNET)
    private fun fp(k: String) = k.substring(1, 9)
    private fun path(k: String) = k.substringAfter('/').substringBefore(']')
    private fun xkey(k: String) = k.substringAfter(']')
    private fun reversion(key: String, v: Int) = Base58.encodeCheck(byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte()) + Base58.decodeCheck(key)!!.copyOfRange(4, 78))

    @Test fun coldcardJson() {
        val cos = Descriptors.cosigner(abandon, null, Chain.MAINNET)
        val e = Keystores.parse("""{"chain":"BTC","xfp":"73C5DA0A","account":0,
            "bip84":{"name":"p2wpkh","deriv":"m/84'/0'/0'","xpub":"$xpub","_pub":"$zpub"},
            "bip48_2":{"deriv":"m/${path(cos)}","xpub":"${xkey(cos)}"}}""")
        assertEquals("Coldcard", e!!.source)
        assertEquals(first84, single(e))
        assertEquals(cos, e.cosigner)
    }

    @Test fun electrumFiles() {
        val std = """{"keystore":{"type":"bip32","xpub":"$zpub","derivation":"m/84'/0'/0'","root_fingerprint":"73c5da0a"},"wallet_type":"standard"}"""
        assertEquals(first84, single(Keystores.parse(std)))
        // Coldcard's Electrum export: the fingerprint as a little-endian integer.
        val cc = """{"keystore":{"type":"hardware","hw_type":"coldcard","xpub":"$zpub","derivation":"m/84'/0'/0'","ckcc_xfp":182109555},"wallet_type":"standard"}"""
        assertTrue(Keystores.parse(cc)!!.accounts.getValue(ScriptType.P2WPKH).startsWith("wpkh([73c5da0a/84'/0'/0']"))
        // Multisig: x1/, x2/ with Vpub keys (signet P2WSH).
        val ks = cosigners.joinToString(",") { k -> """"x${cosigners.indexOf(k) + 1}/":{"type":"bip32","xpub":"${reversion(xkey(k), 0x02575483)}","derivation":"m/${path(k)}","root_fingerprint":"${fp(k)}"}""" }
        val multi = Keystores.parse("""{"wallet_type":"2of2",$ks}""")!!
        assertEquals(vaultFirst, firstOf(multi.multisig!!, Chain.SIGNET))
    }

    @Test fun descriptorFiles() {
        // Bitcoin Core's listdescriptors: receive and change, private ones skipped.
        val core = """{"wallet_name":"w","descriptors":[{"desc":"wpkh([73c5da0a/84h/0h/0h]$xpub/0/*)","active":true,"internal":false},
            {"desc":"wpkh([73c5da0a/84h/0h/0h]$xpub/1/*)","internal":true}]}"""
        assertEquals(first84, single(Keystores.parse(core)))
        // Sparrow / Specter / Nunchuk wallet files.
        val sparrow = """{"label":"Vault","blockheight":0,"descriptor":"${Descriptors.multisig(2, cosigners, Chain.SIGNET).external}"}"""
        assertEquals(vaultFirst, firstOf(Keystores.parse(sparrow)!!.multisig!!, Chain.SIGNET))
        // Wasabi, Keystone/Cobo.
        val wasabi = """{"ExtPubKey":"$zpub","MasterFingerprint":"73C5DA0A","AccountKeyPath":"84'/0'/0'"}"""
        assertEquals(first84, single(Keystores.parse(wasabi)))
    }

    @Test fun multisigTextAndBsms() {
        val text = """# Coldcard Multisig setup file
            Name: Vault
            Policy: 2 of 2
            Derivation: m/48'/1'/0'/2'
            Format: P2WSH

            ${cosigners.joinToString("\n") { "${fp(it).uppercase()}: ${xkey(it)}" }}"""
        assertEquals(vaultFirst, firstOf(Keystores.parse(text)!!.multisig!!, Chain.SIGNET))
        val bsms = "BSMS 1.0\nwsh(sortedmulti(2,${cosigners.joinToString(",") { "$it/**" }}))\n/0/*,/1/*\n$vaultFirst"
        assertEquals(vaultFirst, firstOf(Keystores.parse(bsms)!!.multisig!!, Chain.SIGNET))
    }

    @Test fun keyWithOrigin() {
        // SeedSigner, Jade, Krux, Specter DIY: a key with its origin; the path's purpose says the script.
        assertEquals(first84, single(Keystores.parse("[73c5da0a/84h/0h/0h]$zpub")))
        val cos = Keystores.parse(cosigners[0])!!
        assertEquals(cosigners[0], cos.cosigner)
        assertTrue(cos.accounts.isEmpty())
        // Plain keys, descriptors and phrases aren't exports: they go the usual way.
        assertNull(Keystores.parse(zpub))
        assertNull(Keystores.parse("wpkh($xpub/0/*)"))
        assertNull(Keystores.parse(abandon.joinToString(" ")))
    }

    @Test fun bcUrAccount() {
        // A Keystone/Passport/Jade account export: crypto-account with a BIP-84 output, animated and assembled.
        val p = Base58.decodeCheck(xpub)!!
        val origin = CryptoKeypath(listOf(IndexPathComponent(84, true), IndexPathComponent(0, true), IndexPathComponent(0, true)), byteArrayOf(0x73, 0xc5.toByte(), 0xda.toByte(), 0x0a))
        val key = CryptoHDKey(false, p.copyOfRange(45, 78), p.copyOfRange(13, 45), null, origin, null, p.copyOfRange(5, 9))
        val ur = CryptoAccount(origin.sourceFingerprint, listOf(CryptoOutput(listOf(ScriptExpression.WITNESS_PUBLIC_KEY_HASH), key))).toUR()
        val enc = UREncoder(ur, 30, 10, 0)
        val asm = UrAssembler()
        var text: String? = null
        repeat(200) { if (text == null) text = asm.receive(enc.nextPart()).second }
        val e = Keystores.parse(assertNotNullAndGet(text))
        assertEquals("BC-UR", e!!.source)
        assertEquals(first84, single(e))
        // A single key (crypto-hdkey) comes back with its origin.
        assertEquals("[73c5da0a/84'/0'/0']$xpub", Keystores.urText(key.toUR()))
    }

    @Test fun importAndScanner() = runBlocking<Unit> {
        val bip39 = Bip39(File("src/main/res/raw/bip39_english.txt").readLines())
        val wasabi = """{"ExtPubKey":"$zpub","MasterFingerprint":"73C5DA0A","AccountKeyPath":"84'/0'/0'"}"""
        assertTrue(BtcImport.detect(wasabi, bip39) is BtcInput.Export)
        val r = ScanRouter(Lnurl(OkHttpClient())) { bip39 }.route(wasabi, emptySet()) as ScanResult.WalletKey
        assertEquals("Wasabi", r.label)
        assertEquals(ScanResult.KeyKind.PUBLIC, r.kind)
    }

    private fun assertNotNullAndGet(s: String?): String { assertNotNull(s); return s!! }
}
