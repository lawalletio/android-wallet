package io.lawallet.wallet.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import io.lawallet.wallet.BuildConfig
import io.lawallet.wallet.app.AppContainer
import io.lawallet.wallet.app.AuthState
import io.lawallet.wallet.app.PaymentNotice
import io.lawallet.wallet.ui.components.BrandImage
import io.lawallet.wallet.ui.components.NavTabBar
import io.lawallet.wallet.ui.components.PaymentNoticePill
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.screens.*
import io.lawallet.wallet.ui.theme.LaWalletTheme
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.brandOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalNav = staticCompositionLocalOf<NavHostController> { error("NavController not provided") }

/** Routes mirror /wallet paths. */
object R0 {
    const val LANDING = "landing"
    const val SIGNUP = "signup"
    const val CREATE_ACCOUNT = "create-account"
    const val CREATE_PASSKEY = "create-passkey"
    const val LOGIN = "login"
    const val LOGIN_NOSTR = "login/nostr"
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val ACTIVITY = "activity"
    const val SEND = "send"
    const val SEND_AMOUNT = "send/amount"
    const val SEND_PREVIEW = "send/preview"
    const val SEND_SUMMARY = "send/summary"
    const val RECEIVE = "receive"
    const val RECEIVE_AMOUNT = "receive/amount"
    const val RECEIVE_INVOICE = "receive/invoice"
    const val RECEIVE_SUMMARY = "receive/summary"
    const val SCAN = "scan"
    const val WITHDRAW = "withdraw"
    const val CLAIM = "claim-username"
    const val SETTINGS = "settings"
    const val CARDS = "settings/cards"
    const val SECURITY = "settings/security"
    const val REMOTE_WALLETS = "settings/remote-wallets"
    const val CURRENCIES = "settings/currencies"
    const val LANGUAGE = "settings/language"
    const val SOUND = "settings/payment-sound"
    const val INSTANCE = "settings/instance"
    fun activity(hash: String) = "activity/$hash"
    fun activate(id: String) = "activate/$id"
    fun remoteWallet(id: String) = "settings/remote-wallets/$id"
}

/** Deep links / notifications / NFC push routes or raw payloads here; the UI consumes them. */
object Intents {
    // Channels (not SharedFlows) so a link that arrives before the UI subscribes is not lost.
    val routes = Channel<String>(Channel.BUFFERED)
    val payloads = Channel<String>(Channel.BUFFERED)
}

fun NavHostController.goHome() = navigate(R0.HOME) { popUpTo(0) { inclusive = true }; launchSingleTop = true }

/** After a login: resume a pending card activation (1h TTL), else [fallback] (postAuthPath). */
fun NavHostController.afterAuth(container: AppContainer, fallback: String = R0.HOME) {
    val pending = container.prefs.pendingActivation?.takeIf { System.currentTimeMillis() - it.second < 3_600_000 }?.first
    navigate(pending?.let { R0.activate(it) } ?: fallback) { popUpTo(0) { inclusive = true } }
}

@Composable
fun LaWalletRoot(container: AppContainer) {
    val settings by container.session.settings.collectAsState()
    val brand = settings.let { brandOf(it?.brand_theme, it?.brand_rounding, it?.community_name, it?.logotype_url, it?.isotypo_url) }
    LaWalletTheme(brand) {
        CompositionLocalProvider(LocalContainer provides container) {
            Box(Modifier.fillMaxSize().background(Lw.Background).semantics { testTagsAsResourceId = true }) {
                val auth by container.session.auth.collectAsState()
                when (auth) {
                    AuthState.Loading -> LoadingScreen()
                    else -> AppNavHost(container, loggedIn = auth == AuthState.LoggedIn)
                }
            }
        }
    }
}

@Composable
private fun AppNavHost(container: AppContainer, loggedIn: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    var notice by remember { mutableStateOf<PaymentNotice?>(null) }

    // Logging in navigates from the auth screens themselves (welcome / pending activation);
    // here we only bounce to the landing page when the session ends.
    LaunchedEffect(loggedIn) {
        if (!loggedIn && route != null && route !in PUBLIC_ROUTES) nav.navigate(R0.LANDING) { popUpTo(0) { inclusive = true } }
    }
    LaunchedEffect(Unit) { Intents.routes.receiveAsFlow().collect { r -> if (loggedIn || r.startsWith("activate/")) nav.navigate(r) { launchSingleTop = true } } }
    LaunchedEffect(loggedIn) { Intents.payloads.receiveAsFlow().collect { p -> handleScanPayload(container, nav, p) } }
    LaunchedEffect(loggedIn) {
        if (loggedIn) container.wallet.notices.collect { n -> notice = n; delay(3800); if (notice?.id == n.id) notice = null }
    }

    CompositionLocalProvider(LocalNav provides nav) {
        Box(Modifier.fillMaxSize()) {
            // Fixed for the NavHost's lifetime: changing it would reset the back stack on login
            // (skipping the welcome screen / pending activation).
            val start = remember { if (loggedIn) R0.HOME else R0.LANDING }
            NavHost(nav, startDestination = start, modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                composable(R0.LANDING) { LandingScreen() }
                composable(R0.SIGNUP) { SignupChoiceScreen() }
                composable(R0.CREATE_ACCOUNT) { CreateAccountScreen() }
                composable(R0.CREATE_PASSKEY) { CreatePasskeyScreen() }
                composable(R0.LOGIN) { LoginScreen() }
                composable(R0.LOGIN_NOSTR) { NostrLoginScreen() }
                composable(R0.WELCOME) { WelcomeScreen() }
                composable(R0.HOME) { HomeScreen() }
                composable(R0.ACTIVITY) { ActivityScreen() }
                composable("activity/{hash}") { ActivityDetailScreen(it.arguments?.getString("hash").orEmpty()) }
                composable(R0.SEND) { SendRecipientScreen() }
                composable(R0.SEND_AMOUNT) { SendAmountScreen() }
                composable(R0.SEND_PREVIEW) { SendPreviewScreen() }
                composable(R0.SEND_SUMMARY) { SendSummaryScreen() }
                composable(R0.RECEIVE) { ReceiveAddressScreen() }
                composable(R0.RECEIVE_AMOUNT) { ReceiveAmountScreen() }
                composable(R0.RECEIVE_INVOICE) { ReceiveInvoiceScreen() }
                composable(R0.RECEIVE_SUMMARY) { ReceiveSummaryScreen() }
                composable(R0.SCAN) { ScanScreen() }
                composable(R0.WITHDRAW) { WithdrawScreen() }
                composable("${R0.CLAIM}?from={from}&bonus={bonus}") { ClaimUsernameScreen(it.arguments?.getString("from"), it.arguments?.getString("bonus") == "1") }
                composable("activate/{id}") { ActivateScreen(it.arguments?.getString("id").orEmpty()) }
                composable(R0.SETTINGS) { SettingsScreen() }
                composable(R0.CARDS) { CardsScreen() }
                composable(R0.SECURITY) { SecurityScreen() }
                composable(R0.REMOTE_WALLETS) { RemoteWalletsScreen() }
                composable("settings/remote-wallets/{id}") { RemoteWalletDetailScreen(it.arguments?.getString("id").orEmpty()) }
                composable(R0.CURRENCIES) { CurrenciesScreen() }
                composable(R0.LANGUAGE) { LanguageScreen() }
                composable(R0.SOUND) { PaymentSoundScreen() }
                composable(R0.INSTANCE) { InstanceScreen() }
            }
            if (route == R0.HOME || route == R0.SETTINGS) {
                NavTabBar(
                    current = if (route == R0.HOME) "home" else "settings",
                    onHome = { nav.navigate(R0.HOME) { popUpTo(R0.HOME); launchSingleTop = true } },
                    onSettings = { nav.navigate(R0.SETTINGS) { popUpTo(R0.HOME); launchSingleTop = true } },
                    onScan = { nav.navigate(R0.SCAN) },
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
            AnimatedVisibility(notice != null, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
                notice?.let { PaymentNoticePill(it.type, it.tx.amountSats) }
            }
        }
    }
}

private val PUBLIC_ROUTES = setOf(R0.LANDING, R0.SIGNUP, R0.CREATE_ACCOUNT, R0.CREATE_PASSKEY, R0.LOGIN, R0.LOGIN_NOSTR, "activate/{id}")

/** wallet-loading-screen.tsx — isotype in an 80dp tile, indeterminate bar, version. */
@Composable
fun LoadingScreen() {
    val brand = LocalBrand.current
    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        Box(Modifier.size(80.dp).clip(RoundedCornerShape(24.dp)).background(Lw.Card), contentAlignment = Alignment.Center) {
            BrandImage(brand.isotype, "isotype.svg", Modifier.size(48.dp))
        }
        Spacer(Modifier.height(24.dp))
        LinearProgressIndicator(Modifier.width(160.dp).height(4.dp).clip(RoundedCornerShape(2.dp)), color = brand.t300, trackColor = Lw.Card)
        Spacer(Modifier.height(16.dp))
        Txt("v${BuildConfig.VERSION_NAME}", size = 12.sp, color = Lw.MutedFg)
    }
}
