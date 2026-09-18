package com.wunderhand.design

import androidx.annotation.DrawableRes
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The dashboard's icons: Lucide at a 1.6 stroke, written into this module by
 * `scripts/sync-brand.sh` from the lucide-react the website builds with.
 * The names are Lucide's own, as on iOS.
 */
enum class WHIcons(@DrawableRes val drawable: Int) {
    CalendarDays(R.drawable.ic_calendar_days), Users(R.drawable.ic_users), Scissors(R.drawable.ic_scissors),
    Banknote(R.drawable.ic_banknote), Store(R.drawable.ic_store), X(R.drawable.ic_x), Check(R.drawable.ic_check),
    ChevronRight(R.drawable.ic_chevron_right), ChevronLeft(R.drawable.ic_chevron_left),
    ChevronUp(R.drawable.ic_chevron_up), ChevronDown(R.drawable.ic_chevron_down),
    ChevronsUpDown(R.drawable.ic_chevrons_up_down), Plus(R.drawable.ic_plus), Trash(R.drawable.ic_trash_2),
    Pencil(R.drawable.ic_pencil), Search(R.drawable.ic_search), Lock(R.drawable.ic_lock), Menu(R.drawable.ic_menu),
    Repeat(R.drawable.ic_repeat), Share(R.drawable.ic_share), UserPlus(R.drawable.ic_user_plus),
    ArrowUpRight(R.drawable.ic_arrow_up_right), CircleX(R.drawable.ic_circle_x),
}

/**
 * An icon, in the colour of the text around it, growing with the phone's
 * font size the way the words beside it do.
 *
 * @param label what a screen reader says. Null for an icon that only repeats
 *   the words next to it, which is most of them.
 */
@Composable
fun WHIcon(
    icon: WHIcons,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = LocalContentColor.current,
    label: String? = null,
) {
    val scaled = size * LocalDensity.current.fontScale.coerceIn(1f, 1.6f)
    Icon(painterResource(icon.drawable), contentDescription = label, modifier = modifier.size(scaled), tint = tint)
}
