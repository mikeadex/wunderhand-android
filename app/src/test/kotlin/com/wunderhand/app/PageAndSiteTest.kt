package com.wunderhand.app

import com.wunderhand.app.features.shop.PageViewModel
import com.wunderhand.app.features.shop.ShopRoute
import com.wunderhand.app.features.shop.SiteViewModel
import com.wunderhand.app.support.StubApi
import com.wunderhand.core.ChairtimeJson
import com.wunderhand.core.PageResponse
import com.wunderhand.core.PageWords
import com.wunderhand.core.PageWrite
import com.wunderhand.core.SideWrite
import com.wunderhand.core.SiteResponse
import com.wunderhand.core.SiteWords
import com.wunderhand.network.ApiError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Your page and Your website: redrawn from each answer, one change at a time, and nothing typed thrown away by one. */
@OptIn(ExperimentalCoroutinesApi::class)
class PageAndSiteTest {
    @Before fun main() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun reset() = Dispatchers.resetMain()

    private fun text(name: String) = checkNotNull(javaClass.getResourceAsStream("/$name.json")).bufferedReader().use { it.readText() }
    private inline fun <reified T> fixture(name: String): T = ChairtimeJson.decodeFromString(text(name))
    private val page: PageResponse = fixture("page")
    private val site: SiteResponse = fixture("site")
    private val pointing: SiteResponse = fixture("site-domain-pointing")
    private val handled = mutableListOf<ApiError>()

    private open inner class Shop : StubApi() {
        var lastPage: PageWrite? = null
        var lastSide: SideWrite? = null
        override suspend fun page(): PageResponse { calls += "page"; return page }
        override suspend fun savePage(write: PageWrite): PageResponse { calls += "savePage"; lastPage = write; return page.copy(headline = write.headline, about = write.about, instagram = write.instagram, website = write.website, phone = write.phone, trades = write.trades) }
        override suspend fun publishPage(published: Boolean): PageResponse { calls += "publish $published"; return page.copy(published = published) }
        override suspend fun uploadPageImage(kind: String, image: ByteArray, type: String): PageResponse { calls += "upload $kind $type ${image.size}"; return if (kind == "logo") page.copy(logoUrl = "https://wunderhand.test/logo.webp") else page.copy(coverUrl = "https://wunderhand.test/cover-2.webp") }
        override suspend fun removePageImage(kind: String): PageResponse { calls += "removeImage $kind"; return if (kind == "logo") page.copy(logoUrl = null) else page.copy(coverUrl = null) }
        override suspend fun setAddressPrivate(outletId: String, isPrivate: Boolean): PageResponse { calls += "private $outletId $isPrivate"; return page.copy(outlets = page.outlets.map { if (it.id == outletId) it.copy(addressPrivate = isPrivate, shown = page.exampleArea) else it }) }
        override suspend fun addWorkPhoto(jpeg: ByteArray, caption: String?, staffId: String?): PageResponse { calls += "addPhoto $caption $staffId"; return page.copy(photos = page.photos + PageResponse.Photo("new", null, caption, null)) }
        override suspend fun removeWorkPhoto(id: String): PageResponse { calls += "removePhoto $id"; return page.copy(photos = page.photos.filter { it.id != id }) }
        override suspend fun site(): SiteResponse { calls += "site"; return site }
        override suspend fun chooseDesign(template: String): SiteResponse { calls += "design $template"; return site.copy(template = template) }
        override suspend fun addSide(name: String): SiteResponse { calls += "addSide $name"; return site.copy(sides = site.sides.copy(sides = site.sides.sides + SiteResponse.Side("new", name))) }
        override suspend fun saveSide(id: String, write: SideWrite): SiteResponse { calls += "saveSide $id"; lastSide = write; return site.copy(sides = site.sides.copy(sides = site.sides.sides.map { if (it.id == id) it.copy(name = write.name, line = write.line, portfolioImageId = write.portfolioImageId, categoryIds = write.categoryIds) else it })) }
        override suspend fun removeSide(id: String): SiteResponse { calls += "removeSide $id"; return site.copy(sides = site.sides.copy(sides = site.sides.sides.filter { it.id != id })) }
        override suspend fun moveSide(id: String, direction: String): SiteResponse { calls += "move $id $direction"; return site.copy(sides = site.sides.copy(sides = site.sides.sides.reversed())) }
        override suspend fun addDomain(domain: String): SiteResponse { calls += "addDomain $domain"; return site }
        override suspend fun removeDomain(id: String): SiteResponse { calls += "removeDomain $id"; return site.copy(domains = site.domains.filter { it.id != id }) }
        override suspend fun checkDomain(id: String): SiteResponse { calls += "check $id"; return pointing }
    }

    // Routes

    @Test fun `your page and your website are the owner's screens, and are come back to after the app is killed`() {
        for (route in listOf(ShopRoute.Page, ShopRoute.Site)) {
            assertFalse(route.isAnybodys)
            assertEquals(route, ShopRoute.of(route.raw))
        }
        assertEquals("Your page", ShopRoute.Page.title)
        assertEquals("Your website", ShopRoute.Site.title)
    }

    // Your page

    @Test fun `the page opens with its words in the fields and nothing to save`() {
        val api = Shop()
        val state = PageViewModel(api, handle = {}).apply { enter(1) }.state.value
        assertEquals(listOf("page"), api.calls)
        assertEquals("Skin fades and beard work in Hackney", state.draft.headline)
        assertEquals(setOf("barber", "colour"), state.draft.trades)
        assertFalse(state.isChanged)
    }

    @Test fun `the words and the trades are saved together, an emptied line cleared, and the screen redrawn from the answer`() {
        val api = Shop()
        val model = PageViewModel(api, handle = {}).apply { enter(1) }
        model.edit { it.copy(headline = "Fades in Hackney", about = " ", instagram = "@foldbarbers") }
        model.toggle("colour"); model.toggle("nails")
        assertTrue(model.state.value.isChanged)
        model.save()
        assertEquals(PageWrite("Fades in Hackney", null, "foldbarbers", null, null, listOf("barber", "nails")), api.lastPage)
        val state = model.state.value
        assertEquals("Fades in Hackney", state.response?.headline)
        assertTrue(state.justSaved)
        assertFalse(state.isChanged)
        // Typing again takes "Saved." away.
        model.edit { it.copy(phone = "020") }
        assertFalse(model.state.value.justSaved)
    }

    @Test fun `a line stops at the length the page gives`() {
        val model = PageViewModel(Shop(), handle = {}).apply { enter(1) }
        model.edit { it.copy(headline = "x".repeat(300)) }
        assertEquals(120, model.state.value.draft.headline.length)
        model.caption("c".repeat(300))
        assertEquals(140, model.state.value.caption.length)
    }

    @Test fun `a refused line is said at that line and what was typed is kept`() {
        val api = object : Shop() { override suspend fun savePage(write: PageWrite) = throw ApiError.Validation("That does not look like a web address. Try it like yourshop.com", "website") }
        val model = PageViewModel(api, handle = {}).apply { enter(1) }
        model.edit { it.copy(website = "nope") }
        model.save()
        val state = model.state.value
        assertEquals("website", state.problem?.field)
        assertEquals("nope", state.draft.website)
        assertFalse(state.isSaving)
        model.edit { it.copy(website = "fold.example") }
        assertNull(model.state.value.problem)
    }

    @Test fun `publishing and taking down say what the web says, beside the link`() {
        val api = Shop()
        val model = PageViewModel(api, handle = {}).apply { enter(1) }
        model.publish(true)
        assertEquals("publish true", api.calls.last())
        assertTrue(model.state.value.response!!.published)
        assertEquals("link", model.state.value.said?.where)
        assertEquals(PageWords.PUBLISHED, model.state.value.said?.text)
        model.publish(false)
        assertEquals(PageWords.TAKEN_DOWN, model.state.value.said?.text)
        assertNull(model.state.value.busy)
    }

    @Test fun `a change elsewhere on the page leaves words half typed as they are`() {
        val api = Shop()
        val model = PageViewModel(api, handle = {}).apply { enter(1) }
        model.edit { it.copy(headline = "Half typed") }
        val outlet = model.state.value.response!!.outlets.first()
        model.setPrivate(outlet, true)
        val state = model.state.value
        assertEquals("private ${outlet.id} true", api.calls.last())
        assertTrue(state.response!!.outlets.first().addressPrivate)
        assertEquals("Half typed", state.draft.headline)
        assertTrue(state.isChanged)
        assertEquals(PageWords.ADDRESS_SAVED, state.said?.text)
    }

    @Test fun `a cover and a logo go up at once and come off, each redrawing the page`() {
        val api = Shop()
        val model = PageViewModel(api, handle = {}).apply { enter(1) }
        model.upload("cover", ByteArray(3))
        assertEquals("upload cover image/jpeg 3", api.calls.last())
        assertEquals("https://wunderhand.test/cover-2.webp", model.state.value.response?.coverUrl)
        model.upload("logo", ByteArray(5), "image/png")
        assertEquals("upload logo image/png 5", api.calls.last())
        model.removeImage("logo")
        assertEquals("removeImage logo", api.calls.last())
        assertNull(model.state.value.response?.logoUrl)
    }

    @Test fun `a photograph of the work carries its caption and who did it, and the form is cleared after`() {
        val api = Shop()
        val model = PageViewModel(api, handle = {}).apply { enter(1) }
        val kit = model.state.value.response!!.team.first { it.name == "Kit Alvarez" }
        model.caption("  ZZ Low fade "); model.who(kit.id)
        model.addPhoto(ByteArray(4))
        assertEquals("addPhoto ZZ Low fade ${kit.id}", api.calls.last())
        val state = model.state.value
        assertEquals(7, state.response?.photos?.size)
        assertEquals("", state.caption)
        assertNull(state.staffId)
        assertEquals(PageWords.PHOTO_ADDED, state.said?.text)
        // The new one has no picture to show; it still has a place, and can be taken off.
        val added = state.response!!.photos.last()
        assertNull(added.url)
        model.removePhoto(added)
        assertEquals("removePhoto new", api.calls.last())
        assertEquals(PageWords.PHOTO_REMOVED, model.state.value.said?.text)
    }

    @Test fun `an upload the server refuses is said beside what it was for, and nothing changes`() {
        val api = object : Shop() { override suspend fun uploadPageImage(kind: String, image: ByteArray, type: String) = throw ApiError.Server("That photograph is too big. Keep it under 4 MB. Nothing changed.") }
        val model = PageViewModel(api, handle = {}).apply { enter(1) }
        model.upload("cover", ByteArray(1))
        val state = model.state.value
        assertEquals("cover", state.said?.where)
        assertTrue(state.said!!.isProblem)
        assertEquals(page.coverUrl, state.response?.coverUrl)
        assertNull(state.busy)
        model.unreadable("work")
        assertEquals(PageWords.UNREADABLE, model.state.value.said?.text)
    }

    @Test fun `one change at a time`() {
        val gate = CompletableDeferred<Unit>()
        val api = object : Shop() { override suspend fun publishPage(published: Boolean): PageResponse { gate.await(); return super.publishPage(published) } }
        val model = PageViewModel(api, handle = {}).apply { enter(1) }
        model.publish(true)
        assertEquals("publish", model.state.value.busy)
        assertNull(model.removePhoto(page.photos.first()))
        gate.complete(Unit)
        assertNull(model.state.value.busy)
        assertEquals(listOf("page", "publish true"), api.calls)
    }

    @Test fun `somebody signed out underneath is the app's to deal with`() {
        val api = object : Shop() { override suspend fun page() = throw ApiError.Unauthorized("Sign in to carry on.") }
        val model = PageViewModel(api, handle = { handled += it }).apply { enter(1) }
        assertEquals(1, handled.size)
        assertNull(model.state.value.failure)
    }

    // Your website

    @Test fun `a design is chosen and said beside that design`() {
        val api = Shop()
        val model = SiteViewModel(api, handle = {}).apply { enter(1) }
        val grain = site.designs.first { it.key != site.template }
        model.choose(grain)
        assertEquals("design ${grain.key}", api.calls.last())
        assertEquals(grain.key, model.state.value.response?.template)
        assertEquals("design:${grain.key}", model.state.value.said?.where)
        assertEquals(SiteWords.DESIGN_SAVED, model.state.value.said?.text)
    }

    @Test fun `a side is changed in its own draft and saved whole`() {
        val api = Shop()
        val model = SiteViewModel(api, handle = {}).apply { enter(1) }
        val (hair, makeUp) = site.sides.sides
        val bridal = site.sides.categories.single { it.sideId == makeUp.id }
        val photo = site.sides.photos.first()
        model.edit(hair) { it.copy(line = "Cuts and colour") }
        model.toggle(hair, bridal.id)
        model.edit(hair) { it.copy(photoId = photo.id) }
        assertTrue(model.state.value.isChanged(hair))
        assertFalse(model.state.value.isChanged(makeUp))
        model.saveSide(hair)
        assertEquals("saveSide ${hair.id}", api.calls.last())
        assertEquals(SideWrite("Hair", "Cuts and colour", photo.id, listOf(bridal.id)), api.lastSide)
        val state = model.state.value
        assertEquals(SiteWords.SIDE_SAVED, state.said?.text)
        assertEquals("side:${hair.id}", state.said?.where)
        assertTrue(state.drafts.isEmpty())
    }

    @Test fun `a side with its name rubbed out is refused before anything is sent`() {
        val api = Shop()
        val model = SiteViewModel(api, handle = {}).apply { enter(1) }
        val hair = site.sides.sides.first()
        model.edit(hair) { it.copy(name = "  ") }
        assertNull(model.saveSide(hair))
        assertEquals(listOf("site"), api.calls)
        assertEquals("name", model.state.value.said?.field)
        assertEquals(SiteWords.NEEDS_NAME, model.state.value.said?.text)
        // Typing in it again takes the refusal away.
        model.edit(hair) { it.copy(name = "Hair") }
        assertNull(model.state.value.said)
    }

    @Test fun `sides are added, moved and removed, and a removed side's draft goes with it`() {
        val api = Shop()
        val model = SiteViewModel(api, handle = {}).apply { enter(1) }
        assertNull(model.addSide())
        assertEquals("addSide", model.state.value.said?.where)

        model.typeSide("ZZ Side".padEnd(60, 'x'))
        assertEquals(40, model.state.value.newSide.length)
        model.typeSide(" ZZ Side ")
        model.addSide()
        assertEquals("addSide ZZ Side", api.calls.last())
        assertEquals("", model.state.value.newSide)
        assertEquals(3, model.state.value.response?.sides?.sides?.size)

        val hair = site.sides.sides.first()
        model.move(hair, "down")
        assertEquals("move ${hair.id} down", api.calls.last())
        assertEquals("Make-up", model.state.value.response?.sides?.sides?.first()?.name)

        model.edit(hair) { it.copy(line = "half typed") }
        model.removeSide(hair)
        assertEquals("removeSide ${hair.id}", api.calls.last())
        assertTrue(model.state.value.drafts.isEmpty())
        assertEquals(SiteWords.SIDE_REMOVED, model.state.value.said?.text)
    }

    @Test fun `a refusal from the server is said where it was asked`() {
        val api = object : Shop() { override suspend fun addSide(name: String) = throw ApiError.Validation("4 sides is the most a site shows. Remove one to add another", "name") }
        val model = SiteViewModel(api, handle = {}).apply { enter(1) }
        model.typeSide("Fifth"); model.addSide()
        val said = model.state.value.said!!
        assertEquals("addSide", said.where)
        assertTrue(said.isProblem)
        assertEquals("Fifth", model.state.value.newSide)
    }

    @Test fun `a domain is checked, looked at again and removed`() {
        val api = Shop()
        val model = SiteViewModel(api, handle = {}).apply { enter(1) }
        val waiting = site.domains.single { it.isWaiting }
        model.check(waiting)
        assertEquals("check ${waiting.id}", api.calls.last())
        assertEquals("pointing", model.state.value.response?.domains?.single()?.state)

        model.refresh()
        assertEquals("site", api.calls.last())
        assertEquals(site.domains.size, model.state.value.response?.domains?.size)

        model.removeDomain(waiting)
        assertEquals("removeDomain ${waiting.id}", api.calls.last())
        assertEquals(SiteWords.DOMAIN_REMOVED, model.state.value.said?.text)
        assertEquals("address", model.state.value.said?.where)
    }

    @Test fun `a domain is added trimmed, and an empty box asks nothing`() {
        val api = Shop()
        val model = SiteViewModel(api, handle = {}).apply { enter(1) }
        assertNull(model.addDomain())
        model.typeDomain("  zz-site-api-test.co.uk ")
        model.addDomain()
        assertEquals("addDomain zz-site-api-test.co.uk", api.calls.last())
        assertEquals("", model.state.value.newDomain)
        assertEquals(SiteWords.DOMAIN_ADDED, model.state.value.said?.text)
    }

    @Test fun `the website is an owner's, and somebody who is not is told so in the server's words`() {
        val api = object : Shop() { override suspend fun site() = throw ApiError.NotOwner("Only an owner can change the shop.") }
        val model = SiteViewModel(api, handle = {}).apply { enter(1) }
        assertEquals("Only an owner can change the shop.", model.state.value.failure)
        assertNull(model.state.value.response)
    }
}
