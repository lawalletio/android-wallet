package io.lawallet.wallet.core.nostr

import java.util.Base64

/** NIP-98 HTTP auth: `Authorization: Nostr <base64(event)>`. */
object Nip98 {
    const val KIND = 27235

    /** [body] must be the exact bytes sent — the server hashes the raw request body. */
    fun tags(url: String, method: String, body: ByteArray? = null): List<List<String>> = buildList {
        add(listOf("u", url))
        add(listOf("method", method.uppercase()))
        if (body != null && body.isNotEmpty()) add(listOf("payload", sha256(body).toHex()))
    }

    fun event(keys: Keys, url: String, method: String, body: ByteArray? = null): NostrEvent =
        NostrEvent.sign(keys, KIND, "", tags(url, method, body))

    fun header(event: NostrEvent): String = "Nostr " + Base64.getEncoder().encodeToString(event.toJson().toByteArray())

    fun header(keys: Keys, url: String, method: String, body: ByteArray? = null) = header(event(keys, url, method, body))
}
