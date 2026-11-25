package io.lawallet.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.lawallet.wallet.core.store.SecretStore
import io.lawallet.wallet.testing.FakeInstance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.Base64
import javax.crypto.SecretKey

/** The nsec wrapper on the device's real Android Keystore (Robolectric tests run it on an in-memory fake). */
@RunWith(AndroidJUnit4::class)
class SecretStoreTest {
    @Test fun roundTripsThroughTheRealKeystore() {
        val secret = FakeInstance.throwawaySecret()
        val blob = SecretStore.encrypt(secret)
        assertFalse(blob.contains(secret))
        assertEquals(secret, SecretStore.decrypt(blob))
        // A fresh IV every time.
        assertNotEquals(blob, SecretStore.encrypt(secret))

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        // The platform's provider, not the tests' FakeAndroidKeyStore.
        assertFalse(ks.provider.javaClass.name.startsWith("io.lawallet"))
        // Non-exportable: the key material never leaves the Keystore.
        assertNull((ks.getKey("lawallet-signer-key", null) as SecretKey).encoded)
    }

    @Test fun aTamperedBlobIsRejected() {
        val data = Base64.getDecoder().decode(SecretStore.encrypt(FakeInstance.throwawaySecret()))
        // IV (0..11), ciphertext, GCM tag (last 16): a flipped bit anywhere fails authentication.
        for (i in listOf(0, 12, data.size - 1)) {
            val tampered = data.copyOf().also { it[i] = (it[i].toInt() xor 1).toByte() }
            assertThrows(GeneralSecurityException::class.java) { SecretStore.decrypt(Base64.getEncoder().encodeToString(tampered)) }
        }
    }
}
