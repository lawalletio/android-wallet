package io.lawallet.wallet.app

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import io.lawallet.wallet.BuildConfig
import io.lawallet.wallet.core.diag.Scrubber
import io.lawallet.wallet.core.store.Prefs
import io.lawallet.wallet.service.PaymentListenerService
import io.sentry.Breadcrumb
import io.sentry.Sentry
import io.sentry.SentryEvent
import io.sentry.SentryLevel
import io.sentry.android.core.SentryAndroid
import io.sentry.protocol.Message
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

/**
 * Why the process died, and (opt-in) crash reports — docs/monitoring-plan.md.
 *
 * Always, on device: each launch records new ApplicationExitInfo entries (API 30+) into a short
 * log the Diagnostics screen shows and copies, and keeps a 128-byte process-state summary current
 * so the next exit record says what the process was doing. With consent and a DSN (CI release
 * builds): the same exits plus crashes/ANRs go to Sentry, through [Scrubber].
 */
object Diagnostics {
    private const val TAG = "Diagnostics"

    /** False in builds without a DSN (local, F-Droid): the consent UI is hidden there. */
    val available = BuildConfig.SENTRY_DSN.isNotEmpty()

    @Volatile private var sentryOn = false
    private lateinit var container: AppContainer

    /** New abnormal exits found at launch while reports are off: the UI offers to send them. */
    private val _prompt = MutableStateFlow<List<ExitRecord>>(emptyList())
    val prompt: StateFlow<List<ExitRecord>> = _prompt

    data class ExitRecord(val at: Long, val reason: String, val importance: Int, val pssKb: Long, val rssKb: Long, val state: String, val description: String) {
        val abnormal get() = reason in ABNORMAL
        fun line() = listOf(at, reason, importance, pssKb, rssKb, state, description).joinToString("|") { it.toString().replace('|', '/').replace('\n', ' ') }

        companion object {
            fun parse(line: String) = line.split('|').takeIf { it.size == 7 }?.let {
                ExitRecord(it[0].toLongOrNull() ?: 0, it[1], it[2].toIntOrNull() ?: 0, it[3].toLongOrNull() ?: 0, it[4].toLongOrNull() ?: 0, it[5], it[6])
            }
        }
    }

    fun start(context: Context, c: AppContainer) {
        container = c
        if (c.prefs.crashReports.value) initSentry(context)
        c.scope.launch { c.prefs.crashReports.drop(1).collect { on -> if (on) initSentry(context) else closeSentry() } }
        c.scope.launch(Dispatchers.IO) { runCatching { recordExits(context, c.prefs) }.onFailure { Log.w(TAG, "exit history", it) } }
        trackState(context, c)
    }

    fun exits(prefs: Prefs): List<ExitRecord> = prefs.exitLog.mapNotNull(ExitRecord::parse)

    /** One line under each exit on the Diagnostics screen. */
    fun summary(r: ExitRecord) = listOfNotNull(
        DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(r.at)),
        "importance ${r.importance}",
        maxOf(r.pssKb, r.rssKb).takeIf { it > 0 }?.let { "${it shr 10} MB" },
        r.state.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")

    /** What "Copy report" puts on the clipboard: already redacted, safe to paste in an issue. */
    fun report(prefs: Prefs) = buildString {
        appendLine("LaWallet ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("listener=${prefs.backgroundListener.value} running=${PaymentListenerService.running}")
        exits(prefs).asReversed().forEach { appendLine("${Date(it.at)} ${it.reason} importance=${it.importance} pss=${it.pssKb shr 10}MB rss=${it.rssKb shr 10}MB [${it.state}] ${it.description}") }
        prefs.lastAnrTrace?.let { appendLine(); appendLine("Last ANR, main thread:"); append(it) }
    }

    /** Prompt answered "Send": turn reports on and send what the prompt was about. */
    fun accept(context: Context) {
        container.prefs.setCrashReports(true)
        initSentry(context)
        _prompt.value.forEach(::send)
        _prompt.value = emptyList()
    }

    fun decline() {
        container.prefs.crashPromptDeclined = true
        _prompt.value = emptyList()
    }

    fun nonFatal(e: Throwable) {
        Log.e(TAG, "non-fatal", e)
        if (sentryOn) Sentry.captureException(e)
    }

    /** One of our own named events ("fgs.start_denied"); the name is all it carries. */
    fun event(name: String) {
        Log.w(TAG, name)
        if (sentryOn) Sentry.captureMessage(name, SentryLevel.WARNING)
    }

    fun breadcrumb(category: String, message: String) {
        if (sentryOn) Sentry.addBreadcrumb(Breadcrumb().apply { this.category = category; this.message = message })
    }

    private fun initSentry(context: Context) {
        if (sentryOn || !available) return
        SentryAndroid.init(context) { o ->
            o.dsn = BuildConfig.SENTRY_DSN
            o.environment = if (BuildConfig.DEBUG) "debug" else "production"
            o.isSendDefaultPii = false
            o.isAttachScreenshot = false // would show the balance
            o.isAttachViewHierarchy = false
            o.isEnableUserInteractionBreadcrumbs = false
            o.isEnableAutoSessionTracking = false // sessions carry an installation id; Play vitals has crash-free rates
            o.isAnrEnabled = true
            // The raw thread dump is an attachment beforeSend can't scrub (OkHttp thread names are
            // URLs); the ANR event's own threads are scrubbed and are enough.
            o.isAttachAnrThreadDump = false
            o.isTombstoneEnabled = false // secp256k1 JNI handles keys: not until a payload is reviewed
            o.tracesSampleRate = 0.0
            o.setBeforeBreadcrumb { b, _ -> Scrubber.breadcrumb(b) }
            o.setBeforeSend { e, _ -> Scrubber.event(e).also { if (!describesDeadProcess(it)) liveTags(it) } }
        }
        sentryOn = true
    }

    private fun closeSentry() {
        if (!sentryOn) return
        Sentry.close()
        sentryOn = false
    }

    /** Our exit records and the SDK's ANR/tombstone reports (read from exit history on the next launch). */
    private fun describesDeadProcess(e: SentryEvent) =
        e.message?.formatted?.startsWith("exit.") == true || e.exceptions?.any { it.mechanism?.type == "AppExitInfo" } == true

    /** What the process looks like right now — not added to events about a dead one. */
    private fun liveTags(e: SentryEvent) {
        e.setTag("fgs_running", PaymentListenerService.running.toString())
        e.setTag("relay_connected", (container.wallet.client.value?.relay?.connected?.value == true).toString())
        e.setTag("uptime_min", ((SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 60_000).toString())
        e.setTag("heap_mb", ((Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()) shr 20).toString())
        e.setTag("native_mb", (Debug.getNativeHeapAllocatedSize() shr 20).toString())
    }

    private fun send(r: ExitRecord) {
        if (!sentryOn) return
        Sentry.captureEvent(SentryEvent(Date(r.at)).apply {
            message = Message().apply { formatted = "exit.${r.reason}" }
            level = if (r.abnormal) SentryLevel.WARNING else SentryLevel.INFO
            fingerprints = listOf("exit", r.reason)
            setTag("reason", r.reason)
            setTag("importance", r.importance.toString())
            setTag("pss_mb", (r.pssKb shr 10).toString())
            setTag("rss_mb", (r.rssKb shr 10).toString())
            setTag("state", r.state)
        })
    }

    private fun recordExits(context: Context, prefs: Prefs) {
        if (Build.VERSION.SDK_INT < 30) return
        val am = context.getSystemService(ActivityManager::class.java)
        val since = prefs.lastExitTs
        val infos = am.getHistoricalProcessExitReasons(context.packageName, 0, 16)
            .filter { it.timestamp > since && it.reason != ApplicationExitInfo.REASON_EXIT_SELF }
            .sortedBy { it.timestamp }
        if (infos.isEmpty()) return
        val fresh = infos.map {
            ExitRecord(
                it.timestamp, reasonName(it.reason), it.importance, it.pss, it.rss,
                it.processStateSummary?.decodeToString().orEmpty(),
                Scrubber.redact(it.description.orEmpty()).take(160),
            )
        }
        prefs.lastExitTs = fresh.last().at
        prefs.exitLog = prefs.exitLog + fresh.map(ExitRecord::line)
        infos.lastOrNull { it.reason == ApplicationExitInfo.REASON_ANR }?.let { anr -> mainThread(anr)?.let { prefs.lastAnrTrace = it } }
        when {
            sentryOn -> fresh.forEach(::send)
            available && !prefs.crashPromptDeclined && fresh.any { it.abnormal } -> _prompt.value = fresh
        }
    }

    /** The "main" thread block of an ANR trace, redacted — the stack that was blocked. */
    @RequiresApi(30)
    private fun mainThread(info: ApplicationExitInfo): String? = runCatching {
        val trace = info.traceInputStream?.bufferedReader()?.use { it.readText() } ?: return null
        val start = trace.indexOf("\n\"main\"").takeIf { it >= 0 } ?: return null
        val end = trace.indexOf("\n\n", start + 1).takeIf { it > 0 } ?: trace.length
        Scrubber.redact(trace.substring(start + 1, end)).take(4000)
    }.getOrNull()

    /** Relay breadcrumbs, and on API 30+ setProcessStateSummary: ≤128 bytes handed back with the next exit record. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun trackState(context: Context, c: AppContainer) {
        val am = context.getSystemService(ActivityManager::class.java)
        c.scope.launch {
            c.wallet.client.flatMapLatest { it?.relay?.connected ?: flowOf(false) }.collectLatest { connected ->
                breadcrumb("relay", if (connected) "connected" else "disconnected")
                if (Build.VERSION.SDK_INT < 30) return@collectLatest
                while (true) {
                    val rt = Runtime.getRuntime()
                    val summary = "fgs=${if (PaymentListenerService.running) 1 else 0};relay=${if (connected) 1 else 0}" +
                        ";up=${(SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 60_000}m" +
                        ";heap=${(rt.totalMemory() - rt.freeMemory()) shr 20}M;native=${Debug.getNativeHeapAllocatedSize() shr 20}M"
                    runCatching { am.setProcessStateSummary(summary.toByteArray()) }
                    delay(15 * 60_000L)
                }
            }
        }
    }

    // Literal values: several constants postdate API 30 (minSdk 28 reads them only on 30+).
    private fun reasonName(r: Int) = when (r) {
        1 -> "exit_self"; 2 -> "signaled"; 3 -> "low_memory"; 4 -> "crash"; 5 -> "crash_native"; 6 -> "anr"
        7 -> "initialization_failure"; 8 -> "permission_change"; 9 -> "excessive_resource_usage"; 10 -> "user_requested"
        11 -> "user_stopped"; 12 -> "dependency_died"; 13 -> "other"; 14 -> "freezer"; 15 -> "package_state_change"
        16 -> "package_updated"; else -> "unknown_$r"
    }

    private val ABNORMAL = setOf("signaled", "low_memory", "crash", "crash_native", "anr", "initialization_failure", "excessive_resource_usage", "freezer", "other")
}
