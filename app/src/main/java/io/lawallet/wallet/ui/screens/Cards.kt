package io.lawallet.wallet.ui.screens

import android.text.format.DateUtils
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.annotation.StringRes
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import io.lawallet.wallet.R
import io.lawallet.wallet.app.AppContainer
import io.lawallet.wallet.app.AuthState
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.core.api.WalletCard
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.components.CardShadow
import io.lawallet.wallet.ui.components.EmptyState
import io.lawallet.wallet.ui.components.GroupDivider
import io.lawallet.wallet.ui.components.LoadingBox
import io.lawallet.wallet.ui.components.LoadingDots
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.Pill
import io.lawallet.wallet.ui.components.SettingsGroup
import io.lawallet.wallet.ui.components.ScreenHeader
import io.lawallet.wallet.ui.components.SettingsRow
import io.lawallet.wallet.ui.components.SettingsSwitchRow
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.components.toastError
import io.lawallet.wallet.ui.awaitSettled
import io.lawallet.wallet.ui.rememberSettled
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.core.fx.Rates
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import java.time.Instant
import kotlin.math.abs
import com.composables.icons.lucide.R as L

/** A card stood upright: ISO/IEC 7810 ID-1 (85.6 × 54 mm), width over height. */
private const val CARD_ASPECT = 1f / 1.586f
private val CardShape = RoundedCornerShape(16.dp)
/** How far each card behind the front one shows past the card in front of it. */
private val PEEK = 22.dp

/** Active cards first, then disabled, then blocked; by name within each (as the web list). */
private val cardOrder = compareBy<WalletCard>({ it.blocked }, { it.disabled }, { cardName(it, "") })

private const val CARDS_KEY = "cards"
private val cardsSer = ListSerializer(WalletCard.serializer())

/**
 * The account's card whose chip UID is [uid] (hex, as the tag reports it; LaWallet's `ntag424.cid`), or null. The
 * cached list first; on a miss a fresh one, since the card may have been activated since (a stranger's card waits
 * for that one request before going on to Withdraw).
 */
internal suspend fun findOwnCard(c: AppContainer, uid: String): WalletCard? {
    if (c.session.auth.first { it != AuthState.Loading } != AuthState.LoggedIn) return null
    fun List<WalletCard>.mine() = find { it.ntag424?.cid.equals(uid, ignoreCase = true) }
    c.prefs.cached(CARDS_KEY, cardsSer)?.mine()?.let { return it }
    val fresh = runCatching { c.session.api.value.cards() }.getOrNull() ?: return null
    c.prefs.setCached(CARDS_KEY, cardsSer, fresh)
    return fresh.mine()
}

private fun cardName(card: WalletCard, fallback: String) = card.title?.trim()?.ifEmpty { null } ?: card.design?.description?.trim()?.ifEmpty { null } ?: fallback

/**
 * My Cards as a 3D deck: swipe sideways through the cards, the front one's options sit under it. A [HorizontalPager]
 * gives the fling, snapping and accessibility; its pages are then drawn into a stack (cards still to come wait behind
 * the front one, fanned out to the right; the one swiped away tilts off to the left). Every transform is read in a
 * graphicsLayer, so a swipe never recomposes.
 */
@Composable
fun CardsScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    // Requests live here, not in the options panel: that one leaves composition when the deck moves on.
    val scope = rememberCoroutineScope()
    var cards by remember { mutableStateOf(c.prefs.cached(CARDS_KEY, cardsSer)?.sortedWith(cardOrder)) }
    var wallets by remember { mutableStateOf(c.remoteWallets.cached.orEmpty()) }
    var failed by remember { mutableStateOf(false) }
    val fallback = stringResource(R.string.settings_cardsScreen_fallbackName)
    val updateFailed = stringResource(R.string.settings_cardsScreen_updateFailed)
    val linkFailed = stringResource(R.string.settings_cardsScreen_linkFailed)
    val enabledFmt = stringResource(R.string.settings_cardsScreen_enabledToast)
    val disabledFmt = stringResource(R.string.settings_cardsScreen_disabledToast)
    val linkedFmt = stringResource(R.string.cards_linkedTo)
    LaunchedEffect(Unit) {
        runCatching { c.session.api.value.cards() }
            .onSuccess { cards = it.sortedWith(cardOrder); failed = false; c.prefs.setCached(CARDS_KEY, cardsSer, it) }
            .onFailure { failed = true }
    }
    LaunchedEffect(Unit) { c.remoteWallets.fetch()?.let { wallets = it } }
    // Each card spends from its wallet, and the picker lists the rest: the last known balances at once, then live
    // ones once the screen is in, each wallet asked once per visit (in [scope]: the list arriving restarts the effect).
    var balances by remember { mutableStateOf(c.remoteWallets.balances) }
    var fetching by remember { mutableStateOf(emptySet<String>()) }
    val asked = remember { mutableSetOf<String>() }
    val wanted = cards.orEmpty().mapNotNullTo(mutableSetOf()) { it.remoteWalletId } + wallets.filter { it.isActive && !it.isPlugin }.map { it.id }
    LaunchedEffect(wanted) {
        nav.awaitSettled()
        (wanted - asked).forEach { id ->
            asked += id
            scope.launch {
                fetching += id
                runCatching { c.remoteWallets.balance(id) }.onSuccess { balances += (id to it) }
                fetching -= id
            }
        }
    }

    // Optimistic: the deck shows the change at once and takes it back if LaWallet refuses. Order stays put meanwhile.
    fun put(card: WalletCard) {
        cards = cards?.map { if (it.id == card.id) card else it }?.also { c.prefs.setCached(CARDS_KEY, cardsSer, it) }
    }
    fun toggle(card: WalletCard, on: Boolean) {
        val name = cardName(card, fallback)
        put(card.copy(disabled = !on))
        scope.launch {
            runCatching { c.session.api.value.setCardEnabled(card.id, on) }
                .onSuccess { put(it); context.toast((if (on) enabledFmt else disabledFmt).format(name)) }
                .onFailure { put(card); context.toastError(it, updateFailed) }
        }
    }
    fun bind(card: WalletCard, wallet: RemoteWallet) {
        if (wallet.id == card.remoteWalletId) return
        put(card.copy(remoteWalletId = wallet.id))
        scope.launch {
            runCatching { c.session.api.value.bindCardToWallet(card.id, wallet.id) }
                .onSuccess { put(it); context.toast(linkedFmt.format(cardName(card, fallback), wallet.name)) }
                .onFailure { put(card); context.toastError(it, linkFailed) }
        }
    }

    Column(Modifier.fillMaxSize()) {
        ScreenHeader(stringResource(R.string.settings_myCards), { nav.popBackStack() })
        val list = cards
        when {
            list == null && failed -> Column(Modifier.padding(20.dp)) {
                Txt(stringResource(R.string.settings_cardsScreen_errorTitle), weight = FontWeight.SemiBold)
                MutedText(stringResource(R.string.settings_cardsScreen_tryAgain))
            }
            list == null -> LoadingBox(Modifier.padding(top = 64.dp))
            list.isEmpty() -> EmptyState(L.drawable.lucide_ic_credit_card, stringResource(R.string.settings_cardsScreen_emptyTitle), stringResource(R.string.settings_cardsScreen_emptyBody), Modifier.padding(top = 48.dp))
            else -> CardDeck(list, wallets, balances, fetching, fallback, ::toggle, ::bind)
        }
    }
}

@Composable
private fun CardDeck(
    cards: List<WalletCard>, wallets: List<RemoteWallet>, balances: Map<String, Long>, fetching: Set<String>, fallback: String,
    onToggle: (WalletCard, Boolean) -> Unit, onBind: (WalletCard, RemoteWallet) -> Unit,
) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val glow = LocalBrand.current.t400
    // Opened by tapping one of these cards on the phone: the deck opens on it.
    val first = remember { cards.indexOfFirst { it.id == c.flow.focusCard.value }.coerceAtLeast(0) }
    val pager = rememberPagerState(first) { cards.size }
    val pulse = remember { Animatable(0f) }
    // A tapped card (MainActivity → FlowStore.focusCard) comes to the front, wherever the deck is, and bounces once.
    LaunchedEffect(cards) {
        c.flow.focusCard.filterNotNull().collect { id ->
            val i = cards.indexOfFirst { it.id == id }
            if (i < 0) return@collect // not in this list yet: the refresh brings it, and this runs again
            c.flow.focusCard.value = null
            if (i != pager.currentPage) pager.animateScrollToPage(i)
            nav.awaitSettled()
            haptics.performHapticFeedback(HapticFeedbackType.Confirm)
            pulse.snapTo(1f)
            pulse.animateTo(0f, spring(dampingRatio = 0.35f, stiffness = 380f))
        }
    }
    // The cards behind wait squared up under the front one until the screen has slid in, then fan out.
    val settled = rememberSettled()
    val deal = remember { Animatable(0f) }
    LaunchedEffect(settled) { if (settled) deal.animateTo(1f, spring(dampingRatio = 0.68f, stiffness = 120f)) }
    LaunchedEffect(pager) { snapshotFlow { pager.currentPage }.drop(1).collect { haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) } }

    // No scrolling: the options keep their height and the deck takes the rest, the card sized to fit it.
    Column(Modifier.fillMaxSize().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f)) {
            // Room above and below for the tilt; never wider than 60% of the screen, so the fan shows beside it.
            val cardW = minOf((maxHeight - 40.dp) * CARD_ASPECT, maxWidth * 0.6f, 300.dp).coerceAtLeast(48.dp)
            // The front card sits a little left of centre so the fan behind it balances the deck.
            val side = (maxWidth - cardW) / 2
            HorizontalPager(
                pager,
                Modifier.fillMaxSize().testTag("cards-deck").drawBehind {
                    val r = size.height * 0.5f
                    val at = Offset(size.width / 2, size.height / 2)
                    drawCircle(Brush.radialGradient(listOf(glow.copy(alpha = 0.20f), Color.Transparent), at, r), r, at)
                },
                contentPadding = PaddingValues(start = side - PEEK, end = side + PEEK),
                pageSize = PageSize.Fixed(cardW),
                beyondViewportPageCount = 2,
                flingBehavior = PagerDefaults.flingBehavior(pager, snapAnimationSpec = spring(dampingRatio = 0.82f, stiffness = 260f)),
                key = { cards[it].id },
            ) { page ->
                val card = cards[page]
                DeckCard(
                    card, card.design?.imageUrl?.let { absolute(c.session.instanceUrl, it) }, cardName(card, fallback),
                    depth = { page - pager.currentPage - pager.currentPageOffsetFraction }, deal = { deal.value }, pulse = { pulse.value },
                    // Lower pages on top: the swiped-away card passes over the deck, the next ones wait under it.
                    modifier = Modifier.zIndex(-page.toFloat()).pointerInput(page) {
                        detectTapGestures { if (page != pager.currentPage) scope.launch { pager.animateScrollToPage(page) } }
                    },
                )
            }
        }
        if (cards.size > 1) DeckDots(pager, cards.size)
        Spacer(Modifier.height(16.dp))
        // One set of controls that follows the front card: its name cross-fades, the balance rolls to the next
        // card's figure, the switch slides to its state.
        val card = cards[pager.currentPage.coerceIn(cards.indices)]
        CardOptions(card, cardName(card, fallback), wallets, balances, fetching, onToggle, onBind)
    }
}

/**
 * One card in the deck. [depth] is where it stands (0 front, 1 the next one behind, -1 swiped away, fractions in
 * between), read only while drawing; [deal] fans the cards behind out on entry; [pulse] bounces the front one.
 */
@Composable
private fun DeckCard(card: WalletCard, imageUrl: String?, name: String, depth: () -> Float, deal: () -> Float, pulse: () -> Float, modifier: Modifier = Modifier) {
    val brand = LocalBrand.current
    val saturation by animateFloatAsState(if (card.blocked || card.disabled) 0f else 1f, tween(450), label = "card-saturation")
    Box(
        modifier.fillMaxWidth().aspectRatio(CARD_ASPECT)
            .graphicsLayer {
                val d = depth()
                cameraDistance = 14f * density
                if (d >= 0f) {
                    // Behind: pull it back from its pager slot under the front card, shrink it, and fan it out right
                    // so PEEK of it shows past the card in front, its far edge turned away into the screen.
                    val k = d.coerceAtMost(2f) * deal()
                    val s = (1f - 0.07f * k) * (1f + 0.07f * pulse() * (1f - d).coerceAtLeast(0f)) // the front card's bounce
                    scaleX = s; scaleY = s
                    translationX = -d * size.width + k * (PEEK.toPx() + size.width * 0.035f)
                    rotationY = 9f * k
                    alpha = (3f - d).coerceIn(0f, 1f) // a third card fades in as the second moves up
                } else {
                    // Swiped away: it swings off to the left, turning its leading edge into the screen.
                    val e = -d
                    translationX = -e * size.width * 0.12f
                    rotationY = -34f * e.coerceAtMost(1.2f)
                    rotationZ = -4f * e
                    val s = 1f - 0.08f * e.coerceAtMost(1.5f)
                    scaleX = s; scaleY = s
                    alpha = (1.45f - e).coerceIn(0f, 1f) // a hint of the card before, at the edge
                }
            }
            .drawBehind(CardShadow)
            .clip(CardShape)
            .background(Lw.Card)
            .semantics { contentDescription = name }
            .testTag("card-${card.id}"),
    ) {
        if (imageUrl != null) {
            AsyncImage(
                imageUrl, null, Modifier.fillMaxSize().sideways(), contentScale = ContentScale.Crop,
                colorFilter = if (saturation < 1f) ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(saturation) }) else null,
            )
        } else {
            Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(brand.t300, Lw.Card))), contentAlignment = Alignment.Center) {
                LwIcon(L.drawable.lucide_ic_nfc, size = 40.dp, tint = Color.White.copy(alpha = 0.85f))
            }
        }
        // Gloss from a light that stays put: the band slides across as the card moves under it. A tall, tilted layer
        // whose sides are clear, so no edge of it ever shows; only its offset changes, nothing repaints.
        Box(Modifier.fillMaxSize().graphicsLayer {
            translationX = size.width * (-0.35f - 1.1f * depth())
            rotationZ = 18f; scaleY = 1.8f
        }.background(Sheen))
        // Cards further back sit in shadow.
        Box(Modifier.fillMaxSize().graphicsLayer {
            val d = depth()
            alpha = if (d >= 0f) 0.22f * d.coerceAtMost(2f) * deal() else 0.35f * (-d).coerceAtMost(1f)
        }.background(Color.Black))
        Box(Modifier.fillMaxSize().border(1.dp, Edge, CardShape))
    }
}

private val Sheen = Brush.horizontalGradient(0.38f to Color.Transparent, 0.5f to Color.White.copy(alpha = 0.14f), 0.62f to Color.Transparent)
private val Edge = Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.26f), Color.White.copy(alpha = 0.04f)))

/** Lays a landscape card design out sideways and turns it upright, so it fills the portrait card. */
// ponytail: assumes every design is landscape (as the web draws them); check the image's size if portrait ones appear.
private fun Modifier.sideways() = layout { measurable, constraints ->
    val p = measurable.measure(Constraints.fixed(constraints.maxHeight, constraints.maxWidth))
    layout(constraints.maxWidth, constraints.maxHeight) {
        p.placeWithLayer((constraints.maxWidth - p.width) / 2, (constraints.maxHeight - p.height) / 2) { rotationZ = 90f }
    }
}

/** Where the deck is: one dot per card, the front one stretched into a brand pill that slides with the swipe. */
// ponytail: one dot per card; a counter instead past ~20 cards.
@Composable
private fun DeckDots(pager: PagerState, count: Int) {
    val active = LocalBrand.current.t400
    Canvas(Modifier.size(width = (count * 12 + 8).dp, height = 6.dp)) {
        val dot = 6.dp.toPx(); val gap = 6.dp.toPx(); val grow = 14.dp.toPx()
        val at = pager.currentPage + pager.currentPageOffsetFraction
        var x = 0f
        for (i in 0 until count) {
            val t = (1f - abs(i - at)).coerceIn(0f, 1f)
            val w = dot + grow * t
            drawRoundRect(lerp(Color.White.copy(alpha = 0.22f), active, t), Offset(x, 0f), Size(w, dot), CornerRadius(dot / 2))
            x += w + gap
        }
    }
}

private data class CardHeader(val name: String, val subtitle: String, @StringRes val status: Int, val statusColor: Color)

/** The front card's name and status, what its wallet holds, and its settings. */
@Composable
private fun CardOptions(
    card: WalletCard, name: String, wallets: List<RemoteWallet>, balances: Map<String, Long>, fetching: Set<String>,
    onToggle: (WalletCard, Boolean) -> Unit, onBind: (WalletCard, RemoteWallet) -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    val money = rememberMoney()
    val header = CardHeader(
        name,
        card.design?.description?.trim()?.takeIf { it.isNotEmpty() && it != name } ?: card.id.let { if (it.length <= 12) it else "${it.take(6)}…${it.takeLast(4)}" },
        when { card.blocked -> R.string.settings_cardsScreen_status_Blocked; card.disabled -> R.string.settings_cardsScreen_status_Disabled; else -> R.string.settings_cardsScreen_status_Active },
        when { card.blocked -> Lw.Red; card.disabled -> Lw.MutedFg; else -> Lw.Green },
    )
    val lastUsed = card.lastUsedAt?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }
        ?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        AnimatedContent(header, transitionSpec = { fadeIn(tween(220, 60)) togetherWith fadeOut(tween(120)) }, label = "card-header") { h ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(h.name, size = 22.sp, weight = FontWeight.SemiBold, maxLines = 1, letterSpacing = (-0.3).sp)
                    Txt(h.subtitle, size = 13.sp, color = Lw.MutedFg, maxLines = 1)
                }
                Pill(stringResource(h.status), h.statusColor)
            }
        }
        BalancePanel(
            card.remoteWalletId?.let { id -> wallets.find { it.id == id } }, card.remoteWalletId != null,
            card.remoteWalletId?.let { balances[it] }, card.remoteWalletId in fetching, money,
            onPick = if (card.blocked) null else ({ picking = true }),
        )
        SettingsGroup {
            SettingsSwitchRow(
                stringResource(R.string.settings_cardsScreen_tapPayments), !card.disabled, { onToggle(card, it) },
                icon = L.drawable.lucide_ic_nfc, enabled = !card.blocked, switchModifier = Modifier.testTag("card-toggle"),
            )
            if (lastUsed != null) {
                GroupDivider()
                SettingsRow(stringResource(R.string.cards_lastUsed), null, icon = L.drawable.lucide_ic_clock, value = lastUsed)
            }
        }
    }
    if (picking) WalletPickerSheet(
        wallets.filter { it.isActive && !it.isPlugin }, card.remoteWalletId, { picking = false },
        stringResource(R.string.cards_pickWallet), "card-wallet-picker",
        trailing = { w ->
            val sats = balances[w.id]
            when {
                sats != null -> Txt(money.figure(sats) + " " + money.unit, Modifier.padding(start = 12.dp, end = 10.dp), size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg, maxLines = 1)
                w.id in fetching -> Box(Modifier.padding(start = 12.dp, end = 10.dp)) { Spinner(14.dp, Lw.MutedFg) }
            }
        },
    ) { picking = false; onBind(card, it) }
}

/**
 * What the card can spend: its wallet's balance, big, in the currency Home shows (rolling digit by digit when the next
 * card spends from another wallet), on glass lit in that wallet's colour. The chip names the wallet and picks another.
 * [linked]: the card has a wallet, even if the list hasn't arrived to name it.
 */
@Composable
private fun BalancePanel(wallet: RemoteWallet?, linked: Boolean, balance: Long?, loading: Boolean, money: Money, onPick: (() -> Unit)?) {
    val c = LocalContainer.current
    val hidden = money.hidden
    val accent by animateColorAsState(wallet?.let(::walletAccent) ?: Lw.MutedFg, tween(400), label = "wallet-accent")
    val sensitiveGate = rememberSensitiveGate()
    val showLabel = stringResource(if (hidden) R.string.home_showBalance else R.string.home_hideBalance)
    val shape = RoundedCornerShape(24.dp)
    Column(
        Modifier.fillMaxWidth().glass(shape)
            .drawBehind {
                // The wallet's colour, glowing in from the top left (read here: a new wallet only repaints).
                val r = size.width * 0.75f
                drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.20f), Color.Transparent), Offset.Zero, r), r, Offset.Zero)
            }
            .border(1.dp, Brush.verticalGradient(listOf(accent.copy(alpha = 0.30f), Color.White.copy(alpha = 0.04f))), shape)
            .padding(start = 18.dp, end = 12.dp, top = 12.dp, bottom = 16.dp)
            .testTag("card-balance"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.remote_wallet_balance), Modifier.weight(1f), size = 13.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
            WalletChip(wallet, linked, onPick)
        }
        Spacer(Modifier.height(2.dp))
        // Tap the figure to hide/show it, as on Home (showing passes the lock first when Locking asks for it).
        Box(
            Modifier.height(48.dp).clickable(interactionSource = null, indication = null, onClickLabel = showLabel) {
                if (hidden) sensitiveGate { c.prefs.setHideBalance(false) } else c.prefs.setHideBalance(true)
            },
            contentAlignment = Alignment.BottomStart,
        ) {
            when {
                !linked -> Txt("—", size = 36.sp, weight = FontWeight.SemiBold, color = Lw.MutedFg)
                balance == null && loading -> LoadingDots(Modifier.padding(bottom = 14.dp), dot = 8.dp, color = Lw.MutedFg)
                balance == null -> Txt("—", size = 36.sp, weight = FontWeight.SemiBold, color = Lw.MutedFg)
                else -> Row(verticalAlignment = Alignment.Bottom) {
                    if (hidden) Txt("•••••", size = 36.sp, weight = FontWeight.SemiBold, lineHeight = 44.sp, letterSpacing = (-1).sp)
                    else Odometer(money.figure(balance), Modifier, 36.sp, (-1).sp, Lw.Foreground, fromZero = false, lineHeight = 44.sp)
                    Txt(money.unit, Modifier.padding(start = 6.dp, bottom = 7.dp), size = 15.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
                }
            }
        }
    }
}

/** Sats as Home shows them: in the currency picked there, dots while balances are hidden. */
private class Money(val code: String, val rates: Rates?, val hidden: Boolean) {
    fun figure(sats: Long) = if (hidden) "•••••" else Currencies.formatBalance(sats, code, rates)
    val unit get() = Currencies.unitLabel(code)
}

@Composable
private fun rememberMoney(): Money {
    val c = LocalContainer.current
    val hidden by c.prefs.hideBalance.collectAsState()
    val currencies by c.prefs.currencies.collectAsState()
    val rates by c.rates.rates.collectAsState()
    return Money(currencies.selected.takeIf { it in currencies.active } ?: "SAT", rates, hidden)
}

/** The wallet a card pays from, as a chip; tapping it ([onPick]) picks another. "Not linked" when it has none. */
@Composable
private fun WalletChip(wallet: RemoteWallet?, linked: Boolean, onPick: (() -> Unit)?) {
    val label = if (linked) wallet?.name ?: "…" else stringResource(R.string.cards_notLinked)
    val described = stringResource(R.string.cards_paysFrom) + ": " + label
    Row(
        Modifier.clip(CircleShape).glass(CircleShape, GlassLevel.Thin)
            .then(if (onPick != null) Modifier.clickable(onClick = onPick) else Modifier)
            .padding(start = 4.dp, end = 10.dp, top = 4.dp, bottom = 4.dp)
            .semantics(mergeDescendants = true) { contentDescription = described }
            .testTag("card-wallet"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (wallet != null) WalletTile(wallet, 26.dp)
        else Box(Modifier.size(26.dp).glass(CircleShape, GlassLevel.Strong), contentAlignment = Alignment.Center) {
            LwIcon(L.drawable.lucide_ic_wallet, size = 14.dp, tint = if (linked) Lw.MutedFg else Lw.Orange)
        }
        Spacer(Modifier.width(8.dp))
        Txt(label, Modifier.widthIn(max = 160.dp), size = 13.sp, weight = FontWeight.Medium, maxLines = 1, color = if (linked) Lw.Foreground else Lw.Orange)
        if (onPick != null) LwIcon(L.drawable.lucide_ic_chevron_down, Modifier.padding(start = 4.dp), size = 14.dp, tint = Lw.MutedFg)
    }
}
