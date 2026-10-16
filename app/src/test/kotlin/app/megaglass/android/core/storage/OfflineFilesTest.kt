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
        File(store.stage("download", "node"), "folder").mkdir()
        File(store.stage("download", "node"), "folder/part").writeText("partial")
        assertTrue(store.keys().isEmpty())
        assertTrue(OfflineFiles(temp.root).keys().isEmpty())
    }

    @Test fun stagedDownloadsKeepTheirNodeAcrossRestartUntilDiscarded() {
        val store = OfflineFiles(temp.root)
        store.stage("download", "node")
        val reopened = OfflineFiles(temp.root)
        assertEquals(listOf("download"), reopened.stagedIds())
        assertEquals("node", reopened.stagedKey("download"))
        reopened.discard("download")
        assertNull(reopened.stagedKey("download"))
        assertTrue(reopened.stagedIds().isEmpty())
    }

    @Test fun completedFilesSurviveRestartIncludingZeroByteAndDotNames() {
        val store = OfflineFiles(temp.root)
        File(store.stage("first", "node"), ".complete").writeText("")
        store.commit("first", "node")
        val reopened = OfflineFiles(temp.root)
        assertEquals(setOf("node"), reopened.keys())
        assertEquals(".complete", reopened.entry("node")!!.name)
        assertEquals(0, reopened.entry("node")!!.length())
    }

    @Test fun copiesWithNamesThatTheDisplayRulesChangeStayAvailable() {
        val store = OfflineFiles(temp.root)
        val name = "report\u200F\u2066draft\uFEFF.txt"
        File(store.stage("first", "node"), name).writeText("kept")
        store.commit("first", "node")
        val reopened = OfflineFiles(temp.root)
        assertEquals(setOf("node"), reopened.keys())
        assertEquals("kept", reopened.entry("node")!!.readText())
    }

    @Test fun completionMarkersCannotNameAnotherPath() {
        val dir = File(temp.root, "node").apply { mkdirs() }
        File(temp.root, "outside").writeText("secret")
        File(dir, "data").mkdir()
        for (name in listOf("", ".", "..", "../../outside", "a\u0000b")) {
            File(dir, ".complete").writeText(name)
            assertNull(OfflineFiles(temp.root).entry("node"))
        }
    }

    @Test fun failedReplacementKeepsAnExistingOfflineCopy() {
        val store = OfflineFiles(temp.root)
        File(store.stage("first", "node"), "file").writeText("original")
        store.commit("first", "node")
        store.stage("replacement", "node")
        try {
            store.commit("replacement", "node")
            fail("An empty staging directory must not be committed")
        } catch (_: IOException) { }
        assertEquals("original", store.entry("node")!!.readText())
    }

    @Test fun redownloadReplacesALegacyCopyWithoutLeavingItBehind() {
        val previous = File(temp.root, "node").apply { mkdir() }
        File(previous, "file").writeText("previous")
        val store = OfflineFiles(temp.root)
        assertNull(store.entry("node"))
        File(store.stage("download", "node"), "file").writeText("new")
        store.commit("download", "node")
        assertEquals("new", store.entry("node")!!.readText())
        assertTrue(temp.root.listFiles()!!.none { it.name.startsWith(".previous-") })
    }

    @Test fun recoveryRestoresACopyThatAnInterruptedCommitMovedAside() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        File(temp.root, ".previous-node-$uuid").apply { mkdir() }.let { File(it, "file").writeText("previous") }
        File(temp.root, ".previous-done-$uuid").mkdir()
        File(temp.root, "done").mkdir()
        OfflineFiles(temp.root).recover()
        assertEquals("previous", File(temp.root, "node/file").readText())
        assertTrue(temp.root.listFiles()!!.none { it.name.startsWith(".previous-") })
    }

    @Test fun removingAnItemAlsoRemovesItsPreviousCopies() {
        val uuid = "123e4567-e89b-12d3-a456-426614174000"
        File(temp.root, ".previous-node-$uuid").mkdir()
        File(temp.root, ".previous-other-$uuid").mkdir()
        OfflineFiles(temp.root).removePrevious("node")
        assertEquals(listOf(".previous-other-$uuid"), temp.root.list()!!.filter { it.startsWith(".previous-") })
    }

    @Test fun cancelledStagingDoesNotBecomeAnOfflineCopy() {
        val store = OfflineFiles(temp.root)
        File(store.stage("download", "node"), "file").writeText("partial")
        store.discard("download")
        assertTrue(store.keys().isEmpty())
    }

    @Test fun stagingRejectsTraversal() {
        try {
            OfflineFiles(temp.root).stage("../outside", "node")
            fail("Unsafe operation identifier must fail")
        } catch (_: IllegalArgumentException) { }
    }
}
