package com.wunderhand.design

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp

private val Card = RoundedCornerShape(12.dp)

// region Words

/** The small uppercase label over a group. Uppercased here, not in the
 *  string, so TalkBack reads a word and not a row of letters. */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier = modifier.semantics { contentDescription = text }, style = WHType.Eyebrow, color = WHColors.Eyebrow)
}

/** Eyebrow and title at the top of a screen. */
@Composable
fun ScreenHeader(title: String, modifier: Modifier = Modifier, eyebrow: String? = null, style: TextStyle = WHType.ScreenTitle) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (eyebrow != null) Eyebrow(eyebrow)
        Text(title, modifier = Modifier.semantics { heading() }, style = style, color = WHColors.Ink)
    }
}

/** The mark and the word, as the web's header sets them. */
@Composable
fun Lockup(modifier: Modifier = Modifier, fontSize: Double = 22.0) {
    val mark = (fontSize * 1.15).dp
    Row(modifier.clearAndSetSemantics { contentDescription = "Wunderhand" }, verticalAlignment = Alignment.Bottom) {
        Icon(painterResource(R.drawable.ic_mark), contentDescription = null, modifier = Modifier.size(mark), tint = WHColors.Ink)
        // Tucked under the right-hand blade, as the drawn lockup has it.
        Text("wunderhand", modifier = Modifier.offset(x = -(mark * 0.12f)), style = WHType.wordmark(fontSize), color = WHColors.Ink)
    }
}

// endregion
// region Actions

/**
 * The one filled action on a screen: flat red, 50dp, radius 12.
 *
 * Working is not the same as unavailable: a button busy with the tap it just
 * took stays red, with its ring, and takes no second tap.
 */
@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, loading: Boolean = false, fill: Boolean = true) {
    Box(
        modifier
            // As wide as its row by default; as wide as its words in a header beside other things.
            .then(if (fill) Modifier.fillMaxWidth() else Modifier)
            .heightIn(min = if (fill) 50.dp else 48.dp)
            .clip(Card)
            .background(WHColors.Accent.copy(alpha = if (enabled || loading) 1f else 0.45f))
            .clickable(enabled = enabled && !loading, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, modifier = Modifier.alpha(if (loading) 0f else 1f), style = WHType.Button, color = WHColors.Surface)
        if (loading) CircularProgressIndicator(Modifier.size(20.dp), color = WHColors.Surface, strokeWidth = 2.dp)
    }
}

/** The pinned bar at the bottom of a screen that holds its primary action,
 *  where a thumb already rests. */
@Composable
fun FooterBar(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(modifier.fillMaxWidth().background(WHColors.Bg)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(WHColors.Divider))
        Box(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp), contentAlignment = Alignment.Center) { content() }
    }
}

// endregion
// region Fields

/**
 * A labelled input in a white rounded card, as the web's `Field`: the label
 * small and uppercase above the value, the border darkening while it has focus.
 */
@Composable
fun WHField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** For the input itself: a focus requester, a test tag, what autofill should offer. */
    inputModifier: Modifier = Modifier,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    Column(
        modifier
            .fillMaxWidth()
            .clip(Card)
            .background(WHColors.Surface)
            .border(1.dp, if (focused) WHColors.Ink else WHColors.Divider, Card)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // The field itself carries the label for a screen reader; said twice is noise.
        Text(label.uppercase(), modifier = Modifier.clearAndSetSemantics { }, style = WHType.FieldLabel, color = WHColors.Neutral700)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = inputModifier.fillMaxWidth().heightIn(min = 24.dp).semantics { contentDescription = label },
            textStyle = WHType.FieldValue.copy(color = WHColors.Ink),
            singleLine = true,
            keyboardOptions = keyboardOptions,
            keyboardActions = keyboardActions,
            visualTransformation = visualTransformation,
            interactionSource = interaction,
            cursorBrush = SolidColor(WHColors.Accent),
        )
    }
}

// endregion
// region Notes and cards

/** A short note on the accent's lightest tint: a refusal, a warning, a hint
 *  that matters. Never a red fill — the web's state rules keep red for acting. */
@Composable
fun NoteCard(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.fillMaxWidth().clip(Card).background(WHColors.Accent100).border(1.dp, WHColors.Accent300, Card).padding(16.dp),
        style = WHType.Body,
        color = WHColors.Accent,
    )
}

/** A white card with the small lift, for grouped rows. */
@Composable
fun WHCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .shadow(1.dp, Card, ambientColor = WHColors.Shadow, spotColor = WHColors.Shadow)
            .clip(Card)
            .background(WHColors.Surface),
        content = content,
    )
}

/** A hairline between rows in a card. */
@Composable
fun RowDivider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(WHColors.Divider))
}

/**
 * A thin bar across the top of a screen: what the app could not do, and the
 * way to make it try again.
 *
 * Deliberately not a card and not a red fill. The day underneath is still
 * worth reading — this says how old it is, it does not take the screen away.
 */
@Composable
fun SignalBar(message: String, modifier: Modifier = Modifier, isTrying: Boolean = false, onRetry: (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().background(WHColors.Accent100)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 4.dp).heightIn(min = 40.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), style = WHType.Bar, color = WHColors.Accent)
            when {
                isTrying -> CircularProgressIndicator(Modifier.size(16.dp), color = WHColors.Accent, strokeWidth = 2.dp)
                // 48dp to the touch, however small the words are.
                onRetry != null -> Box(
                    Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onRetry)
                        .heightIn(min = 48.dp).padding(horizontal = 6.dp),
                    contentAlignment = Alignment.Center,
                ) { Text("Try again", style = WHType.BarAction, color = WHColors.Accent) }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(WHColors.Accent300))
    }
}

/** A list with nothing in it yet, in the shop's own words: what belongs here,
 *  and how it gets here. Never a bare "No items". */
@Composable
fun EmptyNote(title: String, says: String, modifier: Modifier = Modifier) {
    Column(modifier.widthIn(max = 520.dp).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = WHType.EmptyTitle, color = WHColors.Ink)
        Text(says, style = WHType.Body, color = WHColors.Neutral700)
    }
}

// endregion
