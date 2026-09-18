package com.wunderhand.app.features.today

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.app.PageColumn
import com.wunderhand.core.Me
import com.wunderhand.core.ShopClock
import com.wunderhand.design.EmptyNote
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.RowDivider
import com.wunderhand.design.ScreenHeader
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHType
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * A0's stand-in for the diary: proof that sign-in, the chosen shop and the
 * shop's clock all work, drawn from `GET /me`. A1 replaces it.
 */
@Composable
fun TodayScreen(model: AppModel, me: Me) {
    val scope = rememberCoroutineScope()
    val clock = remember(me.shop.timezone) { ShopClock(me.shop.timezone) }

    PageColumn {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            // The shop's date, not the phone's.
            ScreenHeader("Today", Modifier.padding(horizontal = 4.dp), eyebrow = clock.dayEyebrow(Instant.now()))

            EmptyNote(
                "Today at ${me.shop.name}",
                "The diary arrives in the next milestone. For now this shows that you are signed in, and which shop you are acting for.",
                Modifier.padding(horizontal = 4.dp).testTag("todayAt"),
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("Signed in", Modifier.padding(horizontal = 4.dp))
                WHCard {
                    Fact("Name", me.staff.name)
                    RowDivider(Modifier.padding(start = 16.dp))
                    Fact("Email", me.user.email)
                    RowDivider(Modifier.padding(start = 16.dp))
                    Fact("At this shop", if (me.staff.isOwner) "Owner" else "Team")
                    RowDivider(Modifier.padding(start = 16.dp))
                    Fact("Shop's clock", me.shop.timezone)
                }
            }

            WHCard {
                if (me.worksAtSeveral) {
                    Action("Switch shop", WHColors.Ink, "switchShop") { scope.launch { model.switchShop() } }
                    RowDivider(Modifier.padding(start = 16.dp))
                }
                // Red words, not a red button: leaving is never the action a screen wants.
                Action("Sign out", WHColors.Accent, "signOut") { scope.launch { model.signOut() } }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = WHType.Meta, color = WHColors.Neutral700)
        Text(value, style = WHType.RowName, color = WHColors.Ink)
    }
}

@Composable
private fun Action(text: String, color: androidx.compose.ui.graphics.Color, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp).testTag(tag),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = WHType.RowName, color = color)
    }
}
