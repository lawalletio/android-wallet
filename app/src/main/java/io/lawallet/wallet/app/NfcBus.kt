package io.lawallet.wallet.app

/**
 * Routes a tapped NFC tag's URL. The receive-invoice screen claims it to charge the
 * displayed invoice; otherwise taps fall through to the scan router (LUD-03 withdraw).
 */
object NfcBus {
    @Volatile var consumer: ((String) -> Unit)? = null
}
