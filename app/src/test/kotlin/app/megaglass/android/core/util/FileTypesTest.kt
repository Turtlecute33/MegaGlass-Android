package app.megaglass.android.core.util

import org.junit.Assert.assertEquals
import org.junit.Test

class FileTypesTest {
    @Test fun transportStreamsAreVideos() {
        assertEquals(FileType.VIDEO, FileType.of("clip.ts"))
        assertEquals(FileType.VIDEO, FileType.of("CLIP.TS"))
        assertEquals(FileType.CODE, FileType.of("app.tsx"))
    }
}
