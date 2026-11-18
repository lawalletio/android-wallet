package io.lawallet.wallet.core

import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.plugin.InstalledPlugin
import io.lawallet.wallet.core.plugin.PluginAccount
import io.lawallet.wallet.core.plugin.PluginCandidate
import io.lawallet.wallet.core.plugin.PluginManifest
import io.lawallet.wallet.core.plugin.PluginResolver
import io.lawallet.wallet.core.plugin.PluginSource
import io.lawallet.wallet.core.plugin.PluginSourceException
import io.lawallet.wallet.core.plugin.hostAllowed
import io.lawallet.wallet.ui.screens.withPlugins
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PluginsTest {
    private val manifest = """
        { "id": "io.example.wallet", "name": "Example", "version": "0.1.0", "api": 1, "main": "index.js", "icon": "icon.png",
          "provides": ["wallet"], "wallet": { "methods": ["get_balance"] }, "background": { "pollSec": 5 },
          "permissions": { "hosts": ["api.example.com"], "secrets": true, "webLogin": { "url": "https://login.example.com" } },
          "somethingNew": true }
    """.trimIndent()

    @Test
    fun manifestParsesAndValidates() {
        val m = PluginManifest.parse(manifest)!!
        assertTrue(m.isWallet)
        assertEquals(PluginManifest.MIN_POLL_SEC, m.pollSec) // floored
        assertNull(PluginManifest.parse(manifest.replace("\"io.example.wallet\"", "\"Example\""))) // id must be reverse-dns, lowercase
        assertNull(PluginManifest.parse(manifest.replace("\"api\": 1", "\"api\": 2")))
        assertNull(PluginManifest.parse(manifest.replace("\"index.js\"", "\"../index.js\"")))
        assertNull(PluginManifest.parse(manifest.replace("\"index.js\"", "\"https://evil.example/x.js\"")))
        assertNull(PluginManifest.parse(manifest.replace("\"api.example.com\"", "\"api.example.com/v1\"")))
        assertNull(PluginManifest.parse(manifest.replace("https://login.example.com", "http://login.example.com")))
        assertNull(PluginManifest.parse("not json"))
    }

    @Test
    fun onlyListedHostsOverHttps() {
        val hosts = listOf("api.example.com")
        assertTrue(hostAllowed("https://api.example.com/v1.0/auth", hosts))
        assertTrue(hostAllowed("https://API.example.com/x", hosts))
        assertFalse(hostAllowed("http://api.example.com/x", hosts))
        assertTrue(hostAllowed("http://api.example.com/x", hosts, allowHttp = true))
        assertFalse(hostAllowed("https://evil.example/?h=api.example.com", hosts))
        assertFalse(hostAllowed("https://api.example.com.evil.example/", hosts))
        assertFalse(hostAllowed("https://sub.api.example.com/", hosts))
        assertFalse(hostAllowed("file:///etc/hosts", hosts))
    }

    @Test
    fun parsesPastedUrls() {
        assertEquals(PluginSource.GitHub("lawalletio", "mobile-plugins"), PluginSource.parse("https://github.com/lawalletio/mobile-plugins"))
        assertEquals(PluginSource.GitHub("lawalletio", "mobile-plugins"), PluginSource.parse(" github.com/lawalletio/mobile-plugins.git "))
        assertEquals(PluginSource.GitHub("o", "r", listOf("claude", "branch", "plugins")), PluginSource.parse("https://github.com/o/r/tree/claude/branch/plugins"))
        assertEquals(PluginSource.Manifest("http://192.168.1.5:8000/example/manifest.json"), PluginSource.parse("http://192.168.1.5:8000/example/manifest.json"))
        assertNull(PluginSource.parse("https://github.com/lawalletio"))
        assertNull(PluginSource.parse("https://github.com/o/r/blob/main/README.md"))
        assertNull(PluginSource.parse("https://example.com/index.js"))
        assertNull(PluginSource.parse(""))
    }

    @Test
    fun findsManifestsInTheDirectory() {
        val paths = listOf("README.md", "plugins/example/manifest.json", "plugins/example/index.js", "plugins/cashu/manifest.json", "plugins/a/b/manifest.json", "other/x/manifest.json")
        assertEquals(listOf("plugins/example/manifest.json", "plugins/cashu/manifest.json"), PluginResolver.manifestPaths(paths, null))
        assertEquals(listOf("other/x/manifest.json"), PluginResolver.manifestPaths(paths, "other"))
        assertEquals(listOf("plugins/example/manifest.json"), PluginResolver.manifestPaths(paths, "plugins/example/"))
    }

    private val server = MockWebServer().apply { start() }
    @After fun stop() = server.shutdown()

    @Test
    fun resolvesAGitHubBranchWithSlashesPinnedToItsCommit() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val p = request.path ?: ""
                return when {
                    p == "/repos/o/r/commits/claude" -> MockResponse().setResponseCode(422)
                    p == "/repos/o/r/commits/claude/branch" -> MockResponse().setBody("""{"sha":"abc123"}""")
                    p == "/repos/o/r/git/trees/abc123?recursive=1" -> MockResponse().setBody("""{"tree":[{"path":"plugins/example/manifest.json"},{"path":"plugins/bad/manifest.json"},{"path":"plugins/example/index.js"}]}""")
                    p == "/o/r/abc123/plugins/example/manifest.json" -> MockResponse().setBody(manifest)
                    p == "/o/r/abc123/plugins/bad/manifest.json" -> MockResponse().setBody("""{"id":"nope"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        val base = server.url("/").toString().trimEnd('/')
        val r = PluginResolver(OkHttpClient(), apiBase = base, rawBase = base)
        val found = r.resolve(PluginSource.parse("https://github.com/o/r/tree/claude/branch")!!)
        assertEquals(1, found.size) // the invalid manifest is skipped
        val p = found.single()
        assertEquals("io.example.wallet", p.manifest.id)
        assertEquals("abc123", p.ref)
        assertEquals("$base/o/r/abc123/plugins/example/index.js", p.mainUrl)
        assertEquals("$base/o/r/abc123/plugins/example/icon.png", p.iconUrl)
        assertEquals("https://github.com/o/r/tree/claude/branch", p.source)
        assertThrows(PluginSourceException::class.java) { runBlocking { r.resolve(PluginSource.GitHub("o", "r", listOf("nope"))) } }
    }

    @Test
    fun manifestUrlResolvesMainNextToIt() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = if (request.path == "/example/manifest.json") MockResponse().setBody(manifest) else MockResponse().setResponseCode(404)
        }
        val url = server.url("/example/manifest.json").toString()
        val p = PluginResolver(OkHttpClient()).resolve(PluginSource.Manifest(url)).single()
        assertEquals(server.url("/example/index.js").toString(), p.mainUrl)
        assertNull(p.ref)
    }

    @Test
    fun readsTheLocalDevServer() = runBlocking<Unit> {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = when (request.path) {
                "/index.json" -> MockResponse().setBody("""{"plugins":["plugins/example/manifest.json","plugins/broken/manifest.json"]}""")
                "/plugins/example/manifest.json" -> MockResponse().setBody(manifest)
                else -> MockResponse().setResponseCode(404)
            }
        }
        val base = server.url("/").toString() // trailing slash, as -PpluginsDevUrl might have it
        val p = PluginResolver(OkHttpClient()).resolveDev(base).single() // the broken one is skipped
        assertEquals(server.url("/plugins/example/index.js").toString(), p.mainUrl)
        assertEquals(server.url("/plugins/example/manifest.json").toString(), p.source)
    }

    @Test
    fun pluginWalletsJoinTheDeck() {
        val m = PluginManifest.parse(manifest)!!
        val installed = listOf(InstalledPlugin(PluginCandidate(m, "src", "main"), "hash"))
        val server = listOf(RemoteWallet("w1", "Main", isDefault = true))
        assertEquals(server, withPlugins(server, emptyList()))
        val deck = withPlugins(server, installed)!!
        assertEquals(listOf("w1", "plugin:io.example.wallet"), deck.map { it.id })
        assertTrue(deck[0].isDefault)
        assertFalse(deck[1].isDefault) // local: listed, never the primary
        assertEquals("io.example.wallet", deck[1].provider)
        assertNotNull(withPlugins(null, installed))
        assertEquals(server, withPlugins(server, listOf(installed[0].copy(enabled = false))))
        // Signed out: its card leaves the list (unknown, e.g. no account(), keeps it)
        assertEquals(server, withPlugins(server, installed, mapOf("io.example.wallet" to PluginAccount(signedIn = false))))
        assertEquals(2, withPlugins(server, installed, mapOf("io.example.wallet" to PluginAccount(signedIn = true, label = "a@b.c")))!!.size)
    }
}
