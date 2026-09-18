package com.wunderhand.app.features.auth

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.app.PageColumn
import com.wunderhand.core.Me
import com.wunderhand.design.Lockup
import com.wunderhand.design.RowDivider
import com.wunderhand.design.ScreenHeader
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import kotlinx.coroutines.launch

/**
 * For someone who works at more than one shop — a stylist renting a chair in
 * two places has one login and two diaries. Asked once; remembered after,
 * and changed from the Shop tab.
 */
@Composable
fun ShopPickerScreen(model: AppModel, me: Me) {
    val scope = rememberCoroutineScope()
    var choosing by rememberSaveable { mutableStateOf<String?>(null) }

    PageColumn {
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Lockup()
                ScreenHeader("Which shop?", style = WHType.PageTitle)
                Text(
                    "You work at ${me.shops.size} shops. Pick the one to open — you can switch from Shop at any time.",
                    style = WHType.Body, color = WHColors.Neutral800,
                )
            }

            WHCard {
                me.shops.forEachIndexed { index, shop ->
                    if (index > 0) RowDivider(Modifier.padding(start = 16.dp))
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 56.dp)
                            .clickable(enabled = choosing == null, role = Role.Button) {
                                choosing = shop.id
                                scope.launch { model.choose(shop.id); choosing = null }
                            }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                            .testTag("shop-${shop.slug}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(shop.name, Modifier.weight(1f), style = WHType.RowName, color = WHColors.Ink)
                        if (choosing == shop.id) {
                            CircularProgressIndicator(Modifier.size(18.dp), color = WHColors.Neutral500, strokeWidth = 2.dp)
                        } else {
                            WHIcon(WHIcons.ChevronRight, size = 16.dp, tint = WHColors.Neutral500)
                        }
                    }
                }
            }
        }
    }
}
