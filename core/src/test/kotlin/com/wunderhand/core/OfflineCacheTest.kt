package com.wunderhand.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class OfflineCacheTest {
    @get:Rule val folder = TemporaryFolder()
    private val me get() = ChairtimeJson.decodeFromString(Me.serializer(), Fixtures.text("me"))

    @Test fun `who was signed in comes back as they were`() {
        val cache = OfflineCache(File(folder.root, "wunderhand"))
        cache.save(me)
        assertEquals(me, cache.savedMe())
    }

    @Test fun `nothing kept reads as nothing, not as a failure`() {
        assertNull(OfflineCache(File(folder.root, "empty")).savedMe())
    }

    @Test fun `signing out leaves nothing behind`() {
        val directory = File(folder.root, "wunderhand")
        val cache = OfflineCache(directory)
        cache.save(me)
        cache.clear()
        assertNull(cache.savedMe())
        assertEquals(false, directory.exists())
    }

    @Test fun `a file that has been cut short is nothing, not a crash`() {
        val directory = File(folder.root, "wunderhand").apply { mkdirs() }
        File(directory, "me.json").writeText("{\"user\":{\"id\":")
        assertNull(OfflineCache(directory).savedMe())
    }
}
