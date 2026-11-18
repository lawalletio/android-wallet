package io.lawallet.wallet

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.lawallet.wallet.core.nwc.NwcException
import io.lawallet.wallet.core.plugin.JsPlugin
import io.lawallet.wallet.core.plugin.JsWallet
import io.lawallet.wallet.core.plugin.PluginCandidate
import io.lawallet.wallet.core.plugin.PluginHost
import io.lawallet.wallet.core.plugin.PluginManifest
import io.lawallet.wallet.core.plugin.PluginRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The QuickJS bridge, host API and registry on a real runtime (the engine has no desktop build for JVM tests). */
@RunWith(AndroidJUnit4::class)
class JsPluginTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val server = MockWebServer().apply { start() }
    private val http = OkHttpClient()
    private var answer: String? = null
    private val host = PluginHost(context, http, allowHttp = true, webLogin = { _, _, _ -> null }, prompt = { _, _ ->
        kotlinx.coroutines.delay(800) // the user typing: longer than the call's own timeout
        answer
    })

    @After fun stop() { server.shutdown(); host.clear(ID); host.clear(NO_SECRETS) }

    private fun manifest(id: String = ID, secrets: Boolean = true) = PluginManifest(
        id, "Test", version = "1.0.0", provides = listOf("wallet"), wallet = PluginManifest.WalletSpec(listOf("get_balance", "pay_invoice")),
        background = PluginManifest.Background(pollSec = 60),
        permissions = PluginManifest.Permissions(hosts = listOf(server.hostName), secrets = secrets),
    )

    private fun plugin(m: PluginManifest = manifest()) = JsPlugin(m, CODE, host, scope)
    private fun call(p: JsPlugin, fn: String, vararg args: String, timeoutMs: Long = 5_000) =
        runBlocking { p.call(fn, JsonArray(args.map { JsonPrimitive(it) }), timeoutMs) }
    private fun code(block: () -> Unit): String = try { block(); fail("expected an error"); "" } catch (e: NwcException) { e.code }

    @Test
    fun walletCallsGoThroughNip47() = runBlocking {
        val w = JsWallet(plugin())
        assertEquals(21_000L, w.getBalanceMsat())
        assertEquals("RATE_LIMITED", code { runBlocking { w.payInvoice("lnbc1") } }) // the plugin's own error code
        assertEquals("NOT_IMPLEMENTED", code { runBlocking { w.lookupInvoice("00") } }) // not in the manifest
        val n = w.notifications().first()
        assertEquals("payment_received", n.type)
        assertEquals(1_000L, n.tx.amount)
    }

    @Test
    fun fetchReachesOnlyListedHostsAndNeverFollowsRedirects() {
        val p = plugin()
        server.enqueue(MockResponse().setBody("hi").setHeader("X-Reply", "yes"))
        val ok = Json.parseToJsonElement(call(p, "fetch", server.url("/a").toString())).jsonObject
        assertEquals(200, ok["status"]!!.jsonPrimitive.content.toInt())
        assertEquals("hi", ok["body"]!!.jsonPrimitive.content)
        assertEquals("1", server.takeRequest().getHeader("x-plugin"))
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://example.com/"))
        assertEquals("302", Json.parseToJsonElement(call(p, "fetch", server.url("/b").toString())).jsonObject["status"]!!.jsonPrimitive.content)
        assertEquals("FORBIDDEN", code { call(p, "fetch", "https://example.com/") })
    }

    @Test
    fun storageIsPerPluginAndSecretsNeedThePermission() {
        val p = plugin()
        call(p, "put", "k", "v1")
        assertEquals("\"v1\"", call(p, "get", "k"))
        assertEquals("\"v1\"", call(p, "getSecret", "k"))
        val other = plugin(manifest(NO_SECRETS, secrets = false))
        assertEquals("null", call(other, "getStore", "k")) // another plugin's store is empty
        assertEquals("FORBIDDEN", code { call(other, "put", "k", "v") })
    }

    @Test
    fun aHungPromiseTimesOutButABusyLoopKillsThePlugin() {
        val p = plugin()
        assertEquals("TIMEOUT", code { call(p, "hang", timeoutMs = 300) })
        assertFalse(p.dead) // only waiting: the JS thread is free
        assertEquals("TIMEOUT", code { call(p, "spin", timeoutMs = 300) })
        assertTrue(p.dead)
        assertEquals("INTERNAL", code { call(p, "get", "k") })
    }

    @Test
    fun aPromptPausesTheTimeoutAndCancelRejects() {
        val p = plugin()
        answer = "123456"
        assertEquals("\"123456\"", call(p, "ask", timeoutMs = 300))
        answer = null
        assertEquals("CANCELLED", code { call(p, "ask", timeoutMs = 300) })
        assertFalse(p.dead)
    }

    @Test
    fun aTamperedBundleDoesNotRun() = runBlocking {
        server.enqueue(MockResponse().setBody(CODE))
        val registry = PluginRegistry(context, http, scope, allowHttp = true)
        val installed = registry.install(PluginCandidate(manifest(), "test", server.url("/index.js").toString()))
        try {
            assertNotNull(registry.wallet(ID))
            registry.setEnabled(ID, false); registry.setEnabled(ID, true) // unloads it
            File(context.filesDir, "plugins/$ID/index.js").appendText("\n// changed")
            assertNull(registry.wallet(ID))
            assertEquals(installed.sha256.length, 64)
        } finally {
            registry.remove(ID)
        }
        assertFalse(File(context.filesDir, "plugins/$ID").exists())
    }

    @Test
    fun anotherSourceUnderTheSameIdStartsClean() = runBlocking {
        val registry = PluginRegistry(context, http, scope, allowHttp = true)
        val m = manifest()
        try {
            server.enqueue(MockResponse().setBody(CODE))
            registry.install(PluginCandidate(m, "https://github.com/a/plugins", server.url("/index.js").toString()))
            host.sync(m, "secrets.set", """["session","s3cret"]""")
            server.enqueue(MockResponse().setBody(CODE))
            registry.install(PluginCandidate(m, "https://github.com/a/plugins", server.url("/index.js").toString())) // an update
            assertEquals("\"s3cret\"", host.sync(m, "secrets.get", """["session"]"""))
            server.enqueue(MockResponse().setBody(CODE))
            registry.install(PluginCandidate(m, "https://github.com/someone-else/plugins", server.url("/index.js").toString()))
            assertNull(host.sync(m, "secrets.get", """["session"]""")) // a look-alike never reads the original's session
        } finally {
            registry.remove(ID)
        }
    }

    private companion object {
        const val ID = "io.lawallet.test-plugin"
        const val NO_SECRETS = "io.lawallet.test-nosecrets"
        val CODE = """
            export default {
              async request(method, params) {
                if (method === 'get_balance') return { balance: 21000 };
                if (method === 'pay_invoice') throw { code: 'RATE_LIMITED', message: 'slow down' };
              },
              async poll() { return [{ notification_type: 'payment_received', notification: { type: 'incoming', payment_hash: 'ab', amount: 1000, created_at: 1 } }]; },
              async fetch(url, h) { return await h.fetch(url, { headers: { 'x-plugin': '1' } }); },
              async put(k, v, h) { h.store.set(k, v); h.secrets.set(k, v); },
              async get(k, h) { return h.store.get(k); },
              async getStore(k, h) { return h.store.get(k); },
              async getSecret(k, h) { return h.secrets.get(k); },
              hang() { return new Promise(() => {}); },
              async ask(h) { const a = await h.prompt({ title: 'Code', input: 'code', actions: ['resend'] }); return a.value; },
              spin() { const t = Date.now(); while (Date.now() - t < 3000) {} },
            };
        """.trimIndent()
    }
}
