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
 * The app's modal: [AlertDialog]'s slots, LaWallet's look (rounded glass card with a lit top edge) and
 * motion. It springs in from a slight zoom, and the window zooms out as it goes, including when the
 * caller simply stops composing it after a button tap (a window exit animation outlives the composable).
 */
@Composable
fun LwAlertDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    icon: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
    containerColor: Color = Glass.Dialog,
    properties: DialogProperties = DialogProperties(),
) {
    val zoom = remember { Animatable(0f) }
    LaunchedEffect(Unit) { zoom.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 520f)) }
    val shape = RoundedCornerShape(28.dp)
    AlertDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = { DialogWindowAnimation(); confirmButton() },
        modifier = modifier
            .graphicsLayer {
                val s = 0.86f + 0.14f * zoom.value
                scaleX = s; scaleY = s
                alpha = zoom.value.coerceIn(0f, 1f)
            }
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.03f))), shape),
        dismissButton = dismissButton,
        icon = icon,
        title = title,
        text = text,
        shape = shape,
        containerColor = containerColor,
        tonalElevation = 0.dp,
        properties = properties,
    )
}

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
