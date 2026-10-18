package io.lawallet.wallet.core.auth

import io.lawallet.wallet.core.nostr.hexToBytes
import io.lawallet.wallet.core.nostr.toHex
import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters

/**
 * Port of apps/web/lib/client/passkey-prf.ts. CONSENSUS-CRITICAL: these constants must
 * match the web exactly or the same passkey derives a different Nostr identity.
 */
object PasskeyPrf {
    /** sha256("lawallet-nsec-prf-v1") */
    const val SALT_HEX = "8ee22949c1045c627e14236f1d06cf730b46b4cf309cbf4ede25a446cf33ad8d"
    private const val INFO = "lawallet-nsec-v1"

    fun salt(): ByteArray = SALT_HEX.hexToBytes()

    /** HKDF-SHA256(ikm = prfOutput, salt = SALT, info = INFO) → 32-byte secret, hex. */
    fun deriveSecretHex(prfOutput: ByteArray): String {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters(prfOutput, salt(), INFO.toByteArray()))
        return ByteArray(32).also { gen.generateBytes(it, 0, 32) }.toHex()
    }
}
