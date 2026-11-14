package io.lawallet.wallet.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.runtime.remember
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.chrisbanes.haze.HazeState
import io.lawallet.wallet.R
import io.lawallet.wallet.app.Tx
import io.lawallet.wallet.core.fx.Currencies
import io.lawallet.wallet.ui.components.LwIcon
import io.lawallet.wallet.ui.components.Txt
import io.lawallet.wallet.ui.theme.Inter
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.frosted
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.roundToLong
import com.composables.icons.lucide.R as L

/**
 * Home's activity search and filters. The bottom bar (AppNav, outside Home) edits it and Home's
 * sheet reads it, so it lives here rather than in either. Cleared whenever the sheet docks.
 */
@Stable
internal object ActivitySearch {
    /** Home's sheet progress (0 docked … 1 covering Home): the tab bar turns into the search bar as it rises. */
    var sheet by mutableFloatStateOf(0f)
    var query by mutableStateOf("")
    var sent by mutableStateOf(true)
    var received by mutableStateOf(true)
    var sizeOn by mutableStateOf(false)
    /** Slider thumbs as fractions of a log scale over 0..[maxSats] (sats span decades). */
    var size by mutableStateOf(0f..1f)
    var panel by mutableStateOf(false)
    // ponytail: the scale follows the largest loaded payment, so thumbs drift a little as older pages load.
    var maxSats by mutableLongStateOf(1L)

    val filters get() = (if (!sent || !received) 1 else 0) + (if (sizeOn) 1 else 0)
    val active get() = query.isNotBlank() || filters > 0

    fun sats(f: Float) = (exp(f * ln(maxSats + 1.0)) - 1).roundToLong()

    fun matches(t: Tx): Boolean {
        if (if (t.incoming) !received else !sent) return false
        if (sizeOn && t.amountSats !in sats(size.start)..sats(size.endInclusive)) return false
        val q = query.trim()
        if (q.isEmpty()) return true
        if (t.description?.contains(q, ignoreCase = true) == true) return true
        // "21,000" / "21000" find the amount.
        val digits = q.filter(Char::isDigit)
        return digits.isNotEmpty() && q.all { it.isDigit() || it in ",. " } && t.amountSats.toString().contains(digits)
    }

    fun clearFilters() { sent = true; received = true; sizeOn = false; size = 0f..1f }
    fun reset() { query = ""; panel = false; clearFilters() }
}

private val Bouncy = spring<Float>(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)

/** Replaces the tab bar while Home's activity sheet is up: search field + filters button, the panel unfolding above. */
@Composable
internal fun ActivitySearchBar(haze: HazeState?, modifier: Modifier = Modifier) {
    val s = ActivitySearch
    val brand = LocalBrand.current
    val focus = LocalFocusManager.current
    // The whole pill focuses the field, not just its one line of text.
    val field = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    BackHandler(s.panel) { s.panel = false }
    Column(modifier.fillMaxWidth().navigationBarsPadding().imePadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp, top = 12.dp)) {
        AnimatedVisibility(
            s.panel,
            enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow), expandFrom = Alignment.Bottom) +
                fadeIn(tween(220)) + scaleIn(Bouncy, initialScale = 0.92f, transformOrigin = TransformOrigin(1f, 1f)),
            exit = shrinkVertically(tween(220), shrinkTowards = Alignment.Bottom) +
                fadeOut(tween(160)) + scaleOut(tween(220), targetScale = 0.92f, transformOrigin = TransformOrigin(1f, 1f)),
        ) { FilterPanel(haze, Modifier.padding(bottom = 10.dp)) }

        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                Modifier.weight(1f).fillMaxHeight().shadow(16.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                    .frosted(haze, CircleShape).clickable(interactionSource = null, indication = null) { field.requestFocus(); keyboard?.show() }
                    .padding(start = 18.dp, end = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LwIcon(L.drawable.lucide_ic_search, size = 20.dp, tint = Lw.MutedFg)
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (s.query.isEmpty()) Txt(stringResource(R.string.activity_search_hint), size = 16.sp, color = Lw.MutedFg, maxLines = 1)
                    BasicTextField(
                        s.query, { s.query = it }, Modifier.fillMaxWidth().focusRequester(field).testTag("activity-search"),
                        singleLine = true,
                        textStyle = TextStyle(fontFamily = Inter, fontSize = 16.sp, color = Lw.Foreground),
                        cursorBrush = SolidColor(brand.t300),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
                    )
                }
                AnimatedVisibility(s.query.isNotEmpty(), enter = fadeIn(tween(150)) + scaleIn(Bouncy, 0.6f), exit = fadeOut(tween(120)) + scaleOut(tween(120), 0.6f)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).clickable { s.query = "" }, contentAlignment = Alignment.Center) {
                        LwIcon(L.drawable.lucide_ic_circle_x, size = 18.dp, tint = Lw.MutedFg, contentDescription = stringResource(R.string.activity_search_clear))
                    }
                }
            }

            val open by animateFloatAsState(if (s.panel) 1f else 0f, Bouncy, label = "filters")
            val lit = s.panel || s.filters > 0
            val bg by animateColorAsState(if (lit) brand.t400.copy(alpha = 0.35f) else Color.Transparent, tween(220), label = "filtersBg")
            Box(
                Modifier.size(56.dp).shadow(16.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                    .frosted(haze, CircleShape).background(bg, CircleShape)
                    .clickable(onClickLabel = stringResource(R.string.activity_filters)) { s.panel = !s.panel }.testTag("activity-filters"),
                contentAlignment = Alignment.Center,
            ) {
                LwIcon(
                    L.drawable.lucide_ic_sliders_horizontal, Modifier.graphicsLayer { rotationZ = 180f * open }, size = 22.dp,
                    tint = lerp(Lw.Foreground, brand.t200, open), contentDescription = stringResource(R.string.activity_filters),
                )
                androidx.compose.animation.AnimatedVisibility(s.filters > 0, Modifier.align(Alignment.TopEnd).padding(top = 12.dp, end = 12.dp), enter = scaleIn(Bouncy), exit = scaleOut(tween(150))) {
                    Box(Modifier.size(8.dp).background(brand.t300, CircleShape))
                }
            }
        }
    }
}

@Composable
private fun FilterPanel(haze: HazeState?, modifier: Modifier) {
    val s = ActivitySearch
    val brand = LocalBrand.current
    val shape = RoundedCornerShape(28.dp)
    Column(
        modifier.fillMaxWidth().shadow(16.dp, shape, ambientColor = Color.Black, spotColor = Color.Black).frosted(haze, shape).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
            Txt(stringResource(R.string.activity_filters), Modifier.weight(1f), size = 15.sp, weight = FontWeight.SemiBold)
            AnimatedVisibility(s.filters > 0, enter = fadeIn(), exit = fadeOut()) {
                Txt(stringResource(R.string.activity_filter_clear), Modifier.clip(CircleShape).clickable { s.clearFilters() }.padding(horizontal = 10.dp, vertical = 4.dp), size = 13.sp, weight = FontWeight.Medium, color = brand.t300)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(L.drawable.lucide_ic_arrow_up_right, stringResource(R.string.activity_filter_sent), s.sent, Modifier.testTag("filter-sent")) { s.sent = !s.sent }
            FilterChip(L.drawable.lucide_ic_arrow_down_right, stringResource(R.string.activity_filter_received), s.received, Modifier.testTag("filter-received")) { s.received = !s.received }
        }
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Txt(stringResource(R.string.activity_filter_amount), size = 14.sp, weight = FontWeight.Medium)
                    Txt(
                        if (s.sizeOn) stringResource(R.string.activity_filter_range, Currencies.formatSats(s.sats(s.size.start)), Currencies.formatSats(s.sats(s.size.endInclusive)))
                        else stringResource(R.string.activity_filter_anyAmount),
                        size = 12.sp, color = Lw.MutedFg, maxLines = 1,
                    )
                }
                Switch(s.sizeOn, { s.sizeOn = it }, Modifier.testTag("filter-amount"), colors = lwSwitchColors())
            }
            AnimatedVisibility(s.sizeOn, enter = expandVertically(spring(dampingRatio = 0.85f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(tween(200)), exit = shrinkVertically(tween(200)) + fadeOut(tween(140))) {
                RangeSlider(
                    s.size, { s.size = it }, Modifier.padding(top = 4.dp),
                    colors = SliderDefaults.colors(thumbColor = brand.t200, activeTrackColor = brand.t400, inactiveTrackColor = Color.White.copy(alpha = 0.10f)),
                )
            }
        }
    }
}

@Composable
private fun FilterChip(icon: Int, label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val brand = LocalBrand.current
    val bg by animateColorAsState(if (on) brand.t400.copy(alpha = 0.30f) else Color.White.copy(alpha = 0.04f), tween(200), label = "chipBg")
    val edge by animateColorAsState(if (on) brand.t300.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.12f), tween(200), label = "chipEdge")
    val fg by animateColorAsState(if (on) Lw.Foreground else Lw.MutedFg, tween(200), label = "chipFg")
    Row(
        modifier.height(36.dp).clip(CircleShape).background(bg).border(1.dp, edge, CircleShape).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LwIcon(icon, size = 16.dp, tint = fg)
        Spacer(Modifier.width(6.dp))
        Txt(label, size = 13.sp, weight = FontWeight.Medium, color = fg)
    }
}
