package io.lawallet.wallet.core.nostr

import io.lawallet.wallet.core.nwc.Relay
import io.lawallet.wallet.core.nwc.RelayPool
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.OkHttpClient

/** A kind-0 profile. [raw] keeps every key, including the ones this app doesn't model, so a save never drops them. */
data class Kind0(val raw: JsonObject = JsonObject(emptyMap()), val createdAt: Long = 0) {
    private fun str(vararg keys: String) = keys.firstNotNullOfOrNull { (raw[it] as? JsonPrimitive)?.contentOrNull?.takeIf(String::isNotBlank) }
    val name get() = str("name", "username")
    val displayName get() = str("display_name", "displayName")
    val picture get() = str("picture", "image")
    val banner get() = str("banner")
    val about get() = str("about")
    val nip05 get() = str("nip05")
    val lud16 get() = str("lud16")
    val website get() = str("website")

    /** New content with [patch] applied over every existing key; a blank value removes that key. */
    fun merged(patch: Map<String, String?>): JsonObject = JsonObject(buildMap {
        putAll(raw)
        patch.forEach { (k, v) -> if (v.isNullOrBlank()) remove(k) else put(k, JsonPrimitive(v.trim())) }
        // NIP-24 calls it display_name; drop the deprecated camelCase twin so the two never disagree.
        if ("display_name" in patch) remove("displayName")
    })

    companion object {
        fun of(event: NostrEvent) = Kind0(runCatching { NostrEvent.json.parseToJsonElement(event.content).jsonObject }.getOrDefault(JsonObject(emptyMap())), event.createdAt)
    }
}

object Profiles {
    // ponytail: fixed relay set (the web app's, plus Primal); read the user's NIP-65 list if profiles go missing.
    val RELAYS = listOf("wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net", "wss://relay.lawallet.ar")

    /**
     * Newest kind-0 for [pubkey] across [relays]: an empty [Kind0] when relays answered but have none,
     * null when no relay answered at all — then a save must not go ahead, or it would wipe the real profile.
     */
    suspend fun fetch(http: OkHttpClient, pubkey: String, relays: List<String> = RELAYS, timeoutMs: Long = 6_000, graceMs: Long = 1_500): Kind0? = coroutineScope {
        val conns = relays.map { RelayPool.get(it, http) }
        val subId = "p0-" + pubkey.take(8) + "-" + randomBytes(3).toHex()
        try {
            val filter = buildJsonObject { putJsonArray("kinds") { add(JsonPrimitive(0)) }; putJsonArray("authors") { add(JsonPrimitive(pubkey)) }; put("limit", 1) }
            var best: NostrEvent? = null
            val answered = MutableStateFlow(0)
            // Collecting before the REQs go out, so no EOSE slips past.
            val collector = launch(start = CoroutineStart.UNDISPATCHED) {
                merge(*conns.map { it.messages }.toTypedArray()).collect { m ->
                    when (m) {
                        is Relay.Msg.Event -> if (m.event.kind == 0 && m.event.pubkey == pubkey && m.event.createdAt > (best?.createdAt ?: -1)) best = m.event
                        is Relay.Msg.Eose -> answered.value++
                    }
                }
            }
            conns.forEach { it.subscribe(subId, filter) }
            // Wait for the first answer, then give the rest a short grace: a dead relay mustn't hold the screen.
            if (withTimeoutOrNull(timeoutMs) { answered.first { it > 0 } } != null) withTimeoutOrNull(graceMs) { answered.first { it == conns.size } }
            collector.cancel()
            best?.let(Kind0::of) ?: if (answered.value > 0) Kind0() else null
        } finally {
            conns.forEach { it.unsubscribe(subId) }
        }
    }

    /** Signs [content] as kind 0 and publishes it; succeeds when any relay accepts. */
    suspend fun publish(http: OkHttpClient, signer: Signer, content: JsonObject, relays: List<String> = RELAYS): NostrEvent {
        val event = signer.sign(0, content.toString(), emptyList())
        coroutineScope {
            val conns = relays.map { RelayPool.get(it, http) }
            val results = conns.map { r -> async { runCatching { r.publish(event, 10_000) } } }.awaitAll()
            if (results.none { it.isSuccess }) throw results.first().exceptionOrNull()!!
        }
        return event
    }
}
