package com.wunderhand.app.features.shop

import androidx.compose.runtime.Composable
import com.wunderhand.app.app.AppModel
import com.wunderhand.core.Me

@Composable internal fun AccountScreen(app: AppModel, me: Me, onBack: (() -> Unit)?) = OwnerOnlyNote("Your login", onBack)
@Composable internal fun CloseShopScreen(app: AppModel, me: Me, visit: Int, onBack: (() -> Unit)?) = OwnerOnlyNote("Close this shop", onBack)
