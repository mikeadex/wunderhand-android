package com.wunderhand.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** The second action beside the filled one: white, outlined, the same height. */
@Composable
fun SecondaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, minHeight: Dp = 50.dp) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.heightIn(min = minHeight).clip(shape).background(WHColors.Surface).border(1.dp, WHColors.Divider, shape)
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick).alpha(if (enabled || loading) 1f else 0.5f)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, Modifier.alpha(if (loading) 0f else 1f), style = WHType.Button, color = WHColors.Ink, maxLines = 1)
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = WHColors.Ink, strokeWidth = 2.dp)
    }
}

/** An action inside a card that is not the screen's one red button: ink, 48dp. */
@Composable
fun InkButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(9.dp)).background(WHColors.Ink.copy(alpha = if (enabled || loading) 1f else 0.45f))
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick).padding(horizontal = 20.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, Modifier.alpha(if (loading) 0f else 1f), style = WHType.Semi14, color = WHColors.Bg)
        if (loading) CircularProgressIndicator(Modifier.size(18.dp), color = WHColors.Bg, strokeWidth = 2.dp)
    }
}

/** Words that act, with a hand-sized target. Red for ending something; ink otherwise. */
@Composable
fun WordsButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = WHColors.Ink, enabled: Boolean = true, loading: Boolean = false) {
    Box(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .alpha(if (enabled || loading) 1f else 0.5f).padding(horizontal = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, Modifier.alpha(if (loading) 0f else 1f), style = WHType.BarAction, color = color)
        if (loading) CircularProgressIndicator(Modifier.size(16.dp), color = color, strokeWidth = 2.dp)
    }
}

/**
 * "Are you sure?", for the things that cannot be taken back. The title is the
 * question, the message is what will happen, and the way out is always
 * "Keep it" — never a bare Cancel beside "Cancel appointment".
 */
@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit, keep: String = "Keep it") {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = WHType.EmptyTitle, color = WHColors.Ink) },
        text = { Text(message, style = WHType.Body, color = WHColors.Neutral800) },
        confirmButton = { WordsButton(confirm, { onDismiss(); onConfirm() }, Modifier.testTag("confirm"), color = WHColors.Accent) },
        dismissButton = { WordsButton(keep, onDismiss, Modifier.testTag("keep")) },
        containerColor = WHColors.Surface,
        shape = RoundedCornerShape(18.dp),
    )
}
