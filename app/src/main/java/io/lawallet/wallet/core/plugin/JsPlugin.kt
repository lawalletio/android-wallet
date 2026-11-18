package io.lawallet.wallet.core.plugin

import android.content.Context
import androidx.core.content.edit
import com.whl.quickjs.android.QuickJSLoader
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.QuickJSContext
import io.lawallet.wallet.core.diag.Perf
import io.lawallet.wallet.core.diag.Trail
import io.lawallet.wallet.core.nwc.NwcException
import io.lawallet.wallet.core.nwc.NwcNotification
import io.lawallet.wallet.core.nwc.NwcTransaction
import io.lawallet.wallet.core.store.SecretStore
import io.lawallet.wallet.core.wallet.Wallet
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Headers.Companion.toHeaders
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.Closeable
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
private fun jsonStr(s: String) = JsonPrimitive(s).toString()

/**
 * One plugin's JS: a QuickJS context on its own thread (QuickJS isn't thread-safe), built on first use. Only JSON strings
 * cross the bridge. Kotlin → JS is [call]; JS → Kotlin is `host.*` (docs/plugins.md), async ones resolved by callback id.
 */
class JsPlugin(val manifest: PluginManifest, private val code: String, private val host: PluginHost, private val scope: CoroutineScope) : Closeable {
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "plugin-${manifest.id}").apply { isDaemon = true } }
    private val js = executor.asCoroutineDispatcher()
    private var ctx: QuickJSContext? = null // js thread only
    private val seq = AtomicInteger()
    private val calls = ConcurrentHashMap<Int, CompletableDeferred<Pair<Boolean, String>>>()

    /** Host calls that wait on the user (prompt, webLogin) in flight: the call timeout pauses meanwhile. */
    private val interacting = AtomicInteger()

    /** A call outlived its timeout while the JS thread was busy: the context can't be trusted, the registry rebuilds it. */
    @Volatile var dead = false
        private set

    /** Calls `plugin[fn](...args, host)` and returns its JSON result. Throws [NwcException] with the plugin's error code. */
    suspend fun call(fn: String, args: JsonArray, timeoutMs: Long): String {
        if (dead) throw NwcException("INTERNAL", "Plugin stopped")
        val id = seq.incrementAndGet()
        val result = CompletableDeferred<Pair<Boolean, String>>()
        calls[id] = result
        try {
            // ponytail: the wrapper has no interrupt handler, so a plugin stuck in a loop wedges its own thread (only
            // its own). We stop waiting and mark it dead; an engine with JS_SetInterruptHandler would kill it outright.
            withTimeout(timeoutMs) {
                scope.async(js) {
                    val f = start().globalObject.getJSFunction("__invoke")
                    try { f.call(id, fn, args.toString()) } finally { f.release() }
                }.await()
            }
            // The clock stops while the user is answering the plugin (a code prompt, a web login): a payment that
            // waits for an emailed code mustn't time out under them.
            var waited = 0L
            var answer: Pair<Boolean, String>? = null
            while (answer == null) {
                answer = withTimeoutOrNull(TICK_MS) { result.await() }
                if (answer == null && interacting.get() == 0 && (waited + TICK_MS).also { waited = it } >= timeoutMs) {
                    throw TimedOut()
                }
            }
            val (ok, out) = answer
            if (ok) return out
            val e = runCatching { json.parseToJsonElement(out).jsonObject }.getOrNull()
            throw NwcException(e?.get("code")?.jsonPrimitive?.contentOrNull ?: "INTERNAL", e?.get("message")?.jsonPrimitive?.contentOrNull ?: "Plugin error")
        } catch (e: Exception) {
            if (e !is kotlinx.coroutines.TimeoutCancellationException && e !is TimedOut) throw e
            // Slow network is fine (the JS thread is free, awaiting it); a busy JS thread means it's stuck.
            if (!jsIdle()) dead = true
            throw NwcException("TIMEOUT", "The plugin did not answer in time")
        } finally {
            calls.remove(id)
        }
    }

    private class TimedOut : Exception()

    /** Whether the JS thread can still take work (a quick no-op round trip). */
    private suspend fun jsIdle() = runCatching { withTimeout(1_000) { withContext(js) { true } } }.getOrDefault(false)

    private fun start(): QuickJSContext = ctx ?: run { Engine.init; QuickJSContext.create() }.also { c ->
        ctx = c
        c.setMemoryLimit(MEMORY_LIMIT)
        c.setMaxStackSize(STACK_LIMIT)
        val g = c.globalObject
        // Nothing may throw out of a callback (a Java exception can't cross back into QuickJS): errors come back as
        // "!{code,message}", which the prelude rethrows in JS. No JSON value starts with "!".
        g.setProperty("__host_sync", JSCallFunction { a ->
            runCatching { host.sync(manifest, a[0] as String, a[1] as String) }.getOrElse { "!" + errorJson(it) }
        })
        g.setProperty("__host_async", JSCallFunction { a ->
            val callId = (a[0] as? Number)?.toInt() ?: return@JSCallFunction null
            val name = a[1] as? String
            val args = a[2] as? String
            val user = name in USER_CALLS
            if (user) interacting.incrementAndGet()
            scope.launch(Dispatchers.IO) {
                val r = try { runCatching { host.async(manifest, name.orEmpty(), args ?: "[]") } } finally { if (user) interacting.decrementAndGet() }
                val err = r.exceptionOrNull()?.let { errorJson(it) }
                withContext(js) {
                    val f = ctx?.globalObject?.getJSFunction("__resolve") ?: return@withContext
                    runCatching { f.call(callId, r.isSuccess, r.getOrNull() ?: err) }
                    f.release()
                }
            }
            null
        })
        g.setProperty("__done", JSCallFunction { a ->
            runCatching { calls[(a[0] as Number).toInt()]?.complete((a[1] as Boolean) to (a[2] as String)) }
            null
        })
        c.evaluate(PRELUDE, "prelude.js")
        c.setModuleLoader(object : QuickJSContext.DefaultModuleLoader() {
            override fun getModuleStringCode(moduleName: String): String? = if (moduleName == "plugin") code else null
        })
        c.evaluateModule("import p from 'plugin'; globalThis.__plugin = p;", "main.js")
    }

    override fun close() {
        dead = true
        scope.launch(js) { runCatching { ctx?.destroy() }; ctx = null; executor.shutdown() }
    }

    private object Engine { val init: Unit = QuickJSLoader.init() }

    companion object {
        private const val MEMORY_LIMIT = 32 * 1024 * 1024
        private const val TICK_MS = 250L
        private val USER_CALLS = setOf("prompt", "webLogin")
        private const val STACK_LIMIT = 512 * 1024

        /** The host API and call plumbing, set up before the plugin module loads. */
        private val PRELUDE = """
            (() => {
              let seq = 0; const pending = new Map();
              globalThis.__resolve = (id, ok, value) => {
                const p = pending.get(id); if (!p) return; pending.delete(id);
                ok ? p.res(value) : p.rej(JSON.parse(value));
              };
              const call = (name, args) => new Promise((res, rej) => {
                const id = ++seq; pending.set(id, { res, rej }); __host_async(id, name, JSON.stringify(args));
              });
              const sync = (name, args) => {
                const r = __host_sync(name, JSON.stringify(args));
                if (r == null) return null;
                if (r[0] === '!') throw JSON.parse(r.slice(1));
                return JSON.parse(r);
              };
              const str = v => v == null ? null : String(v);
              const host = Object.freeze({
                fetch: (url, init) => call('fetch', [String(url), init || {}]).then(JSON.parse),
                webLogin: (url, extract) => call('webLogin', [String(url), String(extract)]).then(JSON.parse),
                prompt: (opts) => call('prompt', [opts || {}]).then(JSON.parse),
                store: Object.freeze({ get: k => sync('store.get', [String(k)]), set: (k, v) => { sync('store.set', [String(k), str(v)]); } }),
                secrets: Object.freeze({ get: k => sync('secrets.get', [String(k)]), set: (k, v) => { sync('secrets.set', [String(k), str(v)]); } }),
                uuid: () => sync('uuid', []),
                now: () => Date.now(),
              });
              globalThis.host = host;
              const err = e => JSON.stringify({ code: (e && e.code) || 'INTERNAL', message: String((e && e.message) || e) });
              globalThis.__invoke = (id, fn, args) => {
                Promise.resolve().then(() => {
                  const p = globalThis.__plugin;
                  if (!p || typeof p[fn] !== 'function') throw { code: 'NOT_IMPLEMENTED', message: fn };
                  return p[fn](...JSON.parse(args), host);
                }).then(v => __done(id, true, JSON.stringify(v === undefined ? null : v)), e => __done(id, false, err(e)));
              };
            })();
        """.trimIndent()

        internal fun errorJson(e: Throwable): String = buildJsonObject {
            put("code", when (e) { is NwcException -> e.code; is SecurityException -> "FORBIDDEN"; is IOException -> "NETWORK"; else -> "INTERNAL" })
            put("message", e.message ?: e.javaClass.simpleName)
        }.toString()
    }
}

/**
 * What a plugin can reach (docs/plugins.md "Host API"): HTTPS to its manifest's hosts (no redirects, so it can't bounce
 * elsewhere), its own key-value store and Keystore-wrapped secrets, and a web login on its declared origin.
 */
class PluginHost(
    private val context: Context,
    http: OkHttpClient,
    private val allowHttp: Boolean,
    private val webLogin: suspend (manifest: PluginManifest, url: String, extractJs: String) -> String?,
    /** Asks the user (a code, a short text): the answer, [PromptSpec.RESEND], or null when they cancel. */
    private val prompt: suspend (manifest: PluginManifest, spec: PromptSpec) -> String? = { _, _ -> null },
) {
    private val http = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    private fun prefs(id: String) = context.getSharedPreferences(prefsName(id), Context.MODE_PRIVATE)

    /** Everything a plugin stored, gone (remove). */
    fun clear(id: String) { context.deleteSharedPreferences(prefsName(id)) }

    /** Called on the plugin's JS thread; JSON in, JSON (or null) out. Keystore work is fine off the main thread. */
    fun sync(m: PluginManifest, name: String, args: String): String? {
        val a = json.parseToJsonElement(args).jsonArray
        fun arg(i: Int) = a.getOrNull(i)?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
        val key = arg(0)
        return when (name) {
            "store.get" -> prefs(m.id).getString("v:$key", null)?.let(::jsonStr)
            "store.set" -> { prefs(m.id).edit { arg(1)?.let { putString("v:$key", it) } ?: remove("v:$key") }; null }
            "secrets.get" -> {
                if (!m.permissions.secrets) throw SecurityException("secrets not granted")
                prefs(m.id).getString("s:$key", null)?.let { runCatching { SecretStore.decrypt(it) }.getOrNull() }?.let(::jsonStr)
            }
            "secrets.set" -> {
                if (!m.permissions.secrets) throw SecurityException("secrets not granted")
                // commit, not apply: a rotated refresh token must be on disk before the plugin uses it.
                prefs(m.id).edit(commit = true) { arg(1)?.let { putString("s:$key", SecretStore.encrypt(it)) } ?: remove("s:$key") }; null
            }
            "uuid" -> jsonStr(UUID.randomUUID().toString())
            else -> null
        }
    }

    /** IO thread. The result is a JSON string; failures reject the JS promise with `{code, message}`. */
    suspend fun async(m: PluginManifest, name: String, args: String): String {
        val a = json.parseToJsonElement(args).jsonArray
        return when (name) {
            "fetch" -> fetch(m, a[0].jsonPrimitive.content, a.getOrNull(1) as? JsonObject ?: JsonObject(emptyMap()))
            "webLogin" -> {
                val url = a[0].jsonPrimitive.content
                val allowed = m.permissions.webLogin?.url?.toHttpUrlOrNull()?.host
                if (allowed == null || url.toHttpUrlOrNull()?.host != allowed) throw SecurityException("web login not granted for this origin")
                jsonStr(webLogin(m, url, a[1].jsonPrimitive.content) ?: throw NwcException("CANCELLED", "Login cancelled"))
            }
            "prompt" -> {
                val o = a.getOrNull(0) as? JsonObject ?: JsonObject(emptyMap())
                fun str(k: String) = o[k]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.take(PROMPT_TEXT_MAX)
                val spec = PromptSpec(
                    title = str("title") ?: m.name,
                    message = str("message"),
                    input = str("input") ?: "text",
                    error = str("error"),
                    resend = (o["actions"] as? JsonArray).orEmpty().any { it.jsonPrimitive.content == "resend" },
                )
                when (val answer = prompt(m, spec)) {
                    null -> throw NwcException("CANCELLED", "Cancelled")
                    PromptSpec.RESEND -> buildJsonObject { put("action", "resend") }.toString()
                    else -> buildJsonObject { put("value", answer) }.toString()
                }
            }
            else -> throw NwcException("NOT_IMPLEMENTED", name)
        }
    }

    private fun fetch(m: PluginManifest, url: String, init: JsonObject): String {
        if (!hostAllowed(url, m.permissions.hosts, allowHttp)) throw SecurityException("host not allowed")
        val method = init["method"]?.jsonPrimitive?.content?.uppercase() ?: "GET"
        val headers = (init["headers"] as? JsonObject).orEmpty().mapValues { it.value.jsonPrimitive.content }
        val body = init["body"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            ?.toRequestBody(headers.entries.firstOrNull { it.key.equals("content-type", true) }?.value?.toMediaTypeOrNull())
        val req = Request.Builder().url(url).headers(headers.toHeaders())
            .method(method, body ?: if (method in setOf("POST", "PUT", "PATCH")) ByteArray(0).toRequestBody() else null)
            .build()
        http.newCall(req).execute().use { r ->
            return buildJsonObject {
                put("status", r.code)
                put("headers", JsonObject(r.headers.names().associate { it.lowercase() to JsonPrimitive(r.header(it)) }))
                put("body", r.peekBody(MAX_BODY).string())
            }.toString()
        }
    }

    companion object {
        private const val MAX_BODY = 2L * 1024 * 1024
        private const val PROMPT_TEXT_MAX = 300
        fun prefsName(id: String) = "plugin.$id"
    }
}

/** What a plugin's `host.prompt()` asks: shown by the app as a dialog over whatever is on screen. */
data class PromptSpec(val title: String, val message: String?, val input: String, val error: String?, val resend: Boolean) {
    companion object { const val RESEND = "\u0000resend" }
}

/** A plugin as a [Wallet]: `request` goes to the plugin, notifications come from its `poll()` every `pollSec`. */
class JsWallet(private val plugin: JsPlugin) : Wallet {
    val id get() = plugin.manifest.id
    private val _connected = MutableStateFlow(true)
    override val connected: StateFlow<Boolean> = _connected
    private val _lastError = MutableStateFlow<String?>(null)
    override val lastError: StateFlow<String?> = _lastError

    override suspend fun request(method: String, params: JsonObject, timeoutMs: Long): JsonElement {
        if (method !in plugin.manifest.wallet?.methods.orEmpty()) throw NwcException("NOT_IMPLEMENTED", "$method is not supported by ${plugin.manifest.name}")
        val started = System.currentTimeMillis()
        try {
            val out = plugin.call("request", JsonArray(listOf(JsonPrimitive(method), params)), timeoutMs)
            record(method, started, "ok")
            _connected.value = true
            _lastError.value = null
            return json.parseToJsonElement(out)
        } catch (e: NwcException) {
            record(method, started, e.code)
            // A network failure flips connected, so the next success triggers the repository's catch-up.
            if (e.code == "NETWORK") { _connected.value = false; _lastError.value = e.message }
            throw e
        }
    }

    override fun notifications(): Flow<NwcNotification> = flow {
        val every = plugin.manifest.pollSec
        if (every == 0) awaitCancellation()
        while (true) {
            val out = plugin.call("poll", JsonArray(emptyList()), 30_000)
            json.decodeFromString<List<Notification>>(out).forEach { n ->
                if (n.type == "payment_received" || n.type == "payment_sent") emit(NwcNotification(n.type, n.tx))
            }
            delay(every * 1000L)
        }
    }

    private fun record(method: String, started: Long, outcome: String) {
        val ms = System.currentTimeMillis() - started
        // Plugin id + method + outcome only: never params or results (privacy rule).
        Perf.record("plugin", mapOf("plugin" to id, "method" to method, "ms" to ms, "outcome" to outcome))
        if (method != "get_balance" || outcome != "ok") Trail.add("plugin", "$id $method $outcome ${ms}ms", error = outcome != "ok")
    }

    @Serializable private data class Notification(@SerialName("notification_type") val type: String, @SerialName("notification") val tx: NwcTransaction)
}
