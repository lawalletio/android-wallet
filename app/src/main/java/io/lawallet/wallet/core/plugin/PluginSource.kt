package io.lawallet.wallet.core.plugin

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Where Add Plugin looks: a GitHub repo's plugin directory, or one manifest URL. */
sealed interface PluginSource {
    /** `github.com/<owner>/<repo>[/tree/<ref>[/<dir>]]`. [rest] is everything after `/tree/`: refs may contain slashes. */
    data class GitHub(val owner: String, val repo: String, val rest: List<String> = emptyList()) : PluginSource
    data class Manifest(val url: String) : PluginSource

    companion object {
        /** The pasted text, or null when it's neither. A bare `github.com/...` without a scheme is accepted. */
        fun parse(input: String): PluginSource? {
            val text = input.trim().let { if ("://" in it) it else "https://$it" }
            val url = text.toHttpUrlOrNull() ?: return null
            val segs = url.pathSegments.filter { it.isNotEmpty() }
            if (url.host.lowercase().removePrefix("www.") == "github.com") {
                if (segs.size < 2) return null
                val repo = segs[1].removeSuffix(".git")
                return when {
                    segs.size == 2 -> GitHub(segs[0], repo)
                    segs[2] == "tree" && segs.size > 3 -> GitHub(segs[0], repo, segs.drop(3))
                    else -> null
                }
            }
            return if (segs.lastOrNull() == "manifest.json") Manifest(url.toString()) else null
        }
    }
}

/** A plugin found at a source, ready to install. URLs are pinned (a commit sha for GitHub). */
@Serializable
data class PluginCandidate(
    val manifest: PluginManifest,
    val source: String,
    val mainUrl: String,
    val iconUrl: String? = null,
    val ref: String? = null,
)

class PluginSourceException(message: String) : IOException(message)

/**
 * Resolves a [PluginSource] to the plugins it offers. GitHub: two API calls (the ref's commit, then its tree), then one
 * raw fetch per manifest under `plugins/<name>/manifest.json` (or under the `<dir>` the URL points at), all pinned to that
 * commit. Unauthenticated GitHub API calls are limited to 60/h per IP, which browsing never gets near.
 */
class PluginResolver(
    private val http: OkHttpClient,
    private val apiBase: String = "https://api.github.com",
    private val rawBase: String = "https://raw.githubusercontent.com",
) {
    private val json = io.lawallet.wallet.core.relaxedJson

    suspend fun resolve(source: PluginSource): List<PluginCandidate> = when (source) {
        is PluginSource.Manifest -> listOf(manifestAt(source.url, source.url, null))
        is PluginSource.GitHub -> github(source)
    }

    /**
     * A local dev server's plugins (scripts/plugins-dev.py): `<base>/index.json` lists manifest paths, each read like a
     * manifest URL. Debug builds only; plain HTTP.
     */
    suspend fun resolveDev(base: String): List<PluginCandidate> {
        val root = base.trimEnd('/')
        val paths = json.parseToJsonElement(get("$root/index.json")).jsonObject["plugins"]?.jsonArray.orEmpty().map { it.jsonPrimitive.content }
        return coroutineScope { paths.map { async { runCatching { manifestAt("$root/$it", "$root/$it", null) }.getOrNull() } }.awaitAll().filterNotNull() }
    }

    private suspend fun github(s: PluginSource.GitHub): List<PluginCandidate> {
        val (sha, dir) = commit(s)
        val tree = get("$apiBase/repos/${s.owner}/${s.repo}/git/trees/$sha?recursive=1")
        val paths = json.parseToJsonElement(tree).jsonObject["tree"]?.jsonArray.orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("path")?.jsonPrimitive?.content }
        val manifests = manifestPaths(paths, dir)
        if (manifests.isEmpty()) throw PluginSourceException("No plugins in this repository")
        val sourceUrl = "https://github.com/${s.owner}/${s.repo}" + (if (s.rest.isEmpty()) "" else "/tree/" + s.rest.joinToString("/"))
        return coroutineScope {
            manifests.map { path ->
                async { runCatching { manifestAt("$rawBase/${s.owner}/${s.repo}/$sha/$path", sourceUrl, sha) }.getOrNull() }
            }.awaitAll().filterNotNull()
        }.ifEmpty { throw PluginSourceException("No valid plugins in this repository") }
    }

    /**
     * The commit sha and the plugin dir. A ref can contain slashes (`claude/my-branch`), so after `/tree/` the
     * shortest prefix GitHub knows as a ref wins and the rest is the dir.
     */
    private suspend fun commit(s: PluginSource.GitHub): Pair<String, String?> {
        val base = "$apiBase/repos/${s.owner}/${s.repo}/commits/"
        if (s.rest.isEmpty()) return sha(get(base + "HEAD")) to null
        for (k in 1..s.rest.size) {
            val ref = s.rest.take(k).joinToString("/")
            val body = getOrNull(base + ref) ?: continue
            return sha(body) to s.rest.drop(k).joinToString("/").ifEmpty { null }
        }
        throw PluginSourceException("Branch or commit not found")
    }

    private fun sha(body: String) = json.parseToJsonElement(body).jsonObject["sha"]?.jsonPrimitive?.content
        ?: throw PluginSourceException("Unexpected GitHub response")

    private suspend fun manifestAt(url: String, source: String, ref: String?): PluginCandidate {
        val m = PluginManifest.parse(get(url)) ?: throw PluginSourceException("Not a valid plugin manifest")
        val base = url.toHttpUrlOrNull() ?: throw PluginSourceException("Bad URL")
        val main = base.resolve(m.main)?.toString() ?: throw PluginSourceException("Bad main path")
        return PluginCandidate(m, source, main, m.icon?.let { base.resolve(it)?.toString() }, ref)
    }

    private suspend fun get(url: String): String = getOrNull(url) ?: throw PluginSourceException("Not found")

    private suspend fun getOrNull(url: String): String? = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).header("Accept", "application/vnd.github+json").build()
        http.newCall(req).execute().use { r ->
            when {
                r.isSuccessful -> r.body.string()
                r.code == 404 || r.code == 422 -> null
                r.code == 403 || r.code == 429 -> throw PluginSourceException("GitHub rate limit reached, try again later")
                else -> throw PluginSourceException("HTTP ${r.code}")
            }
        }
    }

    companion object {
        /** `<dir or plugins>/<name>/manifest.json`, or `<dir>/manifest.json` when the URL points at one plugin. */
        fun manifestPaths(paths: List<String>, dir: String?): List<String> {
            val base = dir?.trim('/') ?: "plugins"
            return paths.filter { p ->
                p == "$base/manifest.json" ||
                    (p.startsWith("$base/") && p.endsWith("/manifest.json") && p.removePrefix("$base/").count { it == '/' } == 1)
            }
        }
    }
}
