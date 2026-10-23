package io.lawallet.wallet.core.diag

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * The app's flight recorder: one line per notable event, kept on the device across launches so a
 * crash or kill can be read together with what led to it (Settings → Diagnostics → Copy report,
 * and the previous run's tail on the next exit report). Every line is redacted before it is
 * written, but callers still pass shapes only — routes, method names, codes, durations — never
 * payloads. [sink] mirrors lines to logcat and, with consent, Sentry breadcrumbs.
 */
object Trail {
    const val CURRENT = "trail.log"
    const val PREVIOUS = "trail.prev.log"
    private const val MAX_BYTES = 256 * 1024
    private val stamp = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "trail").apply { isDaemon = true } }
    private val lock = Any()

    @Volatile private var dir: File? = null
    @Volatile var sink: ((category: String, message: String, error: Boolean) -> Unit)? = null

    /** Starts this process's trail; the last process's becomes [PREVIOUS]. [header] is the first line. */
    fun open(dir: File, header: String) {
        dir.mkdirs()
        val cur = File(dir, CURRENT)
        if (cur.exists()) cur.renameTo(File(dir, PREVIOUS))
        this.dir = dir
        add("app", header)
    }

    fun add(category: String, message: String, error: Boolean = false) {
        val msg = Scrubber.redact(message)
        sink?.invoke(category, msg, error)
        val f = dir?.let { File(it, CURRENT) } ?: return
        val line = "${LocalDateTime.now().format(stamp)} ${if (error) "E " else ""}$category: $msg\n"
        writer.execute { append(f, line) }
    }

    /** The process is about to die: written synchronously. Class names and frames only — messages can carry payloads. */
    fun crash(thread: Thread, t: Throwable) {
        val f = dir?.let { File(it, CURRENT) } ?: return
        val text = buildString {
            append("${LocalDateTime.now().format(stamp)} E crash: ${t.javaClass.name} on ${Scrubber.redact(thread.name)}\n")
            var cause: Throwable? = t
            var depth = 0
            while (cause != null && depth < 4) {
                if (depth > 0) append("  caused by ${cause.javaClass.name}\n")
                cause.stackTrace.take(if (depth == 0) 15 else 6).forEach { append("    at $it\n") }
                cause = cause.cause?.takeIf { it !== cause }
                depth++
            }
        }
        append(f, text)
    }

    /** Waits for queued lines to reach the file (tests). */
    internal fun flush() { writer.submit {}.get() }

    /** Last [n] lines of this run's trail, or the previous run's. */
    fun tail(previous: Boolean, n: Int): List<String> {
        val f = dir?.let { File(it, if (previous) PREVIOUS else CURRENT) } ?: return emptyList()
        return synchronized(lock) { runCatching { f.readLines().takeLast(n) }.getOrDefault(emptyList()) }
    }

    /** First line of the previous run — `app: start pid=… …` — to match it to an exit record. */
    fun previousHeader(): String? = dir?.let { File(it, PREVIOUS) }?.takeIf { it.exists() }
        ?.let { runCatching { it.useLines { lines -> lines.firstOrNull() } }.getOrNull() }

    private fun append(f: File, text: String) = synchronized(lock) {
        runCatching {
            f.appendText(text)
            // ponytail: halve on overflow; a long-lived process keeps its newest ~128 KB.
            if (f.length() > MAX_BYTES) f.writeText(f.readText().let { it.substring(it.indexOf('\n', it.length / 2) + 1) })
        }
    }
}
