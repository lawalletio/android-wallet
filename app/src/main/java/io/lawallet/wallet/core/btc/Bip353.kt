package io.lawallet.wallet.core.btc

import kotlinx.coroutines.future.await
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xbill.DNS.DClass
import org.xbill.DNS.EDNSOption
import org.xbill.DNS.ExtendedFlags
import org.xbill.DNS.Flags
import org.xbill.DNS.Message
import org.xbill.DNS.OPTRecord
import org.xbill.DNS.Rcode
import org.xbill.DNS.Record
import org.xbill.DNS.Resolver
import org.xbill.DNS.Section
import org.xbill.DNS.TSIG
import org.xbill.DNS.TXTRecord
import org.xbill.DNS.Type
import org.xbill.DNS.dnssec.ValidatingResolver
import java.io.IOException
import java.net.IDN
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/**
 * BIP-353 payment names (`₿user@domain`): a TXT record at `user.user._bitcoin-payment.domain` holding a `bitcoin:` URI.
 * The answers come over DNS-over-HTTPS from [head], but the DNSSEC chain is checked here, from the root keys down: the
 * BIP forbids trusting a resolver's word. A name whose zone isn't signed resolves with [Instructions.dnssec] false, for
 * the user to decide; a signature that doesn't check out is refused.
 */
class Bip353(head: Resolver, clock: Clock = Clock.systemUTC()) {
    constructor(http: OkHttpClient) : this(DohResolver(http, DOH))

    class Name(val user: String, val domain: String) {
        /** Always the ASCII (punycode) form: the BIP's guard against look-alike characters. */
        val display get() = "₿$user@$domain"
        internal val dns get() = "$user.user._bitcoin-payment.$domain."
    }

    data class Instructions(val uri: String, val dnssec: Boolean)

    enum class Reason { NOT_FOUND, AMBIGUOUS, INVALID, OFFLINE }
    class Bip353Exception(val reason: Reason) : Exception(reason.name)

    private val validator = ValidatingResolver(head, clock).apply { loadTrustAnchors(ROOT_ANCHORS.byteInputStream()) }

    suspend fun resolve(name: Name): Instructions {
        val (texts, secure) = txt(name.dns)
        return Instructions(pick(texts), secure)
    }

    /** The TXT strings at [dns] (each record's strings joined) and whether DNSSEC proved them. */
    internal suspend fun txt(dns: String): Pair<List<String>, Boolean> {
        val query = Message.newQuery(Record.newRecord(org.xbill.DNS.Name.fromString(dns), Type.TXT, DClass.IN))
        val r = try { validator.sendAsync(query).await() } catch (e: Exception) {
            if (e is IOException || e.cause is IOException) throw Bip353Exception(Reason.OFFLINE) else throw e
        }
        return when (r.rcode) {
            Rcode.NOERROR -> r.getSection(Section.ANSWER).filterIsInstance<TXTRecord>()
                .map { rr -> rr.stringsAsByteArrays.joinToString("") { String(it, Charsets.UTF_8) } } to r.header.getFlag(Flags.AD.toInt())
            Rcode.NXDOMAIN -> throw Bip353Exception(Reason.NOT_FOUND)
            // What the validator answers for a bad signature, or a chain it couldn't fetch.
            else -> throw Bip353Exception(Reason.INVALID)
        }
    }

    companion object {
        private const val DOH = "https://cloudflare-dns.com/dns-query"

        // The root zone's key-signing keys (KSK-2017 and KSK-2024), from data.iana.org/root-anchors/root-anchors.xml.
        private const val ROOT_ANCHORS = """
. IN DS 20326 8 2 E06D44B80B8F1D39A95C0B0D7C65D08458E880409BBC683457104237C7F8EC8D
. IN DS 38696 8 2 683D2D0ACB8C9B712A1948B27F741219298D0A450D612C483AF444A4C0FB2B16
"""

        private val SHAPE = Regex("^([^@\\s:/?]+)@([^@\\s:/?]+\\.[^@\\s:/?]+)$")

        /** `user@domain`, with or without the ₿; null for anything else. */
        fun parse(text: String): Name? {
            val m = SHAPE.find(text.trim().removePrefix("₿")) ?: return null
            return runCatching {
                val n = Name(IDN.toASCII(m.groupValues[1]).lowercase(), IDN.toASCII(m.groupValues[2]).lowercase().trimEnd('.'))
                n.also { org.xbill.DNS.Name.fromString(it.dns) }
            }.getOrNull()
        }

        /** The one `bitcoin:` record among [texts]; others are ignored, and two of them make the name unusable. */
        fun pick(texts: List<String>): String {
            val uris = texts.filter { it.startsWith("bitcoin:", ignoreCase = true) }
            return when (uris.size) {
                0 -> throw Bip353Exception(Reason.NOT_FOUND)
                1 -> uris.single()
                else -> throw Bip353Exception(Reason.AMBIGUOUS)
            }
        }
    }
}

/** RFC 8484 DNS-over-HTTPS through the app's OkHttp. Asks with DNSSEC records (the DO bit) for the validator to check. */
internal class DohResolver(private val http: OkHttpClient, private val url: String) : Resolver {
    private var edns = OPTRecord(1232, 0, 0, ExtendedFlags.DO)

    override fun setPort(port: Int) {}
    override fun setTCP(flag: Boolean) {}
    override fun setIgnoreTruncation(flag: Boolean) {}
    override fun setTSIGKey(key: TSIG?) {}
    override fun setTimeout(timeout: Duration) {}
    override fun setEDNS(version: Int, payloadSize: Int, flags: Int, options: List<EDNSOption>?) {
        edns = OPTRecord(payloadSize.coerceAtLeast(1232), 0, version, flags, options)
    }

    override fun sendAsync(query: Message, executor: Executor): CompletionStage<Message> = CompletableFuture.supplyAsync({
        val q = query.clone().apply { if (getOPT() == null) addRecord(edns, Section.ADDITIONAL) }
        val req = Request.Builder().url(url).header("Accept", MIME)
            .post(q.toWire().toRequestBody(MIME.toMediaType())).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("DoH HTTP ${r.code}")
            Message(r.body.bytes())
        }
    }, executor)

    private companion object { const val MIME = "application/dns-message" }
}
