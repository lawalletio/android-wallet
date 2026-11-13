package io.lawallet.wallet.ui.screens

import android.content.Intent
import android.util.Patterns
import androidx.activity.compose.BackHandler
import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseInOutSine
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.lawallet.wallet.R
import io.lawallet.wallet.app.AuthState
import io.lawallet.wallet.core.api.ApiException
import io.lawallet.wallet.core.api.EmailLoginPreview
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.afterAuth
import io.lawallet.wallet.ui.components.BrandImage
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.LoadingDots
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.goHome
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.IOException
import com.composables.icons.lucide.R as L

/**
 * Email magic link, step 1: ask for the address, then "check your inbox". One form for everybody —
 * the server signs in a known address and signs up a new one; the link lands on [EmailAuthScreen].
 */
@Composable
fun EmailLoginScreen() {
    val nav = LocalNav.current
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var email by rememberSaveable { mutableStateOf("") }
    var sentTo by rememberSaveable { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cooldown by rememberSaveable { mutableIntStateOf(0) }
    val locale = if (LocalConfiguration.current.locales[0].language == "es") "es" else "en"
    val failed = stringResource(R.string.email_send_failed)
    val invalid = stringResource(R.string.email_invalid)
    val resent = stringResource(R.string.email_resent)
    val noEmailApp = stringResource(R.string.email_no_app)
    val valid = Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()

    LaunchedEffect(cooldown) { if (cooldown > 0) { delay(1000); cooldown-- } }

    fun send(again: Boolean) {
        if (!valid) { error = invalid; return }
        val address = email.trim().lowercase()
        loading = true; error = null
        scope.launch {
            runCatching { c.session.api.value.requestEmailLogin(address, locale) }
                .onSuccess { sentTo = address; cooldown = 60; if (again) context.toast(resent) }
                // Rate limits / provider failures carry a readable server message; network errors don't.
                .onFailure { error = (it as? ApiException)?.message ?: failed }
            loading = false
        }
    }
    fun back() { sentTo = null; error = null }

    BackHandler(sentTo != null) { back() }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(null, { if (sentTo != null) back() else nav.popBackStack() })
        AnimatedContent(
            sentTo, Modifier.weight(1f),
            transitionSpec = {
                val forward = targetState != null
                (fadeIn(tween(260, 90)) + scaleIn(tween(380, easing = EaseOutCubic), initialScale = 0.96f) +
                    slideInVertically(tween(380, easing = EaseOutCubic)) { if (forward) it / 10 else -it / 10 }) togetherWith
                    (fadeOut(tween(110)) + slideOutVertically(tween(260)) { if (forward) -it / 14 else it / 14 })
            },
            label = "email-step",
        ) { to ->
            if (to == null) EmailForm(email, { email = it.trim(); error = null }, valid, loading, error) { send(false) }
            else EmailSent(to, cooldown, loading, error, onResend = { send(true) }, onChange = ::back) {
                val inbox = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_EMAIL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(inbox) }.onFailure { context.toast(noEmailApp) }
            }
        }
    }
}

@Composable
private fun EmailForm(email: String, onEmail: (String) -> Unit, valid: Boolean, loading: Boolean, error: String?, onSend: () -> Unit) {
    val focus = remember { FocusRequester() }
    // After the screen's slide-in, so the keyboard doesn't push the layout mid-transition.
    LaunchedEffect(Unit) { delay(320); runCatching { focus.requestFocus() } }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AuthHero(Modifier.rise(0)) { LwIcon(L.drawable.lucide_ic_mail, size = 38.dp, tint = LocalBrand.current.t400) }
            Column(Modifier.rise(1), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(stringResource(R.string.email_title), size = 24.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
                MutedText(stringResource(R.string.email_subtitle), align = TextAlign.Center)
            }
            OutlinedTextField(
                email, onEmail, Modifier.fillMaxWidth().focusRequester(focus).rise(2).testTag("email-input"), enabled = !loading,
                placeholder = { Txt(stringResource(R.string.email_placeholder), color = Lw.MutedFg) },
                leadingIcon = { LwIcon(L.drawable.lucide_ic_at_sign, size = 18.dp, tint = if (valid) LocalBrand.current.t400 else Lw.MutedFg) },
                singleLine = true, shape = RoundedCornerShape(12.dp), colors = lwFieldColors(),
                keyboardOptions = KeyboardOptions(KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Email, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { if (!loading) onSend() }),
            )
            ErrorText(error, Modifier.fillMaxWidth())
        }
        LwButton(
            stringResource(if (loading) R.string.email_sending else R.string.email_send), onSend,
            Modifier.fillMaxWidth().padding(vertical = 24.dp).testTag("email-send"), BtnVariant.Theme,
            enabled = valid, loading = loading, icon = L.drawable.lucide_ic_send,
        )
    }
}

@Composable
private fun EmailSent(to: String, cooldown: Int, loading: Boolean, error: String?, onResend: () -> Unit, onChange: () -> Unit, onOpenInbox: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp).testTag("email-sent")) {
        CenteredColumn {
            AuthHero(pulse = true) { LwIcon(L.drawable.lucide_ic_mail_check, size = 38.dp, tint = LocalBrand.current.t400) }
            Txt(stringResource(R.string.email_sent_title), Modifier.rise(1), size = 26.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
            Column(Modifier.rise(2), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                MutedText(stringResource(R.string.email_sent_body), align = TextAlign.Center)
                Txt(to, Modifier.glass(RoundedCornerShape(50), GlassLevel.Thin).padding(horizontal = 16.dp, vertical = 8.dp), size = 15.sp, weight = FontWeight.Medium, maxLines = 1)
            }
            MutedText(stringResource(R.string.email_sent_hint), Modifier.rise(3), align = TextAlign.Center, size = 13.sp)
            ErrorText(error, Modifier.fillMaxWidth())
        }
        Column(Modifier.padding(vertical = 24.dp).rise(4), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LwButton(stringResource(R.string.email_open_app), onOpenInbox, Modifier.fillMaxWidth().testTag("email-open-inbox"), BtnVariant.Theme, icon = L.drawable.lucide_ic_mail_open)
            LwButton(
                if (cooldown > 0) stringResource(R.string.email_resend_in, cooldown) else stringResource(R.string.email_resend), onResend,
                Modifier.fillMaxWidth().testTag("email-resend"), BtnVariant.Secondary, enabled = cooldown == 0, loading = loading, icon = L.drawable.lucide_ic_refresh_cw,
            )
            LwButton(stringResource(R.string.email_change), onChange, Modifier.fillMaxWidth(), BtnVariant.Ghost, height = 44.dp)
        }
    }
}

private sealed interface LinkState {
    data object Loading : LinkState
    data object OtherInstance : LinkState
    data object Invalid : LinkState
    data object Offline : LinkState
    data class Ready(val preview: EmailLoginPreview) : LinkState
}

/**
 * Email magic link, step 2 (`/wallet/auth/email#token=…` opened in the app). Previews who the link
 * signs in as — without consuming it — and signs in (or creates the wallet) on one tap.
 */
@Composable
fun EmailAuthScreen(token: String, host: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val auth by c.session.auth.collectAsState()
    var state by remember { mutableStateOf<LinkState>(LinkState.Loading) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.auth_loginFailed)

    LaunchedEffect(token, reload) {
        state = LinkState.Loading
        if (host !in c.session.instanceHosts) { state = LinkState.OtherInstance; return@LaunchedEffect }
        state = runCatching { c.session.api.value.emailLoginPreview(token) }.fold(
            onSuccess = { p ->
                // Already signed in to this very account: nothing to do.
                if (c.session.auth.value == AuthState.LoggedIn && p.pubkey != null && p.pubkey == c.session.accountPubkey.value) { nav.goHome(); return@LaunchedEffect }
                LinkState.Ready(p)
            },
            onFailure = { if (it is IOException) LinkState.Offline else LinkState.Invalid },
        )
    }

    fun confirm() {
        busy = true; error = null
        scope.launch {
            runCatching {
                val r = c.session.api.value.emailLoginVerify(token)
                // Another account on this phone: end it cleanly (caches, avatar) before the new one.
                if (c.session.auth.value == AuthState.LoggedIn) c.session.logout()
                c.session.loginWithSecret(r.secretHex, "email")
                r.isNew
            }.onSuccess { isNew -> nav.afterAuth(c, if (isNew) R0.WELCOME else R0.HOME) }
                .onFailure { error = it.message ?: failed; busy = false }
        }
    }

    fun switchInstance() {
        busy = true; error = null
        scope.launch {
            runCatching { c.session.switchInstance(host) }.onSuccess { reload++ }.onFailure { error = it.message ?: failed }
            busy = false
        }
    }

    fun close() {
        if (auth == AuthState.LoggedIn) nav.goHome() else if (!nav.popBackStack()) nav.navigate(R0.LANDING) { popUpTo(0) { inclusive = true } }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(null, ::close, close = true)
        AnimatedContent(
            state, Modifier.weight(1f),
            transitionSpec = {
                (fadeIn(tween(260, 90)) + scaleIn(tween(420, easing = EaseOutCubic), initialScale = 0.94f)) togetherWith fadeOut(tween(120))
            },
            contentKey = { it::class },
            label = "email-auth",
        ) { s ->
            Column(Modifier.fillMaxSize().padding(horizontal = 24.dp)) {
                when (s) {
                    LinkState.Loading -> CenteredColumn {
                        AuthHero(pulse = true) { LwIcon(L.drawable.lucide_ic_link, size = 36.dp, tint = LocalBrand.current.t400) }
                        MutedText(stringResource(R.string.email_auth_checking), Modifier.rise(1), align = TextAlign.Center)
                        LoadingDots(Modifier.rise(2), dot = 8.dp, color = Lw.MutedFg)
                    }
                    is LinkState.Ready -> ReadyContent(s.preview, switching = auth == AuthState.LoggedIn, busy = busy, error = error, onConfirm = ::confirm)
                    LinkState.Invalid -> {
                        StatusContent(L.drawable.lucide_ic_link_2_off, stringResource(R.string.email_auth_invalid_title), stringResource(R.string.email_auth_invalid_body), Lw.Orange)
                        if (auth == AuthState.LoggedIn) BottomAction(stringResource(R.string.scan_backToWallet), { nav.goHome() }, BtnVariant.Secondary)
                        else BottomAction(stringResource(R.string.email_auth_new_link), {
                            // Back to the "check your inbox" step when it's still there; it has Resend.
                            if (!nav.popBackStack(R0.LOGIN_EMAIL, inclusive = false)) nav.navigate(R0.LOGIN_EMAIL) { popUpTo(R0.EMAIL_AUTH) { inclusive = true } }
                        }, icon = L.drawable.lucide_ic_mail, tag = "email-new-link")
                    }
                    LinkState.Offline -> {
                        StatusContent(L.drawable.lucide_ic_circle_alert, stringResource(R.string.email_auth_offline_title), stringResource(R.string.email_auth_offline_body), Lw.Orange)
                        BottomAction(stringResource(R.string.common_retry), { reload++ }, BtnVariant.Secondary, icon = L.drawable.lucide_ic_refresh_cw)
                    }
                    LinkState.OtherInstance -> {
                        StatusContent(L.drawable.lucide_ic_globe, stringResource(R.string.email_auth_other_title), stringResource(R.string.email_auth_other_body, host, c.session.instanceHost), LocalBrand.current.t400, error)
                        BottomAction(stringResource(R.string.email_auth_switch, host), ::switchInstance, loading = busy)
                    }
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.ReadyContent(p: EmailLoginPreview, switching: Boolean, busy: Boolean, error: String?, onConfirm: () -> Unit) {
    val brand = LocalBrand.current
    CenteredColumn {
        AuthHero(shape = CircleShape) {
            when {
                p.picture != null -> AsyncImage(p.picture, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                p.isNew -> LwIcon(L.drawable.lucide_ic_sparkles, size = 38.dp, tint = brand.t400)
                else -> BrandImage(brand.isotype, "isotype.svg", Modifier.padding(22.dp).fillMaxSize())
            }
        }
        Column(Modifier.rise(1), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Txt(stringResource(if (p.isNew) R.string.email_auth_new_eyebrow else R.string.email_auth_welcome_back), size = 15.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
            Txt(if (p.isNew) stringResource(R.string.email_auth_new_title) else p.displayName ?: p.email, size = 26.sp, weight = FontWeight.SemiBold, align = TextAlign.Center, maxLines = 2)
        }
        MutedText(
            if (p.isNew) stringResource(R.string.email_auth_new_body, p.email) else stringResource(R.string.email_auth_signin_as, p.email),
            Modifier.rise(2), align = TextAlign.Center,
        )
        if (switching) Txt(
            stringResource(R.string.email_auth_switch_note),
            Modifier.rise(3).glass(RoundedCornerShape(14.dp), GlassLevel.Thin, tint = Lw.Orange.copy(alpha = 0.10f)).padding(horizontal = 14.dp, vertical = 10.dp),
            size = 13.sp, color = Lw.Orange, align = TextAlign.Center,
        )
        ErrorText(error, Modifier.fillMaxWidth())
    }
    BottomAction(
        stringResource(
            when {
                busy && p.isNew -> R.string.email_auth_creating
                busy -> R.string.email_auth_signing_in
                p.isNew -> R.string.email_auth_create
                else -> R.string.email_auth_signin
            }
        ),
        onConfirm, loading = busy, icon = if (p.isNew) L.drawable.lucide_ic_sparkles else L.drawable.lucide_ic_arrow_right, tag = "email-auth-confirm",
    )
}

@Composable
private fun ColumnScope.StatusContent(@DrawableRes icon: Int, title: String, body: String, tint: Color, error: String? = null) = CenteredColumn {
    AuthHero(tint = tint) { LwIcon(icon, size = 36.dp, tint = tint) }
    Txt(title, Modifier.rise(1), size = 24.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
    MutedText(body, Modifier.rise(2), align = TextAlign.Center)
    ErrorText(error, Modifier.fillMaxWidth())
}

@Composable
private fun BottomAction(text: String, onClick: () -> Unit, variant: BtnVariant = BtnVariant.Theme, loading: Boolean = false, @DrawableRes icon: Int? = null, tag: String? = null) =
    LwButton(text, onClick, Modifier.fillMaxWidth().padding(vertical = 24.dp).rise(3).then(if (tag != null) Modifier.testTag(tag) else Modifier), variant, loading = loading, icon = icon)

@Composable
private fun ColumnScope.CenteredColumn(content: @Composable ColumnScope.() -> Unit) = Column(
    Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), content = content,
)

/**
 * The flow's centrepiece: a glass tile (or avatar circle) that pops in with a bounce over a soft
 * brand glow. [pulse]: waiting on something — rings ripple out and the tile floats.
 */
@Composable
private fun AuthHero(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    tint: Color = LocalBrand.current.t400,
    pulse: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 260f)) }
    val loop = rememberInfiniteTransition(label = "hero")
    // States read only in draw/layer lambdas: the loops redraw, they never recompose.
    val ring = loop.animateFloat(0f, 1f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "ring")
    val bob = loop.animateFloat(-1f, 1f, infiniteRepeatable(tween(1800, easing = EaseInOutSine), RepeatMode.Reverse), label = "bob")
    Box(modifier.size(168.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.matchParentSize().drawBehind {
            val r = size.minDimension / 2
            val a = appear.value.coerceIn(0f, 1f)
            drawCircle(Brush.radialGradient(0f to tint.copy(alpha = 0.32f * a), 0.55f to tint.copy(alpha = 0.10f * a), 1f to Color.Transparent, center = center, radius = r), r, center)
            if (pulse) for (i in 0..1) {
                val p = (ring.value + i * 0.5f) % 1f
                drawCircle(tint.copy(alpha = 0.40f * (1f - p) * a), r * (0.55f + 0.42f * p), center, style = Stroke(1.5.dp.toPx()))
            }
        })
        Box(
            Modifier.size(96.dp).graphicsLayer {
                val s = 0.55f + 0.45f * appear.value
                scaleX = s; scaleY = s
                alpha = appear.value.coerceIn(0f, 1f)
                if (pulse) translationY = bob.value * 4.dp.toPx()
            }.glass(shape, GlassLevel.Strong),
            contentAlignment = Alignment.Center, content = content,
        )
    }
}

/** Staggered entrance: [order] × 70ms late, rising 20dp into place. */
@Composable
private fun Modifier.rise(order: Int): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(70L * order); a.animateTo(1f, spring(dampingRatio = 0.85f, stiffness = 260f)) }
    return graphicsLayer { alpha = a.value.coerceIn(0f, 1f); translationY = (1f - a.value) * 20.dp.toPx() }
}
