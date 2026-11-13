package io.lawallet.wallet.ui.screens

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.border
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.semantics
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.Inter
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.drawscope.rotate
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.flow.first
import kotlin.math.sin
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.R
import io.lawallet.wallet.core.api.ApiException
import io.lawallet.wallet.core.api.RegistrationInvoice
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.autoFocus
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.QrDisplay
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

private val USERNAME = Regex("^[a-z0-9]+$")

/** claim-address-screen.tsx + use-new-address-flow.ts: username → (402 → pay → verify → claim) → success. */
@Composable
fun ClaimUsernameScreen(from: String?, bonus: Boolean) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val paymentGate = rememberPaymentGate()
    val settings by c.session.settings.collectAsState()
    var username by rememberSaveable { mutableStateOf("") }
    var available by remember { mutableStateOf<Boolean?>(null) }
    var checking by remember { mutableStateOf(false) }
    var step by remember { mutableStateOf("username") } // username | payment | success
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var invoice by remember { mutableStateOf<RegistrationInvoice?>(null) }
    var claimed by remember { mutableStateOf<String?>(null) }
    val domain = settings?.domain ?: stringResource(R.string.claim_yourDomain)
    val fromActivate = from == "activate"
    // From Lightning addresses (the web's /admin register flow): success goes back to configure the
    // new address, or to Home after a beat when it's the user's first.
    val fromAddresses = from == "addresses"
    var priorAddresses by remember { mutableStateOf<Int?>(null) } // snapshot before claiming: is this the first?
    LaunchedEffect(Unit) { if (fromAddresses) priorAddresses = runCatching { c.session.api.value.addresses().size }.getOrNull() }
    val charsetErr = stringResource(R.string.claim_errors_charset)
    val maxErr = stringResource(R.string.claim_errors_maxLength)
    val createFailed = stringResource(R.string.claim_errors_createFailed)
    val noPayment = stringResource(R.string.claim_errors_noPaymentYet)
    val validation = when { username.isEmpty() -> null; username.length > 16 -> maxErr; !USERNAME.matches(username) -> charsetErr; else -> null }

    LaunchedEffect(username) {
        available = null
        if (username.isEmpty() || validation != null) return@LaunchedEffect
        delay(300)
        checking = true
        available = runCatching { c.session.api.value.checkUsername(username).available }.getOrNull()
        checking = false
    }

    suspend fun finish(address: String?) {
        if (fromAddresses && settings?.lncurlEnabled == true) provisionLncurl(c, username)
        claimed = address ?: "$username@$domain"
        step = "success"
        runCatching { c.session.refreshMe() }
    }

    suspend fun claimPaid(inv: RegistrationInvoice, preimage: String) {
        runCatching { c.session.api.value.claimInvoice(inv.id, preimage) }
            .onSuccess { finish(it.lightningAddress) }
            .onFailure { if (it.message?.contains("already been claimed", true) == true) finish(null) else error = it.message }
    }

    // Poll LUD-21 verify while the payment step is up.
    LaunchedEffect(invoice) {
        val inv = invoice ?: return@LaunchedEffect
        val verify = inv.verify ?: return@LaunchedEffect
        c.lnurl.pollVerify(verify, timeoutMs = 600_000)?.let { claimPaid(inv, it) }
    }

    val celebrate = rememberCelebration(step == "success")
    // First address: flash the success, then the dashboard (as /admin/addresses/register does).
    LaunchedEffect(step) { if (fromAddresses && step == "success" && priorAddresses == 0) { delay(2000); nav.goHome() } }
    androidx.activity.compose.BackHandler(step == "payment") { step = "username"; invoice = null }
    Column(Modifier.fillMaxSize().imePadding()) {
        ScreenHeader(if (step == "success") null else stringResource(if (step == "payment") R.string.claim_paymentTitle else R.string.claim_headerTitle), if ((fromActivate && step == "username") || step == "success") null else ({ if (step == "payment") { step = "username"; invoice = null } else nav.popBackStack() }), close = step != "payment")
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (step) {
                "username" -> {
                    Txt(stringResource(R.string.claim_heading), size = 24.sp, weight = FontWeight.SemiBold)
                    MutedText(stringResource(when { fromActivate && bonus -> R.string.claim_introActivateFree; fromActivate -> R.string.claim_introActivate; else -> R.string.claim_intro }))
                    if (bonus && c.prefs.bonusSats > 0) MutedText(stringResource(R.string.claim_cardBonus, Currencies.formatSats(c.prefs.bonusSats)))
                    Txt(stringResource(R.string.claim_username), size = 14.sp, weight = FontWeight.Medium)
                    OutlinedTextField(
                        username, { username = it.lowercase().trim(); error = null }, Modifier.fillMaxWidth().autoFocus().testTag("username-input"), enabled = !loading,
                        singleLine = true, shape = RoundedCornerShape(12.dp), colors = lwFieldColors(),
                        suffix = { Txt("@$domain", color = Lw.MutedFg) },
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, keyboardType = KeyboardType.Ascii, autoCorrectEnabled = false),
                    )
                    when {
                        validation != null -> ErrorText(validation)
                        checking -> MutedText(stringResource(R.string.claim_checkingAvailability), size = 12.sp)
                        available == false -> ErrorText(stringResource(R.string.claim_taken))
                        available == true -> Txt(stringResource(R.string.claim_available), size = 12.sp, color = Lw.Green)
                        else -> MutedText(stringResource(R.string.claim_usernameHint), size = 12.sp)
                    }
                    ErrorText(error, Modifier.fillMaxWidth())
                }
                "payment" -> {
                    val inv = invoice
                    if (inv == null) Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Spinner() }
                    else {
                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            MutedText(stringResource(R.string.claim_toClaim) + " $username@$domain")
                            Txt(stringResource(R.string.claim_payAmount, Currencies.formatSats(inv.amountSats)), size = 24.sp, weight = FontWeight.SemiBold)
                            QrDisplay("lightning:${inv.bolt11}".uppercase(), null, copyValue = inv.bolt11)
                            Row(verticalAlignment = Alignment.CenterVertically) { Spinner(14.dp, Lw.MutedFg); Spacer(Modifier.width(8.dp)); MutedText(stringResource(R.string.claim_waitingPayment)) }
                            ErrorText(error, Modifier.fillMaxWidth())
                            LwButton(stringResource(R.string.claim_payWithWallet), {
                                scope.launch {
                                    if (!paymentGate(inv.amountSats)) return@launch
                                    loading = true
                                    runCatching { c.wallet.freshClient()?.payInvoice(inv.bolt11) ?: error("no wallet") }
                                        .onSuccess { if (it.preimage.isNotEmpty()) claimPaid(inv, it.preimage) }
                                        .onFailure { error = it.message }
                                    loading = false
                                }
                            }, Modifier.fillMaxWidth(), BtnVariant.Theme, loading = loading, enabled = c.wallet.hasWallet)
                            LwButton(stringResource(R.string.claim_checkNow), {
                                scope.launch {
                                    val (settled, pre) = runCatching { c.lnurl.verifyOnce(inv.verify ?: "") }.getOrDefault(false to null)
                                    if (settled && pre != null) claimPaid(inv, pre) else error = noPayment
                                }
                            }, Modifier.fillMaxWidth(), BtnVariant.Secondary)
                        }
                    }
                }
                else -> ClaimedHero(claimed ?: "$username@$domain", celebrate)
            }
        }
        when (step) {
            "username" -> LwButton(
                stringResource(if (loading) R.string.claim_claiming else R.string.claim_claimAddress),
                {
                    loading = true; error = null
                    scope.launch {
                        try {
                            val addr = c.session.api.value.createAddress(username)
                            finish("${addr.username}@$domain")
                        } catch (e: ApiException) {
                            if (e.status == 402) {
                                step = "payment"
                                runCatching { c.session.api.value.createAddressInvoice(username) }
                                    .onSuccess { inv -> if (inv.free) runCatching { finish("${c.session.api.value.createAddress(username).username}@$domain") }.onFailure { error = it.message; step = "username" } else invoice = inv }
                                    .onFailure { error = it.message; step = "username" }
                            } else error = e.message ?: createFailed
                        } catch (e: Exception) {
                            error = e.message ?: createFailed
                        }
                        loading = false
                    }
                },
                Modifier.fillMaxWidth().padding(16.dp).testTag("claim-submit"), BtnVariant.Theme, enabled = username.isNotEmpty() && validation == null && available != false, loading = loading,
            )
            "success" -> LwButton(
                stringResource(if (fromAddresses) R.string.addresses_configure else R.string.claim_goToWallet),
                { if (fromAddresses) { if (!nav.popBackStack(R0.ADDRESSES, inclusive = false)) nav.goHome() } else nav.goHome() },
                Modifier.fillMaxWidth().padding(16.dp).graphicsLayer {
                    val p = celebrate.phase(0.70f, 0.92f)
                    alpha = p; translationY = (1f - p) * 16.dp.toPx()
                }.testTag("claim-done"),
                BtnVariant.Theme, height = 58.dp, icon = L.drawable.lucide_ic_wallet,
            )
            else -> Spacer(Modifier.height(16.dp))
        }
    }
}

/*
 * "Address claimed!" choreography. One linear clock (0→1) drives everything; each element maps a
 * slice of it through its own easing, read inside graphicsLayer/draw lambdas so frames only
 * redraw, never recompose:
 *   title in → card rises → bolt strikes down into the card → flash + shockwave, badge pops,
 *   address reveals → sheen sweeps the card → button settles in.
 */
private const val CELEBRATION_MS = 1900
private const val STRIKE = 0.46f // the moment the bolt hits the card
private val BOLT_DROP = 116.dp
private val Overshoot = CubicBezierEasing(0.34f, 1.56f, 0.64f, 1f)

private fun Animatable<Float, *>.phase(from: Float, to: Float, easing: Easing = FastOutSlowInEasing) =
    easing.transform(((value - from) / (to - from)).coerceIn(0f, 1f))

@Composable
private fun rememberCelebration(active: Boolean): Animatable<Float, AnimationVector1D> {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    // Animations off (animator scale 0): start on the final frame, so there's no blank first frame either.
    val still = remember { Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
    val clock = remember { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(active) {
        if (!active || clock.value >= 1f) return@LaunchedEffect
        launch { snapshotFlow { clock.value >= STRIKE }.first { it }; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
        clock.animateTo(1f, tween(CELEBRATION_MS, easing = LinearEasing))
    }
    return clock
}

@Composable
private fun ClaimedHero(address: String, clock: Animatable<Float, AnimationVector1D>) {
    val brand = LocalBrand.current
    val name = address.substringBefore('@')
    val host = address.substringAfter('@', "")
    val cardShape = RoundedCornerShape(24.dp)
    // Oversized "@" watermark behind the address, brand-tinted and fading out toward the bottom-left.
    val measurer = rememberTextMeasurer()
    val at = remember(measurer) { measurer.measure("@", TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 210.sp)) }
    val atBrush = Brush.linearGradient(listOf(brand.t400.copy(alpha = 0.55f), brand.t400.copy(alpha = 0.12f), Color.Transparent))
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Txt(
            stringResource(R.string.claim_claimedTitle), size = 26.sp, weight = FontWeight.SemiBold, align = TextAlign.Center,
            modifier = Modifier.graphicsLayer { val p = clock.phase(0f, 0.18f); alpha = p; translationY = (1f - p) * 14.dp.toPx() },
        )
        Box(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) {
                Spacer(Modifier.height(BOLT_DROP))
                Column(
                    Modifier.fillMaxWidth()
                        .graphicsLayer { val p = clock.phase(0.06f, 0.34f); alpha = p; scaleX = 0.92f + 0.08f * p; scaleY = scaleX; translationY = (1f - p) * 24.dp.toPx() }
                        .drawBehind {
                            // Brand glow under the glass: builds at the strike, flares with the flash, then holds.
                            val glow = 0.55f * clock.phase(STRIKE, 0.8f) + 0.45f * impact(clock)
                            if (glow > 0f) scale(1f, 0.6f) {
                                drawCircle(Brush.radialGradient(listOf(brand.t400.copy(alpha = 0.6f * glow), Color.Transparent), center, size.width * 0.75f), size.width * 0.75f)
                            }
                        }
                        .glass(cardShape, GlassLevel.Strong, tint = brand.t400.copy(alpha = 0.10f))
                        .drawWithContent {
                            // Watermark drifts in and settles with the address reveal.
                            val w = clock.phase(STRIKE, 0.9f)
                            if (w > 0f) {
                                val tl = Offset(size.width - at.size.width * 0.82f + (1f - w) * 48.dp.toPx(), (size.height - at.size.height) / 2f + 6.dp.toPx())
                                rotate(-14f + 6f * w, tl + Offset(at.size.width / 2f, at.size.height / 2f)) { drawText(at, atBrush, tl, alpha = w) }
                            }
                            drawContent()
                            val flash = impact(clock)
                            if (flash > 0f) drawRect(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.35f * flash), brand.t400.copy(alpha = 0.25f * flash), Color.Transparent), Offset(size.width / 2, 0f), size.width * 0.8f))
                            val sweep = clock.phase(0.66f, 0.98f)
                            if (sweep > 0f && sweep < 1f) {
                                val x = -0.5f * size.width + 2f * size.width * sweep
                                drawRect(Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = 0.12f), Color.Transparent), Offset(x - size.width * 0.3f, 0f), Offset(x + size.width * 0.1f, size.height)))
                            }
                        }
                        .padding(top = 34.dp, bottom = 26.dp, start = 20.dp, end = 20.dp)
                        .testTag("claimed-address").semantics(mergeDescendants = true) {},
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier.graphicsLayer { val p = clock.phase(STRIKE, 0.72f); alpha = p; scaleX = 0.86f + 0.14f * p; scaleY = scaleX },
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Txt(name, size = if (name.length <= 10) 42.sp else 34.sp, weight = FontWeight.SemiBold, maxLines = 1, letterSpacing = (-0.5).sp) // 16-char max still fits on one line
                        if (host.isNotEmpty()) Txt("@$host", size = 22.sp, weight = FontWeight.Medium, color = lerp(brand.t400, Color.White, 0.35f), maxLines = 1)
                    }
                }
            }
            Spacer(Modifier.matchParentSize().drawWithCache { lightning(clock, brand.t400, brand.t300) })
            Box(
                Modifier.align(Alignment.TopCenter).padding(top = BOLT_DROP - 30.dp).size(60.dp)
                    .graphicsLayer { val p = clock.phase(STRIKE - 0.02f, 0.64f, Overshoot); scaleX = p; scaleY = p; alpha = (p * 3f).coerceAtMost(1f) }
                    .shadow(16.dp, CircleShape, ambientColor = brand.t400, spotColor = brand.t400)
                    .background(Glass.Dialog, CircleShape)
                    .border(1.5.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.45f), brand.t400.copy(alpha = 0.8f))), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Image(painterResource(R.drawable.ic_isotype), stringResource(R.string.app_name), Modifier.height(32.dp)) }
        }
        MutedText(
            stringResource(R.string.claim_claimedBody), align = TextAlign.Center,
            modifier = Modifier.padding(top = 20.dp, start = 12.dp, end = 12.dp).graphicsLayer { val p = clock.phase(0.60f, 0.84f); alpha = p; translationY = (1f - p) * 10.dp.toPx() },
        )
    }
}

/** 1 at the strike, decaying to 0. */
private fun impact(clock: Animatable<Float, *>) = if (clock.value < STRIKE) 0f else 1f - clock.phase(STRIKE, 0.66f, LinearOutSlowInEasing)

/** The bolt: drawn progressively from above the card into the badge, then flickers out, plus the impact burst. */
private fun CacheDrawScope.lightning(clock: Animatable<Float, *>, t400: Color, t300: Color): DrawResult {
    val end = Offset(size.width / 2, BOLT_DROP.toPx())
    val top = 14.dp.toPx() // clear of the title above
    // Zigzag as (fraction of the drop, x offset in dp), ending dead centre on the badge.
    val zig = listOf(0f to 26f, 0.2f to -10f, 0.36f to 18f, 0.54f to -16f, 0.7f to 10f, 0.86f to -6f, 1f to 0f)
    fun at(i: Int) = Offset(end.x + zig[i].second.dp.toPx(), top + (end.y - top) * zig[i].first)
    val main = Path().apply { moveTo(at(0).x, at(0).y); for (i in 1 until zig.size) lineTo(at(i).x, at(i).y) }
    val fork = Path().apply { val o = at(3); moveTo(o.x, o.y); lineTo(o.x - 16.dp.toPx(), o.y + 14.dp.toPx()); lineTo(o.x - 12.dp.toPx(), o.y + 30.dp.toPx()) }
    val mainM = PathMeasure().apply { setPath(main, false) }
    val forkM = PathMeasure().apply { setPath(fork, false) }
    val seg = Path()
    val forkSeg = Path()
    val widths = listOf(16.dp.toPx(), 6.dp.toPx(), 2.2.dp.toPx())
    return onDrawBehind {
        val draw = clock.phase(0.26f, STRIKE, FastOutLinearInEasing)
        val fade = 1f - clock.phase(STRIKE + 0.04f, 0.72f, LinearEasing)
        if (draw > 0f && fade > 0f) {
            val a = fade * (if (fade < 1f) 0.8f + 0.2f * sin(clock.value * 260f) else 1f) // flicker on the way out
            seg.reset(); mainM.getSegment(0f, mainM.length * draw, seg, true)
            forkSeg.reset(); val fp = ((draw - 0.45f) / 0.55f).coerceIn(0f, 1f); if (fp > 0f) forkM.getSegment(0f, forkM.length * fp, forkSeg, true)
            listOf(t400.copy(alpha = 0.28f * a), t300.copy(alpha = 0.7f * a), Color.White.copy(alpha = a)).forEachIndexed { i, c ->
                drawPath(seg, c, style = Stroke(widths[i], cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(forkSeg, c, style = Stroke(widths[i] * 0.6f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        val burst = clock.phase(STRIKE, 0.70f, LinearOutSlowInEasing)
        if (clock.value >= STRIKE && burst < 1f) {
            val a = 1f - burst
            val r = 28.dp.toPx() + 70.dp.toPx() * burst
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.8f * a), t400.copy(alpha = 0.35f * a), Color.Transparent), end, r), r, end)
            drawCircle(t300.copy(alpha = 0.8f * a), 30.dp.toPx() + 90.dp.toPx() * burst, end, style = Stroke(2.5.dp.toPx() * a + 0.5.dp.toPx()))
        }
    }
}

/**
 * A new address gets its own disposable LNCurl wallet when the instance runs LNCurl: created, named
 * after the address, and the address routed into it. It becomes primary only if there's no active one.
 * Best effort: a failure leaves the address on the server's default routing.
 */
internal suspend fun provisionLncurl(c: io.lawallet.wallet.app.AppContainer, username: String) {
    val api = c.session.api.value
    runCatching {
        val hasPrimary = api.remoteWallets().any { it.isDefault && it.status == "ACTIVE" }
        val w = api.createLncurlWallet(name = username, isDefault = !hasPrimary)
        api.routeAddressToWallet(username, w.id)
    }
}
