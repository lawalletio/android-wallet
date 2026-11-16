package io.lawallet.wallet.core.diag

/**
 * One screen visit's frames, summarised (pure; fed by [Perf] from FrameMetrics). Per frame: the total time, the
 * main-thread share (input, animation, layout, draw, sync) and the GPU time (-1 before API 31), against that
 * frame's deadline (one refresh interval: 8.3 ms at 120 Hz). Janky = missed its deadline; frozen = over 700 ms.
 * Main thread vs GPU tells which side to optimise: Compose work, or what's drawn (blurs, layers, overdraw).
 */
class FrameStats {
    private var total = FloatArray(256)
    private var ui = FloatArray(256)
    private var gpu = FloatArray(256)
    var frames = 0; private set
    var janky = 0; private set
    var frozen = 0; private set

    fun add(totalMs: Float, uiMs: Float, gpuMs: Float, deadlineMs: Float) {
        if (frames == total.size) { total = total.copyOf(frames * 2); ui = ui.copyOf(frames * 2); gpu = gpu.copyOf(frames * 2) }
        total[frames] = totalMs; ui[frames] = uiMs; gpu[frames] = gpuMs
        frames++
        if (totalMs > deadlineMs) janky++
        if (totalMs > FROZEN_MS) frozen++
    }

    /** Counts and percentiles in ms (one decimal), keys prefixed with [prefix]; empty with no frames. */
    fun summary(prefix: String = ""): Map<String, Number> {
        if (frames == 0) return emptyMap()
        val t = total.copyOf(frames).apply { sort() }
        val u = ui.copyOf(frames).apply { sort() }
        val g = gpu.copyOf(frames).filter { it >= 0f }.toFloatArray().apply { sort() }
        return buildMap {
            put("${prefix}frames", frames)
            put("${prefix}janky", janky)
            put("${prefix}jank_pct", round1(janky * 100f / frames))
            put("${prefix}frozen", frozen)
            put("${prefix}p50", round1(percentile(t, 0.50)))
            put("${prefix}p90", round1(percentile(t, 0.90)))
            put("${prefix}p99", round1(percentile(t, 0.99)))
            put("${prefix}max", round1(t.last()))
            put("${prefix}ui_p90", round1(percentile(u, 0.90)))
            if (g.isNotEmpty()) put("${prefix}gpu_p90", round1(percentile(g, 0.90)))
        }
    }

    companion object {
        const val FROZEN_MS = 700f

        /** Nearest-rank percentile of an ascending array. */
        fun percentile(sorted: FloatArray, p: Double): Float =
            if (sorted.isEmpty()) 0f else sorted[(Math.ceil(p * sorted.size).toInt() - 1).coerceIn(0, sorted.lastIndex)]

        private fun round1(v: Float) = Math.round(v * 10) / 10.0
    }
}

/**
 * One JSON object per line, for the local perf log: numbers as they are, everything else as an escaped string.
 * Only what [Perf] puts in: route patterns, NWC method names, counts and timings — never user data.
 */
fun jsonLine(fields: Map<String, Any?>): String = fields.entries.filter { it.value != null }.joinToString(",", "{", "}") { (k, v) ->
    "\"${esc(k)}\":" + when (v) {
        is Int, is Long, is Short -> v.toString()
        is Number -> v.toDouble().takeIf { it.isFinite() }?.toString() ?: "null"
        is Boolean -> v.toString()
        else -> "\"${esc(v.toString())}\""
    }
}

private fun esc(s: String) = buildString {
    s.forEach { ch ->
        when {
            ch == '"' -> append("\\\""); ch == '\\' -> append("\\\\"); ch == '\n' -> append("\\n")
            ch < ' ' -> append(String.format("\\u%04x", ch.code))
            else -> append(ch)
        }
    }
}
