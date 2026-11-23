package io.lawallet.wallet.core

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/** Receive's QR carries the network's mark over its middle (BigQr, 20% of its width): at level Q it must still scan. */
class ReceiveQrTest {
    private fun scanWithMark(data: String): String {
        val m = QRCodeWriter().encode(data, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.Q, EncodeHintType.MARGIN to 0))
        val scale = 8
        val quiet = 4 * scale
        val side = m.width * scale + 2 * quiet
        val c = side / 2.0
        val r = m.width * scale * 0.2 / 2 // the badge: a disc 20% of the code's width
        val px = IntArray(side * side) { i ->
            val x = i % side; val y = i / side
            val mx = (x - quiet) / scale; val my = (y - quiet) / scale
            val dark = x >= quiet && y >= quiet && mx < m.width && my < m.width && m[mx, my]
            val covered = (x - c) * (x - c) + (y - c) * (y - c) <= r * r
            if (dark && !covered) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val bmp = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(side, side, px)))
        return QRCodeReader().decode(bmp, mapOf(DecodeHintType.PURE_BARCODE to true)).text
    }

    @Test fun lightningAddress() = assertEquals("lightning:agustin@lawallet.io", scanWithMark("lightning:agustin@lawallet.io"))

    @Test fun bip21WithAmount() {
        val uri = "BITCOIN:TB1QNAM0C74ZDN3P28UDLDLPEU5602A95UJELPRP96?amount=0.00005"
        assertEquals(uri, scanWithMark(uri))
    }

    @Test fun multisigAddress() {
        val uri = "BITCOIN:TB1Q9TQCEF0AW55FRKJRYA42RVUJ59FJFA4N40WY8YCU53N2TQK45FHQP6SF4G"
        assertEquals(uri, scanWithMark(uri))
    }
}
