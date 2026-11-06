package io.lawallet.wallet.service

import android.content.Intent
import android.nfc.cardemulation.HostApduService
import android.os.Bundle
import io.lawallet.wallet.MainActivity
import io.lawallet.wallet.core.diag.Trail

/**
 * Phone-to-phone taps are a coin flip: a POS presenting an invoice (HCE Type 4 tag) also polls
 * for cards, so with the wallet closed the POS may read *us* and SELECT the NDEF AID. We answer
 * that SELECT by bringing MainActivity forward — its reader mode then reads the POS invoice.
 * (A system-bound HostApduService is exempt from background-activity-launch limits.)
 */
class TapWakeService : HostApduService() {
    override fun processCommandApdu(apdu: ByteArray, extras: Bundle?): ByteArray {
        Trail.add("nfc", "read by a reader; opening app to read it back")
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return byteArrayOf(0x6A, 0x82.toByte()) // file not found: we're not a tag, drop the link
    }

    override fun onDeactivated(reason: Int) {}
}
