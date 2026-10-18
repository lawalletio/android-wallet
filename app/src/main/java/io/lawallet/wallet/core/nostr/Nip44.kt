package io.lawallet.wallet.core.nostr

import org.bouncycastle.crypto.digests.SHA256Digest
import org.bouncycastle.crypto.engines.ChaCha7539Engine
import org.bouncycastle.crypto.generators.HKDFBytesGenerator
import org.bouncycastle.crypto.params.HKDFParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.ParametersWithIV
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** NIP-44 v2 (ChaCha20 + HMAC-SHA256, padded). */
object Nip44 {
    fun conversationKey(keys: Keys, peerPubkey: String): ByteArray =
        hmac("nip44-v2".toByteArray(), keys.sharedX(peerPubkey)) // HKDF-extract

    fun encrypt(plaintext: String, conversationKey: ByteArray, nonce: ByteArray = randomBytes(32)): String {
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        val ct = chacha(chachaKey, chachaNonce, pad(plaintext))
        val mac = hmac(hmacKey, nonce + ct)
        return Base64.getEncoder().encodeToString(byteArrayOf(2) + nonce + ct + mac)
    }

    fun decrypt(payload: String, conversationKey: ByteArray): String {
        require(payload.isNotEmpty() && payload[0] != '#') { "unknown nip44 version" }
        val data = Base64.getDecoder().decode(payload)
        require(data.size in 99..65603 && data[0].toInt() == 2) { "invalid nip44 payload" }
        val nonce = data.copyOfRange(1, 33)
        val ct = data.copyOfRange(33, data.size - 32)
        val mac = data.copyOfRange(data.size - 32, data.size)
        val (chachaKey, chachaNonce, hmacKey) = messageKeys(conversationKey, nonce)
        require(MessageDigest.isEqual(hmac(hmacKey, nonce + ct), mac)) { "invalid MAC" }
        return unpad(chacha(chachaKey, chachaNonce, ct))
    }

    fun encrypt(plaintext: String, keys: Keys, peerPubkey: String) = encrypt(plaintext, conversationKey(keys, peerPubkey))
    fun decrypt(payload: String, keys: Keys, peerPubkey: String) = decrypt(payload, conversationKey(keys, peerPubkey))

    fun calcPaddedLen(len: Int): Int {
        if (len <= 32) return 32
        val nextPower = 1 shl (32 - Integer.numberOfLeadingZeros(len - 1))
        val chunk = if (nextPower <= 256) 32 else nextPower / 8
        return chunk * ((len - 1) / chunk + 1)
    }

    private fun pad(plaintext: String): ByteArray {
        val b = plaintext.toByteArray()
        require(b.size in 1..65535) { "invalid plaintext length" }
        val out = ByteArray(2 + calcPaddedLen(b.size))
        out[0] = (b.size ushr 8).toByte()
        out[1] = b.size.toByte()
        b.copyInto(out, 2)
        return out
    }

    private fun unpad(padded: ByteArray): String {
        val len = ((padded[0].toInt() and 0xff) shl 8) or (padded[1].toInt() and 0xff)
        require(len > 0 && padded.size == 2 + calcPaddedLen(len)) { "invalid padding" }
        return String(padded, 2, len)
    }

    private fun messageKeys(conversationKey: ByteArray, nonce: ByteArray): Triple<ByteArray, ByteArray, ByteArray> {
        val gen = HKDFBytesGenerator(SHA256Digest())
        gen.init(HKDFParameters.skipExtractParameters(conversationKey, nonce))
        val out = ByteArray(76).also { gen.generateBytes(it, 0, 76) }
        return Triple(out.copyOfRange(0, 32), out.copyOfRange(32, 44), out.copyOfRange(44, 76))
    }

    private fun chacha(key: ByteArray, nonce: ByteArray, input: ByteArray): ByteArray {
        val engine = ChaCha7539Engine()
        engine.init(true, ParametersWithIV(KeyParameter(key), nonce))
        return ByteArray(input.size).also { engine.processBytes(input, 0, input.size, it, 0) }
    }

    private fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(data)
}
