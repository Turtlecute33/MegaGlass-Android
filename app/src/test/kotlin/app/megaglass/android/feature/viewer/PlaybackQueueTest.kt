package app.megaglass.android.feature.viewer

import app.megaglass.android.core.util.FileType
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class PlaybackQueueTest {
    @After fun clear() = PlaybackQueue.clear()

    private fun entry(key: String, handle: Long, name: String, file: File? = null) =
        MediaEntry(key, handle, name, FileType.AUDIO, 1, hasThumbnail = false, hasPreview = false, folderLink = false, file = file)

    @Test fun mediaIdsRevealNoHandlesNamesOrPaths() {
        val cloud = entry("h:1234567890", 1234567890, "Private song.mp3")
        val local = entry("f:/data/user/0/app/files/offline/x/Diary.m4a", -1, "Diary.m4a", File("/data/user/0/app/files/offline/x/Diary.m4a"))
        val ids = PlaybackQueue.add(listOf(cloud, local, cloud))
        assertEquals(3, ids.toSet().size)
        for (id in ids) {
            for (secret in listOf("1234567890", "Private", "Diary", "/data/", "h:", "f:")) assertFalse(id, id.contains(secret))
        }
        assertSame(cloud, PlaybackQueue[ids[0]])
        assertSame(local, PlaybackQueue[ids[1]])
    }

    @Test fun unknownIdsResolveToNothing() {
        val id = PlaybackQueue.add(listOf(entry("h:1", 1, "a.mp3"))).single()
        assertNull(PlaybackQueue["h:1"])
        assertNull(PlaybackQueue[null])
        PlaybackQueue.clear()
        assertNull(PlaybackQueue[id])
    }
}
