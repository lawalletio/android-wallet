package io.lawallet.wallet.core

import io.lawallet.wallet.core.btc.Bip39
import io.lawallet.wallet.core.btc.SeedQr
import io.lawallet.wallet.core.ln.Lnurl
import io.lawallet.wallet.core.ln.ScanResult
import io.lawallet.wallet.core.ln.ScanRouter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class SeedQrTest {
    private val bip39 = Bip39(File("src/main/res/raw/bip39_english.txt").readLines())
    private val abandon = ("abandon ".repeat(11) + "about").split(" ")
    private val legal = "legal winner thank year wave sausage worth useful legal winner thank yellow".split(" ")
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** A QR byte-mode segment as a reader's raw bytes give it: 0100, an 8-bit length, the data, the 0000 terminator. */
    private fun segment(data: ByteArray): ByteArray {
        val bits = StringBuilder("0100").append(data.size.toString(2).padStart(8, '0'))
        data.forEach { bits.append((it.toInt() and 0xff).toString(2).padStart(8, '0')) }
        bits.append("0000")
        while (bits.length % 8 != 0) bits.append('0')
        return bits.chunked(8).map { it.toInt(2).toByte() }.toByteArray()
    }

    @Test fun standard() {
        // Each word's index in 4 digits: "about" is word 3.
        assertEquals(abandon to "SeedQR", SeedQr.read("0000".repeat(11) + "0003", bip39))
        val digits = legal.joinToString("") { bip39.words.indexOf(it).toString().padStart(4, '0') }
        assertEquals(legal, SeedQr.read(digits, bip39)!!.first)
        // A bad checksum, an index past 2047, or the wrong length isn't a phrase.
        assertNull(SeedQr.read("0000".repeat(12), bip39))
        assertNull(SeedQr.read("2048" + "0000".repeat(11), bip39))
        assertNull(SeedQr.read("0000".repeat(11), bip39))
    }

    @Test fun compact() {
        val zero = ByteArray(16)
        assertEquals(abandon to "Compact SeedQR", SeedQr.read(SeedQr.BINARY + hex(zero), bip39))
        // The same entropy inside a byte-mode segment, as some readers hand it over.
        val seven = ByteArray(16) { 0x7f }
        assertEquals(legal, SeedQr.read(SeedQr.BINARY + hex(segment(seven)), bip39)!!.first)
        // 24 words: 32 bytes.
        assertEquals(24, SeedQr.read(SeedQr.BINARY + hex(segment(ByteArray(32) { 0xff.toByte() })), bip39)!!.first.size)
        assertNull(SeedQr.read(SeedQr.BINARY + hex(ByteArray(20)), bip39))
    }

    @Test fun plainWords() {
        assertEquals(legal, SeedQr.read(legal.joinToString(" ").uppercase(), bip39)!!.first)
        assertNull(SeedQr.read("abandon abandon", bip39))
    }

    @Test fun scannerRoutesPhrasesToImport() = runBlocking<Unit> {
        val router = ScanRouter(Lnurl(OkHttpClient())) { bip39 }
        val r = router.route("0000".repeat(11) + "0003", emptySet()) as ScanResult.WalletKey
        assertEquals(ScanResult.KeyKind.PHRASE, r.kind)
        assertEquals(abandon.joinToString(" "), r.text)
        assertEquals(ScanResult.KeyKind.PHRASE, (router.route(SeedQr.BINARY + hex(ByteArray(16)), emptySet()) as ScanResult.WalletKey).kind)
        // Without a word list, a number is just an unsupported code.
        assertEquals(false, ScanRouter(Lnurl(OkHttpClient())).route("0000".repeat(11) + "0003", emptySet()) is ScanResult.WalletKey)
    }
}
