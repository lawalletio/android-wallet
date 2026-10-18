package io.lawallet.wallet.core

import io.lawallet.wallet.core.auth.PasskeyPrf
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.nostr.Nip04
import io.lawallet.wallet.core.nostr.Nip19
import io.lawallet.wallet.core.nostr.Nip44
import io.lawallet.wallet.core.nostr.Nip98
import io.lawallet.wallet.core.nostr.NostrEvent
import io.lawallet.wallet.core.nostr.hexToBytes
import io.lawallet.wallet.core.nostr.sha256
import io.lawallet.wallet.core.nostr.toHex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Vectors for nip04/event/prf were produced by nostr-tools + WebCrypto running the web wallet's code. */
class NostrCryptoTest {
    private val sk1 = Keys.fromHex("0000000000000000000000000000000000000000000000000000000000000001")
    private val sk2 = Keys.fromHex("0000000000000000000000000000000000000000000000000000000000000002")

    @Test fun pubkeys() {
        assertEquals("79be667ef9dcbbac55a06295ce870b07029bfcdb2dce28d959f2815b16f81798", sk1.pubkey)
        assertEquals("c6047f9441ed7d6d3045406e95c07cd85c778e4b8cef3ca7abac09b95c709ee5", sk2.pubkey)
    }

    @Test fun nip19() {
        assertEquals("npub180cvv07tjdrrgpa0j7j7tmnyl2yr6yr7l8j4s3evf6u64th6gkwsyjh6w6", Nip19.npub("3bf0c63fcb93463407af97a5e5ee64fa883d107ef9e558472c4eb9aaaefa459d"))
        assertEquals("67dea2ed018072d675f5415ecfaed7d2597555e202d85b3d65ea4e58d2d92ffa", Nip19.parseSecret("nsec1vl029mgpspedva04g90vltkh6fvh240zqtv9k0t9af8935ke9laqsnlfe5"))
        assertEquals(sk2.secretHex, Nip19.parseSecret(sk2.nsec))
        assertEquals(sk2.pubkey, Nip19.decodeNpub(sk2.npub))
        assertThrows(IllegalArgumentException::class.java) { Nip19.parseSecret(sk2.npub) }
    }

    @Test fun nip04DecryptsNostrToolsCiphertextAndRoundTrips() {
        val ct = "YSw+ZnBf72s7SCM2PDqldvAaU1uLGsVCaHN3K4DyQBqXisp42u8l4o6hQBB9hN3C?iv=8hus5AD2Y+uAW/uKA/BDRw=="
        val expected = "hello nwc ✓ {\"method\":\"get_balance\"}"
        assertEquals(expected, Nip04.decrypt(ct, sk2, sk1.pubkey))
        assertEquals(expected, Nip04.decrypt(Nip04.encrypt(expected, sk1, sk2.pubkey), sk2, sk1.pubkey))
    }

    @Test fun nip44MatchesNostrTools() {
        val ck = Nip44.conversationKey(sk1, sk2.pubkey)
        assertEquals("c41c775356fd92eadc63ff5a0dc1da211b268cbea22316767095b2871ea1412d", ck.toHex())
        val payload = "AgcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHWv/K9jOBW04E3L48XCgnIiVT+3DCnN09bTH4f+pgueGG4yjY5l2n81nw97PLjKj4IEvLx9VuwlnRGQ055GZVCAWn"
        assertEquals(payload, Nip44.encrypt("hello nip44 ✓", ck, ByteArray(32) { 7 }))
        assertEquals("hello nip44 ✓", Nip44.decrypt(payload, Nip44.conversationKey(sk2, sk1.pubkey)))
    }

    @Test fun nip44OfficialVectors() {
        val v2 = Json.parseToJsonElement(javaClass.getResource("/nip44.vectors.json")!!.readText()).jsonObject["v2"]!!.jsonObject
        val valid = v2["valid"]!!.jsonObject
        valid["get_conversation_key"]!!.jsonArray.forEach {
            val o = it.jsonObject
            assertEquals(o.s("conversation_key"), Nip44.conversationKey(Keys.fromHex(o.s("sec1")), o.s("pub2")).toHex())
        }
        valid["calc_padded_len"]!!.jsonArray.forEach {
            val (len, padded) = it.jsonArray.map { n -> n.jsonPrimitive.int }
            assertEquals(padded, Nip44.calcPaddedLen(len))
        }
        valid["encrypt_decrypt"]!!.jsonArray.forEach {
            val o = it.jsonObject
            val ck = o.s("conversation_key").hexToBytes()
            assertEquals(o.s("conversation_key"), Nip44.conversationKey(Keys.fromHex(o.s("sec1")), Keys.fromHex(o.s("sec2")).pubkey).toHex())
            assertEquals(o.s("payload"), Nip44.encrypt(o.s("plaintext"), ck, o.s("nonce").hexToBytes()))
            assertEquals(o.s("plaintext"), Nip44.decrypt(o.s("payload"), ck))
        }
        v2["invalid"]!!.jsonObject["decrypt"]!!.jsonArray.forEach {
            val o = it.jsonObject
            assertTrue(o.s("note"), runCatching { Nip44.decrypt(o.s("payload"), o.s("conversation_key").hexToBytes()) }.isFailure)
        }
    }

    @Test fun eventIdMatchesNostrToolsSerialization() {
        val tags = listOf(listOf("u", "https://beta.lawallet.io/api/jwt"), listOf("method", "POST"))
        val content = "line1\nline2 \"q\" \\ é"
        assertEquals("6ab3d494d891cd7e0598e730a3cde3405221efacbe8d78f737628547ddff3df5", NostrEvent.computeId(sk1.pubkey, 1700000000, 27235, tags, content))
        val js = NostrEvent("6ab3d494d891cd7e0598e730a3cde3405221efacbe8d78f737628547ddff3df5", sk1.pubkey, 1700000000, 27235, tags, content,
            "64509c6a17c82acf3e754306013c34df3b21b8d03a9c12478bd92eadc78dc7b569f44b58d6f76bb7614da2754b43ab39ddfe67788fef80ca7efee16018b28eb2")
        assertTrue(js.verify())
        assertFalse(js.copy(content = "tampered").verify())
        assertTrue(NostrEvent.sign(sk2, 1, content, tags).verify())
    }

    @Test fun nip98HeaderHashesExactBody() {
        val body = """{"expiresIn":"24h"}""".toByteArray()
        val header = Nip98.header(sk1, "https://beta.lawallet.io/api/jwt", "post", body)
        assertTrue(header.startsWith("Nostr "))
        val ev = NostrEvent.json.decodeFromString(NostrEvent.serializer(), String(Base64.getDecoder().decode(header.removePrefix("Nostr "))))
        assertEquals(27235, ev.kind)
        assertEquals("https://beta.lawallet.io/api/jwt", ev.tag("u"))
        assertEquals("POST", ev.tag("method"))
        assertEquals(sha256(body).toHex(), ev.tag("payload"))
        assertTrue(ev.verify())
        assertEquals(null, Nip98.event(sk1, "https://x/api/users/me", "GET").tag("payload"))
    }

    @Test fun passkeyPrfMatchesWebCrypto() {
        assertEquals("ce59511808c75298d7823e8c25dc23bb44a3c25993fc0387cd7c55db7b0c122d", PasskeyPrf.deriveSecretHex(ByteArray(32) { 0xab.toByte() }))
        assertEquals(PasskeyPrf.SALT_HEX, sha256("lawallet-nsec-prf-v1".toByteArray()).toHex())
    }

    private fun JsonObject.s(k: String) = this[k]!!.jsonPrimitive.content
}
