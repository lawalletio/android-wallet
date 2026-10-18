package io.lawallet.wallet.core.nostr

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/** NIP-04: AES-256-CBC over the raw ECDH x-coordinate. Still the NWC default. */
object Nip04 {
    fun encrypt(plaintext: String, keys: Keys, peerPubkey: String): String {
        val iv = randomBytes(16)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keys.sharedX(peerPubkey), "AES"), IvParameterSpec(iv))
        val ct = cipher.doFinal(plaintext.toByteArray())
        val b64 = Base64.getEncoder()
        return b64.encodeToString(ct) + "?iv=" + b64.encodeToString(iv)
    }

    fun decrypt(payload: String, keys: Keys, peerPubkey: String): String {
        val parts = payload.split("?iv=")
        require(parts.size == 2) { "invalid nip04 payload" }
        val b64 = Base64.getDecoder()
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keys.sharedX(peerPubkey), "AES"), IvParameterSpec(b64.decode(parts[1])))
        return String(cipher.doFinal(b64.decode(parts[0])))
    }
}
