package io.lawallet.wallet.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.composables.icons.lucide.R as L
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import io.lawallet.wallet.R
import io.lawallet.wallet.ui.theme.Glass

/**
 * The app's modal: [AlertDialog]'s slots in LaWallet's look and motion. Near full width, a rounded glass
 * card with a lit top edge, a close (X) button at top left (dismissible modals only, it replaces Cancel),
 * and one prominent action ([ModalAction]). It springs in from a slight zoom, and the window zooms out
 * as it goes, including when the caller simply stops composing it after a button tap (a window exit
 * animation outlives the composable).
 */
@Composable
fun LwAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = Glass.Dialog,
    properties: DialogProperties = DialogProperties(),
) {
    val zoom = remember { Animatable(0f) }
    LaunchedEffect(Unit) { zoom.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 520f)) }
    val shape = RoundedCornerShape(28.dp)
    val closable = properties.dismissOnBackPress || properties.dismissOnClickOutside
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { DialogWindowAnimation(); confirmButton() },
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .graphicsLayer {
                val s = 0.86f + 0.14f * zoom.value
                scaleX = s; scaleY = s
                alpha = zoom.value.coerceIn(0f, 1f)
            }
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.03f))), shape),
        icon = icon,
        title = if (!closable) title else ({
            Row(verticalAlignment = Alignment.CenterVertically) {
                GlassIconButton(L.drawable.lucide_ic_x, onDismissRequest, Modifier.testTag("modal-close"), size = 36.dp, contentDescription = stringResource(R.string.common_cancel))
                if (title != null) {
                    Spacer(Modifier.width(12.dp))
                    ProvideTextStyle(LocalTextStyle.current.copy(fontSize = 19.sp, fontWeight = FontWeight.SemiBold)) { Box(Modifier.weight(1f)) { title() } }
                }
            }
        }),
        text = text,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = 0.dp,
        properties = properties.let {
            DialogProperties(it.dismissOnBackPress, it.dismissOnClickOutside, it.securePolicy, usePlatformDefaultWidth = false, decorFitsSystemWindows = it.decorFitsSystemWindows)
        },
    )
}

/** A modal's main action: full width, brand colour (red glass when [destructive]). */
@Composable
fun ModalAction(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, destructive: Boolean = false, loading: Boolean = false) =
    LwButton(text, onClick, modifier.fillMaxWidth().testTag("modal-action"), if (destructive) BtnVariant.Destructive else BtnVariant.Theme, enabled = enabled, loading = loading, height = 52.dp)

/** Gives the hosting dialog window the zoom-out exit; composed inside the dialog so it finds its window. */
@Composable
private fun DialogWindowAnimation() {
    val window = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect { window?.setWindowAnimations(R.style.LwDialogAnimation) }
}

/** A modal's only text field takes focus (keyboard up) as soon as the modal opens. */
@Composable
fun Modifier.autoFocus(): Modifier {
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() }; keyboard?.show() }
    return focusRequester(focus)
}
