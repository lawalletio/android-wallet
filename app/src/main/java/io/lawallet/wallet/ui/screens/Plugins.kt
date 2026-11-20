package io.lawallet.wallet.ui.screens

import io.lawallet.wallet.ui.components.SettingsPage
import io.lawallet.wallet.ui.components.lwFieldColors
import io.lawallet.wallet.ui.components.ConfirmDialog
import io.lawallet.wallet.ui.components.DialogTitle
import io.lawallet.wallet.ui.components.lwSwitchColors
import android.annotation.SuppressLint
import android.content.ClipboardManager
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceResponse
import androidx.core.net.toUri
import android.webkit.WebResourceRequest
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import io.lawallet.wallet.BuildConfig
import io.lawallet.wallet.R
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardActions
import io.lawallet.wallet.core.plugin.PromptSpec
import io.lawallet.wallet.core.plugin.PromptRequest
import io.lawallet.wallet.ui.components.WalletMark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.text.font.FontWeight
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.app.AppContainer
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.plugin.InstalledPlugin
import io.lawallet.wallet.core.plugin.WebLoginRequest
import io.lawallet.wallet.core.plugin.PluginCandidate
import io.lawallet.wallet.core.plugin.PluginManifest
import io.lawallet.wallet.core.plugin.PluginSource
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.GroupDivider
import io.lawallet.wallet.ui.components.LwAlertDialog
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.ModalAction
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.SettingsGroup
import io.lawallet.wallet.ui.components.SettingsHeader
import io.lawallet.wallet.ui.components.SettingsRow
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.autoFocus
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.components.toastError
import io.lawallet.wallet.ui.theme.Lw
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import com.composables.icons.lucide.R as L

/** Settings → Plugins (debug builds for now): what's installed, and Add Plugin from a URL. Contract: docs/plugins.md. */
@Composable
fun PluginsScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val installed by c.plugins.installed.collectAsState()
    val accounts by c.plugins.accounts.collectAsState()
    var adding by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<String?>(null) }
    // Who each plugin is signed in as (a login or a removal updates it too).
    LaunchedEffect(installed.map { it.id to it.sha256 }) { installed.filter { it.enabled }.forEach { launch { c.plugins.refreshAccount(it.id) } } }
    SettingsPage(stringResource(R.string.plugins_title)) {
        MutedText(stringResource(R.string.plugins_description))
        SettingsGroup(stringResource(R.string.plugins_installed)) {
            if (installed.isEmpty()) SettingsRow(stringResource(R.string.plugins_empty), null)
            installed.forEachIndexed { i, p ->
                if (i > 0) GroupDivider()
                val account = accounts[p.id]?.takeIf { p.enabled && it.signedIn }
                SettingsRow(p.manifest.name, { open = p.id }, Modifier.testTag("plugin-${p.id}"),
                    value = account?.label ?: stringResource(R.string.plugins_version, p.manifest.version)) {
                    if (account != null) {
                        LwIcon(L.drawable.lucide_ic_circle_check, Modifier.testTag("plugin-signed-in-${p.id}"), size = 18.dp, tint = Lw.Green)
                        Spacer(Modifier.width(10.dp))
                    }
                    Switch(p.enabled, { c.plugins.setEnabled(p.id, it) }, colors = lwSwitchColors())
                }
            }
        }
        LwButton(stringResource(R.string.plugins_add), { adding = true }, Modifier.fillMaxWidth().testTag("plugins-add"), BtnVariant.Theme)
        if (BuildConfig.DEBUG) LocalPlugins()
    }
    if (adding) AddPluginDialog({ adding = false }) { url -> adding = false; nav.navigate(R0.pluginAdd(url)) }
    open?.let { id -> installed.find { it.id == id }?.let { PluginActions(it) { open = null } } ?: run { open = null } }
}

/**
 * Debug builds: the plugins scripts/plugins-dev.py serves from plugins-dev/ (gitignored), installed or reloaded in one
 * tap, so a plugin can be edited on the Mac and tried at once. No permission sheet here: it's the developer's own code.
 */
@Composable
private fun LocalPlugins() {
    val c = LocalContainer.current
    val context = LocalContext.current
    val installed by c.plugins.installed.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    val found by produceState<Result<List<PluginCandidate>>?>(null, refresh) {
        value = null
        value = runCatching { c.plugins.resolver.resolveDev(BuildConfig.PLUGINS_DEV_URL) }
    }
    var busy by remember { mutableStateOf<String?>(null) }
    val failed = stringResource(R.string.plugins_install_failed)
    val reloaded = stringResource(R.string.plugins_reloaded)
    SettingsGroup(stringResource(R.string.plugins_local)) {
        when (val r = found) {
            null -> Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { Spinner() }
            else -> r.fold(
                { list ->
                    if (list.isEmpty()) SettingsRow(stringResource(R.string.plugins_local_empty), null)
                    list.forEachIndexed { i, p ->
                        if (i > 0) GroupDivider()
                        val id = p.manifest.id
                        val have = installed.any { it.id == id && it.candidate.source == p.source }
                        SettingsRow(p.manifest.name, null, Modifier.testTag("local-$id"), value = stringResource(R.string.plugins_version, p.manifest.version)) {
                            LwButton(stringResource(if (have) R.string.plugins_reload else R.string.plugins_install), {
                                busy = id
                                c.scope.launch {
                                    runCatching { c.plugins.install(p) }
                                        .onSuccess {
                                            context.toast(reloaded.format(p.manifest.name))
                                            if (!have && it.enabled && it.manifest.permissions.webLogin != null) signIn(c, context, it.manifest)
                                        }
                                        .onFailure { e -> context.toastError(e, failed) }
                                    busy = null
                                    refresh++
                                }
                            }, Modifier.padding(start = 6.dp).testTag("local-install-$id"), BtnVariant.Secondary, loading = busy == id)
                        }
                    }
                },
                { MutedText(stringResource(R.string.plugins_local_offline, BuildConfig.PLUGINS_DEV_URL), Modifier.padding(14.dp), size = 13.sp) },
            )
        }
        GroupDivider()
        SettingsRow(stringResource(R.string.plugins_refresh), { refresh++ }, Modifier.testTag("local-refresh"), icon = L.drawable.lucide_ic_refresh_cw)
    }
}

@Composable
private fun AddPluginDialog(onDismiss: () -> Unit, onContinue: (String) -> Unit) {
    val context = LocalContext.current
    var url by remember { mutableStateOf("") }
    val valid = PluginSource.parse(url) != null
    LwAlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(stringResource(R.string.plugins_add)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MutedText(stringResource(R.string.plugins_add_hint))
                OutlinedTextField(
                    url, { url = it.trim() }, Modifier.fillMaxWidth().autoFocus().testTag("plugin-url"),
                    label = { Txt(stringResource(R.string.plugins_url)) }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = lwFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    trailingIcon = {
                        if (url.isEmpty()) LwButton(stringResource(R.string.plugins_paste), {
                            context.getSystemService(ClipboardManager::class.java).primaryClip?.getItemAt(0)?.text?.let { url = it.toString().trim() }
                        }, Modifier.padding(end = 6.dp), BtnVariant.Ghost)
                    },
                )
            }
        },
        confirmButton = { ModalAction(stringResource(R.string.plugins_continue), { onContinue(url) }, Modifier.testTag("plugin-continue"), enabled = valid) },
    )
}

/** Sign in, update, remove. */
@Composable
private fun PluginActions(p: InstalledPlugin, onDismiss: () -> Unit) {
    val c = LocalContainer.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    val upToDate = stringResource(R.string.plugins_up_to_date, p.manifest.name)
    val updated = stringResource(R.string.plugins_updated)
    val failed = stringResource(R.string.plugins_install_failed)
    val signedOut = stringResource(R.string.plugins_signed_out_of, p.manifest.name)
    val signOutFailed = stringResource(R.string.plugins_sign_out_failed, p.manifest.name)
    if (confirmRemove) {
        ConfirmDialog(
            stringResource(R.string.plugins_remove_confirm, p.manifest.name), stringResource(R.string.plugins_remove), { c.plugins.remove(p.id); confirmRemove = false; onDismiss() }, { confirmRemove = false },
            destructive = true, actionModifier = Modifier.testTag("plugin-remove-confirm"),
        )
        return
    }
    // Asked again on open: the session can change while the screen sits (a login elsewhere, a token that expired).
    LaunchedEffect(p.id) { c.plugins.refreshAccount(p.id) }
    LwAlertDialog(
        onDismissRequest = onDismiss,
        icon = { PluginIcon(p.id, url = p.candidate.iconUrl) },
        title = { DialogTitle(p.manifest.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val account by c.plugins.accounts.collectAsState()
                account[p.id]?.let { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (a.signedIn) { LwIcon(L.drawable.lucide_ic_circle_check, size = 16.dp, tint = Lw.Green); Spacer(Modifier.width(6.dp)) }
                        Txt(
                            if (a.signedIn) stringResource(R.string.plugins_signed_in_as, a.label ?: p.manifest.name) else stringResource(R.string.plugins_signed_out),
                            size = 14.sp, color = if (a.signedIn) Lw.Foreground else Lw.MutedFg,
                        )
                    }
                }
                if (account[p.id]?.signedIn == true && p.enabled && p.manifest.isWallet) PluginBalance(p.id)
                if (p.manifest.description.isNotBlank()) MutedText(p.manifest.description)
                MutedText(stringResource(R.string.plugins_version, p.manifest.version) + " · " + p.candidate.source.removePrefix("https://"), size = 12.sp)
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Unknown (no `account()`, or it failed) is neither: offering Sign in would claim it's signed out.
                val signedIn = c.plugins.accounts.collectAsState().value[p.id]?.signedIn
                if (signedIn == true) {
                    LwButton(stringResource(R.string.plugins_sign_out), {
                        busy = true
                        c.scope.launch {
                            val ok = c.plugins.logout(p.id)
                            context.toast(if (ok) signedOut else signOutFailed)
                            busy = false
                        }
                    }, Modifier.fillMaxWidth().testTag("plugin-sign-out"), BtnVariant.Secondary, loading = busy)
                } else if (signedIn == false && p.manifest.permissions.webLogin != null) {
                    ModalAction(stringResource(R.string.plugins_sign_in), { onDismiss(); signIn(c, context, p.manifest) }, Modifier.testTag("plugin-sign-in"))
                }
                LwButton(stringResource(R.string.plugins_check_update), {
                    busy = true
                    scope.launch {
                        runCatching {
                            val src = PluginSource.parse(p.candidate.source) ?: error("bad source")
                            val next = c.plugins.resolver.resolve(src).find { it.manifest.id == p.id } ?: error("gone")
                            if (next.manifest.version == p.manifest.version && next.ref == p.candidate.ref) null else c.plugins.install(next)
                        }.onSuccess { context.toast(it?.let { n -> updated.format(n.manifest.name, n.manifest.version) } ?: upToDate) }
                            .onFailure { context.toast(failed) }
                        busy = false
                    }
                }, Modifier.fillMaxWidth(), BtnVariant.Secondary, loading = busy)
                LwButton(stringResource(R.string.plugins_remove), { confirmRemove = true }, Modifier.fillMaxWidth().testTag("plugin-remove"), BtnVariant.Ghost)
            }
        },
    )
}

/** A wallet plugin's balance, read live: BTC, with sats under it. A failure shows the plugin's own message. */
@Composable
private fun PluginBalance(id: String) {
    val c = LocalContainer.current
    val sats by produceState<Result<Long>?>(null, id) {
        value = runCatching { (c.plugins.wallet(id) ?: error("plugin off")).getBalanceMsat() / 1000 }
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp).testTag("plugin-balance")) {
        MutedText(stringResource(R.string.plugins_balance), size = 12.sp)
        when (val r = sats) {
            null -> Spinner(16.dp)
            else -> r.fold(
                {
                    Txt("${Currencies.format(it, "BTC", null)} BTC", size = 22.sp, weight = FontWeight.SemiBold)
                    MutedText(stringResource(R.string.common_sats, Currencies.formatSats(it)), size = 13.sp)
                },
                { e -> Txt(stringResource(R.string.plugins_balance_failed, e.message ?: e.javaClass.simpleName), size = 13.sp, color = Lw.Red) },
            )
        }
    }
}

/**
 * Runs the plugin's `login()` in the app scope (it outlives this screen: the web login opens on top of it), then
 * says how it went.
 */
internal fun signIn(c: AppContainer, context: android.content.Context, m: PluginManifest) {
    // Resolved now, in the screen's locale: the launch below outlives the screen (up to the 15 min login timeout) and
    // must not hold its Activity.
    val signedIn = context.getString(R.string.plugins_signed_in, m.name)
    val failed = context.getString(R.string.plugins_sign_in_failed, m.name)
    val app = context.applicationContext
    c.scope.launch {
        val ok = c.plugins.login(m.id)
        app.toast(if (ok) signedIn else failed)
        if (ok) c.wallet.refreshBalance()
    }
}

/**
 * A plugin's `host.prompt()`: a security code or a short answer it needs to go on (an emailed or 2FA code before a
 * payment). Over whatever is on screen; says which plugin asks. Cancel rejects the plugin's prompt with CANCELLED.
 */
@Composable
fun PluginPromptDialog(r: PromptRequest) = key(r) {
    var text by remember { mutableStateOf("") }
    val code = r.spec.input == "code"
    fun submit() { if (text.isNotBlank()) r.result.complete(text.trim()) }
    LwAlertDialog(
        onDismissRequest = { r.result.complete(null) },
        icon = { PluginIcon(r.pluginId) },
        title = { DialogTitle(r.spec.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                MutedText(stringResource(R.string.plugins_prompt_from, r.pluginName), size = 12.sp)
                r.spec.message?.let { MutedText(it) }
                OutlinedTextField(
                    text, { text = if (code) it.filter(Char::isLetterOrDigit).take(12) else it.take(200) },
                    Modifier.fillMaxWidth().autoFocus().testTag("plugin-prompt-input"),
                    singleLine = true, isError = r.spec.error != null, shape = RoundedCornerShape(12.dp), colors = lwFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = if (code) KeyboardType.Number else KeyboardType.Text, imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { submit() }),
                )
                r.spec.error?.let { ErrorText(it, Modifier.fillMaxWidth()) }
            }
        },
        confirmButton = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ModalAction(stringResource(R.string.plugins_prompt_continue), ::submit, Modifier.testTag("plugin-prompt-continue"), enabled = text.isNotBlank())
                if (r.spec.resend) LwButton(stringResource(R.string.plugins_prompt_resend), { r.result.complete(PromptSpec.RESEND) }, Modifier.fillMaxWidth().testTag("plugin-prompt-resend"), BtnVariant.Ghost)
            }
        },
    )
}

/** A plugin's icon: the copy stored at install, else [url], else a generic puzzle piece. */
@Composable
internal fun PluginIcon(id: String?, modifier: Modifier = Modifier, url: String? = null, size: Dp = 40.dp) {
    val c = LocalContainer.current
    val icons by c.plugins.iconVersion.collectAsState() // an icon fetched after this was first shown
    val file = remember(id, icons) { id?.let { c.plugins.icon(it) } }
    Box(modifier.size(size).clip(RoundedCornerShape(size / 4)), contentAlignment = Alignment.Center) {
        if (file != null || url != null) AsyncImage(file ?: url, null, Modifier.fillMaxSize())
        else LwIcon(L.drawable.lucide_ic_puzzle, size = size * 0.55f)
    }
}

/** A wallet card's mark: the plugin's icon for a plugin wallet, else the NWC / LNCurl mark. */
@Composable
internal fun DeckMark(w: RemoteWallet, size: Dp, modifier: Modifier = Modifier) =
    if (w.isPlugin) PluginIcon(w.provider, size = size, modifier = modifier) else WalletMark(w.isLncurl, size, modifier)

/** Wallet plugins that can be added as a wallet (enabled, sign-in based, not signed in yet), for the add-wallet choices. */
@Composable
internal fun addablePluginWallets(): List<InstalledPlugin> {
    val c = LocalContainer.current
    val installed by c.plugins.installed.collectAsState()
    val accounts by c.plugins.accounts.collectAsState()
    return installed.filter { it.enabled && it.manifest.isWallet && it.manifest.permissions.webLogin != null && accounts[it.id]?.signedIn != true }
}

/** The plugins a pasted URL offers: a GitHub repo's directory, or the one manifest it points at. */
@Composable
fun PluginAddScreen(url: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val installed by c.plugins.installed.collectAsState()
    val found by produceState<Result<List<PluginCandidate>>?>(null, url) {
        value = runCatching { c.plugins.resolver.resolve(PluginSource.parse(url) ?: error("Not a plugin URL")) }
    }
    var confirm by remember { mutableStateOf<PluginCandidate?>(null) }
    var installing by remember { mutableStateOf(false) }
    val failed = stringResource(R.string.plugins_install_failed)
    SettingsPage(stringResource(R.string.plugins_add)) {
        MutedText(url.removePrefix("https://"), size = 13.sp)
        when (val r = found) {
            null -> Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) { Spinner() }
            else -> r.fold(
                { list ->
                    SettingsGroup(stringResource(R.string.plugins_available)) {
                        list.forEachIndexed { i, p ->
                            if (i > 0) GroupDivider()
                            val have = installed.find { it.id == p.manifest.id }
                            val upToDate = have != null && have.manifest.version == p.manifest.version && have.candidate.ref == p.ref
                            CandidateRow(p, when { have == null -> stringResource(R.string.plugins_install); upToDate -> null; else -> stringResource(R.string.plugins_update) }) { confirm = p }
                        }
                    }
                },
                { e -> ErrorText(e.message ?: failed, Modifier.fillMaxWidth()) },
            )
        }
    }
    confirm?.let { p ->
        LwAlertDialog(
            onDismissRequest = { if (!installing) confirm = null },
            icon = { PluginIcon(null, url = p.iconUrl) },
            title = { DialogTitle(stringResource(R.string.plugins_install_title, p.manifest.name)) },
            text = { Permissions(p.manifest) },
            confirmButton = {
                ModalAction(stringResource(R.string.plugins_install), {
                    installing = true
                    c.scope.launch {
                        runCatching { c.plugins.install(p) }
                            .onSuccess {
                                confirm = null
                                nav.popBackStack(R0.PLUGINS, inclusive = false)
                                // Signing in opens over the list (AppNav shows the request).
                                if (it.manifest.permissions.webLogin != null && it.enabled) signIn(c, context, it.manifest)
                            }
                            .onFailure { e -> context.toastError(e, failed) }
                        installing = false
                    }
                }, Modifier.testTag("plugin-install"), loading = installing)
            },
        )
    }
}

@Composable
private fun CandidateRow(p: PluginCandidate, action: String?, onClick: () -> Unit) {
    SettingsRow(p.manifest.name, action?.let { { onClick() } }, Modifier.testTag("candidate-${p.manifest.id}"), value = stringResource(R.string.plugins_version, p.manifest.version)) {
        if (action != null) LwButton(action, onClick, Modifier.padding(start = 6.dp), BtnVariant.Secondary)
        else Txt(stringResource(R.string.plugins_is_installed), size = 13.sp, color = Lw.MutedFg)
    }
    if (p.manifest.description.isNotBlank()) MutedText(p.manifest.description, Modifier.padding(start = 14.dp, end = 14.dp, bottom = 12.dp), size = 13.sp)
}

/** What installing grants, from the manifest. */
@Composable
private fun Permissions(m: PluginManifest) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (m.description.isNotBlank()) MutedText(m.description)
        if (m.permissions.hosts.isNotEmpty()) Txt("• " + stringResource(R.string.plugins_perm_hosts, m.permissions.hosts.joinToString()), size = 14.sp)
        m.permissions.webLogin?.url?.toHttpUrlOrNull()?.let { Txt("• " + stringResource(R.string.plugins_perm_login, it.host), size = 14.sp) }
        if (m.permissions.secrets) Txt("• " + stringResource(R.string.plugins_perm_secrets), size = 14.sp)
        if (m.pollSec > 0) Txt("• " + pluralStringResource(R.plurals.plugins_perm_background, m.pollSec, m.pollSec), size = 14.sp)
        if (m.isWallet) MutedText(stringResource(R.string.plugins_wallet_no_address), size = 13.sp)
        if (m.isWallet) Txt(stringResource(R.string.plugins_perm_warning), size = 13.sp, color = Lw.Orange)
    }
}

/**
 * A plugin's web login: its declared site in a WebView, where the user signs in themself (the app never sees the
 * password). The plugin's extract script runs there until it returns a value, which goes back to the plugin. Other
 * sites can't be opened in the main frame, and the WebView's cookies and storage are wiped on the way out.
 *
 * Its own window (a full-screen dialog), not a route: screens are drawn through Haze's blur layer, which a WebView
 * can't render into (it showed black). Shown by AppNav while [WebLoginRequest] is pending; closes when it completes.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun PluginLoginDialog(r: WebLoginRequest) {
    fun cancel() { r.result.complete(null) }
    Dialog(::cancel, DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        key(r) { PluginLogin(r, ::cancel) }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun PluginLogin(r: WebLoginRequest, cancel: () -> Unit) {
    val host = r.url.toHttpUrlOrNull()?.host.orEmpty()
    DisposableEffect(Unit) {
        onDispose {
            r.result.complete(null)
            CookieManager.getInstance().removeAllCookies(null)
            WebStorage.getInstance().deleteAllData()
        }
    }
    var web by remember { mutableStateOf<WebView?>(null) }
    // Polls rather than waiting for a page load: single-page logins finish without one (and a 2FA or new-device step
    // keeps the page on screen until the site stores the full session, which is what the extract script waits for).
    LaunchedEffect(web) {
        val w = web ?: return@LaunchedEffect
        var waiting = false
        while (!r.result.isCompleted) {
            w.evaluateJavascript("(function(){ try { return (${r.extractJs}); } catch (e) { return null; } })()") { out ->
                val value = runCatching { (Json.parseToJsonElement(out) as? JsonPrimitive)?.takeIf { it.isString }?.content }.getOrNull()
                if (value != null) { loginStep("session found, handing it to the plugin"); r.result.complete(value) }
                else if (!waiting) { waiting = true; loginStep("waiting for the session (sign in, then any 2FA / device code)") }
            }
            delay(1_000)
        }
    }
    // The site's own domain (panel.example.com → example.com): its login may hop between subdomains. Anything else is blocked.
    val site = r.url.toHttpUrlOrNull()?.topPrivateDomain()
    fun allowed(u: android.net.Uri) = u.scheme == "https" && u.host?.let { h -> h == host || (site != null && (h == site || h.endsWith(".$site"))) } == true
    Column(Modifier.fillMaxSize().background(Lw.Background).systemBarsPadding().imePadding()) {
        SettingsHeader(stringResource(R.string.plugins_login_title, r.pluginName), cancel)
        MutedText(stringResource(R.string.plugins_login_host, host), Modifier.padding(horizontal = 16.dp, vertical = 4.dp), size = 12.sp)
        AndroidView(
            factory = { ctx ->
                WebView(ctx).apply {
                    // AndroidView's default is WRAP_CONTENT, which makes CSS 100vh = 0: pages sized off the viewport
                    // collapse to nothing and show blank.
                    layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    // reCAPTCHA runs in a google.com frame inside the site's page.
                    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                    webChromeClient = object : WebChromeClient() {
                        override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                            if (m.messageLevel() == ConsoleMessage.MessageLevel.ERROR) loginStep("page error: ${m.message().take(200)}")
                            return true
                        }
                    }
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
                            if (!req.isForMainFrame || allowed(req.url)) return false
                            loginStep("blocked navigation to ${req.url.safe()}")
                            return true
                        }
                        override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) = loginStep("loading ${url.toUri().safe()}")
                        override fun onPageFinished(view: WebView, url: String) = loginStep("loaded ${url.toUri().safe()}")
                        // Single-page apps change routes without a load: the login's steps (2FA, device code) show here.
                        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = loginStep("at ${url.toUri().safe()}")
                        override fun onReceivedError(view: WebView, req: WebResourceRequest, err: WebResourceError) {
                            if (req.isForMainFrame) loginStep("failed ${req.url.safe()}: ${err.errorCode} ${err.description}")
                        }
                        override fun onReceivedHttpError(view: WebView, req: WebResourceRequest, res: WebResourceResponse) {
                            if (req.isForMainFrame || res.statusCode >= 500) loginStep("HTTP ${res.statusCode} ${req.url.safe()}")
                        }
                        override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                            loginStep("page crashed (renderer gone)")
                            r.result.complete(null)
                            return true
                        }
                    }
                    loginStep("opening ${r.url.toUri().safe()}")
                    loadUrl(r.url)
                    web = this
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f).testTag("plugin-login-web"),
            onRelease = { it.destroy() },
        )
    }
}

/**
 * Debug builds: each web-login step to logcat (`adb logcat -s PluginLogin`). Origin and path only: query strings and
 * fragments can carry tokens, and nothing the user types is ever read.
 */
private fun loginStep(msg: String) { if (BuildConfig.DEBUG) android.util.Log.i("PluginLogin", msg) }
private fun android.net.Uri.safe() = "$scheme://$host${path.orEmpty()}"
