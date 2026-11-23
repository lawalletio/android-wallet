package io.lawallet.wallet.core

import com.sparrowwallet.hummingbird.UR
import com.sparrowwallet.hummingbird.UREncoder
import io.lawallet.wallet.core.ln.CashuException
import io.lawallet.wallet.core.ln.CashuMint
import io.lawallet.wallet.core.ln.CashuToken
import io.lawallet.wallet.core.btc.UrAssembler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CashuTest {
    // NUT-00 test vectors.
    private val v3 = "cashuAeyJ0b2tlbiI6W3sibWludCI6Imh0dHBzOi8vODMzMy5zcGFjZTozMzM4IiwicHJvb2ZzIjpbeyJhbW91bnQiOjIsImlkIjoiMDA5YTFmMjkzMjUzZTQxZSIsInNlY3JldCI6IjQwNzkxNWJjMjEyYmU2MWE3N2UzZTZkMmFlYjRjNzI3OTgwYmRhNTFjZDA2YTZhZmMyOWUyODYxNzY4YTc4MzciLCJDIjoiMDJiYzkwOTc5OTdkODFhZmIyY2M3MzQ2YjVlNDM0NWE5MzQ2YmQyYTUwNmViNzk1ODU5OGE3MmYwY2Y4NTE2M2VhIn0seyJhbW91bnQiOjgsImlkIjoiMDA5YTFmMjkzMjUzZTQxZSIsInNlY3JldCI6ImZlMTUxMDkzMTRlNjFkNzc1NmIwZjhlZTBmMjNhNjI0YWNhYTNmNGUwNDJmNjE0MzNjNzI4YzcwNTdiOTMxYmUiLCJDIjoiMDI5ZThlNTA1MGI4OTBhN2Q2YzA5NjhkYjE2YmMxZDVkNWZhMDQwZWExZGUyODRmNmVjNjlkNjEyOTlmNjcxMDU5In1dfV0sInVuaXQiOiJzYXQiLCJtZW1vIjoiVGhhbmsgeW91LiJ9"
    private val v4 = "cashuBo2F0gqJhaUgA_9SLj17PgGFwgaNhYQFhc3hAYWNjMTI0MzVlN2I4NDg0YzNjZjE4NTAxNDkyMThhZjkwZjcxNmE1MmJmNGE1ZWQzNDdlNDhlY2MxM2Y3NzM4OGFjWCECRFODGd5IXVW-07KaZCvuWHk3WrnnpiDhHki6SCQh88-iYWlIAK0mjE0fWCZhcIKjYWECYXN4QDEzMjNkM2Q0NzA3YTU4YWQyZTIzYWRhNGU5ZjFmNDlmNWE1YjRhYzdiNzA4ZWIwZDYxZjczOGY0ODMwN2U4ZWVhY1ghAjRWqhENhLSsdHrr2Cw7AFrKUL9Ffr1XN6RBT6w659lNo2FhAWFzeEA1NmJjYmNiYjdjYzY0MDZiM2ZhNWQ1N2QyMTc0ZjRlZmY4YjQ0MDJiMTc2OTI2ZDNhNTdkM2MzZGNiYjU5ZDU3YWNYIQJzEpxXGeWZN5qXSmJjY8MzxWyvwObQGr5G1YCCgHicY2FtdWh0dHA6Ly9sb2NhbGhvc3Q6MzMzOGF1Y3NhdA"

    @Test fun parsesTokens() {
        val a = CashuToken.parse("cashu:$v3")!!
        assertEquals("https://8333.space:3338", a.mint)
        assertEquals(10L, a.amount)
        assertEquals("Thank you.", a.memo)
        assertEquals("009a1f293253e41e", a.proofs[0].id)

        val b = CashuToken.parse("https://wallet.cashu.me/#token=$v4")!!
        assertEquals("http://localhost:3338", b.mint)
        assertEquals(4L, b.amount)
        assertEquals("00ffd48b8f5ecf80", b.proofs[0].id)
        assertEquals("0244538319de485d55bed3b29a642bee5879375ab9e7a620e11e48ba482421f3cf", b.proofs[0].c)

        assertNull(CashuToken.parse("satoshi@lawallet.io"))
        assertNull(CashuToken.parse("cashuBnotcbor"))
    }

    @Test fun assemblesAnimatedQr() {
        val enc = UREncoder(UR.fromBytes(v4.toByteArray()), 60, 10, 0)
        val scanner = UrAssembler()
        var out: String? = null
        var frames = 0
        while (out == null && frames < 200) { out = scanner.receive(enc.nextPart().uppercase()).second; frames++ }
        assertEquals(v4, out)
        assertTrue(frames > 1)
    }

    @Test fun meltsIntoInvoice() = runBlocking {
        val server = MockWebServer()
        val bodies = mutableListOf<String>()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val body = request.body.readUtf8().also { if (it.isNotEmpty()) bodies += it }
                return when (request.path) {
                    "/v1/keysets" -> MockResponse().setBody("""{"keysets":[{"id":"00ffd48b8f5ecf80","unit":"sat","active":true,"input_fee_ppk":100},{"id":"00ad268c4d1f5826","unit":"sat","active":true}]}""")
                    "/v1/melt/quote/bolt11" -> {
                        val amt = body.substringAfter("lnbc").substringBefore("\"").toLong()
                        MockResponse().setBody("""{"quote":"q$amt","amount":$amt,"fee_reserve":1,"state":"UNPAID"}""")
                    }
                    "/v1/melt/bolt11" -> if (body.contains("q2")) MockResponse().setBody("""{"state":"PAID"}""") else MockResponse().setResponseCode(400).setBody("""{"detail":"bad quote","code":20000}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val token = CashuToken.parse(v4)!!.copy(mint = server.url("/").toString())
        val asked = mutableListOf<Long>()
        val r = CashuMint(OkHttpClient()).claim(token) { sats -> asked += sats; "lnbc$sats" to "hash$sats" }
        // 4 sats, 1 sat input fee: 3 (+1 reserve) is too much → 2 (+1 reserve +1 fee = 4).
        assertEquals(listOf(3L, 2L), asked)
        assertEquals(2L, r.sats)
        assertTrue(r.paid)
        val melt = Json.parseToJsonElement(bodies.last()).jsonObject
        assertEquals(3, melt["inputs"]!!.jsonArray.size)
        assertEquals("00ffd48b8f5ecf80", melt["inputs"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)

        assertThrows(CashuException::class.java) { runBlocking { CashuMint(OkHttpClient()).claim(token.copy(unit = "usd")) { "" to null } } }
        server.shutdown()
    }
}
