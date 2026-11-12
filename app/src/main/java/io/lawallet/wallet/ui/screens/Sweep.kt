package io.lawallet.wallet.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.app.AppContainer
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.nwc.NwcClient
import io.lawallet.wallet.core.nwc.NwcException
import io.lawallet.wallet.core.nwc.NwcUri
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.SettingsHeader
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil
import com.composables.icons.lucide.R as L

/*
 * Sweep: move everything a remote wallet holds into another, over as many invoices as it takes.
 * Many wallets refuse to pay out their last ~1% (fee reserve), so each round asks the destination for
 * an invoice of the balance minus 1%, pays it from the source, re-reads the balance, and repeats on
 * what is left. A payment that bounces off the reserve is retried a little smaller.
 */
@Stable
internal class SweepRun(val from: RemoteWallet, val to: RemoteWallet) {
    enum class Phase { Ready, Running, Done, Stopped, Failed }
    enum class Status { Active, Done, Failed }
    sealed interface Step {
        data object Connect : Step
        data object Balance : Step
        data class Invoice(val n: Int, val sats: Long) : Step
        data class Pay(val n: Int) : Step
        data object Retry : Step
    }
    class StepState(val step: Step) { var status by mutableStateOf(Status.Active) }

    val steps = mutableStateListOf<StepState>()
    var phase by mutableStateOf(Phase.Ready)
    /** Source balance before starting (shown on the confirm state). */
    var available by mutableStateOf<Long?>(null)
    var initial by mutableLongStateOf(0L)
    var moved by mutableLongStateOf(0L)
    var left by mutableLongStateOf(0L)
    var stopping by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    val finished get() = phase == Phase.Done || phase == Phase.Stopped || phase == Phase.Failed

    private suspend fun <T> step(s: Step, block: suspend () -> T): T {
        val st = StepState(s).also { steps += it }
        return try { block().also { st.status = Status.Done } } catch (e: Exception) { st.status = Status.Failed; throw e }
    }

    fun start(c: AppContainer) {
        if (phase != Phase.Ready) return
        phase = Phase.Running
        // App scope: leaving the screen mid-payment must not abandon the run.
        c.scope.launch { run(c) }
    }

    private suspend fun run(c: AppContainer) {
        val api = c.session.api.value
        var src: NwcClient? = null
        var dst: NwcClient? = null
        try {
            step(Step.Connect) {
                src = NwcClient(NwcUri.parse(api.remoteWalletConnectionString(from.id)), c.http, c.scope)
                dst = NwcClient(NwcUri.parse(api.remoteWalletConnectionString(to.id)), c.http, c.scope)
            }
            val s = src!!
            val d = dst!!
            var bal = step(Step.Balance) { s.getBalanceMsat() / 1000 }
            initial = bal; left = bal
            var n = 0
            while (!stopping && n < MAX_ROUNDS && bal >= 1) {
                // Big balances start 1% short (most wallets keep that as a fee reserve, so a full-balance try is a
                // wasted round trip); small remainders start at every last sat. Failures step down from there.
                var amt = if (bal > FULL_TRY_BELOW) bal - reserveFor(bal) else bal
                n++
                var paid = false
                var tries = 0
                var stepDown = 1L
                while (!paid && tries < MAX_TRIES && amt >= 1) {
                    val invoice = step(Step.Invoice(n, amt)) { d.makeInvoice(amt * 1000, "Sweep from ${from.name}").invoice ?: error("No invoice from ${to.name}") }
                    try {
                        step(Step.Pay(n)) { s.payInvoice(invoice) }
                        paid = true
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        // A timeout may still have paid: trust the balance, not the error.
                        val now = runCatching { s.getBalanceMsat() / 1000 }.getOrDefault(bal)
                        if (now <= bal - amt) { paid = true; break }
                        tries++
                        if (e !is NwcException && tries >= MAX_TRIES) throw e
                        if (tries >= MAX_TRIES) break
                        step(Step.Retry) {}
                        // Trial and error: shave a little more each time (1, 2, 4… sats, or 0.5%, 1%, 2%… of
                        // the amount, whichever is bigger), so the sweep settles on the most the wallet will release.
                        amt = smaller(amt, stepDown)
                        stepDown *= 2
                    }
                }
                if (!paid) { if (moved == 0L) error("Payment failed"); break }
                moved += amt
                left = (bal - amt).coerceAtLeast(0)
                val now = s.getBalanceMsat() / 1000
                left = now
                if (now >= bal) break // no progress: stop rather than loop
                bal = now
            }
            phase = if (stopping) Phase.Stopped else Phase.Done
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            error = e.message
            phase = Phase.Failed
        } finally {
            src?.close(); dst?.close()
            runCatching { c.wallet.refreshBalance() }
            runCatching { c.wallet.refreshActivity() }
        }
    }

    companion object {
        private const val MAX_ROUNDS = 8
        private const val MAX_TRIES = 6
        /** Below this the first try is the whole balance: fees and reserves are a few sats at most. */
        private const val FULL_TRY_BELOW = 1_000L
        /** 1% fee reserve, at least 1 sat. */
        fun reserveFor(bal: Long) = maxOf(1L, ceil(bal * 0.01).toLong())
        /** The next, smaller try after a bounced payment of [amt]: [stepDown] sats or stepDown × 0.5%, whichever is more. */
        fun smaller(amt: Long, stepDown: Long) = amt - maxOf(stepDown, ceil(amt * 0.005 * stepDown).toLong())
    }
}

/** The run outlives the screen (payments are in flight); the screen picks it back up by its wallets. */
internal object Sweeps { var current: SweepRun? = null }

@Composable
fun SweepScreen(fromId: String, toId: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    var run by remember { mutableStateOf(Sweeps.current?.takeIf { it.from.id == fromId && it.to.id == toId }) }
    LaunchedEffect(fromId, toId) {
        if (run != null) return@LaunchedEffect
        val api = c.session.api.value
        val cached = c.prefs.cached("remote-wallets", remoteWalletsSer).orEmpty()
        val from = cached.find { it.id == fromId } ?: runCatching { api.remoteWallet(fromId) }.getOrNull() ?: return@LaunchedEffect
        val to = cached.find { it.id == toId } ?: runCatching { api.remoteWallet(toId) }.getOrNull() ?: return@LaunchedEffect
        val r = SweepRun(from, to).also { Sweeps.current = it; run = it }
        r.available = runCatching { api.remoteWalletBalance(fromId) }.getOrNull()
    }
    // Sweeps start from Home's wallet list: going back reopens it there.
    val back = {
        if (run?.phase != SweepRun.Phase.Running) Sweeps.current = null
        c.flow.revealWallets = true
        nav.popBackStack(); Unit
    }
    BackHandler { back() }
    // Once the funds are across, let the outcome land for a beat, then return to where the sweep started.
    LaunchedEffect(run?.phase) {
        val r = run ?: return@LaunchedEffect
        if (r.phase == SweepRun.Phase.Done && r.moved > 0) { delay(1800); back() }
    }
    Column(Modifier.fillMaxSize().testTag("sweep")) {
        SettingsHeader(stringResource(R.string.sweep_title), back, stringResource(R.string.common_back))
        val r = run
        if (r == null) { Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) { Spinner() }; return@Column }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(12.dp))
            FlowHeader(r)
            Spacer(Modifier.height(28.dp))
            Figures(r)
            Spacer(Modifier.height(24.dp))
            AnimatedContent(r.finished, transitionSpec = { (fadeIn(tween(300)) + scaleIn(spring(0.6f, Spring.StiffnessMediumLow), 0.85f)).togetherWith(fadeOut(tween(150))) }, label = "outcome") { done ->
                if (done) Outcome(r) else if (r.phase == SweepRun.Phase.Ready) MutedText(stringResource(R.string.sweep_reserve_note), Modifier.padding(horizontal = 12.dp), align = TextAlign.Center, size = 13.sp)
                else Spacer(Modifier.height(0.dp))
            }
            AnimatedVisibility(r.steps.isNotEmpty(), enter = fadeIn(tween(250)) + expandVertically(spring(0.85f, Spring.StiffnessMediumLow))) { Steps(r) }
            Spacer(Modifier.height(24.dp))
        }
        Box(Modifier.fillMaxWidth().padding(16.dp)) {
            when (r.phase) {
                SweepRun.Phase.Ready -> LwButton(
                    stringResource(R.string.sweep_start), { r.start(c) }, Modifier.fillMaxWidth().testTag("sweep-start"), BtnVariant.Theme,
                    enabled = (r.available ?: 0L) > 0L, icon = L.drawable.lucide_ic_arrow_right_left,
                )
                SweepRun.Phase.Running -> LwButton(
                    stringResource(if (r.stopping) R.string.sweep_stopping else R.string.sweep_stop), { r.stopping = true },
                    Modifier.fillMaxWidth().testTag("sweep-stop"), BtnVariant.Secondary, enabled = !r.stopping,
                )
                else -> LwButton(stringResource(R.string.common_done), back, Modifier.fillMaxWidth().testTag("sweep-done"), BtnVariant.Theme)
            }
        }
    }
}

/** Source → destination, joined by a dashed track that sats travel along while the sweep runs. */
@Composable
private fun FlowHeader(r: SweepRun) {
    val brand = LocalBrand.current
    val running = r.phase == SweepRun.Phase.Running
    val done = r.phase == SweepRun.Phase.Done
    val travel by rememberInfiniteTransition(label = "flow").animateFloat(0f, 1f, infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart), label = "dots")
    val fill by animateFloatAsState(if (done) 1f else 0f, tween(600, easing = FastOutSlowInEasing), label = "fill")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        WalletBadge(r.from, dim = done)
        Canvas(Modifier.weight(1f).height(24.dp).padding(horizontal = 6.dp)) {
            val y = size.height / 2
            drawLine(Color.White.copy(alpha = 0.16f), Offset(0f, y), Offset(size.width, y), 2.dp.toPx(), StrokeCap.Round, PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())))
            if (fill > 0f) drawLine(brand.t400, Offset(0f, y), Offset(size.width * fill, y), 2.dp.toPx(), StrokeCap.Round)
            if (running) for (i in 0 until 3) {
                val t = (travel + i / 3f) % 1f
                drawCircle(brand.t400.copy(alpha = (1f - kotlin.math.abs(t - 0.5f) * 2f).coerceIn(0f, 1f)), 4.dp.toPx(), Offset(size.width * t, y))
            }
        }
        WalletBadge(r.to, dim = false, highlight = done)
    }
}

@Composable
private fun WalletBadge(w: RemoteWallet, dim: Boolean, highlight: Boolean = false) {
    val lncurl = w.provider == "lncurl"
    val accent = if (lncurl) Lw.Gold else Lw.NwcPurple
    val glow by animateFloatAsState(if (highlight) 1f else 0f, spring(0.5f, Spring.StiffnessLow), label = "badge")
    val alpha by animateFloatAsState(if (dim) 0.55f else 1f, tween(400), label = "dim")
    Column(Modifier.widthIn(max = 116.dp).graphicsLayer { this.alpha = alpha }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).graphicsLayer { val s = 1f + 0.08f * glow; scaleX = s; scaleY = s }
                .clip(RoundedCornerShape(20.dp)).background(Brush.linearGradient(listOf(accent.copy(alpha = 0.38f), accent.copy(alpha = 0.10f)))),
            contentAlignment = Alignment.Center,
        ) { LwIcon(if (lncurl) L.drawable.lucide_ic_zap else L.drawable.lucide_ic_link, size = 26.dp, tint = accent) }
        Spacer(Modifier.height(8.dp))
        Txt(w.name, size = 13.sp, weight = FontWeight.SemiBold, maxLines = 1, align = TextAlign.Center)
    }
}

/** The headline odometer (available, then transferred), the progress bar, and what is left behind. */
@Composable
private fun Figures(r: SweepRun) {
    val brand = LocalBrand.current
    val ready = r.phase == SweepRun.Phase.Ready
    val figure = if (ready) r.available else r.moved
    Txt(stringResource(if (ready) R.string.sweep_available else R.string.sweep_transferred), size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
    Spacer(Modifier.height(6.dp))
    Box(Modifier.height(56.dp), contentAlignment = Alignment.Center) {
        if (figure == null) Spinner()
        else Row(verticalAlignment = Alignment.Bottom) {
            Odometer(Currencies.formatSats(figure), Modifier.testTag("sweep-figure"), 44.sp, (-1).sp, Lw.Foreground, fromZero = false, lineHeight = 52.sp)
            Spacer(Modifier.width(6.dp))
            Txt("sats", Modifier.padding(bottom = 8.dp), size = 16.sp, color = Lw.MutedFg)
        }
    }
    Spacer(Modifier.height(18.dp))
    val target = if (r.initial > 0) (r.moved.toFloat() / r.initial).coerceIn(0f, 1f) else 0f
    val frac by animateFloatAsState(target, spring(0.9f, Spring.StiffnessLow), label = "progress")
    val shimmer by rememberInfiniteTransition(label = "shimmer").animateFloat(-0.3f, 1.3f, infiniteRepeatable(tween(1400, easing = LinearEasing)), label = "sheen")
    val running = r.phase == SweepRun.Phase.Running
    Box(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.08f)).testTag("sweep-progress")) {
        Box(
            Modifier.fillMaxHeight().fillMaxWidth(frac).clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(brand.t400.copy(alpha = 0.7f), brand.t400)))
                .drawWithContent {
                    drawContent()
                    if (running) {
                        val x = size.width * shimmer
                        drawRect(Brush.horizontalGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.35f), Color.Transparent), x - 60.dp.toPx(), x + 60.dp.toPx()))
                    }
                },
        )
    }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Txt(stringResource(R.string.sweep_left, r.from.name), Modifier.weight(1f), size = 13.sp, color = Lw.MutedFg, maxLines = 1)
        val left = if (ready) r.available else r.left
        if (left != null) Odometer(Currencies.formatSats(left), Modifier, 13.sp, 0.sp, Lw.Foreground, fromZero = false, lineHeight = 18.sp)
        else Txt("—", size = 13.sp, color = Lw.MutedFg)
        Spacer(Modifier.width(4.dp))
        Txt("sats", size = 13.sp, color = Lw.MutedFg)
    }
}

@Composable
private fun Steps(r: SweepRun) {
    Column(
        Modifier.fillMaxWidth().padding(top = 20.dp).glass(RoundedCornerShape(24.dp), GlassLevel.Thin).padding(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) { r.steps.forEach { StepRow(r, it) } }
}

@Composable
private fun StepRow(r: SweepRun, s: SweepRun.StepState) {
    val brand = LocalBrand.current
    // Each step rises in as it starts.
    val p = remember(s) { Animatable(0f) }
    LaunchedEffect(s) { p.animateTo(1f, spring(0.8f, Spring.StiffnessMediumLow)) }
    val label = when (val st = s.step) {
        SweepRun.Step.Connect -> stringResource(R.string.sweep_step_connect)
        SweepRun.Step.Balance -> stringResource(R.string.sweep_step_balance, r.from.name)
        is SweepRun.Step.Invoice -> stringResource(R.string.sweep_step_invoice, r.to.name, st.n, Currencies.formatSats(st.sats))
        is SweepRun.Step.Pay -> stringResource(R.string.sweep_step_pay, st.n)
        SweepRun.Step.Retry -> stringResource(R.string.sweep_step_retry)
    }
    Row(
        Modifier.fillMaxWidth().graphicsLayer { alpha = p.value; translationY = (1f - p.value) * 12.dp.toPx() }.padding(horizontal = 16.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) {
            AnimatedContent(
                s.status,
                transitionSpec = { scaleIn(spring(0.5f, Spring.StiffnessMedium), 0.4f).togetherWith(scaleOut(tween(120)) + fadeOut(tween(120))) },
                label = "status",
            ) { st ->
                when (st) {
                    SweepRun.Status.Active -> Spinner(16.dp, brand.t400)
                    SweepRun.Status.Done -> Box(Modifier.size(20.dp).clip(CircleShape).background(brand.t400.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        LwIcon(L.drawable.lucide_ic_check, size = 13.dp, tint = brand.t400)
                    }
                    SweepRun.Status.Failed -> Box(Modifier.size(20.dp).clip(CircleShape).background(Lw.Orange.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                        LwIcon(L.drawable.lucide_ic_x, size = 13.dp, tint = Lw.Orange)
                    }
                }
            }
        }
        Spacer(Modifier.width(12.dp))
        Txt(label, Modifier.weight(1f), size = 14.sp, color = if (s.status == SweepRun.Status.Active) Lw.Foreground else Lw.Foreground.copy(alpha = 0.72f))
    }
}

@Composable
private fun Outcome(r: SweepRun) {
    val brand = LocalBrand.current
    val ok = r.phase == SweepRun.Phase.Done && r.moved > 0
    val tint = if (ok) brand.t400 else Lw.Orange
    Column(Modifier.fillMaxWidth().testTag("sweep-outcome"), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(56.dp).clip(CircleShape).background(tint.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
            LwIcon(if (ok) L.drawable.lucide_ic_check else if (r.phase == SweepRun.Phase.Failed) L.drawable.lucide_ic_x else L.drawable.lucide_ic_info, size = 26.dp, tint = tint)
        }
        Spacer(Modifier.height(12.dp))
        Txt(
            stringResource(
                when {
                    r.phase == SweepRun.Phase.Failed -> R.string.sweep_failed
                    r.phase == SweepRun.Phase.Stopped -> R.string.sweep_stopped
                    r.moved == 0L -> R.string.sweep_nothing
                    else -> R.string.sweep_done
                },
            ),
            size = 18.sp, weight = FontWeight.SemiBold,
        )
        val sub = r.error ?: if (r.left > 0) stringResource(R.string.sweep_done_left, Currencies.formatSats(r.left)) else null
        if (sub != null) { Spacer(Modifier.height(4.dp)); MutedText(sub, align = TextAlign.Center, size = 13.sp) }
    }
}
