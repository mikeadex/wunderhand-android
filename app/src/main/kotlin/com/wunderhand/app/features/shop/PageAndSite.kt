package com.wunderhand.app.features.shop

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.wunderhand.app.app.AppModel
import com.wunderhand.app.app.SaveProblem
import com.wunderhand.app.app.WebLink
import com.wunderhand.app.features.menu.ServicePhoto
import com.wunderhand.core.PageResponse
import com.wunderhand.core.PageWords
import com.wunderhand.core.ServerImage
import com.wunderhand.core.ShopClock
import com.wunderhand.core.SiteResponse
import com.wunderhand.core.SiteWords
import com.wunderhand.design.ChoiceField
import com.wunderhand.design.ConfirmDialog
import com.wunderhand.design.Hint
import com.wunderhand.design.InkButton
import com.wunderhand.design.NoteCard
import com.wunderhand.design.SavedLine
import com.wunderhand.design.SecondaryButton
import com.wunderhand.design.SwitchRow
import com.wunderhand.design.RowDivider
import com.wunderhand.design.WHCard
import com.wunderhand.design.WHColors
import com.wunderhand.design.WHField
import com.wunderhand.design.WHIcon
import com.wunderhand.design.WHIcons
import com.wunderhand.design.WHType
import com.wunderhand.design.WordsButton
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Your page and Your website: the last two Shop rows that opened the web for
// something an owner changes. Nothing here names what a website costs, says it
// can be bought, or leads to where it is — a shop without an address of its
// own is simply not shown the part about domains.

// region What these two screens share

/** A part of a long screen: its name, what it is for, and what is in it. */
@Composable
private fun Group(
    title: String, modifier: Modifier = Modifier, hint: String? = null,
    action: String? = null, actionTag: String = "", actionBusy: Boolean = false, onAction: () -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.padding(top = 28.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title.uppercase(), Modifier.weight(1f).padding(horizontal = 4.dp).semantics { contentDescription = title; heading() }, style = WHType.Eyebrow, color = WHColors.Eyebrow)
            if (action != null) WordsButton(action, onAction, Modifier.testTag(actionTag), color = WHColors.Neutral700, loading = actionBusy)
        }
        if (hint != null) Hint(hint)
        content()
    }
}

/** What a change did, or why it was refused, beside the thing it was about. */
@Composable
private fun SaidLine(said: Said?, where: String, tag: String, modifier: Modifier = Modifier) {
    if (said == null || said.where != where) return
    if (said.isProblem) NoteCard(said.text, modifier.testTag(tag)) else Box(modifier.padding(horizontal = 4.dp).testTag(tag)) { SavedLine(said.text) }
}

/** A refusal about one line, said under that line. */
@Composable
private fun FieldProblem(problem: SaveProblem?, field: String) {
    if (problem?.field != field) return
    Text(problem.text, Modifier.padding(horizontal = 4.dp).testTag("problem-$field"), style = WHType.Meta, color = WHColors.Accent)
}

/** Copies a line, and says that it has. */
@Composable
private fun CopyButton(text: String, what: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var copied by remember(text) { mutableStateOf(false) }
    WordsButton(if (copied) SiteWords.COPIED else SiteWords.COPY, {
        context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(what, text))
        copied = true
    }, modifier.semantics { contentDescription = if (copied) "Copied $what" else "Copy $what" })
}

/** A picture from the server, or the grey it will fill. */
@Composable
private fun Picture(url: String?, server: String, says: String?, modifier: Modifier = Modifier, scale: ContentScale = ContentScale.Crop, alignment: Alignment = Alignment.Center) {
    val reachable = ServerImage.reachable(url, server)
    if (reachable == null) Box(modifier.background(WHColors.Well), contentAlignment = Alignment.Center) { WHIcon(WHIcons.CircleX, size = 18.dp, tint = WHColors.Neutral500) }
    else AsyncImage(reachable, contentDescription = says, modifier.background(WHColors.Well), contentScale = scale, alignment = alignment)
}

private val sentences = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences)
private val addresses = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false, keyboardType = KeyboardType.Uri, imeAction = ImeAction.Done)

private object PagePicture {
    /** What chairtime takes in one photograph. */
    const val MAX_BYTES = 4 * 1024 * 1024

    /**
     * A logo's own PNG, when that is what was picked and it is small enough to
     * send as it is: turned into a JPEG, what is see-through would go white.
     * Null for anything else, which goes the way every other photograph does.
     */
    fun png(context: Context, uri: Uri): ByteArray? {
        if (context.contentResolver.getType(uri) != "image/png") return null
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                    if (out.size() >= MAX_BYTES) return@use null
                }
                out.toByteArray().takeIf { it.isNotEmpty() }
            }
        }.getOrNull()
    }
}

// endregion
// region Your page

/**
 * Your page (chairtime `app/(pro)/shop/profile`): what somebody sees who has
 * not met the shop. The words and the trades are saved together by the button
 * under the screen; a photograph, a switch and publishing happen as asked.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PageScreen(app: AppModel, shop: ShopViewModel, visit: Int, onBack: (() -> Unit)?) {
    val model: PageViewModel = viewModel(key = "shop-page") { PageViewModel(app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    val state by model.state.collectAsStateWithLifecycle()
    val server by app.server.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val page = state.response
    val draft = state.draft
    val problem = state.problem
    var removingPhoto by remember { mutableStateOf<PageResponse.Photo?>(null) }
    var removingImage by remember { mutableStateOf<String?>(null) }
    var takingDown by remember { mutableStateOf(false) }

    // One picker for the cover, the logo and the work: what it was opened for is remembered across it.
    var pickingFor by rememberSaveable { mutableStateOf("work") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val where = pickingFor
        scope.launch {
            if (where == "logo") {
                val png = withContext(Dispatchers.IO) { PagePicture.png(context, uri) }
                if (png != null) { model.upload("logo", png, "image/png"); return@launch }
            }
            val jpeg = withContext(Dispatchers.IO) { ServicePhoto.jpeg(context, uri) }
            when {
                jpeg == null || jpeg.size >= PagePicture.MAX_BYTES -> model.unreadable(where)
                where == "work" -> model.addPhoto(jpeg)
                else -> model.upload(where, jpeg)
            }
        }
    }
    fun pick(where: String) { pickingFor = where; picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }

    SettingPage(
        PageWords.TITLE, "page", onBack, intro = PageWords.INTRO,
        eyebrow = page?.let { if (it.published) PageWords.LIVE else PageWords.NOT_PUBLISHED } ?: "Shop", closeLabel = "Back to the shop",
        save = PageWords.SAVE.takeIf { state.isChanged || state.isSaving || state.justSaved }, onSave = { model.save() }, isSaving = state.isSaving, justSaved = state.justSaved,
    ) {
        Failure(state.failure, page == null) { model.load() }
        if (page == null) { if (state.failure == null) Loading("your page", 4, 96); return@SettingPage }
        if (!page.storage) NoteCard(PageWords.NO_STORAGE, Modifier.padding(top = 16.dp).testTag("pageNoStorage"))
        // A refusal about no line in particular; one about a line is said under that line.
        if (problem != null && problem.field !in setOf("headline", "about", "instagram", "website", "phone")) Problem(problem, "pageProblem")
        val canUpload = page.storage && state.busy == null

        Column(Modifier.padding(top = 20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WHField(
                PageWords.HEADLINE, draft.headline, { v -> model.edit { it.copy(headline = v) } }, inputModifier = Modifier.testTag("field-pageHeadline"),
                keyboardOptions = sentences, singleLine = false, isProblem = problem?.field == "headline", placeholder = "Skin fades and beard work in Dalston", maxLines = 3,
            )
            FieldProblem(problem, "headline")
            Hint(PageWords.HEADLINE_HINT)
        }
        Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            WHField(
                PageWords.ABOUT, draft.about, { v -> model.edit { it.copy(about = v) } }, inputModifier = Modifier.heightIn(min = 96.dp).testTag("field-pageAbout"),
                keyboardOptions = sentences, singleLine = false, isProblem = problem?.field == "about", maxLines = Int.MAX_VALUE,
            )
            FieldProblem(problem, "about")
            Hint(PageWords.ABOUT_HINT)
        }

        Group(PageWords.TRADES, Modifier.testTag("pageTrades"), hint = PageWords.TRADES_HINT) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (trade in page.tradeChoices) TradeChip(PageWords.trade(trade), trade in draft.trades, Modifier.testTag("trade-$trade")) { model.toggle(trade) }
            }
        }

        Group(PageWords.COVER, hint = PageWords.COVER_HINT) {
            PictureRow("cover", page.coverUrl, server, "The cover photograph", if (page.coverUrl == null) "Add a photo" else "Change", canUpload, state.busy, onPick = { pick("cover") }, onRemove = { removingImage = "cover" })
            SaidLine(state.said, "cover", "pageSaid-cover")
        }
        Group(PageWords.LOGO, hint = PageWords.LOGO_HINT) {
            PictureRow("logo", page.logoUrl, server, "The logo", if (page.logoUrl == null) "Add a logo" else "Change", canUpload, state.busy, onPick = { pick("logo") }, onRemove = { removingImage = "logo" })
            SaidLine(state.said, "logo", "pageSaid-logo")
        }

        Group(PageWords.ELSEWHERE, hint = PageWords.ELSEWHERE_HINT) {
            WHField(
                "Instagram", draft.instagram, { v -> model.edit { it.copy(instagram = v) } }, inputModifier = Modifier.testTag("field-pageInstagram"),
                keyboardOptions = addresses, prefix = "@", isProblem = problem?.field == "instagram", placeholder = "yourshop",
            )
            FieldProblem(problem, "instagram")
            WHField(
                "Website", draft.website, { v -> model.edit { it.copy(website = v) } }, inputModifier = Modifier.testTag("field-pageWebsite"),
                keyboardOptions = addresses, isProblem = problem?.field == "website", placeholder = "yourshop.com",
            )
            FieldProblem(problem, "website")
            WHField(
                "Phone people can ring", draft.phone, { v -> model.edit { it.copy(phone = v) } }, inputModifier = Modifier.testTag("field-pagePhone"),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone, imeAction = ImeAction.Done), isProblem = problem?.field == "phone",
            )
            FieldProblem(problem, "phone")
        }

        if (page.outlets.isNotEmpty()) Group(PageWords.ADDRESS, Modifier.testTag("pageAddress"), hint = PageWords.addressSays(page.exampleArea)) {
            WHCard {
                page.outlets.forEachIndexed { index, outlet ->
                    if (index > 0) RowDivider(Modifier.padding(start = 16.dp))
                    SwitchRow(
                        PageWords.addressLabel(outlet, page.outlets.size), outlet.addressPrivate, { model.setPrivate(outlet, it) },
                        Modifier.padding(horizontal = 16.dp, vertical = 6.dp).testTag("pagePrivate-${outlet.id}"), hint = PageWords.addressHint(outlet), enabled = state.busy == null,
                    )
                }
            }
            SaidLine(state.said, "address", "pageSaid-address")
        }

        Group(PageWords.LINK, Modifier.testTag("pageLinkGroup")) {
            WHCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(page.link, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(WHColors.Bg).padding(10.dp).testTag("pageLink"), style = WHType.Meta, color = WHColors.Ink)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CopyButton(page.link, "the link", Modifier.testTag("pageCopy"))
                        WordsButton("Share", {
                            runCatching { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, page.link), PageWords.LINK)) }
                        }, Modifier.testTag("pageShare"))
                    }
                    Text(PageWords.linkSays(page.published), style = WHType.Summary, color = WHColors.Neutral800)
                    SaidLine(state.said, "link", "pageSaid-link")
                    if (page.published) SecondaryButton(PageWords.TAKE_DOWN, { takingDown = true }, Modifier.fillMaxWidth().testTag("pageTakeDown"), enabled = state.busy == null, loading = state.busy == "publish")
                    else InkButton(PageWords.PUBLISH, { model.publish(true) }, Modifier.fillMaxWidth().testTag("pagePublish"), enabled = state.busy == null || state.busy == "publish", loading = state.busy == "publish")
                    PageWords.worthAdding(page)?.let { Text(it, Modifier.testTag("pageMissing"), style = WHType.Meta, color = WHColors.Neutral700) }
                }
            }
        }

        Group(PageWords.workTitle(page.photos.size), Modifier.testTag("pageWork"), hint = PageWords.WORK_HINT) {
            SaidLine(state.said, "work", "pageSaid-work")
            for (row in page.photos.chunked(3)) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (photo in row) WorkTile(photo, server, isRemoving = state.busy == "photo:${photo.id}", canRemove = state.busy == null, Modifier.weight(1f)) { removingPhoto = photo }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            WHField(
                PageWords.CAPTION, state.caption, model::caption, Modifier.padding(top = 6.dp), inputModifier = Modifier.testTag("field-pageCaption"),
                keyboardOptions = sentences.copy(imeAction = ImeAction.Done),
            )
            if (page.team.isNotEmpty()) ChoiceField(PageWords.WHO, "Nobody named", page.team.map { it.id to it.name }, state.staffId, model::who, Modifier.testTag("field-pageWho"))
            InkButton(PageWords.ADD_PHOTO, { pick("work") }, Modifier.fillMaxWidth().testTag("pageAddPhoto"), enabled = canUpload || state.busy == "add", loading = state.busy == "add")
        }

        Hint(PageWords.TEAM_NOTE, Modifier.padding(top = 24.dp))
    }

    removingPhoto?.let { photo ->
        ConfirmDialog(
            title = "Remove this photograph?", message = photo.caption?.let { "“$it” comes off your page." } ?: "It comes off your page.", confirm = "Remove",
            onConfirm = { removingPhoto = null; model.removePhoto(photo) }, onDismiss = { removingPhoto = null },
        )
    }
    removingImage?.let { kind ->
        ConfirmDialog(
            title = if (kind == "cover") "Remove the cover photograph?" else "Remove the logo?",
            message = if (kind == "cover") "Your page shows without it." else "Your website shows your name in its place.", confirm = "Remove",
            onConfirm = { removingImage = null; model.removeImage(kind) }, onDismiss = { removingImage = null },
        )
    }
    if (takingDown) ConfirmDialog(
        title = "Take the page down?", message = "The booking link still works — only the page is hidden.", confirm = PageWords.TAKE_DOWN,
        onConfirm = { takingDown = false; model.publish(false) }, onDismiss = { takingDown = false },
    )
}

/** A trade: ink when it is one of theirs, as the web's. */
@Composable
internal fun TradeChip(name: String, isOn: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Box(
        modifier.heightIn(min = 44.dp).clip(shape).background(if (isOn) WHColors.Ink else WHColors.Surface).border(1.dp, if (isOn) WHColors.Ink else WHColors.Divider, shape)
            .toggleable(isOn, role = Role.Checkbox, onValueChange = { onToggle() }).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(name, style = WHType.Semi14, color = if (isOn) WHColors.Bg else WHColors.Ink)
    }
}

/** The cover or the logo: what is there now, and the two things to do with it. */
@Composable
private fun PictureRow(kind: String, url: String?, server: String, says: String, pick: String, canUpload: Boolean, busy: String?, onPick: () -> Unit, onRemove: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(WHColors.Surface).padding(14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        val frame = Modifier.size(if (kind == "cover") 128.dp else 96.dp, 72.dp).clip(RoundedCornerShape(10.dp))
        if (url != null) Picture(url, server, says, frame.testTag("page-$kind"), scale = if (kind == "logo") ContentScale.Fit else ContentScale.Crop)
        else Box(frame.background(WHColors.Bg), contentAlignment = Alignment.Center) { WHIcon(WHIcons.Plus, size = 18.dp, tint = WHColors.Neutral500) }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            WordsButton(pick, onPick, Modifier.testTag("pick-$kind"), enabled = canUpload)
            if (url != null) WordsButton("Remove", onRemove, Modifier.testTag("remove-$kind"), color = WHColors.Accent, enabled = busy == null)
            if (busy == kind) CircularProgressIndicator(Modifier.size(16.dp), color = WHColors.Ink, strokeWidth = 2.dp)
        }
    }
}

/** One photograph of the work, with the way to take it off. One whose picture has gone still has its place, so it can be removed. */
@Composable
private fun WorkTile(photo: PageResponse.Photo, server: String, isRemoving: Boolean, canRemove: Boolean, modifier: Modifier = Modifier, onRemove: () -> Unit) {
    val says = listOfNotNull(photo.caption, photo.staffName).joinToString(", ").ifEmpty { "A photograph" }
    Column(modifier.testTag("work-${photo.id}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(10.dp))) {
            Picture(photo.url, server, says, Modifier.fillMaxSize())
            Box(
                Modifier.align(Alignment.TopEnd).size(44.dp).clickable(enabled = canRemove, role = Role.Button, onClick = onRemove)
                    .semantics { contentDescription = "${PageWords.REMOVE_PHOTO}: $says" }.testTag("workRemove-${photo.id}"),
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.size(28.dp).clip(CircleShape).background(WHColors.Ink.copy(alpha = 0.72f)), contentAlignment = Alignment.Center) {
                    if (isRemoving) CircularProgressIndicator(Modifier.size(14.dp), color = WHColors.Bg, strokeWidth = 2.dp)
                    else WHIcon(WHIcons.X, size = 14.dp, tint = WHColors.Bg)
                }
            }
        }
        photo.caption?.let { Text(it, Modifier.clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        photo.staffName?.let { Text(it, Modifier.clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Neutral700, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

// endregion
// region Your website

/**
 * Your website (chairtime `app/(pro)/shop/site`): the design, the sides of the
 * business, and — only where the shop's site has an address of its own — the
 * domain it answers on. Where it has not, there is no such part of the screen.
 */
@Composable
internal fun SiteScreen(app: AppModel, shop: ShopViewModel, visit: Int, onBack: (() -> Unit)?) {
    val model: SiteViewModel = viewModel(key = "shop-site") { SiteViewModel(app.client, app::handle) }
    LaunchedEffect(visit) { model.enter(visit) }
    val state by model.state.collectAsStateWithLifecycle()
    val server by app.server.collectAsStateWithLifecycle()
    val web = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    val clock = remember(shop.me.shop.timezone) { ShopClock(shop.me.shop.timezone) }
    val site = state.response
    var removingSide by remember { mutableStateOf<SiteResponse.Side?>(null) }
    var removingDomain by remember { mutableStateOf<SiteResponse.Domain?>(null) }

    SettingPage(SiteWords.TITLE, "site", onBack, intro = SiteWords.INTRO) {
        Failure(state.failure, site == null) { model.load() }
        if (site == null) { if (state.failure == null) Loading("your website", 3, 180); return@SettingPage }

        if (!site.ready) Column(Modifier.padding(top = 16.dp).testTag("siteNotReady")) {
            NoteCard(SiteWords.NOT_READY)
            WordsButton("Your page", { shop.open(ShopRoute.Page) }, Modifier.testTag("siteToPage"))
        }

        Group(SiteWords.DESIGN, Modifier.testTag("siteDesign")) {
            BoxWithConstraints {
                val columns = if (maxWidth >= 520.dp) 2 else 1
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    for ((title, designs) in site.designGroups) {
                        Text(title, Modifier.padding(horizontal = 4.dp, vertical = 2.dp).semantics { heading() }, style = WHType.RowName, color = WHColors.Ink)
                        for (row in designs.chunked(columns)) Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            for (design in row) DesignCard(
                                design, isChosen = design.key == site.template, server, state.busy, state.said, Modifier.weight(1f),
                                onPreview = { scope.launch { WebLink.open(design.previewPath, app.client, server, web) } }, onUse = { model.choose(design) },
                            )
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        val sides = site.sides
        Group(SiteWords.SIDES, Modifier.testTag("siteSides"), hint = SiteWords.SIDES_SAYS) {
            if (!sides.designShows) NoteCard(SiteWords.DESIGN_HIDES, Modifier.testTag("sidesDesignHides"))
            else if (!sides.showing) NoteCard(SiteWords.NOT_SHOWING, Modifier.testTag("sidesNotShowing"))
            SaidLine(state.said, "sides", "sidesSaid")
            sides.sides.forEachIndexed { index, side ->
                SideCard(side, isFirst = index == 0, isLast = index == sides.sides.lastIndex, state, sides, server, model) { removingSide = side }
            }
            if (sides.isFull) Hint(SiteWords.full(sides), Modifier.testTag("sidesFull"))
            else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WHField(
                    SiteWords.ADD_SIDE, state.newSide, model::typeSide, inputModifier = Modifier.testTag("field-newSide"),
                    keyboardOptions = sentences.copy(imeAction = ImeAction.Done), isProblem = state.said?.where == "addSide" && state.said?.isProblem == true, placeholder = SiteWords.sideExample(sides),
                )
                Hint(SiteWords.ADD_SIDE_HINT)
                SaidLine(state.said, "addSide", "addSideSaid")
                InkButton(SiteWords.ADD_IT, { model.addSide() }, Modifier.testTag("addSide"), enabled = state.busy == null || state.busy == "addSide", loading = state.busy == "addSide")
            }
        }

        // Only a shop whose site has an address of its own. For any other there is nothing here, and nothing said about why.
        if (site.ownAddress) Group(
            SiteWords.ADDRESS, Modifier.testTag("siteAddress"),
            action = SiteWords.LOOK_AGAIN, actionTag = "siteRefresh", actionBusy = state.busy == "refresh", onAction = { model.refresh() },
        ) {
            SaidLine(state.said, "address", "addressSaid")
            if (site.domains.isEmpty()) Hint(SiteWords.NO_DOMAINS)
            for (domain in site.domains) DomainCard(domain, state, clock, onCheck = { model.check(domain) }, onRemove = { removingDomain = domain })
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WHField(
                    SiteWords.ADD_DOMAIN, state.newDomain, model::typeDomain, inputModifier = Modifier.testTag("field-newDomain"),
                    keyboardOptions = addresses, isProblem = state.said?.where == "addDomain" && state.said?.isProblem == true, placeholder = SiteWords.DOMAIN_EXAMPLE,
                )
                Hint(SiteWords.DOMAIN_HINT)
                SaidLine(state.said, "addDomain", "addDomainSaid")
                InkButton(SiteWords.ADD_IT, { model.addDomain() }, Modifier.testTag("addDomain"), enabled = (state.busy == null && state.newDomain.isNotBlank()) || state.busy == "addDomain", loading = state.busy == "addDomain")
            }
        }
    }

    removingSide?.let { side ->
        ConfirmDialog(
            title = "Remove “${side.name}”?", message = "Its categories stay on your menu. They are just no longer filed under a side.", confirm = SiteWords.REMOVE,
            onConfirm = { removingSide = null; model.removeSide(side) }, onDismiss = { removingSide = null },
        )
    }
    removingDomain?.let { domain ->
        ConfirmDialog(
            title = SiteWords.removeTitle(domain), message = SiteWords.REMOVE_SAYS, confirm = SiteWords.REMOVE,
            onConfirm = { removingDomain = null; model.removeDomain(domain) }, onDismiss = { removingDomain = null },
        )
    }
}

/** One design: its picture, what it is for, a look at it with the shop in it, and the button that chooses it. */
@Composable
private fun DesignCard(
    design: SiteResponse.Design, isChosen: Boolean, server: String, busy: String?, said: Said?, modifier: Modifier = Modifier,
    onPreview: () -> Unit, onUse: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val key = "design:${design.key}"
    Column(modifier.clip(shape).background(WHColors.Surface).border(if (isChosen) 2.dp else 1.dp, if (isChosen) WHColors.Ink else WHColors.Divider, shape).testTag("design-${design.key}")) {
        Picture(design.imageUrl, server, null, Modifier.fillMaxWidth().aspectRatio(640f / 413f), alignment = Alignment.TopCenter)
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.semantics(mergeDescendants = true) { if (isChosen) stateDescription = SiteWords.IN_USE }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(design.name, Modifier.weight(1f), style = WHType.RowName, color = WHColors.Ink)
                if (isChosen) Row(Modifier.clearAndSetSemantics { }.testTag("designInUse"), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    WHIcon(WHIcons.Check, size = 14.dp, tint = WHColors.Ink)
                    Text(SiteWords.IN_USE, style = WHType.Semi14, color = WHColors.Ink)
                }
            }
            Text(design.blurb, style = WHType.Meta, color = WHColors.Neutral700)
            SaidLine(said, key, "designSaid")
            if (!isChosen) InkButton(SiteWords.USE, onUse, Modifier.fillMaxWidth().testTag("designUse-${design.key}"), enabled = busy == null || busy == key, loading = busy == key)
            WordsButton(SiteWords.PREVIEW, onPreview, Modifier.semantics { contentDescription = "${SiteWords.PREVIEW}: ${design.name}. Opens on the web" }.testTag("designPreview-${design.key}"), color = WHColors.Neutral800)
        }
    }
}

/** One side of the business: what it is called, what is filed under it, the photograph on its card, and its place in the order. */
@Composable
private fun SideCard(
    side: SiteResponse.Side, isFirst: Boolean, isLast: Boolean, state: SiteState, sides: SiteResponse.Sides, server: String, model: SiteViewModel, onRemove: () -> Unit,
) {
    val draft = state.draft(side)
    val where = "side:${side.id}"
    val idle = state.busy == null
    val refused = state.said?.takeIf { it.where == where && it.isProblem }
    WHCard(Modifier.testTag("side-${side.id}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(side.name, Modifier.weight(1f).semantics { heading() }, style = WHType.RowName, color = WHColors.Ink)
                MoveButton(WHIcons.ChevronUp, "Move ${side.name} up", enabled = idle && !isFirst, Modifier.testTag("sideUp-${side.id}")) { model.move(side, "up") }
                MoveButton(WHIcons.ChevronDown, "Move ${side.name} down", enabled = idle && !isLast, Modifier.testTag("sideDown-${side.id}")) { model.move(side, "down") }
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WHField(
                    SiteWords.NAME, draft.name, { v -> model.edit(side) { it.copy(name = v) } }, inputModifier = Modifier.testTag("field-sideName-${side.id}"),
                    keyboardOptions = sentences, isProblem = refused?.field == "name",
                )
                Hint(SiteWords.nameHint(sides))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WHField(
                    SiteWords.LINE, draft.line, { v -> model.edit(side) { it.copy(line = v) } }, inputModifier = Modifier.testTag("field-sideLine-${side.id}"),
                    keyboardOptions = sentences, singleLine = false, isProblem = refused?.field == "line", maxLines = 4,
                )
                Hint(SiteWords.lineHint(sides))
            }

            Text(SiteWords.CATEGORIES, Modifier.padding(top = 4.dp).semantics { heading() }, style = WHType.Semi14, color = WHColors.Ink)
            if (sides.categories.isEmpty()) Hint(SiteWords.NO_CATEGORIES, Modifier.padding(horizontal = 0.dp))
            else Column {
                for (category in sides.categories) Tick(
                    category.name, category.id in draft.categoryIds, hint = SiteWords.elsewhere(category, side, sides),
                    Modifier.testTag("sideCategory-${side.id}-${category.id}"),
                ) { model.toggle(side, category.id) }
            }

            Text(SiteWords.PHOTO, Modifier.padding(top = 4.dp).semantics { heading() }, style = WHType.Semi14, color = WHColors.Ink)
            if (sides.photos.isEmpty()) Text(SiteWords.NO_PHOTOS, style = WHType.Meta, color = WHColors.Neutral700)
            else Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PhotoChoice(null, server, SiteWords.NO_PHOTO, draft.photoId == null) { model.edit(side) { it.copy(photoId = null) } }
                for (photo in sides.photos) PhotoChoice(photo.url, server, photo.caption ?: "A photograph", draft.photoId == photo.id) { model.edit(side) { it.copy(photoId = photo.id) } }
            }

            SaidLine(state.said, where, "sideSaid-${side.id}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                InkButton(SiteWords.SAVE_SIDE, { model.saveSide(side) }, Modifier.testTag("sideSave-${side.id}"), enabled = idle || state.busy == where, loading = state.busy == where)
                WordsButton(SiteWords.REMOVE, onRemove, Modifier.semantics { contentDescription = "Remove ${side.name}" }.testTag("sideRemove-${side.id}"), color = WHColors.Accent, enabled = idle)
            }
        }
    }
}

@Composable
private fun MoveButton(icon: WHIcons, says: String, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(44.dp).clip(RoundedCornerShape(8.dp)).clickable(enabled = enabled, role = Role.Button, onClick = onClick).alpha(if (enabled) 1f else 0.35f).semantics { contentDescription = says },
        contentAlignment = Alignment.Center,
    ) { WHIcon(icon, size = 18.dp, tint = WHColors.Ink) }
}

/** A box to tick, with a line under it when ticking does more than it looks. */
@Composable
private fun Tick(label: String, checked: Boolean, hint: String?, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    val shape = RoundedCornerShape(5.dp)
    Row(
        modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(checked, role = Role.Checkbox, onValueChange = { onToggle() })
            .semantics(mergeDescendants = true) { contentDescription = listOfNotNull(label, hint).joinToString(". ") },
        horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(shape).background(if (checked) WHColors.Ink else WHColors.Surface).border(1.5.dp, if (checked) WHColors.Ink else WHColors.Neutral500, shape), contentAlignment = Alignment.Center) {
            if (checked) WHIcon(WHIcons.Check, size = 14.dp, tint = WHColors.Bg)
        }
        Column(Modifier.weight(1f).clearAndSetSemantics { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = WHType.Medium14, color = WHColors.Ink)
            if (hint != null) Text(hint, style = WHType.Meta, color = WHColors.Neutral700)
        }
    }
}

/** One photograph to put on a side's card, or none. */
@Composable
private fun PhotoChoice(url: String?, server: String, says: String, selected: Boolean, onPick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(72.dp).clip(shape).border(if (selected) 2.5.dp else 1.dp, if (selected) WHColors.Ink else WHColors.Divider, shape)
            .selectable(selected, role = Role.RadioButton, onClick = onPick).semantics { contentDescription = says },
        contentAlignment = Alignment.Center,
    ) {
        if (url == null) Text(says, Modifier.clearAndSetSemantics { }, style = WHType.Meta, color = WHColors.Neutral700)
        else Picture(url, server, null, Modifier.fillMaxSize())
        if (selected && url != null) Box(Modifier.align(Alignment.TopEnd).padding(4.dp).size(20.dp).clip(CircleShape).background(WHColors.Ink), contentAlignment = Alignment.Center) {
            WHIcon(WHIcons.Check, size = 12.dp, tint = WHColors.Bg)
        }
    }
}

/** A domain being connected: where it has got to, the records to add where it was bought, and the one thing to press. */
@Composable
private fun DomainCard(domain: SiteResponse.Domain, state: SiteState, clock: ShopClock, onCheck: () -> Unit, onRemove: () -> Unit) {
    val where = "domain:${domain.id}"
    WHCard(Modifier.testTag("domain-${domain.id}")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(domain.host, style = WHType.RowName, color = WHColors.Ink)
                    Text(domain.status, Modifier.testTag("domainStatus"), style = WHType.Meta, color = WHColors.Neutral700)
                }
                WordsButton(SiteWords.REMOVE, onRemove, Modifier.semantics { contentDescription = "Remove ${domain.host}" }.testTag("domainRemove-${domain.id}"), color = WHColors.Accent, enabled = state.busy == null)
            }
            domain.message?.takeIf { it.isNotBlank() }?.let { Text(it, style = WHType.Summary, color = WHColors.Neutral800) }
            for (record in domain.records) RecordCard(record)
            SiteWords.lastChecked(domain, clock.zone)?.let { NoteCard(it, Modifier.testTag("domainChecked")) }
            SaidLine(state.said, where, "domainSaid")
            SiteWords.button(domain)?.let { label ->
                InkButton(label, onCheck, Modifier.fillMaxWidth().testTag("domainCheck-${domain.id}"), enabled = state.busy == null || state.busy == where, loading = state.busy == where)
            }
            if (domain.isWaiting) Hint(SiteWords.WAIT, Modifier.padding(horizontal = 0.dp))
        }
    }
}

/** One DNS record: what kind, why, and the two things to type, each with a way to copy it exactly. */
@Composable
private fun RecordCard(record: SiteResponse.Record) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(WHColors.Bg).padding(start = 12.dp, end = 4.dp, top = 10.dp, bottom = 6.dp).testTag("record-${record.type}")) {
        Row(Modifier.padding(end = 8.dp).semantics(mergeDescendants = true) { }, horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(record.type, style = WHType.Semi14, color = WHColors.Ink)
            Text(record.why, style = WHType.Meta, color = WHColors.Neutral700)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).semantics(mergeDescendants = true) { }) {
                Text("Name: ${record.name}", style = WHType.Medium14, color = WHColors.Ink)
                Text(SiteWords.nameNote(record), style = WHType.Meta, color = WHColors.Neutral700)
            }
            CopyButton(record.name, "the name")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(record.value, Modifier.weight(1f), style = WHType.Medium14, color = WHColors.Ink)
            CopyButton(record.value, "the value")
        }
    }
}

// endregion
