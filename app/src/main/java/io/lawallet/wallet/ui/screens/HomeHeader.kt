package io.lawallet.wallet.ui.screens

import io.lawallet.wallet.ui.components.toastError
import io.lawallet.wallet.ui.theme.PanelStyle
import androidx.compose.foundation.layout.BoxScope
import io.lawallet.wallet.app.ensureDomainLogo
import io.lawallet.wallet.ui.components.BadgedAvatar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import io.lawallet.wallet.ui.theme.LocalHaze
import io.lawallet.wallet.ui.theme.frosted
import io.lawallet.wallet.ui.components.WalletMark
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.lawallet.wallet.core.api.WalletAddress
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.animation.core.animate
import kotlin.math.sign
import androidx.compose.ui.unit.Constraints
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import kotlin.math.roundToInt
import androidx.compose.ui.layout.Layout
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.foundation.Image
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import kotlin.math.abs
import io.lawallet.wallet.ui.theme.Inter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import io.lawallet.wallet.core.api.RemoteWallet
import io.lawallet.wallet.ui.components.toast
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import io.lawallet.wallet.R
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.R0
import io.lawallet.wallet.ui.sharedAvatar
import io.lawallet.wallet.ui.components.BrandImage
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import io.lawallet.wallet.ui.components.GlassIconButton
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.launch
import io.lawallet.wallet.ui.sharedAddress
import androidx.compose.material3.ripple
import androidx.compose.foundation.interaction.MutableInteractionSource
import io.lawallet.wallet.ui.components.holdProgress
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import com.composables.icons.lucide.R as L

/** Avatar · centre pill · settings, fixed over every page; the pill turns between the onchain primary, the Lightning address and USDT. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun HomeHeader(intro: Boolean, pages: HomePages) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val addressPress = remember { MutableInteractionSource() }
    val addressHold = holdProgress(addressPress)
    val brand = LocalBrand.current
    val me by c.session.me.collectAsState()
    val accountPubkey by c.session.accountPubkey.collectAsState()
    val profiles by c.session.profiles.entries.collectAsState()
    val avatar = accountPubkey?.let { profiles[it]?.picture }
    val onchain by c.onchain.wallets.collectAsState()
    val primary = onchain.firstOrNull { it.isPrimary }
    val usdtSoon = stringResource(R.string.usdt_soon)
    // An activity sheet rising takes the header away with the page (as ActivitySheetLayout does); once it's
    // gone it leaves the composition, so it never catches taps meant for the raised list.
    val sheet = { pages.sheetAt(HomePager.position) }
    val gone by remember { derivedStateOf { sheet() > 0.45f } }
    if (gone) return
    // Centre pill: the user's Lightning address (tap to receive) or the logotype; the onchain primary; USDT.
    val address = me?.lightningAddress
    // Swipe the address sideways to switch between the account's addresses (and their wallets): a local choice.
    val all by c.wallet.addresses.collectAsState()
    val selected by c.wallet.selectedAddress.collectAsState()
    val switchable = remember(all) { c.wallet.switchable(all) }
    val users = remember(switchable) { switchable.map { it.username } }
    // Tap (Lightning): the pill drops down a list of the addresses to switch to, plus "Add address".
    var picker by AddressPickerOverlay::open
    var menuFor by remember { mutableStateOf<WalletAddress?>(null) }
    val scope = rememberCoroutineScope()
    // Leaving Home (Add address, Settings…) never leaves it open behind.
    DisposableEffect(Unit) { onDispose { AddressPickerOverlay.open = false } }
    val primaryUser = address?.substringBefore('@')
    var idx by remember { mutableIntStateOf(0) }
    LaunchedEffect(users, selected, primaryUser) { idx = users.indexOf(selected).takeIf { it >= 0 } ?: users.indexOf(primaryUser).coerceAtLeast(0) }
    val span = with(LocalDensity.current) { 72.dp.toPx() }
    var drag by remember { mutableFloatStateOf(0f) }
    val onLightning by remember { derivedStateOf { HomePager.page == LIGHTNING } } // not every swipe frame
    val canSwipe = address != null && users.size > 1 && onLightning
    val dragState = rememberDraggableState { d ->
        val next = drag + d
        // Past the first or last address it only gives a little.
        drag = (if ((if (next < 0) idx + 1 else idx - 1) in users.indices) next else drag + d * 0.25f).coerceIn(-span, span)
    }
    // A full-screen layer: the header row on top, and the address drop-down (with its tap-outside catcher) over Home
    // when open. Its empty area has no input, so touches pass through to the pages.
    Box(Modifier.fillMaxSize()) {
        AddressPickerScrim(picker) { picker = false }
        Row(
            Modifier.fillMaxWidth().riseIn(0, intro).graphicsLayer {
                val p = sheet()
                alpha = (1f - p * 2.2f).coerceIn(0f, 1f)
                translationY = -p * 64.dp.toPx()
                scaleX = 1f - 0.06f * p; scaleY = scaleX
                transformOrigin = TransformOrigin(0.5f, 0f)
            }.padding(start = 16.dp, end = 16.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Opens the profile; the avatar itself flies there (log out lives on the profile now).
            Box(
                Modifier.sharedAvatar().size(44.dp).glass(CircleShape).clickable(onClickLabel = stringResource(R.string.profile_open)) { nav.navigate(R0.PROFILE) { launchSingleTop = true } }.testTag("avatar"),
                contentAlignment = Alignment.Center,
            ) {
                if (avatar != null) AsyncImage(avatar, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                else BrandImage(brand.isotype, "isotype.svg", Modifier.padding(9.dp).fillMaxSize())
            }
            Box(
                // Tap: receive. Hold (Lightning, with an address): the pill expands into the Lightning addresses screen,
                // "charging" (shrinks, dims) up to the long press.
                Modifier.weight(1f).graphicsLayer { val p = addressHold(); scaleX = 1f - 0.05f * p; scaleY = scaleX; alpha = 1f - 0.25f * p }
                    .then(if (address != null) Modifier.sharedAddress() else Modifier)
                    .height(44.dp).glass(CircleShape)
                    .draggable(dragState, Orientation.Horizontal, enabled = canSwipe, onDragStopped = { v ->
                        val dir = if (drag < 0) 1 else -1 // 1 = the next address, coming in from the right
                        val target = idx + dir
                        if (drag != 0f && target in users.indices && (abs(drag) > span * 0.35f || -dir * v > 600f)) {
                            animate(drag, -dir * span, animationSpec = tween(160, easing = FastOutSlowInEasing)) { x, _ -> drag = x }
                            idx = target; drag = 0f
                            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            c.wallet.selectAddress(users[target].takeIf { it != primaryUser })
                        } else animate(drag, 0f, animationSpec = spring(0.8f, Spring.StiffnessMedium)) { x, _ -> drag = x }
                    })
                    .combinedClickable(
                        interactionSource = addressPress, indication = ripple(),
                        onLongClick = {
                            if (HomePager.page == LIGHTNING && address != null) {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                nav.navigate(R0.ADDRESSES) { launchSingleTop = true }
                            }
                        },
                    ) {
                        when (HomePager.page) {
                            ONCHAIN -> if (!ONCHAIN_SOON) nav.navigate(primary?.let { R0.onchainWallet(it.id) } ?: R0.ONCHAIN_ADD) { launchSingleTop = true }
                            LIGHTNING -> if (address != null) picker = !picker
                            else -> context.toast(usdtSoon)
                        }
                    }.testTag("home-address"),
            ) {
                // A little carousel inside the pill (it clips): each page's face sits on its page's side and slides in
                // 1:1 with the swipe.
                val face = { page: Int -> Modifier.matchParentSize().graphicsLayer { translationX = (page - HomePager.position) * size.width; alpha = smooth(0.15f, 1f, HomePager.weight(page)) } }
                Row(face(ONCHAIN).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Box(Modifier.size(22.dp).clip(CircleShape).background(Lw.Bitcoin), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_bitcoin, size = 15.dp, tint = Color.White) }
                    Spacer(Modifier.width(8.dp))
                    Txt(primary?.name ?: stringResource(R.string.onchain_title), Modifier.weight(1f, fill = false), size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
                }
                Box(face(LIGHTNING), contentAlignment = Alignment.Center) {
                    if (address != null) {
                        // Even room both sides: the address stays centred and never runs under the arrow.
                        Row(Modifier.padding(horizontal = 34.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                            // Username leads; the domain sits a step dimmer and never truncates.
                            if (users.size > 1) SwipeUsername(users, idx.coerceIn(users.indices), { drag }, span, Modifier.weight(1f, fill = false))
                            else Txt(address.substringBefore('@'), Modifier.weight(1f, fill = false), size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
                            Txt("@" + address.substringAfter('@'), size = 16.sp, weight = FontWeight.Medium, color = Lw.Foreground.copy(alpha = 0.6f), maxLines = 1)
                        }
                        // The page's bolt on the left, balancing the arrow: the watermark's tilt and tone wash, small.
                        val tone = PAGE_TONES[LIGHTNING]
                        Image(
                            painterResource(L.drawable.lucide_ic_zap), null,
                            Modifier.align(Alignment.CenterStart).padding(start = 13.dp).size(18.dp)
                                .graphicsLayer { rotationZ = -10f; compositingStrategy = CompositingStrategy.Offscreen }
                                .drawWithContent {
                                    drawContent()
                                    drawRect(Brush.linearGradient(listOf(tone, tone.copy(alpha = 0.45f)), Offset.Zero, Offset(size.width, size.height)), blendMode = BlendMode.SrcIn)
                                },
                        )
                        // The pill reads as a dropdown: a tap here is the pill's tap (opens the address list); it flips while open.
                        val flip = animateFloatAsState(if (picker) 180f else 0f, spring(0.8f, Spring.StiffnessMedium), label = "pickerArrow")
                        LwIcon(
                            L.drawable.lucide_ic_chevron_down,
                            Modifier.align(Alignment.CenterEnd).padding(end = 12.dp).graphicsLayer { rotationZ = flip.value }.testTag("home-address-arrow"),
                            size = 18.dp, tint = Lw.Foreground.copy(alpha = 0.6f),
                        )
                    }
                    else BrandImage(brand.logotype, "logotype.svg", Modifier.height(20.dp).width(88.dp))
                }
                Row(face(USDT).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                    Image(painterResource(R.drawable.ic_usdt), null, Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Txt("USDT", size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
                }
            }
            GlassIconButton(
                L.drawable.lucide_ic_settings, { nav.navigate(R0.SETTINGS) { popUpTo(R0.HOME); launchSingleTop = true } },
                Modifier.testTag("home-settings"), contentDescription = stringResource(R.string.shared_nav_settings),
            )
        }
        if (address != null) AddressPicker(
            picker, { picker = false }, switchable, users.getOrNull(idx), address.substringAfter('@'),
            onPick = { i -> picker = false; if (i != idx) { idx = i; c.wallet.selectAddress(users[i].takeIf { it != primaryUser }) } },
            onAdd = { picker = false; nav.navigate("${R0.CLAIM}?from=addresses&bonus=0") },
            onMenu = { i -> menuFor = switchable.getOrNull(i) },
        )
        // Hold an address in the switcher: its actions sheet, as on the addresses screen.
        if (address != null) menuFor?.let { a ->
            val domain = address.substringAfter('@')
            AddressMenu(a, "${a.username}@$domain", c.remoteWallets.cached.orEmpty(), { block ->
                scope.launch {
                    runCatching { block() }.onFailure { context.toastError(it) }
                    c.wallet.refreshAddresses(); runCatching { c.session.refreshMe() }
                }
            }) { menuFor = null }
        }
    }
}

/** Whether Home's address drop-down is open: AppNav's tab bar steps aside so the drop-down is on top. */
object AddressPickerOverlay {
    var open by mutableStateOf(false)
}

/** What an address does, for its mark: receives into an NWC or LNCurl wallet, forwards elsewhere, or nothing (unlinked). */
internal enum class AddressKind { NWC, LNCURL, FORWARD, IDLE }

/** [wallets] null (not loaded yet) trusts the routing; loaded, a wallet that isn't active there (archived) counts as unlinked. */
internal fun addressKind(a: WalletAddress, wallets: List<RemoteWallet>?): AddressKind = when (a.mode) {
    "ALIAS", "PROXY_ALIAS" -> AddressKind.FORWARD
    "CUSTOM_NWC" -> when {
        wallets == null -> AddressKind.NWC
        else -> wallets.find { it.id == a.remoteWalletId && it.isActive }?.let { if (it.isLncurl) AddressKind.LNCURL else AddressKind.NWC } ?: AddressKind.IDLE
    }
    else -> AddressKind.IDLE
}

@Composable
private fun AddressKindMark(kind: AddressKind) {
    val tint = when (kind) { AddressKind.NWC -> Lw.NwcPurple; AddressKind.LNCURL -> Lw.LncurlGreen; AddressKind.FORWARD -> Lw.Lightning; AddressKind.IDLE -> Lw.Tether }
    Box(Modifier.size(36.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        when (kind) {
            AddressKind.NWC, AddressKind.LNCURL -> WalletMark(kind == AddressKind.LNCURL, 18.dp)
            AddressKind.FORWARD -> LwIcon(L.drawable.lucide_ic_forward, size = 18.dp, tint = tint)
            AddressKind.IDLE -> LwIcon(L.drawable.lucide_ic_bird, size = 18.dp, tint = tint)
        }
    }
}

/** Behind the address drop-down (under the header): no backdrop, just a catcher so a tap outside closes it. */
@Composable
private fun BoxScope.AddressPickerScrim(open: Boolean, onDismiss: () -> Unit) {
    if (open) Box(Modifier.matchParentSize().clickable(interactionSource = null, indication = null, onClick = onDismiss).testTag("address-picker-scrim"))
}

/**
 * The pill's drop-down, in the pill's own style: frosted glass that unfolds from under it, each address written like
 * the pill (username, dimmer domain) with a mark of what it does and a check on the one shown; "Add address" is the
 * list's last row.
 * Drawn in Home's own window (not a popup): Haze then blurs it like the tab bar, and nothing outlives its closing (a
 * popup's blur kept the closed window registered with the app's Haze source, leaking each one).
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BoxScope.AddressPicker(open: Boolean, onDismiss: () -> Unit, list: List<WalletAddress>, current: String?, domain: String, onPick: (Int) -> Unit, onAdd: () -> Unit, onMenu: (Int) -> Unit) {
    val haptics = LocalHapticFeedback.current
    val c = LocalContainer.current
    val brand = LocalBrand.current
    val haze = LocalHaze.current
    val primaryLabel = stringResource(R.string.remoteWallets_list_primary)
    // Re-read on open and kept through the closing animation (dropping it there flashed LNCurl marks as NWC).
    val wallets = remember(open) { c.remoteWallets.cached }
    BackHandler(open, onDismiss)
    val shape = RoundedCornerShape(24.dp)
    // Under the pill (the header row's 12dp top + 44dp pill + a gap), inset like the pill, and as tall as it needs up to
    // just above the tab bar (Home's reveal keeps the same 158dp clear).
    AnimatedVisibility(
        open,
        Modifier.align(Alignment.TopCenter).padding(start = 70.dp, end = 70.dp, top = 64.dp, bottom = 158.dp),
        enter = expandVertically(spring(0.86f, Spring.StiffnessMediumLow), expandFrom = Alignment.Top) + fadeIn(tween(180)) +
            scaleIn(spring(0.86f, Spring.StiffnessMediumLow), initialScale = 0.94f, transformOrigin = TransformOrigin(0.5f, 0f)),
        exit = shrinkVertically(tween(200, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Top) + fadeOut(tween(160)) +
            scaleOut(tween(200), targetScale = 0.96f, transformOrigin = TransformOrigin(0.5f, 0f)),
    ) {
            Column(
                Modifier.fillMaxWidth().frosted(haze, shape, PanelStyle).padding(vertical = 6.dp).testTag("address-picker"),
            ) {
              // The addresses scroll when they don't fit; "Add address" is the last row.
              Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                list.forEachIndexed { i, a ->
                    val sel = a.username == current
                    Row(
                        Modifier.fillMaxWidth()
                            .combinedClickable(onLongClick = { haptics.performHapticFeedback(HapticFeedbackType.LongPress); onMenu(i) }) { onPick(i) }
                            .padding(horizontal = 12.dp, vertical = 9.dp).testTag("address-picker-${a.username}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val kind = addressKind(a, wallets)
                        // An alias wears its destination's domain brand, badged with the forward arrow.
                        if (kind == AddressKind.FORWARD && a.redirect != null) {
                            val d = a.redirect.substringAfter('@')
                            c.avatars.domainTick.collectAsState().value // repaint when a favicon lands
                            LaunchedEffect(d) { c.ensureDomainLogo(d) }
                            BadgedAvatar(c.avatars.domainLogo(d), L.drawable.lucide_ic_forward, Lw.Lightning, 36.dp)
                        }
                        else AddressKindMark(kind)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Row {
                                Txt(a.username, Modifier.weight(1f, fill = false), size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1, color = if (sel) Lw.Foreground else Lw.Foreground.copy(alpha = 0.85f))
                                Txt("@$domain", size = 16.sp, weight = FontWeight.Medium, color = Lw.Foreground.copy(alpha = 0.5f), maxLines = 1)
                                if (a.isPrimary) { Spacer(Modifier.width(6.dp)); LwIcon(L.drawable.lucide_ic_star, size = 14.dp, tint = Lw.Gold, contentDescription = primaryLabel, modifier = Modifier.align(Alignment.CenterVertically)) }
                            }
                            Txt(
                                // The mark already says what kind it is: just where payments go.
                                when (kind) {
                                    AddressKind.FORWARD -> a.redirect ?: "—"
                                    AddressKind.NWC, AddressKind.LNCURL -> a.remoteWalletName ?: "—"
                                    AddressKind.IDLE -> stringResource(R.string.home_unlinked)
                                },
                                size = 12.sp, color = Lw.MutedFg, maxLines = 1,
                            )
                        }
                        if (sel) { Spacer(Modifier.width(8.dp)); LwIcon(L.drawable.lucide_ic_check, size = 18.dp, tint = brand.t400) }
                    }
                }
              // Last row, written like an address.
              Row(
                  Modifier.fillMaxWidth().clickable(onClick = onAdd).padding(horizontal = 12.dp, vertical = 9.dp).testTag("address-picker-add"),
                  verticalAlignment = Alignment.CenterVertically,
              ) {
                  Box(Modifier.size(36.dp).clip(CircleShape).background(brand.t400.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                      LwIcon(L.drawable.lucide_ic_plus, size = 18.dp, tint = brand.t400)
                  }
                  Spacer(Modifier.width(12.dp))
                  Txt(stringResource(R.string.addresses_add), size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
              }
              }
            }
    }
}

/**
 * The username part of the pill while swiping between addresses: the current one slides and fades with [drag] while
 * its neighbour slides in from that side (cut at the "@", so the domain holds still). After a switch the slot eases to
 * the new name's width, which glides the whole address back to centre.
 */
@Composable
private fun SwipeUsername(users: List<String>, index: Int, drag: () -> Float, span: Float, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val style = TextStyle(fontFamily = Inter, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum")
    val width = { i: Int -> measurer.measure(users[i], style, maxLines = 1).size.width.toFloat() }
    val slot = remember { Animatable(width(index)) }
    LaunchedEffect(index, users) { slot.animateTo(width(index), spring(0.85f, Spring.StiffnessMediumLow)) }
    // Only the drag's direction picks the neighbour: composition reruns when that flips, not on every frame (the
    // offsets themselves are read at placement).
    val dir by remember { derivedStateOf { sign(drag()).toInt() } }
    val neighbor = (if (dir < 0) index + 1 else if (dir > 0) index - 1 else -1).takeIf { it in users.indices }
    Layout(
        content = {
            Txt(users[index], size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
            if (neighbor != null) Txt(users[neighbor], size = 16.sp, weight = FontWeight.SemiBold, maxLines = 1)
        },
        // Names may overflow to the left (the pill clips there), never right over the domain.
        modifier = modifier.drawWithContent { clipRect(left = -size.width * 4, right = size.width) { this@drawWithContent.drawContent() } },
    ) { ms, cs ->
        val names = ms.map { it.measure(Constraints(maxWidth = cs.maxWidth)) }
        val w = slot.value.roundToInt().coerceIn(0, cs.maxWidth)
        val h = names.maxOf { it.height }
        layout(w, h) {
            val x = drag()
            val p = (abs(x) / span).coerceIn(0f, 1f)
            names[0].placeWithLayer(w - names[0].width + x.roundToInt(), (h - names[0].height) / 2) { alpha = 1f - p }
            names.getOrNull(1)?.let { n -> n.placeWithLayer(w - n.width + (x - sign(x) * span).roundToInt(), (h - n.height) / 2) { alpha = p } }
        }
    }
}
