package com.wunderhand.app.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.wunderhand.design.Lockup
import com.wunderhand.design.PrimaryButton
import com.wunderhand.design.ScreenHeader
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHType

/** The column every single-purpose screen sits in: a phone's width, centred
 *  on anything wider, and scrolling once the type is large. */
@Composable
fun PageColumn(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxSize().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 430.dp).fillMaxWidth().padding(horizontal = 18.dp).padding(top = 64.dp, bottom = 24.dp)) { content() }
    }
}

/** While a stored session is checked: the lockup on the ground, and nothing
 *  that spins. It is on screen for well under a second. */
@Composable
fun LaunchScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Lockup(fontSize = 26.0) }
}

/** A plain sentence about why there is no shop on screen, and the one thing to do about it. */
@Composable
fun MessageScreen(title: String, message: String, action: String?, onAction: () -> Unit) {
    PageColumn {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Lockup()
            ScreenHeader(title, style = WHType.PageTitle)
            Text(message, style = WHType.Body, color = WHColors.Neutral800)
            if (action != null) PrimaryButton(action, onAction, Modifier.padding(top = 8.dp))
        }
    }
}
