package io.lawallet.wallet.core

import io.lawallet.wallet.core.btc.Bip21
import io.lawallet.wallet.core.btc.BtcImport
import io.lawallet.wallet.core.btc.Chain
import io.lawallet.wallet.core.btc.DescriptorPair
import io.lawallet.wallet.core.btc.Descriptors
import io.lawallet.wallet.core.btc.ScriptType
import io.lawallet.wallet.core.btc.SilentPayment
import io.lawallet.wallet.core.nostr.hexToBytes
import io.lawallet.wallet.core.nostr.toHex
import org.bitcoindevkit.Descriptor
import org.bitcoindevkit.DescriptorSecretKey
import org.bitcoindevkit.FeeRate
import org.bitcoindevkit.KeychainKind
import org.bitcoindevkit.Persister
import org.bitcoindevkit.SignersContainer
import org.bitcoindevkit.Transaction
import org.bitcoindevkit.UnconfirmedTx
import org.bitcoindevkit.Wallet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SilentPaymentTest {
    // BIP-352 send_and_receive_test_vectors.json: its recipient, and the outpoints every sending case spends.
    private val address = "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv"
    private val tx0 = "f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16"
    private val tx1 = "a1075db55d416d3ca199f55b6084e2115b9345e16c5cf302fc80e9d5fbf5d48d"
    private val abandon = ("abandon ".repeat(11) + "about").split(" ")

    private fun send(k0: String, tap0: Boolean, k1: String, tap1: Boolean) = SilentPayment.outputKey(
        SilentPayment.parse(address)!!,
        listOf(SilentPayment.Input(tx0, 0, k0.hexToBytes(), tap0), SilentPayment.Input(tx1, 0, k1.hexToBytes(), tap1)),
    ).toHex()

    @Test fun parsesAddresses() {
        val a = SilentPayment.parse(address)!!
        assertEquals("0220bcfac5b99e04ad1a06ddfb016ee13582609d60b6291e98d01a9bc9a16c96d4", a.scan.toHex())
        assertEquals("025cc9856d6f8375350e123978daac200c260cb5b5ae83106cab90484dcd8fcf36", a.spend.toHex())
        assertEquals(Chain.MAINNET, a.chain)
        assertNull(SilentPayment.parse(address.dropLast(1) + "q"))
        assertNull(SilentPayment.parse("bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu"))
        assertEquals(Chain.MAINNET, Descriptors.chainOf(address))
        // BIP-321: the silent payment address in `sp=` wins over the fallback, and may stand alone.
        assertEquals(address, Bip21.parse("bitcoin:bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu?sp=$address")!!.address)
        assertEquals(address, Bip21.parse("bitcoin:?sp=${address.uppercase()}&amount=0.001")!!.address.lowercase())
        assertEquals(address, Bip21.parse(address.uppercase())!!.address)
    }

    @Test fun bip352Vectors() {
        // "Simple send: two inputs" (P2PKH).
        assertEquals("3e9fce73d4e77a4809908e3c3a2e54ee147b9312dc5044a193d1fc85de46e3c1",
            send("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1", false, "93f5ed907ad5b2bdbbdcb5d9116ebc0a4e1f92f910d5260237fa45a9408aad16", false))
        // "taproot only inputs with even y-values".
        assertEquals("de88bea8e7ffc9ce1af30d1132f910323c505185aec8eae361670421e749a1fb",
            send("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1", true, "fc8716a97a48ba9a05a98ae47b5cd201a25a7fd5d8b73c203c5f7b6b6b3b6ad7", true))
        // "taproot only with mixed even/odd y-values".
        assertEquals("77cab7dd12b10259ee82c6ea4b509774e33e7078e7138f568092241bf26b99f1",
            send("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1", true, "1d37787c2b7116ee983e9f9c13269df29091b391c04db94239e0d2bc2182c3bf", true))
        // "taproot input with odd y-value and non-taproot input".
        assertEquals("359358f59ee9e9eec3f00bdf4882570fd5c182e451aa2650b788544aff012a3a",
            send("1d37787c2b7116ee983e9f9c13269df29091b391c04db94239e0d2bc2182c3bf", true, "8d4751f6e8a3586880fb66c19ae277969bd5aa06f61c4ee2f1e2486efdf666d3", false))
    }

    @Test fun taprootTweakMatchesBdk() {
        val root = Descriptors.secretKey(abandon, null, Chain.MAINNET)
        val d = root.derive(org.bitcoindevkit.DerivationPath("m/86'/0'/0'/0/0")).secretBytes()
        val first = Descriptors.address(Descriptors.fromPhrase(abandon, null, ScriptType.P2TR, Chain.MAINNET).external, 0, Chain.MAINNET)
        val program = org.bitcoindevkit.Address(first, Chain.MAINNET.bdk).scriptPubkey().toBytes().copyOfRange(2, 34)
        assertEquals(program.toHex(), fr.acinq.secp256k1.Secp256k1.pubKeyCompress(fr.acinq.secp256k1.Secp256k1.pubkeyCreate(SilentPayment.taprootTweak(d))).copyOfRange(1, 33).toHex())
    }

    @Test fun draftsFromEveryScriptType() {
        val xprv = "xprv9s21ZrQH143K3QTDL4LXw2F7HEK3wJUD2nW2nRk4stbPy6cq3jPPqjiChkVvvNKmPGJxWUtg6LnF5kejMRNNU3TGtRBeJgk33yuGBxrMPHi"
        val cases = ScriptType.entries.map { t -> Triple(Descriptors.fromPhrase(abandon, null, t, Chain.MAINNET), Descriptors.secretKey(abandon, null, Chain.MAINNET), Descriptors.secret(abandon, null, t, Chain.MAINNET)) } +
            Triple(Descriptors.fromXprv(xprv, ScriptType.P2WPKH, Chain.MAINNET), DescriptorSecretKey.fromString(BtcImport.extendedKey(xprv)!!.xpub), Descriptors.xprvSecret(xprv, ScriptType.P2WPKH, Chain.MAINNET))
        for ((pair, root, secret) in cases) for (sats in listOf(30_000L, null)) {
            val w = wallet(pair)
            fund(w, 20_000, 0x11); fund(w, 20_000, 0x22)
            val to = SilentPayment.parse(address)!!
            val psbt = SilentPayment.draft(w, root, to, sats, FeeRate.fromSatPerVb(2u))
            assertNotNull(pair.external, psbt)
            psbt!!
            val expected = SilentPayment.script(SilentPayment.outputKey(to, SilentPayment.inputs(psbt, root)!!))
            val tx = psbt.extractTxUncheckedFeeRate()
            assertEquals(2, tx.input().size)
            val paid = tx.output().single { it.scriptPubkey.toBytes().contentEquals(expected) }.value.toSat().toLong()
            assertEquals(sats ?: (40_000 - psbt.fee().toLong()), paid)
            assertTrue(w.signWithSigners(psbt, secret.map { SignersContainer.fromDescriptor(it) }))
        }
    }

    @Test fun watchOnlyKeysDontMatch() {
        val pair = Descriptors.fromPhrase(abandon, null, ScriptType.P2WPKH, Chain.MAINNET)
        val w = wallet(pair)
        fund(w, 20_000, 0x11)
        val other = Descriptors.secretKey(("zoo ".repeat(11) + "wrong").split(" "), null, Chain.MAINNET)
        assertNull(SilentPayment.draft(w, other, SilentPayment.parse(address)!!, 5_000, FeeRate.fromSatPerVb(1u)))
    }

    private fun wallet(p: DescriptorPair) =
        Wallet(Descriptor(p.external, Chain.MAINNET.kind), Descriptor(p.internal, Chain.MAINNET.kind), Chain.MAINNET.bdk, Persister.newInMemory())

    /** A made-up unconfirmed tx paying [sats] to the wallet's next address, from a fake input [tag]. */
    private fun fund(w: Wallet, sats: Long, tag: Int) {
        val script = w.revealNextAddress(KeychainKind.EXTERNAL).address.scriptPubkey().toBytes()
        fun le(v: Long, n: Int) = ByteArray(n) { (v ushr (8 * it)).toByte() }
        val raw = le(2, 4) + byteArrayOf(1) + ByteArray(32) { tag.toByte() } + le(0, 4) + byteArrayOf(0) + le(0xffffffffL, 4) +
            byteArrayOf(1) + le(sats, 8) + byteArrayOf(script.size.toByte()) + script + le(0, 4)
        w.applyUnconfirmedTxs(listOf(UnconfirmedTx(Transaction(raw), 1u)))
    }
}
