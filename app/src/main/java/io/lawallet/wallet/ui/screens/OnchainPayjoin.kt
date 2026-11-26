package io.lawallet.wallet.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as L
import io.lawallet.wallet.R
import io.lawallet.wallet.app.PayjoinRun
import io.lawallet.wallet.app.PayjoinRun.Step
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** The payjoin [io.lawallet.wallet.app.OnchainRepository.startPayjoin] runs, as it happens; then on to the receipt. */
@Composable
fun OnchainPayjoinScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val run by c.onchainWallets.payjoinRun.collectAsState()
    val s = c.flow.onchainSend
    val r = run
    val draft = s?.draft
    if (r == null || draft == null) { LaunchedEffect(Unit) { nav.popBackStack() }; return }
    // Nothing to go back to while it runs: the payment is on its way either way.
    BackHandler(enabled = !r.finished) {}
    PayjoinProgress(r, onBack = { nav.popBackStack() }, onReceipt = {
        c.flow.onchainSend = s.copy(draft = draft.copy(fee = draft.fee + r.extraFee), txid = r.txid, joined = r.joined)
        nav.navigate(R0.ONCHAIN_SENT) { popUpTo(R0.HOME) }
    })
}

/** The payjoin's story: what's happening now, the money moving, and every step with what it did. */
@Composable
fun PayjoinProgress(run: PayjoinRun, onBack: () -> Unit, onReceipt: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.onchain_payjoin), if (run.finished) onBack else null)
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Headline(run)
            PayjoinDiagram(run)
            PayjoinSteps(run)
        }
        Box(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
            when {
                run.error != null -> LwButton(stringResource(R.string.common_back), onBack, Modifier.fillMaxWidth(), BtnVariant.Secondary)
                run.finished -> LwButton(stringResource(R.string.onchain_pj_receipt), onReceipt, Modifier.fillMaxWidth().testTag("payjoin-receipt"), BtnVariant.Theme)
            }
        }
    }
}

private enum class Mark { DONE, ACTIVE, PENDING, FAILED, SKIPPED }

private val STEPS = listOf(Step.SIGN, Step.SEND, Step.WAIT, Step.CHECK, Step.BROADCAST)

private fun mark(run: PayjoinRun, s: Step): Mark {
    val failed = run.failedAt
    return when {
        failed == s -> Mark.FAILED
        failed != null && s > failed -> Mark.SKIPPED
        run.step == Step.DONE || s < run.step -> Mark.DONE
        s == run.step -> if (run.error != null) Mark.FAILED else Mark.ACTIVE
        else -> Mark.PENDING
    }
}

private fun sats(n: Long) = Currencies.formatSats(n)

@Composable
private fun Headline(run: PayjoinRun) {
    val key = when {
        run.error != null -> "error"
        run.step == Step.DONE -> if (run.joined == true) "joined" else "fallback"
        else -> run.step.name
    }
    AnimatedContent(key, Modifier.fillMaxWidth().padding(top = 4.dp), transitionSpec = {
        (fadeIn(tween(220)) + slideInVertically(tween(220)) { it / 3 }) togetherWith (fadeOut(tween(150)) + slideOutVertically(tween(150)) { -it / 3 })
    }, label = "headline") { k ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val title = when (k) {
                "error" -> stringResource(R.string.onchain_pj_head_error)
                "joined" -> stringResource(R.string.onchain_pj_head_done)
                "fallback" -> stringResource(R.string.onchain_pj_head_fallback)
                else -> stringResource(when (Step.valueOf(k)) {
                    Step.SIGN -> R.string.onchain_pj_head_sign
                    Step.SEND -> R.string.onchain_pj_head_send
                    Step.WAIT -> R.string.onchain_pj_head_wait
                    Step.CHECK -> R.string.onchain_pj_head_check
                    else -> R.string.onchain_pj_head_broadcast
                })
            }
            Txt(title, Modifier.testTag("payjoin-headline"), size = 24.sp, weight = FontWeight.SemiBold)
            val body = when (k) {
                "error" -> onchainError(run.error)
                "joined" -> stringResource(R.string.onchain_pj_done_body)
                "fallback" -> stringResource(R.string.onchain_pj_fallback_body, whyText(run.why))
                else -> stringResource(R.string.onchain_pj_running_body)
            }
            if (body != null) MutedText(body, size = 14.sp)
        }
    }
}

@Composable
private fun whyText(why: PayjoinRun.Why?) = stringResource(when (why) {
    PayjoinRun.Why.TIMEOUT -> R.string.onchain_pj_why_timeout
    PayjoinRun.Why.DECLINED -> R.string.onchain_pj_why_declined
    PayjoinRun.Why.OFFLINE -> R.string.onchain_pj_why_offline
    else -> R.string.onchain_pj_why_checks
})

// ---- the diagram: coins in, the transaction, money out ----------------------------------------------------------

private val INPUT_TOP = 14.dp
private val NODE_Y = 150.dp
private val NODE = 64.dp
private val OUTPUT_Y = 214.dp

/** Where a column of [n] coins ends (its label, then up to two coins): its link starts there. */
private fun coinsBottom(n: Int) = INPUT_TOP + 18.dp + 31.dp * n.coerceIn(1, 2) - 2.dp

/**
 * Your coins (left) and the receiver's (right, once they add it) flow into the transaction, which pays the receiver
 * (left) and returns your change (right). The links flow while the payjoin runs.
 */
@Composable
private fun PayjoinDiagram(run: PayjoinRun) {
    val flow = run.flow
    val running = !run.finished
    val theirs = flow?.theirs.orEmpty()
    val joinedAlpha by animateFloatAsState(if (theirs.isNotEmpty()) 1f else 0.25f, tween(600), label = "theirs")
    // One shared phase for the dashes; it only moves while the payjoin runs (read in draw, not in composition).
    val phase = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (running) phase.animateTo(phase.value + 1_000f, infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Restart))
    }
    val ours = Lw.Bitcoin
    val them = Lw.Green
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(292.dp).glass(RoundedCornerShape(24.dp), GlassLevel.Thin).testTag("payjoin-diagram")
            .drawBehind {
                val w = size.width
                val inOurs = coinsBottom(flow?.ours.orEmpty().size).toPx()
                val inTheirs = coinsBottom(theirs.size).toPx()
                val nodeTop = (NODE_Y - NODE / 2).toPx()
                val nodeBottom = (NODE_Y + NODE / 2).toPx()
                val outY = OUTPUT_Y.toPx()
                val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), -phase.value)
                fun link(from: Offset, to: Offset, color: Color) {
                    val mid = (from.y + to.y) / 2
                    val p = Path().apply { moveTo(from.x, from.y); cubicTo(from.x, mid, to.x, mid, to.x, to.y) }
                    drawPath(p, color.copy(alpha = color.alpha * 0.25f), style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
                    drawPath(p, color, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, pathEffect = dash))
                }
                link(Offset(w * 0.27f, inOurs), Offset(w / 2 - 10.dp.toPx(), nodeTop), ours)
                link(Offset(w * 0.73f, inTheirs), Offset(w / 2 + 10.dp.toPx(), nodeTop), them.copy(alpha = joinedAlpha))
                link(Offset(w / 2 - 10.dp.toPx(), nodeBottom), Offset(w * 0.27f, outY), them)
                link(Offset(w / 2 + 10.dp.toPx(), nodeBottom), Offset(w * 0.73f, outY), ours)
            },
    ) {
        val w = maxWidth
        // Inputs.
        CoinColumn(stringResource(R.string.onchain_pj_you), ours, flow?.ours.orEmpty(), Modifier.offset { at(w * 0.27f, INPUT_TOP, 120.dp) })
        Box(Modifier.offset { at(w * 0.73f, INPUT_TOP, 120.dp) }) {
            if (theirs.isNotEmpty()) CoinColumn(stringResource(R.string.onchain_pj_receiver), them, theirs, Modifier, appear = true)
            else TheirCoinPending(run.step == Step.WAIT && running)
        }
        // The transaction.
        TxNode(run, Modifier.offset { at(w * 0.5f, NODE_Y - NODE / 2, NODE) })
        // Outputs.
        if (flow != null) {
            AmountTag(stringResource(R.string.onchain_pj_toReceiver), flow.payment, them, Modifier.offset { at(w * 0.27f, OUTPUT_Y + 4.dp, 132.dp) })
            AmountTag(stringResource(R.string.onchain_pj_change), flow.change, ours, Modifier.offset { at(w * 0.73f, OUTPUT_Y + 4.dp, 132.dp) })
        }
        if (flow != null) Txt(
            stringResource(R.string.onchain_pj_feeLine, sats(flow.fee)), Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp),
            size = 11.sp, color = Lw.MutedFg,
        )
    }
}

/** An [width]-wide box centred on [centerX], [top] from the top. */
private fun androidx.compose.ui.unit.Density.at(centerX: Dp, top: Dp, width: Dp) = IntOffset((centerX - width / 2).roundToPx(), top.roundToPx())

@Composable
private fun CoinColumn(label: String, color: Color, coins: List<Long>, modifier: Modifier, appear: Boolean = false) {
    Column(modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Txt(label, size = 11.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
        val shown = coins.take(2)
        shown.forEach { v ->
            // The receiver's coin pops in when their proposal lands.
            if (appear) AnimatedVisibility(remember { MutableTransitionState(false).apply { targetState = true } }, enter = scaleIn(tween(400)) + fadeIn(tween(400))) { Coin(v, color) }
            else Coin(v, color)
        }
        if (coins.size > shown.size) Txt("+${coins.size - shown.size}", size = 11.sp, color = color)
        if (coins.isEmpty()) Coin(null, color)
    }
}

@Composable
private fun Coin(sats: Long?, color: Color) = Row(
    Modifier.glass(RoundedCornerShape(12.dp), GlassLevel.Thin, tint = color.copy(alpha = 0.16f)).border(1.dp, color.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
        .padding(horizontal = 9.dp, vertical = 5.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
) {
    LwIcon(L.drawable.lucide_ic_bitcoin, size = 12.dp, tint = color)
    Txt(sats?.let { sats(it) } ?: "…", size = 12.sp, weight = FontWeight.SemiBold, maxLines = 1)
}

/** The receiver's coin before they add it: an outline, breathing while we wait for them. */
@Composable
private fun TheirCoinPending(waiting: Boolean) {
    val pulse = remember { Animatable(0.35f) }
    LaunchedEffect(waiting) {
        if (waiting) pulse.animateTo(1f, infiniteRepeatable(tween(900), RepeatMode.Reverse)) else pulse.snapTo(0.35f)
    }
    Column(Modifier.width(120.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Txt(stringResource(R.string.onchain_pj_receiver), size = 11.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
        Row(
            Modifier.graphicsLayer { alpha = pulse.value }.border(1.dp, Lw.Green.copy(alpha = 0.6f), RoundedCornerShape(12.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            LwIcon(L.drawable.lucide_ic_users, size = 12.dp, tint = Lw.Green)
            Txt(stringResource(R.string.onchain_pj_theirCoin), size = 12.sp, color = Lw.Green, maxLines = 1)
        }
    }
}

@Composable
private fun TxNode(run: PayjoinRun, modifier: Modifier) {
    val running = !run.finished
    val ring = remember { Animatable(0f) }
    LaunchedEffect(running) {
        if (running) ring.animateTo(1f, infiniteRepeatable(tween(1_600, easing = LinearEasing), RepeatMode.Restart)) else ring.snapTo(0f)
    }
    val tint = when {
        run.error != null -> Lw.Red
        run.step == Step.DONE -> if (run.joined == true) Lw.Green else Lw.Bitcoin
        else -> Lw.Green
    }
    Box(modifier.size(NODE), contentAlignment = Alignment.Center) {
        if (running) Box(Modifier.size(NODE).graphicsLayer { val k = 1f + ring.value * 0.45f; scaleX = k; scaleY = k; alpha = 1f - ring.value }.border(2.dp, tint, CircleShape))
        Box(Modifier.size(NODE).glass(CircleShape, GlassLevel.Regular, tint = tint.copy(alpha = 0.22f)).border(1.dp, tint.copy(alpha = 0.6f), CircleShape), contentAlignment = Alignment.Center) {
            AnimatedContent(nodeIcon(run), transitionSpec = { scaleIn(tween(250)) + fadeIn(tween(250)) togetherWith fadeOut(tween(120)) }, label = "node") { icon ->
                LwIcon(icon, size = 26.dp, tint = tint)
            }
        }
    }
}

private fun nodeIcon(run: PayjoinRun) = when {
    run.error != null -> L.drawable.lucide_ic_x
    run.step == Step.DONE -> if (run.joined == true) L.drawable.lucide_ic_check else L.drawable.lucide_ic_send
    run.step == Step.SIGN -> L.drawable.lucide_ic_signature
    run.step == Step.SEND -> L.drawable.lucide_ic_lock_keyhole
    run.step == Step.WAIT -> L.drawable.lucide_ic_users
    run.step == Step.CHECK -> L.drawable.lucide_ic_shield_check
    else -> L.drawable.lucide_ic_radio_tower
}

@Composable
private fun AmountTag(label: String, sats: Long, color: Color, modifier: Modifier) {
    Column(modifier.width(132.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        AnimatedContent(sats, transitionSpec = { (slideInVertically { it } + fadeIn()) togetherWith (slideOutVertically { -it } + fadeOut()) }, label = "amount") { v ->
            Txt(stringResource(R.string.common_sats, sats(v)), size = 15.sp, weight = FontWeight.SemiBold, color = color, maxLines = 1)
        }
        Txt(label, size = 11.sp, color = Lw.MutedFg, maxLines = 1)
    }
}

// ---- the steps ----------------------------------------------------------------------------------------------------

@Composable
private fun PayjoinSteps(run: PayjoinRun) {
    Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(24.dp), GlassLevel.Thin).padding(16.dp).testTag("payjoin-steps")) {
        STEPS.forEachIndexed { i, s ->
            val m = mark(run, s)
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                Column(Modifier.width(28.dp).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    StepDot(m)
                    if (i < STEPS.lastIndex) Box(Modifier.width(2.dp).weight(1f).padding(vertical = 4.dp).drawBehind {
                        drawRect(if (m == Mark.DONE) Lw.Green.copy(alpha = 0.6f) else Lw.Border)
                    })
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f).padding(bottom = if (i < STEPS.lastIndex) 16.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Txt(stepTitle(s), size = 15.sp, weight = FontWeight.Medium, color = if (m == Mark.PENDING || m == Mark.SKIPPED) Lw.MutedFg else Lw.Foreground)
                    stepDetail(run, s, m)?.let { MutedText(it, size = 13.sp) }
                }
            }
        }
    }
}

@Composable
private fun StepDot(m: Mark) = Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
    when (m) {
        Mark.DONE -> Box(Modifier.size(24.dp).glass(CircleShape, GlassLevel.Thin, tint = Lw.Green.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_check, size = 14.dp, tint = Lw.Green) }
        Mark.ACTIVE -> Spinner(20.dp, Lw.Green)
        Mark.FAILED -> Box(Modifier.size(24.dp).glass(CircleShape, GlassLevel.Thin, tint = Lw.Red.copy(alpha = 0.22f)), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_x, size = 14.dp, tint = Lw.Red) }
        Mark.PENDING, Mark.SKIPPED -> Box(Modifier.size(10.dp).border(2.dp, Lw.Neutral700, CircleShape))
    }
}

@Composable
private fun stepTitle(s: Step) = stringResource(when (s) {
    Step.SIGN -> R.string.onchain_pj_step_sign
    Step.SEND -> R.string.onchain_pj_step_send
    Step.WAIT -> R.string.onchain_pj_step_wait
    Step.CHECK -> R.string.onchain_pj_step_check
    else -> R.string.onchain_pj_step_broadcast
})

/** What a step did, or is doing; nothing for one not reached. */
@Composable
private fun stepDetail(run: PayjoinRun, s: Step, m: Mark): String? {
    if (m == Mark.PENDING || m == Mark.SKIPPED) return null
    if (m == Mark.FAILED) return whyText(run.why)
    val flow = run.flow
    return when (s) {
        Step.SIGN -> stringResource(R.string.onchain_pj_sign_detail)
        Step.SEND -> run.relay?.let { stringResource(R.string.onchain_pj_send_relay, it) }
            ?: run.directory?.let { stringResource(R.string.onchain_pj_send_direct, it) }
            ?: stringResource(R.string.onchain_pj_send_detail)
        Step.WAIT -> if (m == Mark.ACTIVE) {
            val left by produceState(secondsLeft(run.deadline), run.deadline) {
                while (true) { value = secondsLeft(run.deadline); delay(1_000) }
            }
            stringResource(R.string.onchain_pj_wait_detail, left)
        } else flow?.theirs?.takeIf { it.isNotEmpty() }?.let { t ->
            pluralStringResource(R.plurals.onchain_pj_added, t.size, t.size, sats(t.sum()))
        }
        Step.CHECK -> if (m == Mark.ACTIVE) null else stringResource(R.string.onchain_pj_check_detail, sats(run.extraFee))
        else -> if (m == Mark.ACTIVE) null else stringResource(R.string.onchain_pj_broadcast_detail, run.txid?.let { it.take(8) + "…" + it.takeLast(8) } ?: "…")
    }
}

private fun secondsLeft(deadline: Long?) = deadline?.let { ((it - System.currentTimeMillis()) / 1000.0).roundToInt().coerceAtLeast(0) } ?: 0
