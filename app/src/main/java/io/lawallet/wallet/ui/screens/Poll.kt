package io.lawallet.wallet.ui.screens

import io.lawallet.wallet.ui.components.lwFieldColors
import io.lawallet.wallet.ui.components.DialogTitle
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalContext
import io.lawallet.wallet.ui.components.toast
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import io.lawallet.wallet.R
import io.lawallet.wallet.core.api.ApiException
import io.lawallet.wallet.core.api.PollState
import io.lawallet.wallet.core.api.PollState.Companion.CUSTOM
import io.lawallet.wallet.ui.LocalContainer
import io.lawallet.wallet.ui.LocalNav
import io.lawallet.wallet.ui.awaitSettled
import io.lawallet.wallet.ui.components.BtnVariant
import io.lawallet.wallet.ui.components.ErrorText
import io.lawallet.wallet.ui.components.GlassIconButton
import io.lawallet.wallet.ui.components.LwAlertDialog
import io.lawallet.wallet.ui.components.LwButton
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.ModalAction
import io.lawallet.wallet.ui.components.MutedText
import io.lawallet.wallet.ui.components.SettingsHeader
import io.lawallet.wallet.ui.components.Spinner
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.components.autoFocus
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import com.composables.icons.lucide.R as L

/** Rank badge tints: gold, silver, bronze; the rest muted. */
private val Silver = Color(0xFFC3CAD4)
private val Bronze = Color(0xFFD08A4E)
internal fun rankColor(i: Int) = when (i) { 0 -> Lw.Gold; 1 -> Silver; 2 -> Bronze; else -> Lw.MutedFg }

internal fun pollCacheKey(slug: String) = "poll:$slug"

/** A TEXT poll's answer limit (the server's). */
private const val TEXT_MAX = 280

/**
 * A preference poll the instance published (`/api/polls/:slug`). RANKING polls are a drag-to-sort list (best first);
 * SINGLE_CHOICE polls pick one row. Either can take one custom answer when the poll allows it. One-time polls go
 * read-only once answered; updatable ones can be re-submitted any time.
 */
@Composable
fun PollScreen(slug: String) {
    val c = LocalContainer.current
    val nav = LocalNav.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val ser = PollState.serializer()
    var state by remember { mutableStateOf(c.prefs.cached(pollCacheKey(slug), ser)) }
    var failed by remember { mutableStateOf(false) }
    // The user's working answer; null until the poll is known.
    var choices by remember { mutableStateOf(state?.let(::initialChoices)) }
    // SINGLE_CHOICE: the picked row (the list keeps its order so rows don't jump on tap).
    var picked by remember { mutableStateOf(state?.response?.choices?.firstOrNull()) }
    // TEXT: the written answer.
    var text by remember { mutableStateOf(state?.response?.choices?.firstOrNull()?.removePrefix(CUSTOM).orEmpty()) }
    var dirty by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    fun apply(s: PollState) {
        state = s
        c.prefs.setCached(pollCacheKey(slug), ser, s)
        if (!dirty) { choices = initialChoices(s); picked = s.response?.choices?.firstOrNull(); text = picked?.removePrefix(CUSTOM).orEmpty() }
    }
    LaunchedEffect(slug) {
        nav.awaitSettled()
        runCatching { c.session.api.value.poll(slug) }.onSuccess { apply(it); failed = false }.onFailure { failed = true }
    }

    val s = state
    val list = choices
    val locked = s?.locked == true || s?.poll?.open == false
    val genericError = stringResource(R.string.poll_error)
    val savedMsg = stringResource(R.string.poll_saved)
    val context = LocalContext.current
    fun submit() {
        val p = s ?: return
        val answer = when {
            p.poll.freeText -> listOf(CUSTOM + text.trim())
            p.poll.ranking -> list.orEmpty()
            else -> listOfNotNull(picked)
        }
        saving = true; error = null
        scope.launch {
            runCatching { c.session.api.value.submitPoll(slug, answer) }
                .onSuccess {
                    dirty = false
                    apply(p.copy(response = it))
                    haptics.performHapticFeedback(HapticFeedbackType.Confirm)
                    // Back to where they came from (Home's card re-reads the cache and shows the new ranking).
                    context.toast(savedMsg)
                    nav.popBackStack()
                }
                .onFailure { e ->
                    error = (e as? ApiException)?.message ?: genericError
                    if ((e as? ApiException)?.status == 409) runCatching { c.session.api.value.poll(slug) }.onSuccess { dirty = false; apply(it) }
                }
            saving = false
        }
    }

    Column(Modifier.fillMaxSize().testTag("poll-$slug")) {
        SettingsHeader(s?.poll?.title ?: stringResource(R.string.poll_title), { nav.popBackStack() })
        when {
            s == null || list == null -> Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                if (failed) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Txt(stringResource(R.string.poll_unavailable), size = 16.sp, weight = FontWeight.SemiBold)
                    MutedText(stringResource(R.string.poll_unavailable_hint))
                } else Spinner()
            }
            else -> {
                val lazy = rememberLazyListState()
                // Index 0 is the intro item; the options follow it.
                val reorder = rememberReorderableLazyListState(lazy) { from, to ->
                    choices = list.toMutableList().apply { add(to.index - 1, removeAt(from.index - 1)) }
                    dirty = true
                    haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick)
                }
                val ranking = s.poll.ranking
                LazyColumn(Modifier.weight(1f).fillMaxWidth(), lazy, PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item(key = "intro") { PollIntro(slug, s, locked) }
                    if (s.poll.freeText) item(key = "text") {
                        OutlinedTextField(
                            text, { text = it.take(TEXT_MAX); dirty = true }, Modifier.fillMaxWidth().heightIn(min = 140.dp).testTag("poll-text"),
                            enabled = !locked, shape = RoundedCornerShape(20.dp), colors = lwFieldColors(),
                            placeholder = { Txt(stringResource(R.string.poll_text_placeholder), color = Lw.MutedFg) },
                            supportingText = { Txt("${text.length}/$TEXT_MAX", Modifier.fillMaxWidth(), size = 12.sp, color = Lw.MutedFg, align = TextAlign.End) },
                        )
                    }
                    items(list, key = { it }) { choice ->
                        val index = list.indexOf(choice)
                        if (ranking) ReorderableItem(reorder, key = choice, enabled = !locked) { dragging ->
                            val lift by animateFloatAsState(if (dragging) 1f else 0f, label = "lift")
                            val move = { by: Int ->
                                val to = (index + by).coerceIn(0, list.lastIndex)
                                if (to != index) { choices = list.toMutableList().apply { add(to, removeAt(index)) }; dirty = true }
                            }
                            val up = stringResource(R.string.poll_move_up)
                            val down = stringResource(R.string.poll_move_down)
                            PollRow(
                                Modifier
                                    .graphicsLayer { val sc = 1f + 0.03f * lift; scaleX = sc; scaleY = sc }
                                    .shadow((14 * lift).dp, RoundedCornerShape(20.dp), clip = false)
                                    .then(if (locked) Modifier else Modifier.longPressDraggableHandle(
                                        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                        onDragStopped = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                                    ))
                                    .semantics { customActions = listOf(CustomAccessibilityAction(up) { move(-1); true }, CustomAccessibilityAction(down) { move(1); true }) }
                                    .testTag("poll-row-$choice"),
                                label = s.label(choice), custom = choice.startsWith(CUSTOM), highlight = lift,
                                lead = { RankBadge(index) },
                                // ✕ skips an option (it drops to "Skipped" below) or deletes a custom answer.
                                onRemove = if (locked) null else ({ choices = list - choice; dirty = true }),
                                removeLabel = stringResource(if (choice.startsWith(CUSTOM)) R.string.poll_custom_remove else R.string.poll_skip),
                                removeTag = "poll-remove-$choice",
                                trail = if (locked) null else ({
                                    Box(Modifier.size(40.dp).draggableHandle(
                                        onDragStarted = { haptics.performHapticFeedback(HapticFeedbackType.GestureThresholdActivate) },
                                        onDragStopped = { haptics.performHapticFeedback(HapticFeedbackType.GestureEnd) },
                                    ).testTag("poll-handle-$choice"), contentAlignment = Alignment.Center) {
                                        LwIcon(L.drawable.lucide_ic_grip_vertical, size = 20.dp, tint = Lw.MutedFg, contentDescription = stringResource(R.string.poll_reorder))
                                    }
                                }),
                            )
                        } else {
                            val on = picked == choice
                            PollRow(
                                Modifier.clip(RoundedCornerShape(20.dp)).then(if (locked) Modifier else Modifier.clickable {
                                    picked = choice; dirty = true
                                    haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                                }).testTag("poll-row-$choice"),
                                label = s.label(choice), custom = choice.startsWith(CUSTOM), highlight = if (on) 1f else 0f,
                                lead = { Radio(on) },
                                onRemove = if (choice.startsWith(CUSTOM) && !locked) ({ choices = list - choice; if (on) picked = null; dirty = true }) else null,
                                removeLabel = stringResource(R.string.poll_custom_remove), removeTag = "poll-remove-$choice",
                            )
                        }
                    }
                    if (s.poll.allowCustom && !s.poll.freeText && !locked && list.none { it.startsWith(CUSTOM) }) item(key = "add") {
                        AddOwnRow { adding = true }
                    }
                    // Options left out of the ranking, crossed out; a tap puts one back at the end.
                    val skipped = if (ranking) s.poll.options.filter { it.id !in list } else emptyList()
                    if (skipped.isNotEmpty()) item(key = "skipped") {
                        Txt(stringResource(R.string.poll_skipped), Modifier.padding(start = 6.dp, top = 8.dp), size = 13.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
                    }
                    items(skipped, key = { "skip:" + it.id }) { o ->
                        PollRow(
                            Modifier.clip(RoundedCornerShape(20.dp)).then(if (locked) Modifier else Modifier.clickable {
                                choices = list + o.id; dirty = true
                                haptics.performHapticFeedback(HapticFeedbackType.SegmentTick)
                            }).animateItem().testTag("poll-skipped-${o.id}"),
                            label = o.label, custom = false, highlight = 0f, struck = true,
                            lead = { Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) { LwIcon(L.drawable.lucide_ic_minus, size = 18.dp, tint = Lw.MutedFg) } },
                            trail = if (locked) null else ({
                                Box(Modifier.size(40.dp), contentAlignment = Alignment.Center) {
                                    LwIcon(L.drawable.lucide_ic_plus, size = 18.dp, tint = Lw.MutedFg, contentDescription = stringResource(R.string.poll_add_back))
                                }
                            }),
                        )
                    }
                }
                Column(Modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp)) {
                    AnimatedVisibility(locked, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                        SavedBanner(locked, s.poll.updatable)
                    }
                    ErrorText(error, Modifier.padding(bottom = 8.dp))
                    if (!locked) LwButton(
                        stringResource(if (s.response == null) R.string.poll_submit else R.string.poll_update),
                        ::submit, Modifier.fillMaxWidth().testTag("poll-submit"), BtnVariant.Theme,
                        enabled = !saving && (s.response == null || dirty) && when {
                            s.poll.freeText -> text.isNotBlank()
                            ranking -> list.isNotEmpty()
                            else -> picked != null
                        }, loading = saving, height = 52.dp,
                    )
                }
            }
        }
    }

    if (adding) {
        var text by remember { mutableStateOf("") }
        LwAlertDialog(
            onDismissRequest = { adding = false },
            title = { DialogTitle(stringResource(R.string.poll_custom_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    MutedText(stringResource(R.string.poll_custom_hint))
                    OutlinedTextField(text, { text = it.take(40) }, Modifier.fillMaxWidth().autoFocus().testTag("poll-custom-field"), singleLine = true, colors = lwFieldColors(),
                        placeholder = { Txt(stringResource(R.string.poll_custom_placeholder), color = Lw.MutedFg) })
                }
            },
            confirmButton = {
                ModalAction(stringResource(R.string.poll_custom_add), {
                    val entry = CUSTOM + text.trim()
                    val current = choices.orEmpty()
                    // Ranking: lands last, to be dragged up. Single choice: picked right away.
                    choices = current + entry
                    if (state?.poll?.ranking == false) picked = entry
                    dirty = true; adding = false
                }, enabled = text.isNotBlank())
            },
        )
    }
}

/** Ranking: the stored answer (what it left out stays skipped), else every option in the poll's order. Single choice: every row. */
private fun initialChoices(s: PollState): List<String> {
    val ids = s.poll.options.map { it.id }
    val saved = s.response?.choices.orEmpty().filter { it in ids || it.startsWith(CUSTOM) }
    return when {
        s.poll.freeText -> emptyList()
        s.poll.ranking -> if (s.response != null) saved else ids
        else -> ids + saved.filter { it.startsWith(CUSTOM) }.take(1)
    }
}

@Composable
private fun PollIntro(slug: String, s: PollState, locked: Boolean) {
    Column(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(44.dp).glass(CircleShape, GlassLevel.Thin, tint = Lw.Tether.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                if (slug.startsWith("usdt")) Image(painterResource(R.drawable.ic_usdt), null, Modifier.size(24.dp))
                else LwIcon(L.drawable.lucide_ic_vote, size = 22.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val (prompt, hint) = when {
                    s.poll.freeText -> R.string.poll_text_prompt to R.string.poll_text_hint
                    s.poll.ranking -> R.string.poll_rank_prompt to R.string.poll_rank_hint
                    else -> R.string.poll_pick_prompt to R.string.poll_pick_hint
                }
                Txt(stringResource(prompt), size = 15.sp, weight = FontWeight.SemiBold)
                Txt(stringResource(hint), size = 12.sp, color = Lw.MutedFg)
            }
        }
        s.poll.description?.takeIf { it.isNotBlank() }?.let { MutedText(it) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            LwIcon(if (s.poll.updatable) L.drawable.lucide_ic_pencil else L.drawable.lucide_ic_lock, size = 13.dp, tint = Lw.MutedFg)
            Spacer(Modifier.width(6.dp))
            Txt(
                stringResource(when { !s.poll.open -> R.string.poll_closed; s.poll.updatable -> R.string.poll_updatable; else -> R.string.poll_one_time }),
                size = 12.sp, color = Lw.MutedFg,
            )
        }
    }
}

@Composable
private fun PollRow(
    modifier: Modifier,
    label: String,
    custom: Boolean,
    highlight: Float,
    lead: @Composable () -> Unit,
    onRemove: (() -> Unit)? = null,
    removeLabel: String? = null,
    removeTag: String = "poll-remove",
    trail: (@Composable () -> Unit)? = null,
    struck: Boolean = false,
) {
    val brand = LocalBrand.current
    Row(
        modifier.fillMaxWidth().height(64.dp)
            .glass(RoundedCornerShape(20.dp), if (highlight > 0.5f) GlassLevel.Strong else GlassLevel.Regular, tint = brand.t400.copy(alpha = 0.10f * highlight))
            .padding(start = 12.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        lead()
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Txt(label, size = 16.sp, weight = if (struck) FontWeight.Normal else FontWeight.SemiBold, maxLines = 1, color = if (struck) Lw.MutedFg else Lw.Foreground,
                decoration = if (struck) TextDecoration.LineThrough else null)
            if (custom) Txt(stringResource(R.string.poll_custom_tag), size = 11.sp, color = Lw.MutedFg)
        }
        if (onRemove != null) GlassIconButton(L.drawable.lucide_ic_x, onRemove, Modifier.testTag(removeTag), size = 30.dp, contentDescription = removeLabel)
        trail?.invoke()
    }
}

@Composable
private fun RankBadge(index: Int) {
    val tint = rankColor(index)
    Box(
        Modifier.size(34.dp).clip(CircleShape).background(tint.copy(alpha = if (index < 3) 0.18f else 0.08f)).border(1.dp, tint.copy(alpha = if (index < 3) 0.55f else 0.2f), CircleShape),
        contentAlignment = Alignment.Center,
    ) { Txt("${index + 1}", size = 15.sp, weight = FontWeight.Bold, color = if (index < 3) tint else Lw.Foreground) }
}

@Composable
private fun Radio(checked: Boolean) {
    val brand = LocalBrand.current
    Box(Modifier.size(24.dp).clip(CircleShape).border(2.dp, if (checked) brand.t400 else Lw.MutedFg.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
        if (checked) Box(Modifier.size(12.dp).clip(CircleShape).background(brand.t400))
    }
}

/** Dashed glass row that opens the custom-answer modal. */
@Composable
private fun AddOwnRow(onClick: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick).testTag("poll-add-custom")) {
        Canvas(Modifier.matchParentSize()) {
            drawRoundRect(
                Lw.MutedFg.copy(alpha = 0.45f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(20.dp.toPx()),
                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))),
            )
        }
        Row(Modifier.matchParentSize(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
            LwIcon(L.drawable.lucide_ic_plus, size = 18.dp, tint = Lw.MutedFg)
            Spacer(Modifier.width(8.dp))
            Txt(stringResource(R.string.poll_custom_cta), size = 14.sp, weight = FontWeight.Medium, color = Lw.MutedFg)
        }
    }
}

@Composable
private fun SavedBanner(locked: Boolean, updatable: Boolean) {
    Row(
        Modifier.fillMaxWidth().padding(bottom = 10.dp).glass(RoundedCornerShape(16.dp), GlassLevel.Thin, tint = Lw.Green.copy(alpha = 0.14f)).padding(horizontal = 14.dp, vertical = 10.dp).testTag("poll-saved"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LwIcon(L.drawable.lucide_ic_circle_check, size = 18.dp, tint = Lw.Green)
        Spacer(Modifier.width(10.dp))
        Txt(stringResource(if (locked && !updatable) R.string.poll_saved_locked else R.string.poll_saved), size = 13.sp, color = Lw.Foreground)
    }
}
