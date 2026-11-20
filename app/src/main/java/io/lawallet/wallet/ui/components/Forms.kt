package io.lawallet.wallet.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.lawallet.wallet.ui.theme.GlassLevel
import io.lawallet.wallet.ui.theme.LocalBrand
import io.lawallet.wallet.ui.theme.Lw
import io.lawallet.wallet.ui.theme.glass

// Form pieces shared by screens: field colours, captions, a segmented tab switch and the single-field prompt modal.

@Composable
fun lwFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedBorderColor = LocalBrand.current.t400, unfocusedBorderColor = Color.White.copy(alpha = 0.12f),
    focusedContainerColor = Color.White.copy(alpha = 0.06f), unfocusedContainerColor = Color.White.copy(alpha = 0.04f),
    cursorColor = Lw.Foreground, focusedTextColor = Lw.Foreground, unfocusedTextColor = Lw.Foreground,
    focusedPlaceholderColor = Lw.MutedFg, unfocusedPlaceholderColor = Lw.MutedFg,
    // Fields are disabled while their form submits: still readable, visibly on hold.
    disabledTextColor = Lw.Foreground.copy(alpha = 0.55f), disabledBorderColor = Color.White.copy(alpha = 0.06f),
    disabledContainerColor = Color.White.copy(alpha = 0.02f), disabledPlaceholderColor = Lw.MutedFg.copy(alpha = 0.5f),
    disabledLabelColor = Lw.MutedFg.copy(alpha = 0.6f), disabledPrefixColor = Lw.MutedFg.copy(alpha = 0.6f),
    disabledSuffixColor = Lw.MutedFg.copy(alpha = 0.6f), disabledSupportingTextColor = Lw.MutedFg.copy(alpha = 0.6f),
    disabledTrailingIconColor = Lw.MutedFg.copy(alpha = 0.5f),
)

/** Small muted caption above a field, like the Add wallet form's. */
@Composable
internal fun FieldCaption(text: String) = Txt(text, Modifier.padding(start = 4.dp), size = 13.sp, weight = FontWeight.Medium, color = Lw.MutedFg)

@Composable
internal fun TextPrompt(title: String, initial: String, onDismiss: () -> Unit, saveLabel: String = title, placeholder: String? = null, maxLength: Int = 64, onSave: (String) -> Unit) {
    // Opens focused with the current text selected: type to replace, Done (or the button) to save.
    var v by remember { mutableStateOf(androidx.compose.ui.text.input.TextFieldValue(initial, androidx.compose.ui.text.TextRange(0, initial.length))) }
    val save = { if (v.text.isNotBlank()) onSave(v.text.trim()) }
    LwAlertDialog(
        onDismissRequest = onDismiss,
        title = { DialogTitle(title) },
        text = {
            OutlinedTextField(
                v, { v = it.copy(text = it.text.take(maxLength)) }, Modifier.fillMaxWidth().heightIn(min = 60.dp).autoFocus().testTag("prompt-field"),
                placeholder = placeholder?.let { p -> { Txt(p, color = Lw.MutedFg) } },
                singleLine = true, colors = lwFieldColors(), shape = RoundedCornerShape(16.dp),
                textStyle = androidx.compose.material3.LocalTextStyle.current.copy(fontSize = 18.sp),
                keyboardOptions = KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Done),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { save() }),
            )
        },
        confirmButton = { ModalAction(saveLabel, save, Modifier.testTag("prompt-save"), enabled = v.text.isNotBlank()) },
    )
}

/** Two-segment glass switch; the selected segment takes the brand tint. */
@Composable
internal fun TabSwitch(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().glass(CircleShape, GlassLevel.Thin).padding(4.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Box(
                Modifier.weight(1f).height(40.dp)
                    .then(if (on) Modifier.glass(CircleShape, GlassLevel.Strong, tint = LocalBrand.current.t400.copy(alpha = 0.25f)) else Modifier)
                    .clickable { onSelect(i) }.testTag("bunker-tab-$i"),
                contentAlignment = Alignment.Center,
            ) { Txt(label, size = 14.sp, weight = FontWeight.Medium, color = if (on) Lw.Foreground else Lw.MutedFg) }
        }
    }
}
