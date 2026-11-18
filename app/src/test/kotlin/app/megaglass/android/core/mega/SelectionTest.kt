package app.megaglass.android.core.mega

import org.junit.Assert.assertEquals
import org.junit.Test

class SelectionTest {
    // 1 contains 2, 2 contains 3; 4 is elsewhere.
    private val parents = mapOf(2L to 1L, 3L to 2L, 4L to 9L)
    private val parent: (Long) -> Long? = { parents[it] }

    @Test fun anItemInsideASelectedFolderIsDropped() {
        assertEquals(listOf(2L, 4L), withoutNested(listOf(2L, 3L, 4L), parent))
        assertEquals(listOf(1L), withoutNested(listOf(3L, 1L), parent))
    }

    @Test fun itemsInDifferentFoldersStay() {
        assertEquals(listOf(3L, 4L), withoutNested(listOf(3L, 4L), parent))
    }
}
