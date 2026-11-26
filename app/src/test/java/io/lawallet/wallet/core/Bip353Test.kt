package io.lawallet.wallet.core

import io.lawallet.wallet.core.btc.Bip353
import io.lawallet.wallet.core.btc.DohResolver
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.xbill.DNS.EDNSOption
import org.xbill.DNS.Message
import org.xbill.DNS.Resolver
import org.xbill.DNS.Section
import org.xbill.DNS.TSIG
import org.xbill.DNS.TXTRecord
import org.xbill.DNS.Type
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executor

/**
 * DNSSEC validation runs against real answers recorded once from Cloudflare's DoH (src/test/resources/bip353), at the
 * time they were recorded. To record again: `LW_RECORD_BIP353=1 ./gradlew testDebugUnitTest --tests '*Bip353Test'`.
 */
class Bip353Test {
    @Test fun parsesNames() {
        val n = Bip353.parse("₿Matt@MattCorallo.com")!!
        assertEquals("matt.user._bitcoin-payment.mattcorallo.com.", n.dns)
        assertEquals("₿matt@mattcorallo.com", n.display)
        assertEquals("₿matt@mattcorallo.com", Bip353.parse("matt@mattcorallo.com")!!.display)
        // Non-ASCII is shown as punycode, so a look-alike name can't pass for another.
        assertEquals("₿pago@xn--espaa-rta.com", Bip353.parse("pago@españa.com")!!.display)
        for (bad in listOf("matt", "matt@", "@mattcorallo.com", "matt@localhost", "bitcoin:bc1q@x.com", "a@b@c.com", "https://x.com/a@b.com")) assertNull(bad, Bip353.parse(bad))
    }

    @Test fun picksTheOneBitcoinRecord() {
        assertEquals("BITCOIN:?sp=x", Bip353.pick(listOf("v=spf1 -all", "BITCOIN:?sp=x")))
        reason(Bip353.Reason.NOT_FOUND) { Bip353.pick(listOf("v=spf1 -all")) }
        reason(Bip353.Reason.AMBIGUOUS) { Bip353.pick(listOf("bitcoin:a", "bitcoin:b")) }
    }

    @Test fun signedNameValidates() = runBlocking {
        val i = bip353("secure").resolve(Bip353.parse("matt@mattcorallo.com")!!)
        assertTrue(i.dnssec)
        assertTrue(i.uri, i.uri.startsWith("bitcoin:"))
    }

    @Test fun forgedRecordIsRefused() = runBlocking {
        reason(Bip353.Reason.INVALID) { runBlocking { bip353("secure", forge = true).resolve(Bip353.parse("matt@mattcorallo.com")!!) } }
    }

    @Test fun unsignedZoneIsInsecure() = runBlocking {
        val (texts, secure) = bip353("insecure").txt("google.com.")
        assertTrue(texts.isNotEmpty())
        assertFalse(secure)
    }

    private fun reason(expected: Bip353.Reason, block: () -> Unit) {
        try { block(); fail("expected $expected") } catch (e: Bip353.Bip353Exception) { assertEquals(expected, e.reason) }
    }

    private fun bip353(case: String, forge: Boolean = false): Bip353 {
        val file = File("src/test/resources/bip353/$case.txt")
        if (System.getenv("LW_RECORD_BIP353") != null) {
            val rec = Recorder(DohResolver(OkHttpClient(), "https://cloudflare-dns.com/dns-query"), file)
            return Bip353(rec, Clock.systemUTC())
        }
        val lines = file.readLines().filter { it.isNotBlank() }
        val time = Instant.ofEpochSecond(lines.first().removePrefix("time ").toLong())
        val answers = lines.drop(1).associate { it.substringBefore('\t') to Base64.getDecoder().decode(it.substringAfter('\t')) }
        return Bip353(Replay(answers, forge), Clock.fixed(time, ZoneOffset.UTC))
    }

    private abstract class Fake : Resolver {
        override fun setPort(port: Int) {}
        override fun setTCP(flag: Boolean) {}
        override fun setIgnoreTruncation(flag: Boolean) {}
        override fun setEDNS(version: Int, payloadSize: Int, flags: Int, options: List<EDNSOption>?) {}
        override fun setTSIGKey(key: TSIG?) {}
        override fun setTimeout(timeout: Duration) {}
        protected fun key(q: Message) = "${q.question.name.toString().lowercase()} ${Type.string(q.question.type)}"
    }

    /** Answers from the recording, with the same id as the query; [forge] swaps every TXT answer for another one. */
    private class Replay(private val answers: Map<String, ByteArray>, private val forge: Boolean) : Fake() {
        override fun sendAsync(query: Message, executor: Executor): CompletionStage<Message> {
            val bytes = answers[key(query)] ?: return CompletableFuture.failedFuture(IOException("not recorded: ${key(query)}"))
            val m = Message(bytes).apply { header.id = query.header.id }
            if (forge && query.question.type == Type.TXT) {
                val ans = m.getSection(Section.ANSWER)
                m.removeAllRecords(Section.ANSWER)
                ans.forEach { rr -> m.addRecord(if (rr is TXTRecord) TXTRecord(rr.name, rr.dClass, rr.ttl, "bitcoin:bc1qforged") else rr, Section.ANSWER) }
            }
            return CompletableFuture.completedFuture(m)
        }
    }

    private class Recorder(private val live: Resolver, private val file: File) : Fake() {
        init { file.parentFile.mkdirs(); file.writeText("time ${Instant.now().epochSecond}\n") }
        override fun sendAsync(query: Message, executor: Executor): CompletionStage<Message> =
            live.sendAsync(query, executor).thenApply { m ->
                synchronized(file) { file.appendText("${key(query)}\t${Base64.getEncoder().encodeToString(m.toWire())}\n") }
                m
            }
    }
}
