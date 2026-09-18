package com.wunderhand.app.features.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.wunderhand.design.CloseButton
import com.wunderhand.design.FooterBar
import com.wunderhand.design.SheetTitle
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHType

/** The sheet something is changed in: full height, the app's ground, no handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = WHColors.Bg, dragHandle = null) { content() }
}

/**
 * What every editor looks like: the way out, what is being changed, a sentence
 * or two about it, the fields, and the one button pinned under them where a
 * thumb finds it with the keyboard up.
 */
@Composable
fun EditorPage(
    title: String, onClose: () -> Unit, tag: String, modifier: Modifier = Modifier,
    intro: String? = null, accentIntro: String? = null, back: Boolean = false,
    footer: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(WHColors.Bg).imePadding().testTag(tag)) {
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), contentAlignment = Alignment.TopCenter) {
            Column(Modifier.widthIn(max = 640.dp).fillMaxWidth().padding(horizontal = 18.dp).padding(bottom = 24.dp)) {
                CloseButton(onClose, Modifier.padding(top = 12.dp).testTag("$tag-close"), back = back)
                SheetTitle(title, Modifier.padding(top = 10.dp).testTag("$tag-heading"))
                if (intro != null) Text(intro, Modifier.padding(top = 10.dp), style = WHType.Body, color = WHColors.Neutral800)
                if (accentIntro != null) Text(accentIntro, Modifier.padding(top = 8.dp), style = WHType.Body, color = WHColors.Accent)
                content()
            }
        }
        if (footer != null) FooterBar(Modifier.navigationBarsPadding()) { Box(Modifier.widthIn(max = 604.dp)) { footer() } }
    }
}
