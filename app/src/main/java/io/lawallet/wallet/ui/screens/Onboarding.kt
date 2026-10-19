package io.lawallet.wallet.ui.screens

import android.app.Activity
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import io.lawallet.wallet.R
import io.lawallet.wallet.core.auth.AmberSigner
import io.lawallet.wallet.core.auth.PasskeyException
import io.lawallet.wallet.core.nostr.Keys
import io.lawallet.wallet.core.nostr.Nip19
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.afterAuth
import io.lawallet.wallet.ui.components.BrandImage
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.copyToClipboard
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

/** max-w-md column with px-6, like the (auth) layout. */
@Composable
private fun AuthColumn(content: @Composable ColumnScope.() -> Unit) =
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), content = content)

@Composable
fun LandingScreen() {
    val nav = LocalNav.current
    val brand = LocalBrand.current
    AuthColumn {
        Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Box(Modifier.size(168.dp).shadow(40.dp, RoundedCornerShape(40.dp), ambientColor = LocalBrand.current.t400, spotColor = LocalBrand.current.t400).glass(RoundedCornerShape(40.dp), GlassLevel.Strong), contentAlignment = Alignment.Center) {
                BrandImage(brand.isotype, "isotype.svg", Modifier.size(88.dp))
            }
            Spacer(Modifier.height(32.dp))
            Txt(brand.name ?: "LaWallet", size = 24.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            MutedText(stringResource(R.string.onboarding_landing_tagline), align = TextAlign.Center, size = 16.sp)
        }
        Column(Modifier.padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LwButton(stringResource(R.string.onboarding_landing_createAccount), { nav.navigate(R0.SIGNUP) }, Modifier.fillMaxWidth().testTag("landing-create"), BtnVariant.Theme)
            LwButton(stringResource(R.string.onboarding_landing_login), { nav.navigate(R0.LOGIN) }, Modifier.fillMaxWidth().testTag("landing-login"), BtnVariant.Secondary)
        }
    }
}

@Composable
fun SignupChoiceScreen() {
    val nav = LocalNav.current
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(null, { nav.popBackStack() })
        Column(Modifier.weight(1f).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt(stringResource(R.string.onboarding_signup_title), size = 24.sp, weight = FontWeight.SemiBold)
                MutedText(stringResource(R.string.onboarding_signup_subtitlePasskey))
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                LwButton(stringResource(R.string.onboarding_signup_passkey), { nav.navigate(R0.CREATE_PASSKEY) }, Modifier.fillMaxWidth(), BtnVariant.Theme, icon = L.drawable.lucide_ic_fingerprint_pattern)
                LwButton(stringResource(R.string.onboarding_signup_randomKey), { nav.navigate(R0.CREATE_ACCOUNT) }, Modifier.fillMaxWidth().testTag("signup-random"), BtnVariant.Secondary)
                LwButton(stringResource(R.string.onboarding_signup_haveKey), { nav.navigate(R0.LOGIN) }, Modifier.fillMaxWidth(), BtnVariant.Secondary)
            }
        }
    }
}

/** shared/secret-key-reveal.tsx — blurred nsec, eye toggle, copy, "I've saved it" checkbox. */
@Composable
fun SecretKeyReveal(nsec: String, confirmed: Boolean?, onConfirmed: ((Boolean) -> Unit)?, enabled: Boolean = true) {
    val context = LocalContext.current
    var revealed by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(nsec, Modifier.weight(1f).then(if (revealed) Modifier else Modifier.blur(4.dp)).testTag("nsec"), size = 12.sp, mono = true)
            Box(Modifier.size(32.dp).clickable { revealed = !revealed }, contentAlignment = Alignment.Center) {
                LwIcon(if (revealed) L.drawable.lucide_ic_eye_off else L.drawable.lucide_ic_eye, size = 16.dp, tint = Lw.MutedFg)
            }
        }
        LwButton(
            stringResource(if (copied) R.string.common_copied else R.string.auth_copyKey),
            { context.copyToClipboard(nsec); copied = true }, Modifier.fillMaxWidth(), BtnVariant.Secondary, enabled = enabled, height = 40.dp,
            icon = if (copied) L.drawable.lucide_ic_check else L.drawable.lucide_ic_copy,
        )
        if (onConfirmed != null) Row(
            Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), GlassLevel.Thin)
                .clickable(enabled = enabled) { onConfirmed(confirmed != true) }.padding(16.dp).testTag("confirm-saved"),
            verticalAlignment = Alignment.Top,
        ) {
            Checkbox(confirmed == true, { onConfirmed(it) }, Modifier.size(20.dp), enabled = enabled, colors = CheckboxDefaults.colors(checkedColor = Lw.Foreground, checkmarkColor = Lw.Background, uncheckedColor = Lw.MutedFg))
            Spacer(Modifier.width(12.dp))
            Txt(stringResource(R.string.auth_savedKeyConfirm), size = 14.sp, lineHeight = 19.sp)
        }
    }
}

@Composable
fun CreateAccountScreen() {
    val nav = LocalNav.current
    val c = LocalContainer.current
    val scope = rememberCoroutineScope()
    val keys = remember { Keys.generate() }
    var confirmed by rememberSaveable { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.onboarding_createAccount_failed)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(null, { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt(stringResource(R.string.onboarding_createAccount_title), size = 24.sp, weight = FontWeight.SemiBold)
                MutedText(stringResource(R.string.onboarding_createAccount_subtitle))
            }
            SecretKeyReveal(keys.nsec, confirmed, { confirmed = it }, enabled = !loading)
            ErrorText(error, Modifier.fillMaxWidth())
        }
        LwButton(
            stringResource(if (loading) R.string.onboarding_createAccount_creating else R.string.common_continue),
            {
                loading = true; error = null
                scope.launch {
                    runCatching { c.session.loginWithSecret(keys.secretHex, "nsec") }
                        .onSuccess { nav.afterAuth(c, R0.WELCOME) }
                        .onFailure { error = it.message ?: failed; loading = false }
                }
            },
            Modifier.fillMaxWidth().padding(24.dp).testTag("create-continue"), BtnVariant.Theme, enabled = confirmed, loading = loading,
        )
    }
}

@Composable
fun CreatePasskeyScreen() {
    val nav = LocalNav.current
    val c = LocalContainer.current
    val activity = androidx.activity.compose.LocalActivity.current!!
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cancelled = stringResource(R.string.onboarding_passkey_cancelled)
    val prfMsg = stringResource(R.string.auth_passkeyErrors_prf_unsupported)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(null, { nav.popBackStack() })
        Column(Modifier.weight(1f).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt(stringResource(R.string.onboarding_passkey_title), size = 24.sp, weight = FontWeight.SemiBold)
                MutedText(stringResource(R.string.onboarding_passkey_subtitle, c.session.instanceHost))
            }
            listOf(
                L.drawable.lucide_ic_fingerprint_pattern to R.string.onboarding_passkey_benefitBiometric,
                L.drawable.lucide_ic_smartphone to R.string.onboarding_passkey_benefitSync,
                L.drawable.lucide_ic_key_round to R.string.onboarding_passkey_benefitKey,
            ).forEach { (icon, text) ->
                Row(verticalAlignment = Alignment.Top) {
                    Box(Modifier.size(40.dp).glass(RoundedCornerShape(14.dp), GlassLevel.Strong), contentAlignment = Alignment.Center) { LwIcon(icon, size = 18.dp, tint = LocalBrand.current.t400) }
                    Spacer(Modifier.width(12.dp))
                    MutedText(stringResource(text), Modifier.weight(1f))
                }
            }
            ErrorText(error, Modifier.fillMaxWidth())
        }
        LwButton(
            stringResource(if (loading) R.string.onboarding_passkey_waiting else R.string.onboarding_passkey_submit),
            {
                loading = true; error = null
                scope.launch {
                    runCatching {
                        val id = c.passkeys.register(activity, null, authenticated = false)
                        c.session.loginWithSecret(id.secretHex, "passkey")
                    }.onSuccess { nav.afterAuth(c, R0.WELCOME) }
                        .onFailure { error = passkeyError(it, cancelled, prfMsg); loading = false }
                }
            },
            Modifier.fillMaxWidth().padding(24.dp), BtnVariant.Theme, loading = loading, icon = L.drawable.lucide_ic_fingerprint_pattern,
        )
    }
}

fun passkeyError(e: Throwable, cancelled: String, prf: String): String = when ((e as? PasskeyException)?.kind) {
    "cancelled" -> cancelled
    "prf-unsupported" -> prf
    else -> e.message ?: "Passkey error"
}

@Composable
fun LoginScreen() {
    val nav = LocalNav.current
    val c = LocalContainer.current
    val brand = LocalBrand.current
    val activity = androidx.activity.compose.LocalActivity.current!!
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val cancelled = stringResource(R.string.auth_passkeyErrors_cancelled)
    val prfMsg = stringResource(R.string.auth_passkeyErrors_prf_unsupported)
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(null, { nav.popBackStack() })
        Column(Modifier.weight(1f).padding(horizontal = 24.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(32.dp)) {
                BrandImage(brand.logotype, "logotype.svg", Modifier.height(28.dp).width(120.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Txt(stringResource(R.string.onboarding_login_title), size = 24.sp, weight = FontWeight.SemiBold)
                    MutedText(stringResource(R.string.onboarding_login_subtitle), align = TextAlign.Center)
                }
                Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
                    LwButton(
                        stringResource(if (loading) R.string.auth_waitingForDevice else R.string.onboarding_login_passkey),
                        {
                            loading = true; error = null
                            scope.launch {
                                runCatching {
                                    val id = c.passkeys.authenticate(activity)
                                    c.session.loginWithSecret(id.secretHex, "passkey")
                                }.onSuccess { nav.afterAuth(c) }.onFailure { error = passkeyError(it, cancelled, prfMsg); loading = false }
                            }
                        },
                        Modifier.fillMaxWidth(), BtnVariant.Theme, loading = loading, icon = L.drawable.lucide_ic_fingerprint_pattern,
                    )
                    ErrorText(error, Modifier.fillMaxWidth())
                    OrDivider(stringResource(R.string.onboarding_login_or))
                    LwButton(stringResource(R.string.onboarding_login_continueWithNostr), { nav.navigate(R0.LOGIN_NOSTR) }, Modifier.fillMaxWidth().testTag("login-nostr"), BtnVariant.Secondary, icon = L.drawable.lucide_ic_key_round)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MutedText(stringResource(R.string.onboarding_login_newHere))
                LwButton(stringResource(R.string.onboarding_login_createAccount), { nav.navigate(R0.SIGNUP) }, Modifier.fillMaxWidth(), BtnVariant.Secondary, height = 44.dp)
            }
        }
    }
}

@Composable
fun lwFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LocalBrand.current.t400, unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
    focusedContainerColor = Color.White.copy(alpha = 0.06f), unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
    cursorColor = Lw.Foreground, focusedTextColor = Lw.Foreground, unfocusedTextColor = Lw.Foreground,
    focusedPlaceholderColor = Lw.MutedFg, unfocusedPlaceholderColor = Lw.MutedFg,
)

@Composable
fun NostrLoginScreen() {
    val nav = LocalNav.current
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var key by rememberSaveable { mutableStateOf("") }
    var visible by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.auth_loginFailed)
    val amberMissing = stringResource(R.string.login_amber_missing)
    val amberLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val data = res.data
        val raw = data?.getStringExtra("result")
        val pkg = data?.getStringExtra("package")
        if (res.resultCode != Activity.RESULT_OK || raw == null || pkg == null) { loading = false; return@rememberLauncherForActivityResult }
        scope.launch {
            runCatching {
                val pubkey = if (raw.startsWith("npub")) Nip19.decodeNpub(raw) else raw
                c.session.loginWithAmber(pubkey, pkg)
            }.onSuccess { nav.afterAuth(c) }.onFailure { error = it.message ?: failed; loading = false }
        }
    }
    Column(Modifier.fillMaxSize()) {
        ScreenHeader(null, { nav.popBackStack() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(stringResource(R.string.onboarding_nostr_title), size = 24.sp, weight = FontWeight.SemiBold)
                MutedText(stringResource(R.string.onboarding_nostr_subtitle))
            }
            Txt(stringResource(R.string.auth_privateKeyTab), size = 14.sp, weight = FontWeight.Medium)
            OutlinedTextField(
                key, { key = it.trim(); error = null }, Modifier.fillMaxWidth().testTag("nsec-input"),
                placeholder = { Txt(stringResource(R.string.auth_nsecPlaceholder), color = Lw.MutedFg) },
                singleLine = true, shape = RoundedCornerShape(12.dp), colors = lwFieldColors(),
                visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = { Box(Modifier.size(40.dp).clickable { visible = !visible }, contentAlignment = Alignment.Center) { LwIcon(if (visible) L.drawable.lucide_ic_eye_off else L.drawable.lucide_ic_eye, size = 18.dp, tint = Lw.MutedFg) } },
            )
            ErrorText(error, Modifier.fillMaxWidth())
            LwButton(
                stringResource(if (loading) R.string.auth_signingIn else R.string.onboarding_nostr_submit),
                {
                    val hex = runCatching { Nip19.parseSecret(key) }.getOrNull()
                    if (hex == null) { error = failed; return@LwButton }
                    loading = true; error = null
                    scope.launch {
                        runCatching { c.session.loginWithSecret(hex, "nsec") }
                            .onSuccess { nav.afterAuth(c) }.onFailure { error = it.message ?: failed; loading = false }
                    }
                },
                Modifier.fillMaxWidth().testTag("nsec-submit"), BtnVariant.Theme, enabled = key.isNotBlank(), loading = loading,
            )
            OrDivider(stringResource(R.string.auth_orContinueWith))
            LwButton(stringResource(R.string.login_amber), {
                if (!AmberSigner.isInstalled(context)) { error = amberMissing; return@LwButton }
                loading = true
                amberLauncher.launch(AmberSigner.loginIntent())
            }, Modifier.fillMaxWidth(), BtnVariant.Secondary, enabled = !loading, icon = L.drawable.lucide_ic_smartphone)
        }
    }
}

@Composable
fun WelcomeScreen() {
    val nav = LocalNav.current
    AuthColumn {
        Column(Modifier.weight(1f).padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(Modifier.size(80.dp).clip(RoundedCornerShape(16.dp)).background(Lw.NwcPurple.copy(alpha = 0.1f)), contentAlignment = Alignment.Center) {
                    LwIcon(L.drawable.lucide_ic_sparkles, size = 40.dp, tint = Lw.NwcPurple)
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Txt(stringResource(R.string.onboarding_welcome_title), size = 24.sp, weight = FontWeight.SemiBold, align = TextAlign.Center)
                MutedText(stringResource(R.string.onboarding_welcome_body), align = TextAlign.Center)
            }
            Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), GlassLevel.Thin).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Txt(stringResource(R.string.onboarding_welcome_thingsToKnow), size = 14.sp, weight = FontWeight.Medium)
                listOf(R.string.onboarding_welcome_tipKey, R.string.onboarding_welcome_tipAddress, R.string.onboarding_welcome_tipSats).forEach {
                    Txt("•  " + stringResource(it), size = 12.sp, color = Lw.MutedFg)
                }
            }
        }
        LwButton(stringResource(R.string.onboarding_welcome_enter), { nav.navigate(R0.HOME) { popUpTo(0) { inclusive = true } } }, Modifier.fillMaxWidth().padding(bottom = 24.dp).testTag("welcome-enter"))
    }
}

/** "or" between two hairlines — no opaque patch over the glass background. */
@Composable
private fun OrDivider(text: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        HorizontalDivider(Modifier.weight(1f), color = Color.White.copy(alpha = 0.10f))
        Txt(text, Modifier.padding(horizontal = 12.dp), size = 12.sp, color = Lw.MutedFg)
        HorizontalDivider(Modifier.weight(1f), color = Color.White.copy(alpha = 0.10f))
    }
}
