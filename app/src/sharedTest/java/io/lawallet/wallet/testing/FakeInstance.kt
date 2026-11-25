package io.lawallet.wallet.testing

import io.lawallet.wallet.core.FakeNwcWallet
import io.lawallet.wallet.core.api.BankAccount
import io.lawallet.wallet.core.api.CurrentUser
import io.lawallet.wallet.core.api.NostrProfile
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.api.Swap
import io.lawallet.wallet.core.api.SwapQuote
import io.lawallet.wallet.core.api.WalletAddress
import io.lawallet.wallet.core.apiJson
import io.lawallet.wallet.core.nostr.Bech32
import io.lawallet.wallet.core.nostr.NostrEvent
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import java.io.Closeable
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * A LaWallet instance on a local [MockWebServer], for JVM (Robolectric) and on-device tests alike: no real account,
 * key or wallet is ever involved.
 *
 * Out of the box it answers like a fresh account on a community with cash-out on: `/api/jwt` mints a JWT for whoever
 * signs the NIP-98 header, `/api/users/me` returns the primary address `satoshi@<host>` whose NWC connection is
 * [wallet] (a [FakeNwcWallet]: relay + NIP-47 service, so balance, payments and invoices really work), one remote
 * wallet `w1` backed by that same wallet, and the ARS cash-out endpoints (quotes at [arsPerBtc], [bankAccounts] for the
 * CBU/alias lookup, [swaps]).
 *
 * Everything is a plain `var`: change it between steps. [offline] makes every request fail at the socket (the app's
 * "offline mode"); [requests] records what the app asked for.
 *
 * ```
 * val instance = FakeInstance().start()
 * val c = TestContainer.create(context, instance)
 * runBlocking { c.loginThrowaway() }
 * assertTrue(instance.requests.any { it.path == "/api/users/me" })
 * instance.close()
 * ```
 */
class FakeInstance(nip44: Boolean = true) : Closeable {
    /** A request the app made: method, path (no query), query string, body and Authorization header. */
    data class Req(val method: String, val path: String, val query: String?, val body: String, val auth: String?)

    val server = MockWebServer()
    /** The account's wallet: its relay and NIP-47 service. Set [FakeNwcWallet.balanceMsat] to change the balance. */
    val wallet = FakeNwcWallet(nip44)

    /** Every request, in order. */
    val requests = CopyOnWriteArrayList<Req>()

    /** True: every request is dropped at the socket, like an unreachable server. */
    @Volatile var offline = false

    @Volatile var username = "satoshi"
    /** The JWT's `pubkey` claim; null = the signer's own key. Set it to simulate a passkey (secondary key) login. */
    @Volatile var accountPubkey: String? = null
    /** `/api/settings` keys (InstanceSettings field names). */
    @Volatile var settings: Map<String, String> = mapOf("community_name" to "Test Community", "integration_bullbitcoin" to "true", "integration_alias" to "true")
    /** False: `me` carries no NWC string (a new account), so the app falls back to the default remote wallet. */
    @Volatile var nwcInMe = true
    @Volatile var addresses: List<WalletAddress> = listOf(WalletAddress(username, mode = "CUSTOM_NWC", remoteWalletId = "w1", remoteWalletName = "Main", isPrimary = true))
    @Volatile var remoteWallets: List<RemoteWallet> = listOf(RemoteWallet("w1", "Main", isDefault = true))
    /** NWC connection per remote wallet id (`w1` → [wallet]). */
    @Volatile var connections: Map<String, String> = emptyMap()
    /** Kind-0 profiles `/api/nostr/profiles` knows, by pubkey. */
    @Volatile var profiles: Map<String, NostrProfile> = emptyMap()

    /** Cash-out: the providers' rate (default 1 ARS = 1 sat) and minimum. */
    @Volatile var arsPerBtc = 100_000_000.0
    @Volatile var minArs = 100.0
    /** CBU/CVU/alias lookup, by the lower-cased query. Unknown → 404. */
    @Volatile var bankAccounts: Map<String, BankAccount> = emptyMap()
    /** Cash-out orders, newest first. A created one is PENDING; `/paid` completes it. */
    @Volatile var swaps: List<Swap> = emptyList()
    /** False: `/paid` only records the payment (PENDING, transferring) and the test completes the order itself. */
    @Volatile var completeOnPaid = true
    private val swapIds = AtomicInteger()

    val url: String get() = server.url("/").toString().trimEnd('/')
    val host: String get() = server.url("/").let { "${it.host}:${it.port}" }

    fun start(): FakeInstance {
        wallet.start()
        connections = mapOf("w1" to wallet.connectionString)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.requestUrl?.encodedPath.orEmpty()
                val req = Req(request.method.orEmpty(), path, request.requestUrl?.encodedQuery, request.body.readUtf8(), request.getHeader("Authorization"))
                requests += req
                if (offline) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
                return runCatching { respond(req.method, path, req) }.getOrElse { error(500, it.toString()) }
            }
        }
        server.start()
        return this
    }

    override fun close() {
        runCatching { wallet.dropConnections(); wallet.server.shutdown() }
        runCatching { server.shutdown() }
    }

    fun paths(): List<String> = requests.map { "${it.method} ${it.path}" }

    private fun respond(method: String, path: String, req: Req): MockResponse {
        val seg = path.split('/').filter { it.isNotEmpty() }
        return when {
            path == "/api/settings" -> ok(JsonObject(settings.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }).toString())
            path == "/api/version" -> ok("""{"currentVersion":"test"}""")
            path == "/api/jwt" && method == "POST" -> ok("""{"token":"${jwtFor(req)}"}""")
            path == "/api/users/me" -> ok(apiJson.encodeToString(CurrentUser.serializer(), me()))
            path == "/api/nostr/profiles" -> {
                val wanted = apiJson.parseToJsonElement(req.body).jsonObject["pubkeys"]?.let { apiJson.decodeFromJsonElement(ListSerializer(kotlinx.serialization.serializer<String>()), it) }.orEmpty()
                ok(buildJsonObject { put("profiles", apiJson.encodeToJsonElement(ListSerializer(NostrProfile.serializer()), wanted.mapNotNull { profiles[it] })) }.toString())
            }
            path == "/api/wallet/addresses" && method == "GET" -> ok(apiJson.encodeToString(ListSerializer(WalletAddress.serializer()), addresses))
            path == "/api/wallet/cards" -> ok("[]")
            path == "/api/remote-wallets" && method == "GET" -> ok(apiJson.encodeToString(ListSerializer(RemoteWallet.serializer()), remoteWallets))
            seg.size == 3 && seg[1] == "remote-wallets" && method == "GET" ->
                remoteWallets.find { it.id == seg[2] }?.let { ok(apiJson.encodeToString(RemoteWallet.serializer(), it)) } ?: error(404, "not found")
            seg.size == 4 && seg[1] == "remote-wallets" && seg[3] == "connection-string" ->
                connections[seg[2]]?.let { ok(buildJsonObject { put("connectionString", it) }.toString()) } ?: error(404, "not found")
            seg.size == 4 && seg[1] == "remote-wallets" && seg[3] == "balance" -> ok("""{"balanceSats":${wallet.balanceMsat / 1000}}""")
            seg.size == 4 && seg[1] == "remote-wallets" && seg[3] == "notifications" -> ok("[]")
            path == "/api/users/me/currency-prefs" -> ok("{}")
            path == "/api/wallet/swaps/quote" -> {
                val ars = Regex("amountArs=([0-9.]+)").find(req.query.orEmpty())?.groupValues?.get(1)?.toDouble() ?: 0.0
                ok(apiJson.encodeToString(ListSerializer(SwapQuote.serializer()), listOf(SwapQuote("bullbitcoin", sats = satsFor(ars), arsPerBtc = arsPerBtc, minArs = minArs))))
            }
            path == "/api/wallet/swaps" && method == "POST" -> {
                val b = apiJson.parseToJsonElement(req.body).jsonObject
                val ars = b["amountArs"]!!.jsonPrimitive.double
                val sats = satsFor(ars)
                val swap = Swap(
                    id = "swap-${swapIds.incrementAndGet()}", provider = b["provider"]!!.jsonPrimitive.content, destination = b["destination"]!!.jsonPrimitive.content,
                    holderName = b["holderName"]?.jsonPrimitive?.content, amountArs = ars, amountSats = sats, bolt11 = invoice(sats),
                    createdAt = java.time.Instant.now().toString(), expiresAt = java.time.Instant.now().plusSeconds(600).toString(),
                )
                swaps = listOf(swap) + swaps
                ok(apiJson.encodeToString(Swap.serializer(), swap))
            }
            path == "/api/wallet/swaps" -> ok(buildJsonObject {
                put("items", apiJson.encodeToJsonElement(ListSerializer(Swap.serializer()), swaps)); put("total", swaps.size)
            }.toString())
            seg.size == 4 && seg[2] == "swaps" -> swaps.find { it.id == seg[3] }?.let { ok(apiJson.encodeToString(Swap.serializer(), it)) } ?: error(404, "not found")
            seg.size == 5 && seg[2] == "swaps" && (seg[4] == "paid" || seg[4] == "cancel") -> {
                val now = java.time.Instant.now().toString()
                swaps = swaps.map { if (it.id != seg[3]) it else if (seg[4] == "paid") it.copy(status = if (completeOnPaid) "COMPLETED" else it.status, paidAt = now, updatedAt = now) else it.copy(status = "CANCELED", updatedAt = now) }
                swaps.find { it.id == seg[3] }?.let { ok(apiJson.encodeToString(Swap.serializer(), it)) } ?: error(404, "not found")
            }
            seg.size == 4 && seg[2] == "alias" -> bankAccounts[java.net.URLDecoder.decode(seg[3], "UTF-8")]
                ?.let { ok(apiJson.encodeToString(BankAccount.serializer(), it)) } ?: error(404, "Account not found")
            else -> error(404, "not found")
        }
    }

    private fun satsFor(ars: Double) = Math.round(ars / arsPerBtc * 100_000_000)

    private fun me() = CurrentUser(
        userId = "user-1", lightningAddress = "$username@${server.hostName}", nwcString = if (nwcInMe) wallet.connectionString else null,
        primaryUsername = username, primaryAddressMode = "CUSTOM_NWC",
    )

    /** A JWT (`exp` 2100) for the key that signed the NIP-98 header; [accountPubkey] makes it a secondary key's. */
    private fun jwtFor(req: Req): String {
        val signer = req.auth?.let { NostrEvent.json.parseToJsonElement(String(Base64.getDecoder().decode(it.removePrefix("Nostr ")))).jsonObject["pubkey"]?.jsonPrimitive?.content }
        val primary = accountPubkey ?: signer
        return "${b64("""{"alg":"none"}""")}.${b64("""{"exp":4102444800,"pubkey":"$primary","authPubkey":"$signer"}""")}.x"
    }

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)
    private fun error(code: Int, message: String) = MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json")
        .setBody(buildJsonObject { put("success", false); put("error", buildJsonObject { put("message", message) }) }.toString())

    companion object {
        private fun b64(s: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(s.toByteArray())
        private fun randomHex() = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        /** A throwaway Nostr secret key (hex, which the login screen accepts): never a real account's. */
        fun throwawaySecret() = randomHex()

        /**
         * A syntactically valid mainnet BOLT11 invoice for [sats] (0 = no amount), created now. Its signature is zeros:
         * the app only decodes invoices, the (fake) wallet pays them.
         */
        fun invoice(sats: Long, description: String = "test", expirySeconds: Long = 3600): String {
            val words = ArrayList<Int>()
            fun num(v: Long, n: Int) = (n - 1 downTo 0).map { ((v ushr (5 * it)) and 31).toInt() }
            fun tag(type: Int, data: List<Int>) { words += type; words += num(data.size.toLong(), 2); words += data }
            fun bytes(b: ByteArray) = Bech32.convertBits(b.map { it.toInt() and 0xff }.toIntArray(), 8, 5, true).toList()
            words += num(System.currentTimeMillis() / 1000, 7)
            tag(1, bytes(ByteArray(32).also { SecureRandom().nextBytes(it) }))
            tag(13, bytes(description.toByteArray()))
            tag(6, num(expirySeconds, 4))
            words += List(104) { 0 }
            return Bech32.encode(if (sats > 0) "lnbc${sats * 10}n" else "lnbc", words.toIntArray())
        }
    }
}
