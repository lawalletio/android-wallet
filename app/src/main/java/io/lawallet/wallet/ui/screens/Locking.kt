package io.lawallet.wallet.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.style.TextAlign
import io.lawallet.wallet.ui.components.BrandImage
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.PrismBackground
import io.lawallet.wallet.ui.theme.glass
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import io.lawallet.wallet.R
import io.lawallet.wallet.app.AppLock
import io.lawallet.wallet.core.store.LockPrefs
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.GroupDivider
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.SettingsGroup
import io.lawallet.wallet.ui.components.SettingsRow
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import kotlinx.coroutines.launch
import com.composables.icons.lucide.R as L

/** Settings → Locking. Changing it asks for the device lock first once any lock is on. */
@Composable
fun LockingScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val activity = LocalActivity.current as FragmentActivity
    val lock by c.prefs.lock.collectAsState()
    var open by remember { mutableStateOf(lock == LockPrefs(paymentsMinSats = lock.paymentsMinSats)) }
    val authTitle = stringResource(R.string.lock_auth_settings)
    val unavailable = stringResource(R.string.lock_unavailable)
    LaunchedEffect(Unit) { if (!open) { if (AppLock.authenticate(activity, authTitle)) open = true else nav.popBackStack() } }
    var minText by remember { mutableStateOf(lock.paymentsMinSats.toString()) }
    // Turning a lock on needs one to ask for; turning it off is already behind the gate above.
    fun set(on: Boolean, next: LockPrefs) { if (on && !AppLock.available(context)) context.toast(unavailable) else c.prefs.setLock(next) }
    SettingsPage(stringResource(R.string.lock_title)) {
        if (!open) return@SettingsPage
        SettingsGroup(stringResource(R.string.lock_require)) {
            LockToggle(R.string.lock_sensitive, "lock-sensitive", lock.sensitive) { set(it, lock.copy(sensitive = it)) }
            GroupDivider()
            LockToggle(R.string.lock_startup, "lock-startup", lock.onStartup) { set(it, lock.copy(onStartup = it)) }
            GroupDivider()
            LockToggle(R.string.lock_access, "lock-access", lock.onAccess) { set(it, lock.copy(onAccess = it)) }
            GroupDivider()
            LockToggle(R.string.lock_payments, "lock-payments", lock.payments) { set(it, lock.copy(payments = it)) }
            if (lock.payments) Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(minText, { v ->
                    minText = v.filter(Char::isDigit).take(10)
                    c.prefs.setLock(lock.copy(paymentsMinSats = minText.toLongOrNull() ?: 0))
                }, Modifier.fillMaxWidth().testTag("lock-min-amount"), label = { Txt(stringResource(R.string.lock_min_amount)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = lwFieldColors())
                MutedText(stringResource(R.string.lock_min_amount_hint), size = 13.sp)
            }
        }
        MutedText(stringResource(R.string.lock_hint), Modifier.padding(horizontal = 6.dp), size = 13.sp)
    }
}

@Composable
private fun LockToggle(label: Int, tag: String, checked: Boolean, onChange: (Boolean) -> Unit) =
    SettingsRow(stringResource(label), { onChange(!checked) }) { Switch(checked, onChange, Modifier.testTag(tag), colors = lwSwitchColors()) }

/**
 * Covers the app while [AppLock.locked]; asks at once ([prompt]) and again from the button. The system prompt is a
 * bottom sheet, so everything sits in the top half. Cancelling the prompt, or back, closes the app.
 */
@Composable
fun LockScreen(prompt: Boolean) {
    val c = LocalContainer.current
    val activity = LocalActivity.current as FragmentActivity
    val brand = LocalBrand.current
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.lock_auth_open)
    fun unlock() { scope.launch { if (AppLock.authenticate(activity, title)) c.lock.locked.value = false else activity.finish() } }
    LaunchedEffect(prompt) { if (prompt) unlock() }
    BackHandler { activity.finish() }
    Box(
        Modifier.fillMaxSize().background(Lw.Background)
            .clickable(remember { MutableInteractionSource() }, null) {} // swallows taps meant for the screen underneath
            .testTag("lock-screen"),
    ) {
        PrismBackground(brand)
        Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 24.dp)) {
            BrandImage(brand.logotype, "logotype.svg", Modifier.padding(top = 24.dp).height(24.dp).width(110.dp).align(Alignment.CenterHorizontally), contentDescription = brand.name ?: "lawallet")
            Column(Modifier.fillMaxWidth().fillMaxHeight(0.5f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Box(Modifier.size(96.dp).glass(CircleShape, GlassLevel.Strong, tint = brand.t400.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    LwIcon(L.drawable.lucide_ic_fingerprint_pattern, size = 44.dp, tint = brand.t400)
                }
                Spacer(Modifier.height(24.dp))
                Txt(stringResource(R.string.lock_locked), size = 22.sp, weight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                MutedText(stringResource(R.string.lock_locked_body), align = TextAlign.Center)
                Spacer(Modifier.height(28.dp))
                LwButton(stringResource(R.string.lock_unlock), ::unlock, Modifier.fillMaxWidth().testTag("lock-unlock"), BtnVariant.Theme, icon = L.drawable.lucide_ic_fingerprint_pattern)
            }
        }
    }
}

/** Runs an action once the device lock passes when Locking → sensitive data is on, straight away otherwise. */
@Composable
fun rememberSensitiveGate(): (() -> Unit) -> Unit {
    val c = LocalContainer.current
    val activity = LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.lock_auth_sensitive)
    return { action -> if (!c.lock.sensitive) action() else scope.launch { if (AppLock.authenticate(activity, title)) action() } }
}

/** True when a payment of these sats may go ahead: under Locking's minimum, or the device lock passed. */
@Composable
fun rememberPaymentGate(): suspend (Long) -> Boolean {
    val c = LocalContainer.current
    val activity = LocalActivity.current as FragmentActivity
    val title = stringResource(R.string.lock_auth_pay)
    return { sats -> c.lock.allowPayment(activity, title, sats) }
}
