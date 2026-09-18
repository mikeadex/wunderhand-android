package com.wunderhand.app.features.shell

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.onConsumedWindowInsetsChanged
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteItemColors
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.app.PageColumn
import com.wunderhand.app.features.clients.ClientsScreen
import com.wunderhand.app.features.diary.DiaryScreen
import com.wunderhand.app.features.menu.MenuScreen
import com.wunderhand.app.features.shop.ShopScreen
import com.wunderhand.core.Me
import com.wunderhand.design.EmptyNote
import com.wunderhand.design.ScreenHeader
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType

/** The web's five tabs, in the web's order. */
enum class AppTab(val title: String, val icon: WHIcons) {
    Diary("Diary", WHIcons.CalendarDays),
    Clients("Clients", WHIcons.Users),
    Menu("Menu", WHIcons.Scissors),
    Money("Money", WHIcons.Banknote),
    Shop("Shop", WHIcons.Store),
}

/**
 * Diary · Clients · Menu · Money · Shop. A bar along the bottom of a phone; on
 * anything wider the same tabs become a rail down the side, as the web's side
 * rail does on a wide screen. The scaffold chooses by the window's width, so a
 * phone unfolded, or an app dragged into split screen, changes with it.
 */
@Composable
fun MainScaffold(model: AppModel, me: Me) {
    var tab by rememberSaveable { mutableStateOf(AppTab.Diary) }

    val selected = WHColors.Ink
    val resting = WHColors.Neutral700
    val colors = NavigationSuiteItemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            selectedIconColor = selected, selectedTextColor = selected, indicatorColor = WHColors.Well,
            unselectedIconColor = resting, unselectedTextColor = resting,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            selectedIconColor = selected, selectedTextColor = selected, indicatorColor = WHColors.Well,
            unselectedIconColor = resting, unselectedTextColor = resting,
        ),
        navigationDrawerItemColors = androidx.compose.material3.NavigationDrawerItemDefaults.colors(),
    )

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            for (item in AppTab.entries) {
                item(
                    selected = tab == item,
                    onClick = { tab = item },
                    icon = { WHIcon(item.icon, size = 22.dp) },
                    label = { Text(item.title, style = WHType.Meta) },
                    colors = colors,
                    modifier = Modifier.testTag("tab-${item.name}"),
                )
            }
        },
        navigationSuiteColors = NavigationSuiteDefaults.colors(
            navigationBarContainerColor = WHColors.Surface,
            navigationRailContainerColor = WHColors.Surface,
        ),
        containerColor = WHColors.Bg,
        contentColor = WHColors.Ink,
    ) {
        /* The keyboard's height, and the gesture bar's, are measured from the bottom of the window — and under a tab
         * bar the page does not reach it. So the page is told how much of the bottom is already spoken for, and a
         * footer that pads itself clear of the keyboard sits on the keyboard, not a tab bar's height above it. Beside
         * a rail nothing is below the page, and nothing is taken off. The scaffold has already taken off the system's
         * own gesture bar, so only what it has not is taken off here: counted twice, the footer slides under the keys. */
        val density = LocalDensity.current
        val windowHeight = LocalWindowInfo.current.containerSize.height
        var below by remember { mutableStateOf(0) }
        var already by remember { mutableStateOf(0) }
        val covered = with(density) { (below - already).coerceAtLeast(0).toDp() }
        // A new shop, or a new person, is a new diary.
        Box(Modifier.fillMaxSize().onConsumedWindowInsetsChanged { already = it.getBottom(density) }.onGloballyPositioned { below = (windowHeight - it.boundsInWindow().bottom.toInt()).coerceAtLeast(0) }.consumeWindowInsets(PaddingValues(bottom = covered))) {
        key(me.shop.id, me.staff.id) {
            when (tab) {
                AppTab.Diary -> DiaryScreen(model, me)
                AppTab.Clients -> ClientsScreen(model, me)
                AppTab.Menu -> MenuScreen(model, me)
                AppTab.Money -> Coming("Money", "What the month has taken, against the last one. It arrives in milestone A6.")
                AppTab.Shop -> ShopScreen(model, me, openMenu = { tab = AppTab.Menu })
            }
        }
        }
    }
}

/** A tab whose milestone has not been built yet, saying so plainly. */
@Composable
private fun Coming(title: String, says: String) {
    PageColumn {
        Column(Modifier.padding(horizontal = 4.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            ScreenHeader(title)
            EmptyNote("Not built yet", says)
        }
    }
}
