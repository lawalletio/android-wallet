package io.lawallet.wallet.ui.screens

import io.lawallet.wallet.ui.components.SettingsPage
import io.lawallet.wallet.ui.components.lwFieldColors
import io.lawallet.wallet.ui.components.ConfirmDialog
import io.lawallet.wallet.ui.components.SettingsSwitchRow
import android.net.Uri
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.os.LocaleListCompat
import io.lawallet.wallet.BuildConfig
import io.lawallet.wallet.R
import io.lawallet.wallet.app.Diagnostics
import io.lawallet.wallet.app.Updater
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.service.PaymentSound
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.BrandImage
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.GroupDivider
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.SettingsGroup
import io.lawallet.wallet.ui.components.SettingsHeader
import io.lawallet.wallet.ui.components.SettingsRow
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.copyToClipboard
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.composables.icons.lucide.R as L

@Composable
fun SettingsScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val brand = LocalBrand.current
    var confirm by remember { mutableStateOf(false) }
    val bg by c.prefs.backgroundListener.collectAsState()
    Column(Modifier.fillMaxSize()) {
        SettingsHeader(stringResource(R.string.settings_title), { nav.popBackStack() }) { LanguageMenu() }
        Spacer(Modifier.height(12.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 140.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
            SettingsGroup(stringResource(R.string.settings_account)) {
                SettingsRow(stringResource(R.string.settings_myCards), { nav.navigate(R0.CARDS) }, Modifier.testTag("settings-cards"), icon = L.drawable.lucide_ic_credit_card)
                GroupDivider()
                SettingsRow(stringResource(R.string.settings_security), { nav.navigate(R0.SECURITY) }, Modifier.testTag("settings-security"), icon = L.drawable.lucide_ic_shield)
                GroupDivider()
                SettingsRow(stringResource(R.string.lock_title), { nav.navigate(R0.LOCKING) }, Modifier.testTag("settings-locking"), icon = L.drawable.lucide_ic_fingerprint_pattern)
            }
            SettingsGroup(stringResource(R.string.settings_preferences)) {
                SettingsRow(stringResource(R.string.payments_title), { nav.navigate(R0.PAYMENTS) }, Modifier.testTag("settings-payments"), icon = L.drawable.lucide_ic_zap)
                GroupDivider()
                SettingsRow(stringResource(R.string.onchain_settings_title), { nav.navigate(R0.ONCHAIN_SETTINGS) }, Modifier.testTag("settings-onchain"), icon = L.drawable.lucide_ic_bitcoin)
                GroupDivider()
                SettingsSwitchRow(stringResource(R.string.bg_listener_title), bg, { c.prefs.setBackgroundListener(it) }, icon = L.drawable.lucide_ic_bell)
                GroupDivider()
                SettingsRow(stringResource(R.string.instance_title), { nav.navigate(R0.INSTANCE) }, Modifier.testTag("settings-instance"), icon = L.drawable.lucide_ic_server, value = c.session.instanceHost)
                // ponytail: debug (or -Pplugins) builds only until plugins are signed (docs/plugins.md "Later").
                if (BuildConfig.DEBUG || BuildConfig.PLUGINS) {
                    GroupDivider()
                    SettingsRow(stringResource(R.string.plugins_title), { nav.navigate(R0.PLUGINS) }, Modifier.testTag("settings-plugins"), icon = L.drawable.lucide_ic_puzzle)
                }
            }
            SettingsGroup(stringResource(R.string.settings_support)) {
                // NIP-04 needs the raw key: Amber logins don't get the row.
                if (c.session.secretHex() != null) {
                    SettingsRow(stringResource(R.string.feedback_title), { nav.navigate(R0.FEEDBACK) }, Modifier.testTag("settings-feedback"), icon = L.drawable.lucide_ic_message_square)
                    GroupDivider()
                }
                SettingsRow(stringResource(R.string.diag_title), { nav.navigate(R0.DIAGNOSTICS) }, Modifier.testTag("settings-diagnostics"), icon = L.drawable.lucide_ic_activity)
                GroupDivider()
                SettingsRow(stringResource(R.string.update_title), { nav.navigate(R0.UPDATES) }, Modifier.testTag("settings-updates"), icon = L.drawable.lucide_ic_download, value = "v${BuildConfig.VERSION_NAME}")
            }
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                BrandImage(brand.isotype, "isotype.svg", Modifier.size(32.dp))
                Txt(brand.name ?: stringResource(R.string.settings_communityBy), size = 13.sp, color = Lw.MutedFg)
                Txt(stringResource(R.string.settings_version, BuildConfig.VERSION_NAME), size = 12.sp, color = Lw.MutedFg)
            }
            SettingsGroup(stringResource(R.string.settings_dangerZone)) {
                SettingsRow(stringResource(R.string.settings_removeWallet), { confirm = true }, Modifier.testTag("settings-remove"), labelColor = Lw.Red, icon = L.drawable.lucide_ic_log_out)
            }
        }
    }
    if (confirm) ConfirmDialog(
        stringResource(R.string.settings_removeWalletConfirm), stringResource(R.string.settings_removeWallet), { confirm = false; c.session.logout() }, { confirm = false },
        destructive = true, actionModifier = Modifier.testTag("confirm-remove"),
    )
}

@Composable
fun CurrenciesScreen() {
    val c = LocalContainer.current
    val state by c.prefs.currencies.collectAsState()
    var query by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    fun update(active: List<String>) {
        val next = state.copy(active = active, selected = state.selected.takeIf { it in active } ?: "SAT", localEdit = true)
        c.prefs.setCurrencies(next)
        scope.launch { runCatching { c.session.api.value.setCurrencyPrefs(io.lawallet.wallet.core.api.CurrencyPrefs(next.active, next.selected)) } }
    }
    SettingsPage(stringResource(R.string.settings_currenciesScreen_title)) {
        SettingsGroup(stringResource(R.string.settings_currenciesScreen_activeAssets)) {
            val rowPx = with(androidx.compose.ui.platform.LocalDensity.current) { 60.dp.toPx() } // a SettingsRow's height
            var dragging by remember { mutableStateOf<String?>(null) }
            var dragY by remember { mutableStateOf(0f) }
            state.active.forEachIndexed { i, code ->
                if (i > 0) GroupDivider()
                SettingsRow(
                    code, null,
                    Modifier.then(if (dragging == code) Modifier.offset { IntOffset(0, dragY.roundToInt()) }.background(Color.White.copy(alpha = 0.10f)) else Modifier).testTag("active-$code"),
                    subtitle = if (code == "SAT") stringResource(R.string.settings_currenciesScreen_alwaysActive) else Currencies.displayName(code),
                    leading = {
                        if (code == "SAT") LwIcon(L.drawable.lucide_ic_lock, size = 18.dp, tint = Lw.MutedFg)
                        else Box(Modifier.size(28.dp).pointerInput(state.active) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { dragging = code; dragY = 0f },
                                onDragEnd = {
                                    val from = state.active.indexOf(code)
                                    val to = (from + (dragY / rowPx).roundToInt()).coerceIn(1, state.active.size - 1)
                                    if (to != from) update(state.active.toMutableList().apply { add(to, removeAt(from)) })
                                    dragging = null; dragY = 0f
                                },
                                onDragCancel = { dragging = null; dragY = 0f },
                            ) { _, d -> dragY += d.y }
                        }, contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_grip_vertical, size = 18.dp, tint = Lw.MutedFg) }
                    },
                ) {
                    if (code != "SAT") Box(Modifier.size(28.dp).clip(CircleShape).background(Lw.Red.copy(alpha = 0.15f)).clickable { update(state.active - code) }.testTag("remove-$code"), contentAlignment = Alignment.Center) {
                        LwIcon(L.drawable.lucide_ic_minus, size = 16.dp, tint = Lw.Red)
                    }
                }
            }
        }
        MutedText(stringResource(R.string.settings_currenciesScreen_hint), size = 12.sp)
        OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), placeholder = { Txt(stringResource(R.string.settings_currenciesScreen_searchPlaceholder), color = Lw.MutedFg) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = lwFieldColors(),
            leadingIcon = { LwIcon(L.drawable.lucide_ic_search, size = 18.dp, tint = Lw.MutedFg) })
        SettingsGroup(stringResource(R.string.settings_currenciesScreen_available)) {
            val available = Currencies.CATALOG.filter { it !in state.active }.filter { query.isBlank() || it.contains(query, true) || Currencies.displayName(it).contains(query, true) }
            if (available.isEmpty()) MutedText(stringResource(R.string.settings_currenciesScreen_noMatch), Modifier.padding(16.dp))
            available.forEachIndexed { i, code ->
                if (i > 0) GroupDivider()
                SettingsRow("$code · ${Currencies.displayName(code)}", { update(state.active + code) }, Modifier.testTag("add-$code")) {
                    Box(Modifier.size(28.dp).clip(CircleShape).background(Lw.Green.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_plus, size = 16.dp, tint = Lw.Green) }
                }
            }
        }
    }
}

/** Settings' top-right language picker: a glass pill with the current language, its options in a dropdown. */
@Composable
private fun LanguageMenu() {
    val current = remember { AppCompatDelegate.getApplicationLocales().toLanguageTags().substringBefore('-').ifEmpty { "auto" } } // a binder call on 13+
    var selected by remember { mutableStateOf(current) }
    var open by remember { mutableStateOf(false) }
    val deviceLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
    val options = listOf("auto" to stringResource(R.string.settings_languageScreen_automatic), "en" to "English", "es" to "Español")
    Box {
        Row(
            Modifier.height(40.dp).glass(CircleShape).clickable { open = true }.padding(start = 12.dp, end = 10.dp).testTag("settings-language"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LwIcon(L.drawable.lucide_ic_languages, size = 18.dp)
            Spacer(Modifier.width(8.dp))
            Txt(if (selected == "auto") stringResource(R.string.settings_languageScreen_automatic) else selected.uppercase(), size = 14.sp, weight = FontWeight.SemiBold)
            Spacer(Modifier.width(4.dp))
            LwIcon(L.drawable.lucide_ic_chevron_down, size = 16.dp, tint = Lw.MutedFg)
        }
        DropdownMenu(open, { open = false }, containerColor = Glass.Dialog, shape = RoundedCornerShape(16.dp)) {
            options.forEach { (tag, label) ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Txt(label, size = 15.sp, weight = if (tag == selected) FontWeight.SemiBold else FontWeight.Normal)
                            if (tag == "auto") Txt(deviceLocale.displayLanguage, size = 12.sp, color = Lw.MutedFg)
                        }
                    },
                    onClick = {
                        open = false; selected = tag
                        AppCompatDelegate.setApplicationLocales(if (tag == "auto") LocaleListCompat.getEmptyLocaleList() else LocaleListCompat.forLanguageTags(tag))
                    },
                    trailingIcon = { if (tag == selected) LwIcon(L.drawable.lucide_ic_check, size = 18.dp, tint = LocalBrand.current.t400) },
                    modifier = Modifier.testTag("language-$tag"),
                )
            }
        }
    }
}

/** The payment sound choice (tapping one also previews it); lives on the Payments page. */
@Composable
private fun PaymentSoundGroup() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val sound by c.prefs.paymentSound.collectAsState()
    run {
        SettingsGroup(stringResource(R.string.settings_paymentSound)) {
            listOf(
                "chime" to (R.string.settings_paymentSoundScreen_sounds_chime_label to R.string.settings_paymentSoundScreen_sounds_chime_description),
                "bananero" to (R.string.settings_paymentSoundScreen_sounds_bananero_label to R.string.settings_paymentSoundScreen_sounds_bananero_description),
                "off" to (R.string.settings_paymentSoundScreen_sounds_off_label to R.string.settings_paymentSoundScreen_sounds_off_description),
            ).forEachIndexed { i, (k, labels) ->
                if (i > 0) GroupDivider()
                SettingsRow(stringResource(labels.first), { c.prefs.setPaymentSound(k); PaymentSound.play(context, k) }, Modifier.testTag("sound-$k"), subtitle = stringResource(labels.second)) {
                    RadioButton(sound == k, null, colors = RadioButtonDefaults.colors(selectedColor = LocalBrand.current.t400))
                }
            }
        }
    }
}

@Composable
fun InstanceScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(c.session.instanceUrl) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val invalid = stringResource(R.string.instance_invalid)
    val switched = stringResource(R.string.instance_switched)
    fun switch(target: String) {
        loading = true; error = null
        scope.launch {
            runCatching { c.session.switchInstance(target) }
                .onSuccess { context.toast(switched.format(it)); nav.popBackStack() }
                .onFailure { error = invalid + (it.message?.let { m -> "\n$m" } ?: "") }
            loading = false
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        SettingsHeader(stringResource(R.string.instance_title), { nav.popBackStack() })
        Column(Modifier.weight(1f).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            MutedText(stringResource(R.string.instance_description))
            SettingsGroup { SettingsRow(stringResource(R.string.instance_current), null, value = c.session.instanceUrl) }
            OutlinedTextField(url, { url = it.trim(); error = null }, Modifier.fillMaxWidth().testTag("instance-input"), enabled = !loading, label = { Txt(stringResource(R.string.instance_url)) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = lwFieldColors(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            ErrorText(error, Modifier.fillMaxWidth())
            if (c.session.instanceUrl != BuildConfig.DEFAULT_INSTANCE) LwButton(stringResource(R.string.instance_reset, BuildConfig.DEFAULT_INSTANCE.removePrefix("https://")), { url = BuildConfig.DEFAULT_INSTANCE; switch(BuildConfig.DEFAULT_INSTANCE) }, Modifier.fillMaxWidth(), BtnVariant.Ghost)
        }
        LwButton(stringResource(R.string.instance_save), { switch(url) }, Modifier.fillMaxWidth().padding(16.dp).testTag("instance-save"), BtnVariant.Theme, enabled = url.isNotBlank(), loading = loading)
    }
}

@Composable
fun PaymentsScreen() {
    val c = LocalContainer.current
    val slide by c.prefs.slideToPay.collectAsState()
    val auto by c.prefs.nfcAutoPay.collectAsState()
    var maxText by remember { mutableStateOf(auto.maxSats.toString()) }
    SettingsPage(stringResource(R.string.payments_title)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsGroup {
                SettingsSwitchRow(stringResource(R.string.slide_to_pay_title), slide, { c.prefs.setSlideToPay(it) }, Modifier.testTag("slide-to-pay"), icon = L.drawable.lucide_ic_chevrons_right)
            }
            MutedText(stringResource(R.string.slide_to_pay_body), Modifier.padding(horizontal = 6.dp), size = 12.sp)
            Spacer(Modifier.height(16.dp))
            SettingsGroup {
                SettingsSwitchRow(stringResource(R.string.nfc_autopay_title), auto.enabled, { c.prefs.setNfcAutoPay(auto.copy(enabled = it)) }, icon = L.drawable.lucide_ic_nfc, switchModifier = Modifier.testTag("nfc-autopay"))
                if (auto.enabled) OutlinedTextField(maxText, { v ->
                    maxText = v.filter(Char::isDigit).take(10)
                    c.prefs.setNfcAutoPay(auto.copy(maxSats = maxText.toLongOrNull() ?: 0))
                }, Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp).testTag("nfc-autopay-max"), label = { Txt(stringResource(R.string.nfc_autopay_max)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), colors = lwFieldColors())
            }
            MutedText(stringResource(R.string.nfc_autopay_body), Modifier.padding(horizontal = 6.dp), size = 12.sp)
            Spacer(Modifier.height(16.dp))
            PaymentSoundGroup()
        }
    }
}

@Composable
fun DiagnosticsScreen() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val reports by c.prefs.crashReports.collectAsState()
    val exits = remember { Diagnostics.exits(c.prefs).asReversed() }
    val copied = stringResource(R.string.diag_copied)
    SettingsPage(stringResource(R.string.diag_title)) {
        if (Diagnostics.available) Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsGroup {
                // BETA builds always report: the switch is shown on and locked (the row used to toggle it anyway).
                SettingsSwitchRow(stringResource(R.string.diag_crash_reports), reports, { c.prefs.setCrashReports(it) }, Modifier.testTag("diag-reports"), icon = L.drawable.lucide_ic_bug, enabled = !BuildConfig.BETA)
            }
            MutedText(stringResource(if (BuildConfig.BETA) R.string.diag_crash_reports_beta else R.string.diag_crash_reports_body), Modifier.padding(horizontal = 6.dp), size = 12.sp)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsGroup(stringResource(R.string.diag_exits)) {
                when {
                    android.os.Build.VERSION.SDK_INT < 30 -> MutedText(stringResource(R.string.diag_exits_unsupported), Modifier.padding(16.dp))
                    exits.isEmpty() -> MutedText(stringResource(R.string.diag_exits_empty), Modifier.padding(16.dp))
                    else -> exits.forEachIndexed { i, e ->
                        if (i > 0) GroupDivider()
                        SettingsRow(e.reason.replace('_', ' ').uppercase(), null, Modifier.testTag("exit-$i"), labelColor = if (e.abnormal) Lw.Red else Lw.Foreground, subtitle = Diagnostics.summary(e))
                    }
                }
            }
            MutedText(stringResource(R.string.diag_exits_hint), Modifier.padding(horizontal = 6.dp), size = 12.sp)
        }
        LwButton(stringResource(R.string.diag_copy), { context.copyToClipboard(Diagnostics.report(c.prefs), copied) }, Modifier.fillMaxWidth().testTag("diag-copy"), BtnVariant.Secondary)
    }
}

@Composable
fun UpdatesScreen() {
    val c = LocalContainer.current
    val activity = androidx.activity.compose.LocalActivity.current
    val scope = rememberCoroutineScope()
    val state by c.updater.state.collectAsState()
    LaunchedEffect(Unit) { if (state is Updater.State.Idle || state is Updater.State.Failed) c.updater.checkNow() }
    SettingsPage(stringResource(R.string.update_title)) {
        Column(Modifier.fillMaxWidth().glass(RoundedCornerShape(20.dp)).padding(16.dp).testTag("update-status"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Txt(stringResource(R.string.update_installed, BuildConfig.VERSION_NAME), size = 13.sp, color = Lw.MutedFg)
            when (val s = state) {
                Updater.State.Idle, Updater.State.Checking -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spinner(16.dp); Txt(stringResource(R.string.update_checking))
                }
                Updater.State.UpToDate -> Txt(stringResource(R.string.update_latest), weight = FontWeight.Medium)
                is Updater.State.Available -> {
                    Txt(if (s.via == Updater.Via.PLAY) stringResource(R.string.update_available_play) else stringResource(R.string.update_available, s.version), weight = FontWeight.Medium)
                    val label = when (s.via) { Updater.Via.PLAY -> R.string.update_play; Updater.Via.DOWNLOAD -> R.string.update_download; Updater.Via.RELEASE_PAGE -> R.string.update_page }
                    LwButton(stringResource(label), { activity?.let { c.updater.start(it, c.scope) } }, Modifier.fillMaxWidth().testTag("update-start"), BtnVariant.Theme, icon = L.drawable.lucide_ic_download)
                }
                is Updater.State.Downloading -> {
                    Txt(stringResource(R.string.update_downloading, (s.progress * 100).roundToInt()))
                    androidx.compose.material3.LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth(), color = LocalBrand.current.t400, trackColor = Color.White.copy(alpha = 0.08f))
                }
                Updater.State.Installing -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Spinner(16.dp); Txt(stringResource(R.string.update_installing))
                }
                is Updater.State.Failed -> ErrorText(stringResource(R.string.update_failed, s.reason))
            }
        }
        val busy = state is Updater.State.Checking || state is Updater.State.Downloading || state is Updater.State.Installing
        LwButton(stringResource(R.string.update_check), { scope.launch { c.updater.checkNow() } }, Modifier.fillMaxWidth().testTag("update-check"), BtnVariant.Secondary, enabled = !busy)
    }
}
