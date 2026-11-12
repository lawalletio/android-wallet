package io.lawallet.wallet.ui.screens

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.EaseOutCubic
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import io.lawallet.wallet.R
import io.lawallet.wallet.core.nostr.Nip19
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.components.BrandImage
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.CropDialog
import io.lawallet.wallet.ui.components.CropShape
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.GlassIconButton
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.copyToClipboard
import io.lawallet.wallet.ui.components.decodeForCrop
import io.lawallet.wallet.ui.components.toast
import io.lawallet.wallet.ui.sharedAvatar
import io.lawallet.wallet.ui.theme.Glass
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.composables.icons.lucide.R as L

/** A picked, cropped image: shown from [preview] at once while it uploads; [url] once it's up. */
private class ImageSlot {
    var preview by mutableStateOf<ImageBitmap?>(null)
    var bytes: ByteArray? = null
    var url by mutableStateOf<String?>(null)
    var progress by mutableStateOf<Float?>(null)
    var failed by mutableStateOf(false)
    var job: Job? = null
    val busy get() = progress != null
    fun reset() { job?.cancel(); preview = null; bytes = null; url = null; progress = null; failed = false }
}

private val AvatarSize = 104.dp

/** Nostr kind-0 profile: cover, avatar (flown in from Home), name, bio; edits publish a new kind 0. */
@Composable
fun ProfileScreen() {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val signer by c.session.signer.collectAsState()
    val pubkey = signer?.pubkey
    val cached by c.session.profiles.entries.collectAsState()
    val profile by c.session.nostrProfile.collectAsState()
    val me by c.session.me.collectAsState()

    var loading by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    fun load() = scope.launch {
        loading = true; loadFailed = false
        loadFailed = !c.session.loadNostrProfile() && c.session.nostrProfile.value == null
        loading = false
    }

    var editing by rememberSaveable { mutableStateOf(false) }
    // Every visit, and every return to the app while here: the profile may have changed elsewhere.
    // Not mid-edit (the photo picker resumes us too): the draft would be left behind.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (!editing) load() }
    var displayName by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var about by rememberSaveable { mutableStateOf("") }
    val avatar = remember { ImageSlot() }
    val banner = remember { ImageSlot() }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }
    var confirmLogout by remember { mutableStateOf(false) }

    fun startEdit() {
        val p = profile ?: return
        displayName = p.displayName.orEmpty(); name = p.name.orEmpty(); about = p.about.orEmpty()
        saveError = null; editing = true
    }
    fun cancelEdit() { avatar.reset(); banner.reset(); saveError = null; editing = false }
    BackHandler(editing) { cancelEdit() }

    fun upload(slot: ImageSlot) {
        val bytes = slot.bytes ?: return
        slot.job?.cancel()
        slot.url = null; slot.failed = false; slot.progress = 0f
        slot.job = scope.launch {
            runCatching { c.session.uploadImage(bytes) { p -> slot.progress = p } }
                .onSuccess { slot.url = it; slot.progress = null }
                .onFailure { if (it !is CancellationException) { slot.failed = true; slot.progress = null } }
        }
    }

    // Pick → crop → preview + upload.
    var pickFor by rememberSaveable { mutableStateOf<CropShape?>(null) }
    var cropping by remember { mutableStateOf<Pair<CropShape, Bitmap>?>(null) }
    val imageFailed = stringResource(R.string.profile_imageFailed)
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val shape = pickFor ?: return@rememberLauncherForActivityResult
        if (uri != null) scope.launch {
            val bmp = withContext(Dispatchers.IO) { runCatching { decodeForCrop(ctx, uri) }.getOrNull() }
            if (bmp != null) cropping = shape to bmp else ctx.toast(imageFailed)
        }
    }
    fun pick(shape: CropShape) {
        pickFor = shape
        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val savedMsg = stringResource(R.string.profile_saved)
    val saveFailed = stringResource(R.string.profile_saveFailed)
    fun save() {
        saving = true; saveError = null
        scope.launch {
            runCatching {
                c.session.saveProfile(buildMap {
                    put("display_name", displayName); put("name", name); put("about", about)
                    avatar.url?.let { put("picture", it) }
                    banner.url?.let { put("banner", it) }
                })
            }.onSuccess { editing = false; ctx.toast(savedMsg) }.onFailure { saveError = saveFailed }
            saving = false
        }
    }

    val avatarUrl = profile?.picture ?: pubkey?.let { cached[it]?.picture }
    val address = me?.lightningAddress
    val npub = remember(pubkey) { pubkey?.let(Nip19::npub) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).imePadding().padding(bottom = 24.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val bannerH = (maxWidth - 24.dp) / 3f
                Cover(
                    url = profile?.banner, slot = banner, editing = editing,
                    onPick = { if (banner.failed) upload(banner) else pick(CropShape.Banner) },
                    modifier = Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(bannerH),
                )
                // Top controls over the cover.
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    GlassIconButton(
                        if (editing) L.drawable.lucide_ic_x else L.drawable.lucide_ic_chevron_left,
                        { if (editing) cancelEdit() else nav.popBackStack() }, Modifier.testTag("profile-back"), size = 40.dp,
                        contentDescription = stringResource(if (editing) R.string.common_cancel else R.string.common_back),
                    )
                }
                Avatar(
                    url = avatarUrl, slot = avatar, editing = editing,
                    onPick = { if (avatar.failed) upload(avatar) else pick(CropShape.Avatar) },
                    modifier = Modifier.padding(start = 28.dp, top = bannerH - AvatarSize / 2),
                )
                // Action beside the avatar: Edit ↔ Save.
                Box(Modifier.align(Alignment.TopEnd).padding(top = bannerH + 14.dp, end = 16.dp)) {
                    AnimatedContent(editing, transitionSpec = { (fadeIn(tween(220)) + scaleIn(initialScale = 0.9f)) togetherWith fadeOut(tween(120)) }, label = "action") { e ->
                        if (e) LwButton(
                            stringResource(R.string.profile_save), ::save, Modifier.testTag("profile-save"), BtnVariant.Theme,
                            enabled = !avatar.busy && !banner.busy && !avatar.failed && !banner.failed, loading = saving, height = 38.dp, icon = L.drawable.lucide_ic_check,
                        )
                        else LwButton(
                            stringResource(R.string.profile_edit), ::startEdit, Modifier.testTag("profile-edit"), BtnVariant.Secondary,
                            enabled = profile != null, height = 38.dp, icon = L.drawable.lucide_ic_pencil,
                        )
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 16.dp)) {
                AnimatedContent(
                    editing,
                    transitionSpec = {
                        (fadeIn(tween(240, delayMillis = 60)) + slideInVertically(tween(280, easing = EaseOutCubic)) { it / 12 }) togetherWith
                            (fadeOut(tween(120)) + slideOutVertically(tween(160)) { -it / 16 })
                    },
                    label = "profile-body",
                ) { e ->
                    if (e) EditForm(displayName, { displayName = it }, name, { name = it }, about, { about = it }, saveError)
                    else Column {
                        RiseIn(0) {
                            Details(
                                displayName = profile?.displayName ?: profile?.name ?: address?.substringBefore('@'),
                                handle = profile?.name?.takeIf { profile?.displayName != null },
                                nip05 = profile?.nip05, about = profile?.about,
                                placeholder = profile == null && !loadFailed,
                            )
                        }
                        if (loadFailed) RiseIn(1) {
                            Row(Modifier.padding(top = 12.dp).fillMaxWidth().glass(RoundedCornerShape(16.dp), GlassLevel.Thin, tint = Lw.Orange.copy(alpha = 0.08f)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Txt(stringResource(R.string.profile_loadFailed), Modifier.weight(1f), size = 13.sp, color = Lw.MutedFg, lineHeight = 18.sp)
                                TextButton({ load() }, enabled = !loading) { Txt(stringResource(R.string.common_retry), color = Lw.Orange) }
                            }
                        }
                        RiseIn(2) {
                            Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                val copied = stringResource(R.string.common_copied)
                                (profile?.lud16 ?: address)?.let { InfoChip(L.drawable.lucide_ic_zap, it, Lw.Gold) { ctx.copyToClipboard(it, copied) } }
                                npub?.let { InfoChip(L.drawable.lucide_ic_key, it.take(14) + "…" + it.takeLast(6), Lw.MutedFg, mono = true) { ctx.copyToClipboard(it, copied) } }
                            }
                        }
                        RiseIn(3) {
                            Row(
                                Modifier.padding(top = 32.dp).fillMaxWidth().glass(RoundedCornerShape(16.dp), GlassLevel.Thin).clickable { confirmLogout = true }.padding(16.dp).testTag("profile-logout"),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                LwIcon(L.drawable.lucide_ic_log_out, size = 18.dp, tint = Lw.Red)
                                Spacer(Modifier.width(12.dp))
                                Txt(stringResource(R.string.home_logOut), size = 15.sp, weight = FontWeight.Medium, color = Lw.Red)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.navigationBarsPadding())
        }
    }

    cropping?.let { (shape, bmp) ->
        CropDialog(
            bmp, shape,
            title = stringResource(if (shape == CropShape.Avatar) R.string.profile_cropAvatar else R.string.profile_cropBanner),
            confirm = stringResource(R.string.profile_usePhoto), cancel = stringResource(R.string.common_cancel),
            onCancel = { cropping = null },
            onCrop = { out, bytes ->
                cropping = null
                val slot = if (shape == CropShape.Avatar) avatar else banner
                slot.preview = out.asImageBitmap(); slot.bytes = bytes
                upload(slot)
            },
        )
    }

    if (confirmLogout) AlertDialog(
        onDismissRequest = { confirmLogout = false },
        confirmButton = { TextButton({ confirmLogout = false; c.session.logout() }) { Txt(stringResource(R.string.home_logOut), color = Lw.Red) } },
        dismissButton = { TextButton({ confirmLogout = false }) { Txt(stringResource(R.string.common_cancel)) } },
        text = { Txt(stringResource(R.string.settings_removeWalletConfirm)) },
        containerColor = Glass.Dialog,
    )
}

/** Wide cover: the brand's light when there's no banner, a camera badge while editing, a bar while uploading. */
@Composable
private fun Cover(url: String?, slot: ImageSlot, editing: Boolean, onPick: () -> Unit, modifier: Modifier) {
    val brand = LocalBrand.current
    val shape = RoundedCornerShape(24.dp)
    Box(
        modifier.clip(shape)
            .background(Brush.linearGradient(listOf(brand.t400.copy(alpha = 0.55f), brand.t200.copy(alpha = 0.18f), Lw.NwcPurple.copy(alpha = 0.30f))))
            .border(1.dp, Color.White.copy(alpha = 0.08f), shape)
            .clickable(enabled = editing && !slot.busy, onClick = onPick)
            .testTag("profile-cover"),
    ) {
        Crossfade(slot.preview ?: url, Modifier.fillMaxSize(), tween(350), label = "cover") { model ->
            when (model) {
                is ImageBitmap -> Image(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                is String -> AsyncImage(model, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                else -> Unit
            }
        }
        // Keeps the glass buttons legible on any photo.
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.28f), 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.18f))))
        EditBadge(editing, slot, stringResource(R.string.profile_changeBanner), Modifier.align(Alignment.Center))
        UploadBar(slot.progress, Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 12.dp))
    }
}

/** The avatar the Home header hands over: ringed in the background colour so it sits cut into the cover. */
@Composable
private fun Avatar(url: String?, slot: ImageSlot, editing: Boolean, onPick: () -> Unit, modifier: Modifier) {
    val brand = LocalBrand.current
    val progress by animateFloatAsState(slot.progress ?: 0f, tween(200), label = "avatarProgress")
    Box(modifier.size(AvatarSize), contentAlignment = Alignment.Center) {
        Box(
            Modifier.sharedAvatar().fillMaxSize().clip(CircleShape).background(Lw.Background).padding(4.dp).glass(CircleShape)
                .clickable(enabled = editing && !slot.busy, onClick = onPick).testTag("profile-avatar"),
            contentAlignment = Alignment.Center,
        ) {
            val model: Any? = slot.preview ?: url
            when (model) {
                is ImageBitmap -> Image(model, null, Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop)
                // Home's smaller cached copy stands in while the full size loads, so the flight never shows a blank disc.
                is String -> AsyncImage(
                    ImageRequest.Builder(LocalPlatformContext.current).data(model).placeholderMemoryCacheKey(model).build(), null,
                    Modifier.fillMaxSize().clip(CircleShape), contentScale = ContentScale.Crop,
                )
                else -> BrandImage(brand.isotype, "isotype.svg", Modifier.padding(22.dp).fillMaxSize())
            }
            EditBadge(editing, slot, stringResource(R.string.profile_changeAvatar), Modifier, compact = true)
        }
        // Upload ring hugging the avatar.
        if (slot.progress != null) CircularProgressIndicator(
            { progress }, Modifier.fillMaxSize(), color = brand.t400, strokeWidth = 3.dp, trackColor = Color.White.copy(alpha = 0.12f),
        )
    }
}

/** Camera badge on an editable image; turns into a retry hint after a failed upload. */
@Composable
private fun EditBadge(editing: Boolean, slot: ImageSlot, label: String, modifier: Modifier, compact: Boolean = false) {
    AnimatedVisibility(editing && !slot.busy, modifier, enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.7f), exit = fadeOut(tween(120)) + scaleOut(targetScale = 0.8f)) {
        val tint = if (slot.failed) Lw.Red.copy(alpha = 0.25f) else null
        if (compact) Box(Modifier.size(44.dp).glass(CircleShape, GlassLevel.Strong, tint = tint).background(Color.Black.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
            LwIcon(if (slot.failed) L.drawable.lucide_ic_refresh_cw else L.drawable.lucide_ic_camera, size = 20.dp, contentDescription = label)
        }
        else Row(Modifier.glass(CircleShape, GlassLevel.Strong, tint = tint).background(Color.Black.copy(alpha = 0.25f), CircleShape).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            LwIcon(if (slot.failed) L.drawable.lucide_ic_refresh_cw else L.drawable.lucide_ic_image_plus, size = 16.dp)
            Spacer(Modifier.width(8.dp))
            Txt(if (slot.failed) stringResource(R.string.profile_uploadFailed) else label, size = 13.sp, weight = FontWeight.Medium)
        }
    }
}

@Composable
private fun UploadBar(progress: Float?, modifier: Modifier) {
    AnimatedVisibility(progress != null, modifier, enter = fadeIn(), exit = fadeOut(tween(400))) {
        val p by animateFloatAsState(progress ?: 1f, tween(200), label = "coverProgress")
        LinearProgressIndicator({ p }, Modifier.fillMaxWidth().height(4.dp).clip(CircleShape), color = LocalBrand.current.t400, trackColor = Color.White.copy(alpha = 0.18f), drawStopIndicator = {})
    }
}

@Composable
private fun Details(displayName: String?, handle: String?, nip05: String?, about: String?, placeholder: Boolean) {
    if (placeholder) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Shimmer(180.dp, 26.dp); Shimmer(120.dp, 14.dp); Spacer(Modifier.height(4.dp)); Shimmer(280.dp, 14.dp); Shimmer(220.dp, 14.dp)
        }
        return
    }
    Txt(displayName ?: stringResource(R.string.profile_unnamed), Modifier.testTag("profile-name"), size = 26.sp, weight = FontWeight.SemiBold, letterSpacing = (-0.4).sp, maxLines = 2)
    val sub = listOfNotNull(handle?.let { "@$it" }, nip05?.removePrefix("_@"))
    if (sub.isNotEmpty()) Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Txt(sub.joinToString("  ·  "), Modifier.weight(1f, fill = false), size = 14.sp, color = Lw.MutedFg, maxLines = 1)
        if (nip05 != null) { Spacer(Modifier.width(6.dp)); LwIcon(L.drawable.lucide_ic_badge_check, size = 15.dp, tint = LocalBrand.current.t400) }
    }
    if (!about.isNullOrBlank()) Txt(about, Modifier.padding(top = 14.dp).testTag("profile-about"), size = 15.sp, lineHeight = 22.sp, color = Lw.Foreground.copy(alpha = 0.9f))
}

@Composable
private fun EditForm(displayName: String, onDisplayName: (String) -> Unit, name: String, onName: (String) -> Unit, about: String, onAbout: (String) -> Unit, error: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        val shape = RoundedCornerShape(14.dp)
        OutlinedTextField(displayName, { onDisplayName(it.take(64)) }, Modifier.fillMaxWidth().testTag("profile-displayName"), label = { Txt(stringResource(R.string.profile_displayName)) }, singleLine = true, shape = shape, colors = lwFieldColors())
        OutlinedTextField(name, { onName(it.take(32).replace(" ", "")) }, Modifier.fillMaxWidth().testTag("profile-username"), label = { Txt(stringResource(R.string.profile_username)) }, prefix = { Txt("@", color = Lw.MutedFg) }, singleLine = true, shape = shape, colors = lwFieldColors())
        OutlinedTextField(
            about, { onAbout(it.take(500)) }, Modifier.fillMaxWidth().testTag("profile-aboutField"), label = { Txt(stringResource(R.string.profile_about)) },
            placeholder = { Txt(stringResource(R.string.profile_aboutHint), color = Lw.MutedFg) }, minLines = 3, maxLines = 8, shape = shape, colors = lwFieldColors(),
            supportingText = { Txt("${about.length}/500", Modifier.fillMaxWidth(), size = 11.sp, color = Lw.MutedFg, align = androidx.compose.ui.text.style.TextAlign.End) },
        )
        ErrorText(error, Modifier.fillMaxWidth())
    }
}

@Composable
private fun InfoChip(icon: Int, text: String, tint: Color, mono: Boolean = false, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp), GlassLevel.Thin).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        LwIcon(icon, size = 16.dp, tint = tint)
        Spacer(Modifier.width(10.dp))
        Txt(text, Modifier.weight(1f), size = 14.sp, mono = mono, maxLines = 1)
        LwIcon(L.drawable.lucide_ic_copy, size = 15.dp, tint = Lw.MutedFg)
    }
}

@Composable
private fun Shimmer(width: Dp, height: Dp) {
    val a by rememberInfiniteTransition(label = "shimmer").animateFloat(0.05f, 0.12f, infiniteRepeatable(tween(800, easing = LinearEasing), RepeatMode.Reverse), label = "a")
    Box(Modifier.width(width).height(height).clip(RoundedCornerShape(8.dp)).background(Color.White.copy(alpha = a)))
}

/** Staggered entrance once the avatar has landed: each block rises and fades in, [index] × 50ms apart. */
@Composable
private fun RiseIn(index: Int, content: @Composable () -> Unit) {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { delay(120L + index * 50L); t.animateTo(1f, tween(380, easing = EaseOutCubic)) }
    Column(Modifier.graphicsLayer { alpha = t.value; translationY = (1f - t.value) * 18.dp.toPx() }) { content() }
}
