package io.lawallet.wallet.app

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import com.google.android.play.core.appupdate.AppUpdateInfo
import com.google.android.play.core.appupdate.AppUpdateManagerFactory
import com.google.android.play.core.appupdate.AppUpdateOptions
import com.google.android.play.core.install.model.AppUpdateType
import com.google.android.play.core.install.model.UpdateAvailability
import com.google.android.gms.tasks.Task
import io.lawallet.wallet.BuildConfig
import io.lawallet.wallet.LaWalletApp
import io.lawallet.wallet.core.diag.Scrubber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.security.MessageDigest
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * "Is there a newer LaWallet?" — on every app open (throttled) and from Settings → Updates.
 *
 * - Installed from Google Play: Play's own in-app update flow. Play policy forbids a Play app
 *   from installing APKs itself, and Play may roll out later than GitHub.
 * - Otherwise: the latest GitHub release. GitHub/Zapstore APK builds ([BuildConfig.SELF_UPDATE])
 *   download this device's APK, check it against the release's SHA256SUMS.txt and against our own
 *   package name and signing certificate, then install it with PackageInstaller. Other builds
 *   (F-Droid, local) open the release page instead.
 */
class Updater(private val context: Context, private val http: OkHttpClient) {
    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val version: String, val via: Via) : State
        data class Downloading(val version: String, val progress: Float) : State
        data object Installing : State
        data class Failed(val reason: String) : State
    }

    enum class Via { PLAY, DOWNLOAD, RELEASE_PAGE }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state

    /**
     * "Later" on the launch prompt: the version it dismissed. A newer release prompts again — the
     * payment listener keeps this process alive for days, so "until the next start" meant never.
     */
    val dismissed = MutableStateFlow<String?>(null)

    private var release: GithubRelease? = null
    private var etag: String? = null
    private var playInfo: AppUpdateInfo? = null
    private var lastCheck = 0L

    val fromPlay: Boolean by lazy { installer() == PLAY_STORE }

    /**
     * Every foreground, at most every 5 min. GitHub allows 60 anonymous calls an hour, but a
     * conditional request answered 304 doesn't count, so only a new release costs one.
     */
    suspend fun checkIfDue() {
        if (lastCheck != 0L && SystemClock.elapsedRealtime() - lastCheck < 300_000) return
        checkNow()
    }

    suspend fun checkNow() {
        if (_state.value is State.Downloading || _state.value is State.Installing) return
        _state.value = State.Checking
        lastCheck = SystemClock.elapsedRealtime()
        _state.value = runCatching { if (fromPlay) checkPlay() else checkGithub() }
            .getOrElse { State.Failed(it.message?.let(Scrubber::redact) ?: "Couldn't check for updates") }
    }

    private suspend fun checkPlay(): State {
        val info = AppUpdateManagerFactory.create(context).appUpdateInfo.await()
        playInfo = info
        val ready = info.updateAvailability() == UpdateAvailability.UPDATE_AVAILABLE && info.isUpdateTypeAllowed(AppUpdateType.IMMEDIATE)
        return if (ready) State.Available(info.availableVersionCode().toString(), Via.PLAY) else State.UpToDate
    }

    private suspend fun checkGithub(): State {
        val cached = release
        val r = withContext(Dispatchers.IO) {
            val req = Request.Builder().url(BuildConfig.UPDATE_FEED).header("accept", "application/vnd.github+json")
            if (cached != null) etag?.let { req.header("if-none-match", it) }
            http.newCall(req.build()).execute().use { res ->
                if (res.code == 304 && cached != null) return@use cached
                check(res.isSuccessful) { "GitHub answered ${res.code}" }
                etag = res.header("etag")
                json.decodeFromString(GithubRelease.serializer(), res.body.string())
            }
        }
        release = r
        val latest = r.tagName.removePrefix("v")
        if (!isNewer(latest, BuildConfig.VERSION_NAME)) return State.UpToDate
        val via = if (BuildConfig.SELF_UPDATE && pickApk(r.assets.map { it.name }, Build.SUPPORTED_ABIS.toList()) != null) Via.DOWNLOAD else Via.RELEASE_PAGE
        return State.Available(latest, via)
    }

    /** The "Update" button, whichever way this install updates. Downloads run on [scope] so leaving the screen doesn't stop them. */
    fun start(activity: Activity, scope: CoroutineScope) {
        when ((_state.value as? State.Available)?.via) {
            Via.PLAY -> startPlayUpdate(activity)
            Via.DOWNLOAD -> scope.launch { downloadAndInstall() }
            Via.RELEASE_PAGE -> openReleasePage(activity)
            null -> Unit
        }
    }

    /** Play: hands over to Play's full-screen update. */
    private fun startPlayUpdate(activity: Activity) {
        val info = playInfo ?: return
        AppUpdateManagerFactory.create(context).startUpdateFlow(info, activity, AppUpdateOptions.defaultOptions(AppUpdateType.IMMEDIATE))
    }

    private fun openReleasePage(activity: Activity) {
        activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(release?.htmlUrl ?: RELEASES_PAGE)))
    }

    /** GitHub APK builds: download, verify, install. */
    private suspend fun downloadAndInstall() {
        val r = release ?: return
        val version = r.tagName.removePrefix("v")
        val name = pickApk(r.assets.map { it.name }, Build.SUPPORTED_ABIS.toList()) ?: return
        val asset = r.assets.first { it.name == name }
        try {
            _state.value = State.Downloading(version, 0f)
            val apk = withContext(Dispatchers.IO) { download(asset, version) }
            val sums = r.assets.firstOrNull { it.name == SUMS }?.let { withContext(Dispatchers.IO) { fetchText(it.url) } }
            withContext(Dispatchers.IO) { verify(apk, name, sums) }
            _state.value = State.Installing
            withContext(Dispatchers.IO) { install(apk) }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Diagnostics.nonFatal(e)
            _state.value = State.Failed(e.message?.let(Scrubber::redact) ?: "Update failed")
        }
    }

    private fun download(asset: GithubAsset, version: String): File {
        val dir = File(context.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, asset.name)
        http.newCall(Request.Builder().url(asset.url).build()).execute().use { res ->
            check(res.isSuccessful) { "Download failed (${res.code})" }
            val total = res.body.contentLength().takeIf { it > 0 } ?: asset.size
            res.body.byteStream().use { input ->
                out.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    var shown = -1
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        done += n
                        val pct = if (total > 0) (done * 100 / total).toInt() else 0
                        if (pct != shown) { shown = pct; _state.value = State.Downloading(version, pct / 100f) }
                    }
                }
            }
        }
        return out
    }

    private fun fetchText(url: String) = http.newCall(Request.Builder().url(url).build()).execute().use { res ->
        check(res.isSuccessful) { "Checksum download failed (${res.code})" }
        res.body.string()
    }

    /** The checksum, then what Android would refuse anyway: another package, an older build, another signer. */
    private fun verify(apk: File, name: String, sums: String?) {
        if (sums != null) {
            val expected = expectedSha256(sums, name) ?: error("$name is missing from $SUMS")
            check(sha256(apk) == expected) { "The download is corrupted (checksum mismatch)" }
        }
        val pm = context.packageManager
        val flags = PackageManager.GET_SIGNING_CERTIFICATES
        val candidate = pm.getPackageArchiveInfo(apk.path, flags) ?: error("The download isn't a valid APK")
        check(candidate.packageName == context.packageName) { "The download is a different app" }
        val current = pm.getPackageInfo(context.packageName, flags)
        check(candidate.longVersionCode > current.longVersionCode) { "The download isn't newer than this version" }
        val signers = { s: android.content.pm.SigningInfo? -> s?.apkContentsSigners?.map { it.toCharsString() }?.toSet().orEmpty() }
        check(signers(candidate.signingInfo).isNotEmpty() && signers(candidate.signingInfo) == signers(current.signingInfo)) {
            "The download isn't signed by LaWallet's key"
        }
    }

    private fun install(apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val result = PendingIntent.getBroadcast(
                context, id, Intent(context, UpdateInstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            session.commit(result.intentSender)
        }
    }

    /** PackageInstaller's answer (via [UpdateInstallReceiver]). Success replaces this process. */
    internal fun onInstallStatus(status: Int, message: String?, confirm: Intent?) {
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            PackageInstaller.STATUS_SUCCESS -> _state.value = State.UpToDate
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = release?.let { State.Available(it.tagName.removePrefix("v"), Via.DOWNLOAD) } ?: State.Idle
            else -> _state.value = State.Failed(message?.let(Scrubber::redact) ?: "Install failed ($status)")
        }
    }

    private fun installer(): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else @Suppress("DEPRECATION") context.packageManager.getInstallerPackageName(context.packageName)
    }.getOrNull()

    @Serializable
    private data class GithubRelease(
        @kotlinx.serialization.SerialName("tag_name") val tagName: String,
        @kotlinx.serialization.SerialName("html_url") val htmlUrl: String? = null,
        val assets: List<GithubAsset> = emptyList(),
    )

    @Serializable
    private data class GithubAsset(
        val name: String,
        val size: Long = 0,
        @kotlinx.serialization.SerialName("browser_download_url") val url: String,
    )

    companion object {
        private const val PLAY_STORE = "com.android.vending"
        private const val RELEASES_PAGE = "https://github.com/lawalletio/android-wallet/releases/latest"
        private const val SUMS = "SHA256SUMS.txt"
        private val json = Json { ignoreUnknownKeys = true }

        /** "1.0.10" > "1.0.9"; anything after '-' (CI's "0.0.0-abc1234") is ignored. */
        fun isNewer(candidate: String, current: String): Boolean {
            fun parts(v: String) = v.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
            val a = parts(candidate)
            val b = parts(current)
            for (i in 0 until maxOf(a.size, b.size)) {
                val d = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
                if (d != 0) return d > 0
            }
            return false
        }

        /** The release's APK for the first ABI this device supports, else the universal one. */
        fun pickApk(names: List<String>, abis: List<String>): String? {
            val apks = names.filter { it.endsWith(".apk") }
            return abis.firstNotNullOfOrNull { abi -> apks.firstOrNull { it.endsWith("-$abi.apk") } }
                ?: apks.firstOrNull { it.endsWith("-universal.apk") }
        }

        /** `sha256sum` output: "<hex>  <name>" per line. */
        fun expectedSha256(sums: String, name: String): String? =
            sums.lineSequence().map { it.trim().split(Regex("\\s+"), 2) }.firstOrNull { it.size == 2 && it[1].trimStart('*') == name }?.get(0)?.lowercase()

        private fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input -> val buf = ByteArray(64 * 1024); while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
            addOnSuccessListener { cont.resume(it) }
            addOnFailureListener { cont.resumeWithException(it) }
        }
    }
}

/** PackageInstaller reports here (see [Updater.install]). */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
        (context.applicationContext as LaWalletApp).container.updater.onInstallStatus(status, intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE), confirm)
    }
}
