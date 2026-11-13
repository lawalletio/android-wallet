package io.lawallet.wallet.ui

import androidx.compose.ui.platform.testTag
import io.lawallet.wallet.app.Updater
import io.lawallet.wallet.ui.screens.UpdatesScreen
import io.lawallet.wallet.ui.components.LwAlertDialog
import io.lawallet.wallet.ui.components.ModalAction
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import io.lawallet.wallet.R
import io.lawallet.wallet.app.Diagnostics
import io.lawallet.wallet.core.diag.Trail
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.GlassLevel
import androidx.compose.foundation.shape.CircleShape
import io.lawallet.wallet.ui.screens.DiagnosticsScreen
import androidx.compose.animation.AnimatedContentScope
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.res.painterResource
import androidx.compose.foundation.Image
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.screens.*
import io.lawallet.wallet.ui.theme.LaWalletTheme
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.brandOf
import io.lawallet.wallet.ui.theme.LocalHaze
import io.lawallet.wallet.ui.theme.PrismBackground
import io.lawallet.wallet.ui.theme.glass
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import kotlinx.coroutines.delay
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow

val LocalContainer = staticCompositionLocalOf<AppContainer> { error("AppContainer not provided") }
val LocalNav = staticCompositionLocalOf<NavHostController> { error("NavController not provided") }
/** Shared-element scopes: the layout around the NavHost, and the destination's own enter/exit. */
@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }
val LocalDestScope = compositionLocalOf<AnimatedVisibilityScope?> { null }

/** The user's avatar, which flies between Home's header and the profile screen. */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Modifier.sharedAvatar(): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val dest = LocalDestScope.current ?: return this
    return with(shared) {
        this@sharedAvatar.sharedElement(
            rememberSharedContentState("me-avatar"), dest,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.82f, stiffness = 380f) },
            clipInOverlayDuringTransition = OverlayClip(CircleShape),
        )
    }
}

/** Home's address pill expands into the Lightning addresses screen's hero card (and folds back). */
@Composable
fun Modifier.sharedAddress(): Modifier {
    val shared = LocalSharedScope.current ?: return this
    val dest = LocalDestScope.current ?: return this
    return with(shared) {
        this@sharedAddress.sharedBounds(
            rememberSharedContentState("me-address"), dest,
            boundsTransform = { _, _ -> spring(dampingRatio = 0.86f, stiffness = 360f) },
            clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(24.dp)),
        )
    }
}

/** Routes mirror /wallet paths. */
object R0 {
    const val LANDING = "landing"
    const val SIGNUP = "signup"
    const val CREATE_ACCOUNT = "create-account"
    const val CREATE_PASSKEY = "create-passkey"
    const val LOGIN = "login"
    const val LOGIN_NOSTR = "login/nostr"
    const val LOGIN_BUNKER = "login/bunker"
    const val WELCOME = "welcome"
    const val HOME = "home"
    const val PROFILE = "profile"
    const val ADDRESSES = "addresses"
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
    const val REMOTE_WALLET_ADD = "settings/remote-wallets-add"
    const val CURRENCIES = "settings/currencies"
    const val PAYMENTS = "settings/payments"
    const val INSTANCE = "settings/instance"
    const val DIAGNOSTICS = "settings/diagnostics"
    const val UPDATES = "settings/updates"
    const val FEEDBACK = "settings/feedback"
    fun activity(hash: String) = "activity/$hash"
    fun activate(id: String) = "activate/$id"
    fun remoteWallet(id: String) = "settings/remote-wallets/$id"
    fun sweep(from: String, to: String) = "sweep/$from/$to"
    const val ONCHAIN_ADD = "onchain/add"
    const val ONCHAIN_CREATE = "onchain/create"
    const val ONCHAIN_IMPORT = "onchain/import"
    fun onchainWallet(id: String) = "onchain/wallet/$id"
    fun onchainBackup(id: String) = "onchain/backup/$id"
}

/** Deep links / notifications / NFC push routes or raw payloads here; the UI consumes them. */
object Intents {
    // Channels (not SharedFlows) so a link that arrives before the UI subscribes is not lost.
    val routes = Channel<String>(Channel.BUFFERED)
    val payloads = Channel<String>(Channel.BUFFERED)
    /** Prefix on a payload that came from an NFC tap, so the pay screen can say so. */
    const val NFC = "nfc:"
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
                PrismBackground(brand)
                val auth by container.session.auth.collectAsState()
                when (auth) {
                    AuthState.Loading -> LoadingScreen()
                    else -> AppNavHost(container, loggedIn = auth == AuthState.LoggedIn)
                }
            }
        }
    }
}

// Shared-axis fades: the leaving screen is gone before the arriving one shows, so the two never
// cross-fade over each other (Receive's white QR ghosted over Home's balance card). Time-based, so a
// slow first frame (Home recomposing on pop) can't stretch the overlap back in.
private val ScreenOut = fadeOut(tween(90, easing = FastOutLinearInEasing))
private val ScreenIn = fadeIn(tween(210, delayMillis = 90, easing = LinearOutSlowInEasing))
private val ProfileFadeIn = fadeIn(tween(260, delayMillis = 60, easing = LinearOutSlowInEasing))
private val ProfileFadeOut = fadeOut(tween(160, easing = FastOutLinearInEasing))
private val PopIn = ScreenIn + slideInHorizontally(tween(280, easing = EaseOutCubic)) { -it / 14 }
private val PopOut = ScreenOut + slideOutHorizontally(tween(280, easing = EaseOutCubic)) { it / 10 }

/** Publishes this tab screen's enter/exit progress (ScreenIn/ScreenOut timing) for the tab bar's alpha. */
@Composable
private fun AnimatedContentScope.TrackTab(route: String, into: MutableMap<String, State<Float>>) {
    val shown = transition.animateFloat(
        { if (targetState == EnterExitState.Visible) tween(210, delayMillis = 90, easing = LinearOutSlowInEasing) else tween(90, easing = FastOutLinearInEasing) },
        label = "tab",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    DisposableEffect(shown) { into[route] = shown; onDispose { into.remove(route) } }
}

/**
 * The bar's alpha is the tab screens' own enter/exit progress, so it moves with them, back swipe
 * included (`route` only flips once the swipe is released, so a route-driven bar lagged behind).
 * Its own composable: [shown] changes mid-swipe, and recomposing AppNavHost then breaks NavHost's
 * gesture seek (Receive went back to sitting opaque over Home).
 */
@Composable
private fun TabBar(route: String?, shown: Map<String, State<Float>>, nav: NavHostController, haze: HazeState, modifier: Modifier) {
    if (shown.isEmpty()) return
    val c = LocalContainer.current
    val client by c.wallet.client.collectAsState()
    val settings by c.session.settings.collectAsState()
    // Home's activity sheet rising swaps the tabs for search + filters, 1:1 with the finger.
    // Only Lightning has the sheet: swiping to Onchain swaps the search back to the tabs, 1:1 with the pager.
    val sheet = { if (route == R0.HOME) ActivitySearch.sheet * HomePager.position.coerceIn(0f, 1f) else ActivitySearch.sheet }
    val tabs by remember(route) { derivedStateOf { sheet() < 1f } }
    val search by remember(route) { derivedStateOf { sheet() > 0f } }
    val context = LocalContext.current
    val soon = stringResource(R.string.onchain_soon)
    val onchain = route == R0.HOME && HomePager.onchain
    // Both tabs composed = switching tabs: the bar stays put.
    Box(modifier.graphicsLayer { alpha = if (shown.size > 1) 1f else shown.values.maxOfOrNull { it.value } ?: 0f }, contentAlignment = Alignment.BottomCenter) {
        if (tabs) NavTabBar(
            // ponytail: onchain send/receive are UI-only for now; wire them when the wallets can derive addresses and sign.
            onReceive = { if (onchain) context.toast(soon) else nav.navigate(R0.RECEIVE) { launchSingleTop = true } },
            onSend = { if (onchain) context.toast(soon) else nav.navigate(R0.SEND) { launchSingleTop = true } },
            onScan = { nav.navigate(R0.SCAN) { launchSingleTop = true } },
            haze = haze,
            canReceive = onchain || client != null || settings?.lncurlAutoCreate == true,
            canSend = onchain || client != null,
            // Home tints it with the page tone, blending 1:1 with the swipe (Bitcoin orange ⇄ Lightning blue).
            accent = if (route == R0.HOME) ({ androidx.compose.ui.graphics.lerp(Lw.Bitcoin, Lw.Lightning, HomePager.position.coerceIn(0f, 1f)) }) else null,
            modifier = Modifier.graphicsLayer {
                val p = sheet()
                alpha = (1f - p * 2f).coerceIn(0f, 1f)
                translationY = p * 56.dp.toPx()
                scaleX = 1f - 0.1f * p; scaleY = scaleX
            },
        )
        if (search) ActivitySearchBar(
            haze,
            Modifier.graphicsLayer {
                val p = sheet()
                alpha = ((p - 0.45f) / 0.55f).coerceIn(0f, 1f)
                translationY = (1f - p) * 56.dp.toPx()
            },
        )
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
private fun AppNavHost(container: AppContainer, loggedIn: Boolean) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    // The route pattern (`activity/{hash}`), never its arguments. Traced over the transition and
    // the screen's first load; the transaction waits for its `nwc.request` children (deadline in Diagnostics.screen).
    LaunchedEffect(route) {
        route ?: return@LaunchedEffect
        Trail.add("navigation", route)
        val tx = Diagnostics.screen(route)
        try { delay(1000) } finally { tx?.finish() }
    }
    var notice by remember { mutableStateOf<PaymentNotice?>(null) }

    // Logging in navigates from the auth screens themselves (welcome / pending activation);
    // here we only bounce to the landing page when the session ends.
    LaunchedEffect(loggedIn) {
        if (!loggedIn && route != null && route !in PUBLIC_ROUTES) nav.navigate(R0.LANDING) { popUpTo(0) { inclusive = true } }
    }
    LaunchedEffect(Unit) { Intents.routes.receiveAsFlow().collect { r -> if (loggedIn || r.startsWith("activate/")) nav.navigate(r) { launchSingleTop = true } } }
    LaunchedEffect(loggedIn) { Intents.payloads.receiveAsFlow().collect { p -> handleScanPayload(container, nav, p.removePrefix(Intents.NFC), viaNfc = p.startsWith(Intents.NFC)) } }
    LaunchedEffect(loggedIn) {
        if (loggedIn) container.wallet.notices.collect { n -> notice = n; delay(2660); if (notice?.id == n.id) notice = null }
    }

    val haze = rememberHazeState()
    val tabShown = remember { mutableStateMapOf<String, State<Float>>() }
    val brand = LocalBrand.current
    CompositionLocalProvider(LocalNav provides nav, LocalHaze provides haze) {
        Box(Modifier.fillMaxSize()) {
          // Everything the floating glass (tab bar, notices) frosts over: the prism + the current screen.
          Box(Modifier.fillMaxSize().hazeSource(haze)) {
            PrismBackground(brand)
            // Read at draw time only: recomposing AppNavHost mid-transition breaks NavHost's gesture seek (see TabBar).
            val homeShown = animateFloatAsState(if (route == R0.HOME) 1f else 0f, tween(320), label = "homeBackdrop")
            HomeBackdrop { homeShown.value }
            // Fixed for the NavHost's lifetime: changing it would reset the back stack on login
            // (skipping the welcome screen / pending activation).
            val start = remember { if (loggedIn) R0.HOME else R0.LANDING }
            SharedTransitionLayout(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalSharedScope provides this) {
            NavHost(
                nav, startDestination = start, modifier = Modifier.fillMaxSize().safeDrawingPadding(),
                enterTransition = { ScreenIn + slideInHorizontally(tween(280, easing = EaseOutCubic)) { it / 10 } },
                exitTransition = { ScreenOut + slideOutHorizontally(tween(280, easing = EaseOutCubic)) { -it / 14 } },
                popEnterTransition = { PopIn },
                popExitTransition = { PopOut },
                // The back *gesture* (gesture nav + predictive back) has its own transitions, which
                // default to something else: Receive stayed opaque over Home for the whole swipe.
                predictivePopEnterTransition = { PopIn },
                predictivePopExitTransition = { PopOut },
            ) {
                composable(R0.LANDING) { LandingScreen() }
                composable(R0.SIGNUP) { SignupChoiceScreen() }
                composable(R0.CREATE_ACCOUNT) { CreateAccountScreen() }
                composable(R0.CREATE_PASSKEY) { CreatePasskeyScreen() }
                composable(R0.LOGIN) { LoginScreen() }
                composable(R0.LOGIN_NOSTR) { NostrLoginScreen() }
                composable(R0.LOGIN_BUNKER) { BunkerLoginScreen() }
                composable(R0.WELCOME) { WelcomeScreen() }
                // Home ↔ profile is a plain cross-fade, so the avatar flying between them is the motion.
                composable(
                    R0.HOME,
                    // Profile and Addresses grow out of Home's header (shared elements): plain fades around them.
                    exitTransition = { if (targetState.destination.route in setOf(R0.PROFILE, R0.ADDRESSES)) ProfileFadeOut else null },
                    popEnterTransition = { if (initialState.destination.route in setOf(R0.PROFILE, R0.ADDRESSES)) ProfileFadeIn else null },
                ) { TrackTab(R0.HOME, tabShown); CompositionLocalProvider(LocalDestScope provides this) { HomeScreen() } }
                composable(
                    R0.PROFILE,
                    enterTransition = { ProfileFadeIn }, popExitTransition = { ProfileFadeOut },
                ) { CompositionLocalProvider(LocalDestScope provides this) { ProfileScreen() } }
                composable(
                    R0.ADDRESSES,
                    enterTransition = { ProfileFadeIn }, popExitTransition = { ProfileFadeOut },
                ) { CompositionLocalProvider(LocalDestScope provides this) { LightningAddressesScreen() } }
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
                composable(R0.SETTINGS) { TrackTab(R0.SETTINGS, tabShown); SettingsScreen() }
                composable(R0.CARDS) { CardsScreen() }
                composable(R0.SECURITY) { SecurityScreen() }
                composable(R0.REMOTE_WALLETS) { RemoteWalletsScreen() }
                composable(R0.REMOTE_WALLET_ADD) { AddRemoteWalletScreen() }
                composable("sweep/{from}/{to}") { SweepScreen(it.arguments?.getString("from").orEmpty(), it.arguments?.getString("to").orEmpty()) }
                composable("settings/remote-wallets/{id}") { RemoteWalletDetailScreen(it.arguments?.getString("id").orEmpty()) }
                composable(R0.ONCHAIN_ADD) { AddOnchainWalletScreen() }
                composable(R0.ONCHAIN_CREATE) { CreateOnchainWalletScreen() }
                composable(R0.ONCHAIN_IMPORT) { ImportOnchainWalletScreen() }
                composable("onchain/wallet/{id}") { OnchainWalletDetailScreen(it.arguments?.getString("id").orEmpty()) }
                composable("onchain/backup/{id}") { OnchainBackupScreen(it.arguments?.getString("id").orEmpty()) }
                composable(R0.CURRENCIES) { CurrenciesScreen() }
                composable(R0.PAYMENTS) { PaymentsScreen() }
                composable(R0.INSTANCE) { InstanceScreen() }
                composable(R0.DIAGNOSTICS) { DiagnosticsScreen() }
                composable(R0.UPDATES) { UpdatesScreen() }
                composable(R0.FEEDBACK) { FeedbackScreen() }
            }
            }
            }
          }
            TabBar(route, tabShown, nav, haze, Modifier.align(Alignment.BottomCenter))
            // Over the header row (avatar/logo), so the pill never covers the balance it is announcing.
            val offline by container.session.offline.collectAsState()
            AnimatedVisibility(offline && loggedIn, Modifier.align(Alignment.TopCenter).statusBarsPadding(), enter = fadeIn(), exit = fadeOut()) {
                Box(Modifier.clip(CircleShape).background(Glass.Dialog).glass(CircleShape, GlassLevel.Thin, tint = Lw.Orange.copy(alpha = 0.18f)).padding(horizontal = 10.dp, vertical = 3.dp).testTag("offline-badge")) {
                    Txt(stringResource(R.string.offline_mode), size = 11.sp, color = Lw.Orange)
                }
            }
            // Home shows payments on its own balance (cue + odometer), so the pill stays away there.
            // …except on its Onchain page, where the Lightning balance is out of sight.
            AnimatedVisibility(notice != null && (route != R0.HOME || HomePager.onchain), Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 14.dp), enter = fadeIn() + slideInVertically { -it }, exit = fadeOut() + slideOutVertically { -it }) {
                notice?.let { PaymentNoticePill(it.type, it.tx.amountSats, haze) }
            }
        }
    }
    // A card recovery moved this account to another key: tell the old owner until they acknowledge.
    val me by container.session.me.collectAsState()
    if (loggedIn) me?.accountTransfer?.let { AccountTransferredDialog(it) }
    // The last run ended in a crash / ANR / kill and reports are off: ask once.
    val crashed by Diagnostics.prompt.collectAsState()
    if (crashed.isNotEmpty()) CrashReportDialog()
    // Checked on every app open (LaWalletApp); "Later" keeps that version quiet, a newer one prompts again.
    // Local debug builds are always "1.0.0" and can't install a release over themselves: no prompt (-PselfUpdate keeps it).
    val update by container.updater.state.collectAsState()
    val laterTapped by container.updater.dismissed.collectAsState()
    val localDebug = BuildConfig.DEBUG && !BuildConfig.SELF_UPDATE
    (update as? Updater.State.Available)?.takeIf { !localDebug && it.version != laterTapped && route != R0.UPDATES }?.let { UpdateDialog(it, container, nav) }
}

@Composable
private fun UpdateDialog(available: Updater.State.Available, container: AppContainer, nav: NavHostController) {
    val activity = androidx.activity.compose.LocalActivity.current
    LwAlertDialog(
        onDismissRequest = { container.updater.dismissed.value = available.version },
        title = { Txt(stringResource(R.string.update_prompt_title), size = 16.sp, weight = FontWeight.SemiBold) },
        text = {
            Txt(
                if (available.via == Updater.Via.PLAY) stringResource(R.string.update_available_play)
                else stringResource(R.string.update_prompt_body, available.version, BuildConfig.VERSION_NAME),
                color = Lw.MutedFg,
            )
        },
        confirmButton = {
            ModalAction(stringResource(R.string.update_prompt_update), {
                container.updater.dismissed.value = available.version
                // The download shows its progress on the Updates screen.
                if (available.via == Updater.Via.DOWNLOAD) nav.navigate(R0.UPDATES) { launchSingleTop = true }
                activity?.let { container.updater.start(it, container.scope) }
            }, Modifier.testTag("update-prompt-update"))
        },
        containerColor = Glass.Dialog,
    )
}

@Composable
private fun CrashReportDialog() {
    val context = LocalContext.current
    LwAlertDialog(
        onDismissRequest = { Diagnostics.decline() },
        title = { Txt(stringResource(R.string.diag_prompt_title), size = 16.sp, weight = FontWeight.SemiBold) },
        text = { Txt(stringResource(R.string.diag_prompt_body), color = Lw.MutedFg) },
        confirmButton = { ModalAction(stringResource(R.string.diag_prompt_send), { Diagnostics.accept(context) }) },
        containerColor = Glass.Dialog,
    )
}

private val PUBLIC_ROUTES = setOf(R0.LANDING, R0.SIGNUP, R0.CREATE_ACCOUNT, R0.CREATE_PASSKEY, R0.LOGIN, R0.LOGIN_NOSTR, R0.LOGIN_BUNKER, "activate/{id}")

/** wallet-loading-screen.tsx — isotype in an 80dp tile, indeterminate bar, version. */
@Composable
fun LoadingScreen() {
    val brand = LocalBrand.current
    Box(Modifier.fillMaxSize()) {
        // Same drawable + size as the system splash, so the hand-off doesn't jump.
        Image(painterResource(R.drawable.ic_splash), null, Modifier.size(240.dp).align(Alignment.Center))
        LinearProgressIndicator(
            Modifier.align(Alignment.Center).offset(y = 100.dp).width(160.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).testTag("splash-progress"),
            color = brand.t400, trackColor = Color.White.copy(alpha = 0.08f),
        )
        Txt("v${BuildConfig.VERSION_NAME}", Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 24.dp).testTag("splash-version"), size = 12.sp, color = Lw.MutedFg)
    }
}
