package app.megaglass.android.core.storage

import mega.privacy.android.data.filewrapper.FileWrapper
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedStorageTest {
    private val pkg = "app.megaglass.android"

    private fun shared(path: String?) = FileWrapper.isSharedStorage(path, pkg)

    @Test fun sharedStorageFoldersNeedAllFilesAccess() {
        for (path in listOf(
            "/storage/emulated/0",
            "/storage/emulated/0/Documents",
            "/storage/emulated/10/DCIM/Camera",
            "/storage/self/primary/Download",
            "/storage/1234-ABCD/Music",
            "/sdcard/Documents",
            "/storage/emulated/0/Android/data",
            "/storage/emulated/0/Android/data/com.other.app/files",
            "/storage/emulated/0/Android/media/com.other.app",
            "/storage/emulated/0/Notes/Android/data/$pkg",
        )) assertTrue(path, shared(path))
    }

    @Test fun theAppsOwnFoldersInSharedStorageDoNot() {
        for (path in listOf(
            "/storage/emulated/0/Android/data/$pkg",
            "/storage/emulated/0/Android/data/$pkg/files/Download",
            "/storage/emulated/0/android/DATA/$pkg/files",
            "/storage/emulated/0/Android/media/$pkg",
            "/storage/1234-ABCD/Android/obb/$pkg",
            "/sdcard/Android/data/$pkg/cache",
        )) assertFalse(path, shared(path))
    }

    @Test fun aRelativeStepCannotLeaveTheAppsOwnFolder() {
        assertTrue(shared("/storage/emulated/0/Android/data/$pkg/../../../Documents"))
    }

    @Test fun privateStorageAndContentUrisDoNot() {
        for (path in listOf(
            null,
            "",
            "/data/user/0/$pkg/files/offline",
            "/data/data/$pkg/cache",
            "/mnt/expand/1234/user/0/$pkg/files",
            "content://com.android.externalstorage.documents/tree/primary%3ADocuments",
            "relative/storage/emulated/0",
        )) assertFalse(path.toString(), shared(path))
    }
}
