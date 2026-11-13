package io.lawallet.wallet.ui.screens

import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
    var open by remember { mutableStateOf(!(lock.onOpen || lock.sensitive || lock.payments)) }
    val authTitle = stringResource(R.string.lock_auth_settings)
    val unavailable = stringResource(R.string.lock_unavailable)
    LaunchedEffect(Unit) { if (!open) { if (AppLock.authenticate(activity, authTitle)) open = true else nav.popBackStack() } }
    var minText by remember { mutableStateOf(lock.paymentsMinSats.toString()) }
    // Turning a lock on needs one to ask for; turning it off is already behind the gate above.
    fun set(on: Boolean, next: LockPrefs) { if (on && !AppLock.available(context)) context.toast(unavailable) else c.prefs.setLock(next) }
    SettingsPage(stringResource(R.string.lock_title)) {
        if (!open) return@SettingsPage
        MutedText(stringResource(R.string.lock_hint), Modifier.padding(horizontal = 6.dp), size = 13.sp)
        SettingsGroup {
            SettingsRow(stringResource(R.string.lock_sensitive), { set(!lock.sensitive, lock.copy(sensitive = !lock.sensitive)) }, icon = L.drawable.lucide_ic_eye_off) {
                Switch(lock.sensitive, { set(it, lock.copy(sensitive = it)) }, Modifier.testTag("lock-sensitive"), colors = lwSwitchColors())
            }
            MutedText(stringResource(R.string.lock_sensitive_hint), Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), size = 13.sp)
        }
        SettingsGroup {
            SettingsRow(stringResource(R.string.lock_open), { set(!lock.onOpen, lock.copy(onOpen = !lock.onOpen)) }, icon = L.drawable.lucide_ic_lock) {
                Switch(lock.onOpen, { set(it, lock.copy(onOpen = it)) }, Modifier.testTag("lock-open"), colors = lwSwitchColors())
            }
            GroupDivider()
            SettingsRow(stringResource(R.string.lock_payments), { set(!lock.payments, lock.copy(payments = !lock.payments)) }, icon = L.drawable.lucide_ic_zap) {
                Switch(lock.payments, { set(it, lock.copy(payments = it)) }, Modifier.testTag("lock-payments"), colors = lwSwitchColors())
            }
            if (lock.payments) Column(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(minText, { v ->
                    minText = v.filter(Char::isDigit).take(10)
                    c.prefs.setLock(lock.copy(paymentsMinSats = minText.toLongOrNull() ?: 0))
                }, Modifier.fillMaxWidth().testTag("lock-min-amount"), label = { Txt(stringResource(R.string.lock_min_amount)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = lwFieldColors())
                MutedText(stringResource(R.string.lock_min_amount_hint), size = 13.sp)
            }
        }
    }
}

/** Covers the app while [AppLock.locked]; asks at once and again from the button. Back leaves the app. */
@Composable
fun LockScreen() {
    val c = LocalContainer.current
    val activity = LocalActivity.current as FragmentActivity
    val scope = rememberCoroutineScope()
    val title = stringResource(R.string.lock_auth_open)
    fun unlock() { scope.launch { if (AppLock.authenticate(activity, title)) c.lock.locked.value = false } }
    LaunchedEffect(Unit) { unlock() }
    BackHandler { activity.moveTaskToBack(true) }
    Column(
        Modifier.fillMaxSize().background(Lw.Background)
            .clickable(remember { MutableInteractionSource() }, null) {} // swallows taps meant for the screen underneath
            .systemBarsPadding().padding(32.dp).testTag("lock-screen"),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        LwIcon(L.drawable.lucide_ic_fingerprint_pattern, size = 56.dp, tint = LocalBrand.current.t400)
        Spacer(Modifier.height(20.dp))
        Txt(stringResource(R.string.lock_locked), size = 20.sp, weight = FontWeight.SemiBold)
        Spacer(Modifier.height(32.dp))
        LwButton(stringResource(R.string.lock_unlock), ::unlock, Modifier.fillMaxWidth().testTag("lock-unlock"), BtnVariant.Theme)
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
    return { sats -> !c.lock.paymentNeedsAuth(sats) || AppLock.authenticate(activity, title) }
}
