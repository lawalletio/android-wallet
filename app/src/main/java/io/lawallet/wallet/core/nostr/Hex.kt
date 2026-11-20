package io.lawallet.wallet.core.nostr

import java.security.MessageDigest
import java.security.SecureRandom

private val HEX = "0123456789abcdef".toCharArray()

/** Lowercase hex. Runs on every relay event (its id): a table, not a String.format per byte. */
fun ByteArray.toHex(): String {
    val out = CharArray(size * 2)
    for (i in indices) { val v = this[i].toInt() and 0xff; out[2 * i] = HEX[v ushr 4]; out[2 * i + 1] = HEX[v and 0x0f] }
    return String(out)
}

fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd hex length" }
    return ByteArray(length / 2) { i -> substring(i * 2, i * 2 + 2).toInt(16).toByte() }
}

fun sha256(data: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(data)

fun randomBytes(n: Int): ByteArray = ByteArray(n).also { SecureRandom().nextBytes(it) }
