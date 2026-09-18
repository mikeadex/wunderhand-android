package com.wunderhand.design

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.hypot

/** The barely-there shadow that separates a white card from the ground without a border. */
fun Modifier.liftSmall(shape: androidx.compose.ui.graphics.Shape): Modifier =
    shadow(1.dp, shape, ambientColor = WHColors.Shadow, spotColor = WHColors.Shadow)

// region Segmented control

/**
 * The diary's view switch: a well with the chosen option lifted out of it in
 * white (chairtime `Segments`). Not Material's — the web's reads as part of
 * the page, and this app should look like the same product.
 */
@Composable
fun <T> WHSegmented(options: List<Pair<T, String>>, selection: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, compact: Boolean = true) {
    val well = RoundedCornerShape(if (compact) 8.dp else 9.dp)
    val lifted = RoundedCornerShape(if (compact) 6.dp else 7.dp)
    Row(modifier.clip(well).background(WHColors.Well).padding(3.dp)) {
        for ((value, label) in options) {
            val isOn = value == selection
            Box(
                Modifier
                    // 48dp to the touch; the lifted white is drawn smaller, inside it.
                    .heightIn(min = 42.dp)
                    .then(if (isOn) Modifier.liftSmall(lifted).clip(lifted).background(WHColors.Surface) else Modifier.clip(lifted))
                    .clickable(role = Role.Tab) { onSelect(value) }
                    .semantics { selected = isOn }
                    .padding(horizontal = if (compact) 12.dp else 15.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = WHType.Meta.copy(fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal),
                    color = if (isOn) WHColors.Ink else WHColors.Neutral700,
                    maxLines = 1,
                )
            }
        }
    }
}

// endregion
// region Chips and tags

/** A person to narrow the day to: ink when chosen, white otherwise. */
@Composable
fun WHChip(title: String, isOn: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .heightIn(min = 48.dp)
            .clickable(role = Role.Tab, onClick = onClick, indication = null, interactionSource = null)
            .semantics { selected = isOn },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            title,
            modifier = Modifier.liftSmall(CircleShape).clip(CircleShape).background(if (isOn) WHColors.Ink else WHColors.Surface)
                .padding(horizontal = 14.dp, vertical = 8.dp),
            style = WHType.Meta.copy(fontWeight = if (isOn) FontWeight.SemiBold else FontWeight.Normal),
            color = if (isOn) WHColors.Bg else WHColors.Neutral700,
            maxLines = 1,
        )
    }
}

enum class TagTone { Unpaid, Accent, OnInk, Solid }

/** A small label on a card: "No deposit — take £28 in the chair". */
@Composable
fun WHTag(text: String, tone: TagTone, modifier: Modifier = Modifier) {
    when (tone) {
        TagTone.Unpaid, TagTone.Accent -> Text(
            text,
            modifier = modifier.clip(RoundedCornerShape(6.dp))
                .background(if (tone == TagTone.Unpaid) WHColors.BlockUnpaid else WHColors.Accent100)
                .padding(horizontal = 8.dp, vertical = 3.dp),
            style = WHType.Tag, color = WHColors.Accent,
        )
        TagTone.OnInk, TagTone.Solid -> Text(
            text.uppercase(),
            modifier = modifier.clip(RoundedCornerShape(5.dp))
                .background(if (tone == TagTone.OnInk) WHColors.Bg.copy(alpha = 0.2f) else WHColors.Accent)
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .semantics { },
            style = WHType.TagSmall, color = if (tone == TagTone.OnInk) WHColors.Bg else WHColors.Surface, maxLines = 1,
        )
    }
}

// endregion
// region Hatching

/**
 * Diagonal stripes, the web's `.hatch`: time that is not the client's and not
 * free either — somebody off duty, a break, a tint developing. Ink at 5.5%,
 * 135°, six on six off.
 */
fun Modifier.hatch(color: Color = WHColors.Shadow.copy(alpha = 0.055f), degrees: Float = 135f, stripe: Dp = 6.dp): Modifier = drawBehind {
    val width = stripe.toPx()
    val diagonal = hypot(size.width, size.height)
    clipRect {
        rotate(degrees) {
            var x = center.x - diagonal
            while (x < center.x + diagonal) {
                drawRect(color, topLeft = Offset(x, center.y - diagonal), size = Size(width, diagonal * 2))
                x += width * 2
            }
        }
    }
}

// endregion
// region Skeletons

/** A grey stand-in the shape of what is coming. Never a spinner: the web's
 *  loading states mirror the layout so nothing jumps when it arrives. */
@Composable
fun SkeletonBlock(height: Dp, modifier: Modifier = Modifier, width: Dp? = null, radius: Dp = 7.dp) {
    Box(
        modifier.then(if (width == null) Modifier.fillMaxWidth() else Modifier.width(width)).height(height)
            .clip(RoundedCornerShape(radius)).background(WHColors.Neutral200).clearAndSetSemantics { },
    )
}

// endregion
// region Lines and bars

/**
 * One line under the top of a screen that something needs the shop: a red dot
 * on the accent's palest tint. Never dismissable; it goes when the thing it
 * says is fixed.
 */
@Composable
fun AlertLine(text: AnnotatedString, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(WHColors.Accent100)
            .drawBehind { drawRect(WHColors.Accent.copy(alpha = 0.15f), topLeft = Offset(0f, size.height - 1.dp.toPx()), size = Size(size.width, 1.dp.toPx())) }
            .padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(WHColors.Accent))
        Text(text, Modifier.weight(1f), style = WHType.Meta, color = WHColors.Ink)
    }
}

/** How full a day is, as a bar. */
@Composable
fun LoadBar(utilisation: Double, selected: Boolean, height: Dp, modifier: Modifier = Modifier) {
    Box(
        modifier.height(height).clip(RoundedCornerShape(2.dp))
            .background(if (selected) WHColors.Bg.copy(alpha = 0.25f) else WHColors.Ink.copy(alpha = 0.15f)).clearAndSetSemantics { },
    ) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(utilisation.coerceIn(0.0, 1.0).toFloat()).background(if (selected) WHColors.InkWarm else WHColors.Ink))
    }
}

/** One line of words that ends in "…" rather than wrapping. */
@Composable
fun OneLine(text: String, style: androidx.compose.ui.text.TextStyle, color: Color, modifier: Modifier = Modifier) {
    Text(text, modifier, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

// endregion
