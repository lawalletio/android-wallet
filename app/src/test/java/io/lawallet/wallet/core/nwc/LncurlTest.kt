package io.lawallet.wallet.core.nwc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LncurlTest {
    private val uri = "nostr+walletconnect://abc123?relay=wss://relay.example.com&secret=00ff"

    @Test fun parsesBareJsonAndProse() {
        assertEquals(uri, Lncurl.parseNwcUri(uri))
        assertEquals(uri, Lncurl.parseNwcUri("""{"wallet":{"nwc":"$uri"}}"""))
        assertEquals(uri, Lncurl.parseNwcUri("Your wallet: $uri enjoy"))
        assertNull(Lncurl.parseNwcUri("""{"error":"rate limited"}"""))
    }

    @Test fun normalizesProviders() {
        assertEquals("https://lncurl.example.com", Lncurl.normalize(" lncurl.example.com/ "))
        assertEquals("https://lncurl.example.com", Lncurl.normalize("https://lncurl.example.com"))
        assertNull(Lncurl.normalize("http://lncurl.example.com"))
        assertNull(Lncurl.normalize("https://lncurl.example.com/api"))
        assertNull(Lncurl.normalize("localhost"))
    }
}
