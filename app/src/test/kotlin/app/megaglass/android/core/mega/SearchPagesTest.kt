package app.megaglass.android.core.mega

import org.junit.Assert.*
import org.junit.Test

/** MEGAGLASS-UX-009: removed matches (Rubbish bin) do not make the search results short. */
class SearchPagesTest {
    /** A fake search: items 0 until [total], with each item divisible by 3 in the Rubbish bin. */
    private class FakeSearch(private val total: Int) {
        val offsets = ArrayList<Long>()
        fun page(offset: Long, limit: Long): List<Int> {
            offsets += offset
            return (offset until minOf(offset + limit, total.toLong())).map { it.toInt() }
        }
    }

    private fun inCloud(item: Int) = item % 3 != 0

    @Test fun laterPagesFillThePlaceOfRemovedMatches() {
        val search = FakeSearch(total = 100)
        val kept = keptPages(10, { search.page(it, 10) }, ::inCloud)
        assertEquals(listOf(1, 2, 4, 5, 7, 8, 10, 11, 13, 14), kept)
        assertEquals(listOf(0L, 10L), search.offsets)
    }

    @Test fun aShortPageIsTheLastPage() {
        val search = FakeSearch(total = 12)
        val kept = keptPages(10, { search.page(it, 10) }, ::inCloud)
        assertEquals(listOf(1, 2, 4, 5, 7, 8, 10, 11), kept)
        assertEquals(listOf(0L, 10L), search.offsets)
    }

    @Test fun onePageIsEnoughWhenNothingIsRemoved() {
        val search = FakeSearch(total = 100)
        val kept = keptPages(10, { search.page(it, 10) }) { true }
        assertEquals((0 until 10).toList(), kept)
        assertEquals(listOf(0L), search.offsets)
    }

    @Test fun anEmptyResultReadsOnePage() {
        val search = FakeSearch(total = 0)
        assertTrue(keptPages(10, { search.page(it, 10) }, ::inCloud).isEmpty())
        assertEquals(listOf(0L), search.offsets)
    }
}
