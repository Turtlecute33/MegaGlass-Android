package app.megaglass.android.core.storage

import nz.mega.sdk.MegaApiJava
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** MEGAGLASS-UX-001: which new version stays offline after a save, and which copies are outdated. */
class OfflineVersionsTest {
    private val old = 11L
    private val new = 22L

    @Test fun aSaveOfAnOfflineFileKeepsTheNewVersionOffline() {
        assertEquals(new, versionToKeepOffline(old, new, offline = setOf(old)))
    }

    @Test fun aSaveOfAFileThatIsNotOfflineChangesNothing() {
        assertNull(versionToKeepOffline(old, new, offline = emptySet()))
        assertNull(versionToKeepOffline(old, new, offline = setOf(33L)))
    }

    @Test fun aSaveWithoutANewNodeChangesNothing() {
        assertNull(versionToKeepOffline(old, old, offline = setOf(old)))
        assertNull(versionToKeepOffline(old, MegaApiJava.INVALID_HANDLE, offline = setOf(old)))
    }

    @Test fun aNewVersionThatIsOfflineAlreadyDoesNotDownloadAgain() {
        assertNull(versionToKeepOffline(old, new, offline = setOf(old, new)))
    }

    @Test fun onlyACurrentNodeInAFolderIsUpToDate() {
        assertFalse(isOutdatedCopy(parentIsFile = false))
        // An earlier version is a child of the version after it.
        assertTrue(isOutdatedCopy(parentIsFile = true))
        // The node is not in the cloud any more.
        assertTrue(isOutdatedCopy(parentIsFile = null))
    }
}
