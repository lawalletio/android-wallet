package io.lawallet.wallet.core.diag

import android.os.Build
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.Window
import java.io.File
import java.util.concurrent.Executors

/**
 * Local performance log (docs/performance.md): every build, always on, on the device only. One JSON object per line
 * in `files/perf/perf.jsonl` (rotated at 1 MB to `perf.1.jsonl`), each also logged under the `LwPerf` logcat tag;
 * `scripts/perf-report.py` pulls and analyses them. Records ("t"):
 *  - `screen`: one visit of a route pattern — its frames ([FrameStats]), split into the enter transition (`enter_*`,
 *    until the navigation settles) and the rest, with the heap at the end. Also cut when the app goes to background.
 *  - `start`: `ui_ms` activity created → first frame; `cold_ms` process start → first frame, on a true cold start.
 *  - `res`: every minute in foreground — heap, native, PSS, threads (and by pool), CPU share since the last sample.
 *  - `nwc`: one NIP-47 request — method, outcome, ms. `api`: one LaWallet REST call — method, path pattern, outcome, ms.
 * Route patterns (`activity/{hash}`, never arguments) and method names only: no keys, amounts or addresses.
 */
object Perf {
    private const val TAG = "LwPerf"
    private const val MAX_BYTES = 1L shl 20

    private val io = Executors.newSingleThreadExecutor { Thread(it, "perf-log").apply { priority = Thread.MIN_PRIORITY } }
    private var file: File? = null
    private var base: Map<String, Any?> = emptyMap()

    private val lock = Any()
    private var route: String? = null
    private var visitStart = 0L
    private var entering = false
    private var enter = FrameStats()
    private var rest = FrameStats()
    private var refreshMs = 1000f / 60
    private var firstFrameSeen = false
    private var attachedAt = 0L

    fun open(dir: File, build: String, versionCode: Int) {
        dir.mkdirs()
        file = File(dir, "perf.jsonl")
        base = mapOf("build" to build, "vc" to versionCode, "pid" to Process.myPid(), "api" to Build.VERSION.SDK_INT)
    }

    /** A record: timestamped, tagged with the build and process, appended off the main thread. */
    fun record(type: String, fields: Map<String, Any?>) {
        val line = jsonLine(mapOf("t" to type, "ts" to System.currentTimeMillis()) + base + fields)
        Log.i(TAG, line)
        val f = file ?: return
        io.execute {
            runCatching {
                if (f.length() > MAX_BYTES) f.renameTo(File(f.parentFile, "perf.1.jsonl"))
                f.appendText(line + "\n")
            }
        }
    }

    // ---- frames -------------------------------------------------------------------------------------------------

    private val frames by lazy { HandlerThread("perf-frames", Process.THREAD_PRIORITY_BACKGROUND).apply { start() } }

    private val listener = Window.OnFrameMetricsAvailableListener { _, m, _ ->
        val ms = { id: Int -> m.getMetric(id) / 1_000_000f }
        val total = ms(FrameMetrics.TOTAL_DURATION)
        val ui = ms(FrameMetrics.UNKNOWN_DELAY_DURATION) + ms(FrameMetrics.INPUT_HANDLING_DURATION) + ms(FrameMetrics.ANIMATION_DURATION) +
            ms(FrameMetrics.LAYOUT_MEASURE_DURATION) + ms(FrameMetrics.DRAW_DURATION) + ms(FrameMetrics.SYNC_DURATION)
        val gpu = if (Build.VERSION.SDK_INT >= 31) ms(FrameMetrics.GPU_DURATION) else -1f
        val deadline = if (Build.VERSION.SDK_INT >= 31) ms(FrameMetrics.DEADLINE).takeIf { it > 0f } ?: refreshMs else refreshMs
        if (!firstFrameSeen) {
            firstFrameSeen = true
            // ui_ms: activity created → first frame, always. cold_ms: process start → first frame, only when the process
            // was started to show the UI (not already running for the payment listener, which would inflate it).
            val now = SystemClock.elapsedRealtime()
            val processStart = Process.getStartElapsedRealtime()
            record("start", mapOf("ui_ms" to now - attachedAt, "cold_ms" to (now - processStart).takeIf { attachedAt - processStart < 2_000 }))
        }
        synchronized(lock) { (if (entering) enter else rest).add(total, ui, gpu, deadline) }
    }

    /** Starts timing [window]'s frames (MainActivity.onCreate); [refreshHz] for frames without their own deadline. */
    fun attach(window: Window, refreshHz: Float) {
        attachedAt = SystemClock.elapsedRealtime()
        refreshMs = 1000f / refreshHz.coerceAtLeast(1f)
        runCatching { window.addOnFrameMetricsAvailableListener(listener, Handler(frames.looper)) }
    }

    fun detach(window: Window) { runCatching { window.removeOnFrameMetricsAvailableListener(listener) } }

    /** A navigation landed on [pattern]: the previous visit is recorded; this one's enter transition starts. */
    fun screen(pattern: String) = synchronized(lock) {
        flushLocked()
        route = pattern; visitStart = SystemClock.elapsedRealtime(); entering = true
    }

    /** The enter transition finished: its frame summary, for the screen's Sentry transaction. */
    fun settled(): Map<String, Number> = synchronized(lock) { entering = false; enter.summary("enter_") }

    /** App to background: the visit so far is recorded, and counting starts over when it comes back. */
    fun background() = synchronized(lock) { flushLocked(); visitStart = SystemClock.elapsedRealtime() }

    private fun flushLocked() {
        val r = route ?: return
        if (enter.frames + rest.frames > 0) record("screen", mapOf("route" to r, "ms" to SystemClock.elapsedRealtime() - visitStart, "heap_mb" to heapMb()) + enter.summary("enter_") + rest.summary())
        enter = FrameStats(); rest = FrameStats(); entering = false
    }

    // ---- requests and resources ---------------------------------------------------------------------------------

    fun nwc(method: String, ms: Long, outcome: String) = record("nwc", mapOf("method" to method, "ms" to ms, "outcome" to outcome))

    /** One LaWallet REST call: [path] already has its ids and usernames replaced by `:id` (LaWalletApi.PATH_WORDS). */
    fun api(method: String, path: String, ms: Long, outcome: String) = record("api", mapOf("method" to method, "path" to path, "ms" to ms, "outcome" to outcome))

    private var lastCpuMs = -1L
    private var lastWallMs = 0L

    /** One resource sample (call off the main thread: PSS takes a few ms). CPU = this process's share of one core. */
    fun sample() {
        val cpu = Process.getElapsedCpuTime()
        val wall = SystemClock.elapsedRealtime()
        val cpuPct = if (lastCpuMs >= 0 && wall > lastWallMs) Math.round((cpu - lastCpuMs) * 1000.0 / (wall - lastWallMs)) / 10.0 else null
        lastCpuMs = cpu; lastWallMs = wall
        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        record("res", mapOf(
            "heap_mb" to heapMb(), "native_mb" to (Debug.getNativeHeapAllocatedSize() shr 20), "pss_mb" to (mem.totalPss shr 10),
            "threads" to threads(), "pools" to pools(), "cpu_pct" to cpuPct, "route" to synchronized(lock) { route },
        ))
    }

    private fun threads() = runCatching { File("/proc/self/status").useLines { l -> l.first { it.startsWith("Threads:") }.substringAfter(':').trim().toInt() } }.getOrNull()

    /** Which pools the threads belong to, largest first ("DefaultDispatch:7,OkHttp TaskRunn:4,…"): says what a climb is. */
    private fun pools() = runCatching {
        File("/proc/self/task").listFiles().orEmpty().mapNotNull { runCatching { File(it, "comm").readText().trim() }.getOrNull() }
            .groupingBy(::threadPool).eachCount().entries.sortedByDescending { it.value }.take(10).joinToString(",") { "${it.key}:${it.value}" }
    }.getOrNull()

    /** A thread's pool: numbers dropped; names with a dot or slash (a socket reader named after its URL) are "other". */
    internal fun threadPool(name: String): String = when {
        name.startsWith("plugin-") -> "plugin"
        name.startsWith("binder:") -> "binder"
        '.' in name || '/' in name || ':' in name || ',' in name -> "other"
        else -> name.replace(Regex("\\d+"), "#").trimEnd('#', '-', '_', ' ').ifEmpty { "other" }
    }

    private fun heapMb() = Runtime.getRuntime().let { (it.totalMemory() - it.freeMemory()) shr 20 }
}
