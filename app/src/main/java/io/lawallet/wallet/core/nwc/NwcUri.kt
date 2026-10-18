package io.lawallet.wallet.core.nwc

import io.lawallet.wallet.core.nostr.Keys
import java.net.URLDecoder

/** `nostr+walletconnect://<walletPubkey>?relay=wss://…&secret=<hex>&lud16=…` */
data class NwcUri(val walletPubkey: String, val relays: List<String>, val secret: Keys, val lud16: String?) {
    companion object {
        fun parse(raw: String): NwcUri {
            val s = raw.trim()
            val rest = listOf("nostr+walletconnect://", "nostrwalletconnect://", "nostr+walletconnect:")
                .firstOrNull { s.startsWith(it, ignoreCase = true) }
                ?.let { s.substring(it.length) }
                ?: throw IllegalArgumentException("not a NWC connection string")
            val pubkey = rest.substringBefore('?').trimEnd('/')
            require(pubkey.length == 64) { "invalid wallet pubkey" }
            val params = rest.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.map {
                URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('=', ""), "UTF-8")
            }
            val relays = params.filter { it.first == "relay" }.map { it.second }
            val secret = params.firstOrNull { it.first == "secret" }?.second
            require(relays.isNotEmpty() && secret != null) { "missing relay or secret" }
            return NwcUri(pubkey.lowercase(), relays, Keys.fromHex(secret), params.firstOrNull { it.first == "lud16" }?.second)
        }
    }
}
