package com.wunderhand.app.features.shop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.wunderhand.app.app.AppModel
import com.wunderhand.core.Me
import com.wunderhand.core.ShopResponse
import com.wunderhand.design.ConfirmDialog
import com.wunderhand.design.Eyebrow
import com.wunderhand.design.NoteCard
import com.wunderhand.design.RowDivider
import com.wunderhand.design.ScreenHeader
import com.wunderhand.design.SkeletonBlock
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import kotlinx.coroutines.launch

/**
 * The Shop tab (chairtime `app/(pro)/shop/page.tsx`): the web's groups, each
 * row carrying the one fact that says whether to open it. Hours, rules,
 * policy, reminders, the team and the outlets are changed here; the rest open
 * the web for an owner. Nothing here names a price.
 *
 * On a phone the index, and a screen replaces it. Unfolded, the index keeps its
 * own pane and the screen opens beside it, as the web's settings do on a laptop.
 *
 * @param openMenu the Menu tab, for the one row that leads there.
 */
@Composable
fun ShopScreen(app: AppModel, me: Me, openMenu: () -> Unit) {
    val model: ShopViewModel = viewModel(key = "shop-${me.shop.id}-${me.staff.id}") { ShopViewModel(me, app.client, app::handle, createSavedStateHandle()) }
    val state by model.state.collectAsStateWithLifecycle()
    val wide = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.width.toDp() } >= 840.dp
    val route = state.path.lastOrNull()

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { if (model.state.value.response != null && model.state.value.path.isEmpty()) model.load() }
    // Beside the index there is only a way back from a person to the team; on a phone, from anything.
    BackHandler(enabled = route != null && (!wide || state.path.size > 1)) { model.back() }

    Box(Modifier.fillMaxSize().background(WHColors.Bg).testTag("shop")) {
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                ShopIndex(app, model, state, wide = true, openMenu, Modifier.width(380.dp).fillMaxHeight())
                Box(Modifier.fillMaxHeight().width(1.dp).background(WHColors.Divider))
                Box(Modifier.weight(1f).fillMaxHeight()) {
                    if (route != null) ShopDestination(route, app, model, state, onBack = model::back.takeIf { state.path.size > 1 })
                    else Column(Modifier.align(Alignment.Center).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        WHIcon(WHIcons.Store, size = 30.dp, tint = WHColors.Neutral500)
                        Text("Pick something to change", style = WHType.RowName, color = WHColors.Ink)
                        Text("Hours, rules, the team and the outlets open here.", style = WHType.Body, color = WHColors.Neutral700)
                    }
                }
            }
        } else if (route != null) {
            ShopDestination(route, app, model, state, onBack = model::back)
        } else {
            ShopIndex(app, model, state, wide = false, openMenu, Modifier.fillMaxSize())
        }
    }
}

/**
 * A non-owner is not offered the rows that lead to the shop's own screens;
 * arriving any other way — a saved place from before they stopped being an
 * owner — they are told whose it is, not shown a screen that cannot be used,
 * and chairtime is asked nothing it would only refuse.
 */
@Composable
private fun ShopDestination(route: ShopRoute, app: AppModel, shop: ShopViewModel, state: ShopState, onBack: (() -> Unit)?) {
    if (!shop.isOwner && !route.isAnybodys) { OwnerOnlyNote(route.title, onBack); return }
    when (route) {
        ShopRoute.Hours -> HoursScreen(app, shop, state.visit, onBack)
        ShopRoute.Rules -> RulesScreen(app, state.visit, onBack)
        ShopRoute.Policy -> PolicyScreen(app, state.visit, onBack)
        ShopRoute.Reminders -> RemindersScreen(app, state.visit, onBack)
        ShopRoute.Team -> TeamScreen(app, shop, state.visit, onBack)
        is ShopRoute.Person -> TeamPersonScreen(route.id, app, state.visit, onBack)
        ShopRoute.Outlets -> OutletsScreen(app, shop, state.visit, onBack)
        ShopRoute.Account -> AccountScreen(app, shop.me, onBack)
        ShopRoute.Close -> CloseShopScreen(app, shop.me, state.visit, onBack)
    }
}

// region The index

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShopIndex(app: AppModel, model: ShopViewModel, state: ShopState, wide: Boolean, openMenu: () -> Unit, modifier: Modifier = Modifier) {
    val me = model.me
    val scope = rememberCoroutineScope()
    val web = LocalUriHandler.current
    val server by app.server.collectAsStateWithLifecycle()
    var confirmingSignOut by remember { mutableStateOf(false) }
    val open = state.path.firstOrNull().takeIf { wide }

    /** A screen here. */
    @Composable
    fun Link(route: ShopRoute, label: String, value: String, hint: String?, tag: String) =
        IndexRow(label, value, hint, Modifier.testTag(tag), selected = open == route, onClick = { model.open(route) })

    /** Opens on the web, where the page is. The web asks for its own sign-in. */
    @Composable
    fun Web(path: String, label: String, value: String, hint: String) =
        IndexRow(label, value, hint, web = true, onClick = { runCatching { web.openUri("${server.trimEnd('/')}/$path") } })

    PullToRefreshBox(state.isRefreshing, onRefresh = { model.load(byHand = true) }, modifier) {
        Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 18.dp).padding(top = if (wide) 20.dp else 18.dp, bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(22.dp)) {
                ScreenHeader("Shop", Modifier.testTag("shopHeading"), eyebrow = me.shop.name, style = if (wide) WHType.DiaryTitle.copy(fontSize = WHType.DiaryTitle.fontSize * 0.93f) else WHType.DiaryTitle)

                val shop = state.response
                when {
                    /* The shop is an owner's to change (chairtime `lib/auth/owner.ts`). Anybody else — a chair renter, a
                     * stylist — runs their own day, so they are shown what is theirs and none of the rows whose screens
                     * would only refuse them. A row saying "Edit" must lead somewhere it can be edited. */
                    !model.isOwner -> Group("Your work") {
                        Link(ShopRoute.Hours, "Working hours", "Edit", "Your own week · ${me.staff.name}", "shopHours")
                        Rule()
                        IndexRow("Your prices", "Look", "What you charge for the services you do", Modifier.testTag("shopYourPrices"), onClick = openMenu)
                    }
                    shop != null -> OwnerGroups(shop, me, Link = { r, l, v, h, t -> Link(r, l, v, h, t) }, Web = { p, l, v, h -> Web(p, l, v, h) })
                    state.failure != null -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        NoteCard(state.failure)
                        WordsButton("Try again", { model.load() })
                    }
                    else -> Column(Modifier.semantics { contentDescription = "Loading the shop" }, verticalArrangement = Arrangement.spacedBy(12.dp)) { repeat(3) { SkeletonBlock(150.dp, radius = 12.dp) } }
                }
                if (shop != null && model.isOwner) state.failure?.let { NoteCard(it) }

                Group("This shop, and you") {
                    PlainRow("Shop", me.shop.name)
                    Rule()
                    PlainRow("You", me.staff.name, hint = me.user.email)
                    Rule()
                    PlainRow("Role", if (me.staff.isOwner) "Owner" else "Team")
                    Rule()
                    PlainRow("Time zone", me.shop.timezone.replace('_', ' '))
                    Rule()
                    Link(ShopRoute.Account, "Your login", "Look", "The email you sign in with, and how to delete it", "shopAccount")
                }

                if (me.shops.size > 1) Group("Your shops") {
                    me.shops.forEachIndexed { index, other ->
                        if (index > 0) Rule()
                        val isOpen = other.id == me.shop.id
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(enabled = !isOpen && state.switchingTo == null, role = Role.Button) {
                                model.switching(other.id)
                                scope.launch { app.choose(other.id); model.switching(null) }
                            }.semantics(mergeDescendants = true) { contentDescription = if (isOpen) "${other.name}, open now" else "Switch to ${other.name}" }.padding(horizontal = 16.dp).testTag("switchShop"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(other.name, Modifier.weight(1f).clearAndSetSemantics { }, style = WHType.RowName, color = WHColors.Ink)
                            if (state.switchingTo == other.id) CircularProgressIndicator(Modifier.size(18.dp), color = WHColors.Ink, strokeWidth = 2.dp)
                            else if (isOpen) WHIcon(WHIcons.Check, size = 18.dp, tint = WHColors.Ink)
                        }
                    }
                }

                Box { WordsButton("Sign out", { confirmingSignOut = true }, Modifier.testTag("signOut"), color = WHColors.Accent) }
            }
        }
    }

    if (confirmingSignOut) {
        ConfirmDialog(
            title = "Sign out of Wunderhand on this device?", message = "You will need your email and password to get back in.",
            confirm = "Sign out", keep = "Stay signed in", onConfirm = { scope.launch { app.signOut() } }, onDismiss = { confirmingSignOut = false },
        )
    }
}

@Composable
private fun OwnerGroups(
    shop: ShopResponse, me: Me,
    Link: @Composable (ShopRoute, String, String, String?, String) -> Unit,
    Web: @Composable (String, String, String, String) -> Unit,
) {
    Group("Your shop") {
        Link(ShopRoute.Team, "Team", shop.teamValue, shop.teamHint, "shopTeam")
        Rule()
        Link(ShopRoute.Outlets, "Outlets", shop.outletsValue, shop.outletsHint, "shopOutlets")
        Rule()
        Link(ShopRoute.Hours, "Working hours", shop.hoursValue, shop.hoursHint(me.staff.name), "shopHours")
    }
    Group("Taking bookings") {
        Link(ShopRoute.Rules, "Booking rules", shop.rules.summary, shop.rules.detail, "shopRules")
        Rule()
        Link(ShopRoute.Policy, "Deposits and cancellation", shop.policyValue(me.shop.currency), shop.policyHint, "shopPolicy")
        Rule()
        Web("shop/pricing", "Quiet times", "Look", "Which hours are not selling, and what to charge for them")
        Rule()
        Link(ShopRoute.Reminders, "Reminders", shop.remindersValue, shop.remindersHint, "shopReminders")
    }
    Group("What clients see") {
        Web("shop/profile", "Your page", "Edit", "Photos, your story, the link for your bio")
        Rule()
        Web("shop/site", "Your website", shop.siteValue, shop.siteHint)
        Rule()
        Web("shop/promotions", "Offers", "Edit", "Discounts to win a client back, and what each one costs")
        Rule()
        Web("shop/consent", "Consent forms", "Edit", "The wording clients sign, kept as it read on the day")
    }
    Group("Money") {
        Web("shop/payments", "Getting paid", shop.paymentsValue, shop.paymentsHint)
        Rule()
        /* What they are on, and no way from here to change it. The app sells nothing and never shows a price: a row
         * that named what a shop pays and opened the page to pay it is the one thing on this screen a store's review
         * would read as a purchase outside it. Billing stays on the web, where it was bought. */
        PlainRow("Plan", shop.planValue, Modifier.testTag("shopPlan"), hint = "Seats and outlets on this shop")
        Rule()
        Web("shop/export", "Export your data", "Download", "Your clients and appointments, as files any system can read")
        Rule()
        // Last, and quiet: the way out should be findable, not prominent.
        Link(ShopRoute.Close, "Close this shop", "Close", "Cancel the plan and, thirty days later, delete everything", "shopClose")
    }
}

@Composable
private fun Group(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Eyebrow(title, Modifier.padding(horizontal = 4.dp))
        WHCard(content = content)
    }
}

@Composable
private fun Rule() = RowDivider(Modifier.padding(start = 16.dp))

/**
 * A row on the index: what it is, the one fact that says whether to open it,
 * and a line under. A chevron for a screen here; an arrow out for one that
 * opens the web.
 */
@Composable
private fun IndexRow(label: String, value: String, hint: String?, modifier: Modifier = Modifier, web: Boolean = false, selected: Boolean = false, onClick: () -> Unit) {
    val spoken = listOfNotNull(label, value, hint, "Opens on the web".takeIf { web }).joinToString(", ")
    Row(
        modifier.fillMaxWidth().background(if (selected) WHColors.Well else WHColors.Surface).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = spoken; this.selected = selected },
    ) {
        Row(Modifier.weight(1f).clearAndSetSemantics { }.padding(horizontal = 16.dp, vertical = 13.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(label, style = WHType.RowName, color = WHColors.Ink)
                if (hint != null) Text(hint, style = WHType.Meta, color = WHColors.Neutral700)
            }
            Text(value, Modifier.widthIn(max = 150.dp), style = WHType.Semi14, color = WHColors.Neutral800, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            WHIcon(if (web) WHIcons.ArrowUpRight else WHIcons.ChevronRight, size = 16.dp, tint = WHColors.Neutral500)
        }
    }
}

/** A fact that leads nowhere: who you are, what the shop is on. */
@Composable
private fun PlainRow(label: String, value: String, modifier: Modifier = Modifier, hint: String? = null) {
    Row(
        modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = listOfNotNull("$label: $value", hint).joinToString(", ") }.padding(horizontal = 16.dp, vertical = 13.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = WHType.RowName, color = WHColors.Ink)
            if (hint != null) Text(hint, style = WHType.Meta, color = WHColors.Neutral700)
        }
        Text(value, Modifier.widthIn(max = 190.dp).clearAndSetSemantics { }, style = WHType.Semi14, color = WHColors.Neutral800, textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}

// endregion
