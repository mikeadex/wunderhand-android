package com.wunderhand.app.features.shell

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.app.PageColumn
import com.wunderhand.core.Me
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.RowDivider
import com.wunderhand.design.ScreenHeader
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHType
import kotlinx.coroutines.launch

/**
 * The Shop tab until milestone A5 builds it: who is signed in and for which
 * shop, the way to another shop, and the way out.
 */
@Composable
fun ShopStandIn(model: AppModel, me: Me) {
    val scope = rememberCoroutineScope()

    PageColumn {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ScreenHeader(me.shop.name, Modifier.padding(horizontal = 4.dp).testTag("shopName"), eyebrow = "Shop")

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Eyebrow("Signed in", Modifier.padding(horizontal = 4.dp))
                WHCard {
                    Fact("Name", me.staff.name)
                    RowDivider(Modifier.padding(start = 16.dp))
                    Fact("Email", me.user.email)
                    RowDivider(Modifier.padding(start = 16.dp))
                    Fact("At this shop", if (me.staff.isOwner) "Owner" else "Team")
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

            Text(
                "Hours, the team, outlets and the rest of the shop's settings arrive in milestone A5.",
                Modifier.padding(horizontal = 4.dp), style = WHType.Body, color = WHColors.Neutral700,
            )
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
private fun Action(text: String, color: Color, tag: String, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp).testTag(tag),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = WHType.RowName, color = color)
    }
}
