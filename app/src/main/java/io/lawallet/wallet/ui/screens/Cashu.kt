package io.lawallet.wallet.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.layout.layout
import io.lawallet.wallet.ui.components.GroupDivider
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.theme.GlassLevel
import com.composables.icons.lucide.R as L
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.service.PaymentSound
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.components.AmountDisplay
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.keypadDisplay
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin

/**
 * Claim a Cashu ecash token: the token's mint melts it into an invoice from the user's wallet (like LUD-03, the mint
 * pays). One layout throughout: the coin idles over the details, and once the mint is paying the details slide away
 * and the same coin plays the melt, then home.
 */
@Composable
fun CashuScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val token = c.flow.cashu
    if (token == null) { LaunchedEffect(Unit) { nav.goHome() }; return }
    val client by c.wallet.client.collectAsState()
    var phase by remember { mutableStateOf("form") } // form | claiming | won | pending
    var loading by remember { mutableStateOf(false) }
    var claimed by remember { mutableLongStateOf(token.amount) }
    var error by remember { mutableStateOf<String?>(null) }
    val noWallet = stringResource(R.string.withdraw_noWallet)
    val host = remember(token) { runCatching { java.net.URI(token.mint).host }.getOrNull() ?: token.mint }
    val memo = token.memo?.takeIf { it.isNotBlank() }
    val form = phase == "form"

    fun claim() {
        if (loading) return
        error = null; loading = true
        scope.launch {
            runCatching {
                val nwc = c.wallet.ensureWallet() ?: throw IllegalStateException(noWallet)
                // The button spins while we prepare (invoice, quote); the coin takes over once the mint is paying.
                val r = c.cashuMint.claim(token, onMelting = { phase = "claiming" }) { sats ->
                    val inv = nwc.makeInvoice(sats * 1000, memo ?: "Cashu")
                    (inv.invoice ?: error("no invoice")) to inv.paymentHash
                }
                claimed = r.sats
                // PENDING melt: the mint is still routing; watch our invoice like LUD-03 does.
                r.paid || (r.paymentHash != null && nwc.awaitSettled(r.paymentHash))
            }.onSuccess { settled ->
                phase = if (settled) "won" else "pending"
                PaymentSound.play(context, "chime")
                c.scope.launch { c.wallet.refreshBalance(); c.wallet.refreshActivity() }
            }.onFailure { error = it.message; phase = "form"; loading = false }
        }
    }

    Column(Modifier.fillMaxSize()) {
        // Kept (empty) after the claim starts so the coin doesn't jump.
        ScreenHeader(if (form) stringResource(R.string.cashu_title) else null, if (form) ({ nav.popBackStack() }) else null, close = true)
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            CashuCoin(phase, Modifier.size(260.dp)) {
                scope.launch { delay(300); c.flow.cashu = null; nav.goHome() }
            }
            AnimatedContent(phase, transitionSpec = { fadeIn(tween(240, 80)) togetherWith fadeOut(tween(160)) }, label = "cashu-text") { p ->
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    if (p == "form") {
                        AmountDisplay(keypadDisplay(token.amount.toString(), "SAT"), "sats")
                        if (memo != null) MutedText("“$memo”", Modifier.padding(top = 4.dp), align = TextAlign.Center)
                    } else {
                        Txt(stringResource(if (p == "claiming") R.string.cashu_eyebrowClaiming else R.string.cashu_eyebrowClaimed), size = 15.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
                        Txt(
                            stringResource(when (p) { "claiming" -> R.string.cashu_melting; "won" -> R.string.cashu_won; else -> R.string.withdraw_celebration_onItsWay }),
                            Modifier.padding(vertical = 6.dp).testTag("cashu-phase-$p"), size = 28.sp, weight = FontWeight.SemiBold, align = TextAlign.Center,
                        )
                        MutedText(
                            when (p) {
                                "claiming" -> stringResource(R.string.cashu_meltingBody, Currencies.formatSats(token.amount))
                                "won" -> stringResource(R.string.withdraw_celebration_srLanded, Currencies.formatSats(claimed))
                                else -> stringResource(R.string.cashu_pendingBody)
                            }, align = TextAlign.Center,
                        )
                    }
                }
            }
        }
        AnimatedVisibility(form, enter = fadeIn() + expandVertically(), exit = fadeOut(tween(180)) + shrinkVertically(tween(420, easing = FastOutSlowInEasing))) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp))) {
                    DetailRow(L.drawable.lucide_ic_landmark, stringResource(R.string.cashu_mint), host, token.mint, Modifier.testTag("cashu-mint"))
                    GroupDivider()
                    DetailRow(L.drawable.lucide_ic_info, null, stringResource(R.string.cashu_fees), null)
                }
                if (client == null) NoWalletCard()
                ErrorText(error, Modifier.fillMaxWidth())
                MorphButton(stringResource(R.string.cashu_cta, Currencies.formatSats(token.amount)), loading, client != null, ::claim, Modifier.testTag("cashu-cta"))
            }
        }
    }
}

/** Icon tile + optional label over a value, with an optional muted line under it. */
@Composable
private fun DetailRow(@DrawableRes icon: Int, label: String?, value: String, sub: String?, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(32.dp).glass(RoundedCornerShape(10.dp), GlassLevel.Strong), contentAlignment = Alignment.Center) { LwIcon(icon, size = 17.dp) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (label != null) Txt(label, size = 12.sp, color = Lw.MutedFg)
            Txt(value, size = if (label != null) 16.sp else 14.sp, weight = if (label != null) FontWeight.SemiBold else FontWeight.Normal, color = if (label != null) Lw.Foreground else Lw.MutedFg, maxLines = 2)
            if (sub != null && sub != value) Txt(sub, size = 12.sp, color = Lw.MutedFg, maxLines = 1)
        }
    }
}

/** Tall pill CTA that folds into a round spinner while [loading]. The width animates in layout, not composition. */
@Composable
private fun MorphButton(text: String, loading: Boolean, enabled: Boolean, onClick: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val width = animateDpAsState(if (loading) 60.dp else maxWidth, spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow), label = "cta-width")
        LwButton(
            text, onClick,
            Modifier.layout { m, cs ->
                val w = width.value.roundToPx()
                val p = m.measure(cs.copy(minWidth = w, maxWidth = w))
                layout(p.width, p.height) { p.place(0, 0) }
            },
            BtnVariant.Theme, enabled = enabled, loading = loading, height = 60.dp, shape = CircleShape, textSize = 17.sp,
        )
    }
}

// Cashu brand (github.com/cashubtc avatar): pixel cashew in deal-with-it glasses on purple.
private val Purple = Color(0xFF7F38CA)
private val PurpleLight = Color(0xFFA36BE8)
private val PurpleDark = Color(0xFF55209A)
private val PurpleRim = Color(0xFF3F1675)
private val Cream = Color(0xFFE3D3B5)

private val NUT_COLORS = mapOf(
    'b' to Color(0xFFBA9663), 'c' to Cream, 'd' to Color(0xFFB08C5B), 'e' to Color(0xFFC6A980),
    'f' to Color(0xFFDCC099), 'g' to Color(0xFFC3A67D), 'w' to Color(0xFFF7F8F3),
)
/** The logo's 21×21 cashew (glasses drawn separately so they can drop in). */
private val NUT = listOf(
    ".....................",
    ".....................",
    ".....bbcccccc........",
    "....deeffffffc.......",
    "...dgefffffffc.......",
    "...dgefffffffc.......",
    "...dgeffffffc........",
    "...dgeffffffc........",
    "...dgefffffc.........",
    "...dgeffffcc.........",
    "...dgeffffffc........",
    "...dgeefffffc........",
    "...dgeeffffffccc.....",
    "...dgeeeefffffwwc....",
    "....dgeeeefffffffc...",
    "....dgeeeeeefffffc...",
    ".....dgeeeeeeeeeec...",
    "......dggeeeeeeeb....",
    ".......ddbbbbbbb.....",
)
/** The glasses, on a 0.405-cell grid at nut cell (3.1, 7). */
private val GLASSES = listOf(
    "##########################",
    "##w#w#######--##w#w#######",
    "-##w#w######--###w#w#####-",
    "--##w#w####----###w#w###--",
    "---#######------#######---",
)

/** One Path per colour, in a [cell]-sized grid with its origin at (0, 0). */
private fun pixelPaths(rows: List<String>, cell: Float, colors: Map<Char, Color>): List<Pair<Color, Path>> =
    colors.mapNotNull { (ch, color) ->
        val path = Path()
        rows.forEachIndexed { y, row -> row.forEachIndexed { x, c -> if (c == ch) path.addRect(Rect(x * cell, y * cell, (x + 1) * cell + 0.6f, (y + 1) * cell + 0.6f)) } }
        if (path.isEmpty) null else color to path
    }

private fun Float.seg(a: Float, b: Float) = ((this - a) / (b - a)).coerceIn(0f, 1f)

/** Overshoots past 1 and settles: the glasses landing. */
private fun Float.bounce(): Float = if (this >= 1f) 1f else 1f - (1f - this).let { it * it } * cos(this * 9f)

/**
 * Claiming: ecash pixels spiral into a spinning Cashu coin (the melt). Won: the coin settles face-up, pops, the
 * deal-with-it glasses drop onto the cashew and coins burst out; then [onDone]. Animated values are read in draw only.
 */
@Composable
private fun CashuCoin(phase: String, modifier: Modifier, onDone: () -> Unit) {
    val haptics = LocalHapticFeedback.current
    val orbit = rememberInfiniteTransition("cashu").animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), "orbit")
    val flip = remember { Animatable(0f) }
    val win = remember { Animatable(0f) }
    LaunchedEffect(phase) {
        when (phase) {
            // Idle on the claim form: a gentle wobble, so the coin reads as a coin.
            "form" -> while (true) { flip.animateTo(26f, tween(1700, easing = FastOutSlowInEasing)); flip.animateTo(-26f, tween(1700, easing = FastOutSlowInEasing)) }
            "claiming" -> while (true) flip.animateTo(flip.value + 360f, tween(1200, easing = LinearEasing))
            "pending" -> { repeat(2) { flip.animateTo(flip.value + 360f, tween(2400, easing = FastOutSlowInEasing)) }; onDone() }
            else -> {
                flip.animateTo(ceil(flip.value / 360f) * 360f + 360f, tween(1000, easing = FastOutSlowInEasing))
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                win.animateTo(1f, tween(1700, easing = LinearEasing))
                onDone()
            }
        }
    }
    val idle = phase == "form"
    Box(modifier.drawWithCache {
        val r = size.minDimension * 0.27f
        val center = Offset(size.width / 2, size.height / 2)
        val cell = r * 1.5f / 21f
        val nut = pixelPaths(NUT, cell, NUT_COLORS)
        val gCell = cell * 0.405f
        val glasses = pixelPaths(GLASSES, gCell, mapOf('#' to Color.Black, 'w' to Color.White))
        val grid = center - Offset(cell * 10.2f, cell * 10.2f) // the nut sits a touch left/up of the grid centre
        val glassesAt = grid + Offset(cell * 3.1f, cell * 7f)
        val face = Brush.radialGradient(listOf(PurpleLight, Purple, PurpleDark), center + Offset(-r * 0.35f, -r * 0.4f), r * 1.5f)
        val back = Brush.radialGradient(listOf(Purple, PurpleDark, PurpleRim), center + Offset(r * 0.3f, -r * 0.3f), r * 1.5f)
        val glow = Brush.radialGradient(listOf(PurpleLight.copy(alpha = 0.6f), Color.Transparent), center, r * 2.2f)
        val dust = listOf(Cream, NUT_COLORS.getValue('f'), PurpleLight, NUT_COLORS.getValue('e'))
        onDrawBehind {
            val t = orbit.value
            val w = win.value
            val a = Math.toRadians(flip.value.toDouble())
            val pop = sin(PI.toFloat() * w.seg(0f, 0.2f))

            // Glow: breathes while melting, flares on the win.
            val breathe = 1f + 0.08f * sin(2 * PI.toFloat() * t * 2)
            drawCircle(glow, r * 2.2f * (breathe + 0.5f * pop), center, alpha = 0.7f + 0.3f * pop)

            // The melt: ecash pixels spiral in from all around and dissolve at the rim.
            val gone = (1f - w.seg(0f, 0.1f)) * (if (idle) 0.45f else 1f)
            if (gone > 0f) for (i in 0 until 18) {
                val p = (t * 1.6f + i / 18f) % 1f
                val ang = i * 2.399963f + p * 2.4f * PI.toFloat()
                val rad = r * (2.7f - 1.75f * p)
                val pos = center + Offset(cos(ang) * rad, sin(ang) * rad * 0.62f)
                val s = 9.dp.toPx() * (1f - 0.6f * p)
                drawRect(dust[i % dust.size].copy(alpha = sin(p * PI.toFloat()) * gone), pos - Offset(s / 2, s / 2), Size(s, s))
            }

            // Shockwave.
            val e = w.seg(0.03f, 0.5f)
            if (e in 0.001f..0.999f) drawCircle(PurpleLight.copy(alpha = 0.85f * (1 - e)), r * (1.05f + 1.9f * e), center, style = Stroke(6.dp.toPx() * (1 - e) + 1f))

            // The coin: squashed horizontally by the flip; a dark disc behind it shows the edge.
            val sx = cos(a).toFloat()
            val k = abs(sx).coerceAtLeast(0.04f)
            val edge = 6.dp.toPx() * sin(a).toFloat()
            scale(1f + 0.14f * pop, center) {
                val bc = center - Offset(edge / 2, 0f)
                scale(k, 1f, bc) { drawCircle(PurpleRim, r, bc) }
                val shift = Offset(edge / 2, 0f)
                val fc = center + shift
                scale(k, 1f, fc) {
                    drawCircle(if (sx >= 0f) face else back, r, fc)
                    drawCircle(PurpleRim, r, fc, style = Stroke(3.dp.toPx()))
                    drawCircle(Color.White.copy(alpha = 0.22f), r * 0.86f, fc, style = Stroke(1.5.dp.toPx()))
                    // The back shows the cashew mirrored, so the spin never flashes a blank coin.
                    if (sx < 0f) scale(-1f, 1f, fc) { translate(grid.x + shift.x, grid.y) { nut.forEach { (c, path) -> drawPath(path, c, alpha = 0.75f) } } }
                    else {
                        translate(grid.x + shift.x, grid.y) { nut.forEach { (c, path) -> drawPath(path, c) } }
                        // Deal with it: the glasses drop from above and land on the nut.
                        val drop = w.seg(0.12f, 0.42f)
                        if (drop > 0f) {
                            val fall = (1f - drop.bounce()) * size.height * 0.55f
                            translate(glassesAt.x + shift.x, glassesAt.y - fall) { glasses.forEach { (c, path) -> drawPath(path, c) } }
                        }
                    }
                }
            }

            // Burst: coins and pixels fly out, tumble and fall.
            val b = w.seg(0.06f, 1f)
            if (b in 0.001f..0.999f) for (i in 0 until 24) {
                val ang = i * (2 * PI.toFloat() / 24) + (i % 3) * 0.21f
                val v = size.minDimension * (0.42f + 0.18f * ((i * 7) % 5) / 4f)
                val pos = center + Offset(cos(ang) * (r + v * b), sin(ang) * (r + v * b) + size.minDimension * 0.55f * b * b) // from the rim
                val alpha = 1f - b.seg(0.6f, 1f)
                if (i % 2 == 0) miniCoin(pos, 7.dp.toPx() * (1.1f - 0.4f * b), cos(b * 14f + i), alpha)
                else { val s = 7.dp.toPx(); drawRect(dust[i % dust.size].copy(alpha = alpha), pos - Offset(s / 2, s / 2), Size(s, s)) }
            }
        }
    })
}

/** A tiny tumbling Cashu coin: [tumble] squashes it like the big one. */
private fun DrawScope.miniCoin(pos: Offset, s: Float, tumble: Float, alpha: Float) {
    if (alpha <= 0f) return
    scale(abs(tumble).coerceAtLeast(0.15f), 1f, pos) {
        drawCircle(Purple.copy(alpha = alpha), s, pos)
        drawCircle(PurpleRim.copy(alpha = alpha), s, pos, style = Stroke(s * 0.25f))
        val q = s * 0.5f
        drawRect(Cream.copy(alpha = alpha), pos - Offset(q / 2, q / 2), Size(q, q))
    }
}
