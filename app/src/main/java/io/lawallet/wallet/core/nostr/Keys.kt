package io.lawallet.wallet.core.nostr

import fr.acinq.secp256k1.Secp256k1

/** A secp256k1 Nostr key pair. [secret] is 32 raw bytes. */
class Keys(val secret: ByteArray) {
    init {
        require(secret.size == 32 && Secp256k1.secKeyVerify(secret)) { "invalid secret key" }
    }

    /** x-only (32 byte) public key, hex. */
    val pubkey: String by lazy { xOnly(secret).toHex() }
    val secretHex: String get() = secret.toHex()
    val npub: String get() = Nip19.npub(pubkey)
    val nsec: String get() = Nip19.nsec(secretHex)

    fun sign(hash32: ByteArray): ByteArray = Secp256k1.signSchnorr(hash32, secret, randomBytes(32))

    /** ECDH shared x-coordinate with another x-only pubkey (NIP-04/NIP-44 input). */
    fun sharedX(peerPubkeyHex: String): ByteArray {
        val full = byteArrayOf(0x02) + peerPubkeyHex.hexToBytes()
        val point = Secp256k1.pubKeyTweakMul(Secp256k1.pubkeyParse(full), secret)
        return Secp256k1.pubKeyCompress(point).copyOfRange(1, 33)
    }

    companion object {
        fun generate(): Keys {
            while (true) {
                val b = randomBytes(32)
                if (Secp256k1.secKeyVerify(b)) return Keys(b)
            }
        }

        fun fromHex(hex: String) = Keys(hex.hexToBytes())

        fun xOnly(secret: ByteArray): ByteArray =
            Secp256k1.pubKeyCompress(Secp256k1.pubkeyCreate(secret)).copyOfRange(1, 33)

        fun verify(sig: ByteArray, hash32: ByteArray, pubkeyHex: String): Boolean =
            Secp256k1.verifySchnorr(sig, hash32, pubkeyHex.hexToBytes())
    }
}
