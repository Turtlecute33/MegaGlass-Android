package app.megaglass.android.core.storage

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class OfflineFilesTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun interruptedDownloadsStayHiddenAfterRestart() {
        val store = OfflineFiles(temp.root)
        File(store.stage("download"), "folder").mkdir()
        File(store.stage("download"), "folder/part").writeText("partial")
        assertTrue(store.keys().isEmpty())
        assertTrue(OfflineFiles(temp.root).keys().isEmpty())
    }

    @Test fun completedFilesSurviveRestartIncludingZeroByteAndDotNames() {
        val store = OfflineFiles(temp.root)
        File(store.stage("first"), ".complete").writeText("")
        store.commit("first", "node")
        val reopened = OfflineFiles(temp.root)
        assertEquals(setOf("node"), reopened.keys())
        assertEquals(".complete", reopened.entry("node")!!.name)
        assertEquals(0, reopened.entry("node")!!.length())
    }

    @Test fun failedReplacementKeepsAnExistingOfflineCopy() {
        val store = OfflineFiles(temp.root)
        File(store.stage("first"), "file").writeText("original")
        store.commit("first", "node")
        store.stage("replacement")
        try {
            store.commit("replacement", "node")
            fail("An empty staging directory must not be committed")
        } catch (_: IOException) { }
        assertEquals("original", store.entry("node")!!.readText())
    }

    @Test fun legacyCopiesArePreservedDuringRedownload() {
        val previous = File(temp.root, "node").apply { mkdir() }
        File(previous, "file").writeText("previous")
        val store = OfflineFiles(temp.root)
        assertNull(store.entry("node"))
        File(store.stage("download"), "file").writeText("new")
        store.commit("download", "node")
        assertEquals("new", store.entry("node")!!.readText())
        val backup = temp.root.listFiles()!!.single { it.name.startsWith(".previous-") }
        assertEquals("previous", File(backup, "file").readText())
    }

    @Test fun cancelledStagingDoesNotBecomeAnOfflineCopy() {
        val store = OfflineFiles(temp.root)
        File(store.stage("download"), "file").writeText("partial")
        store.discard("download")
        assertTrue(store.keys().isEmpty())
    }

    @Test fun stagingRejectsTraversal() {
        try {
            OfflineFiles(temp.root).stage("../outside")
            fail("Unsafe operation identifier must fail")
        } catch (_: IllegalArgumentException) { }
    }
}
