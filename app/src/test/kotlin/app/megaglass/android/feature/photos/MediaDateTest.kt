package app.megaglass.android.feature.photos

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MediaDateTest {
    @Test fun invalidMetadataDoesNotCrashTheTimeline() {
        assertEquals(LocalDate.ofEpochDay(0), mediaDay(Long.MAX_VALUE))
        assertEquals(LocalDate.ofEpochDay(0), mediaDay(Long.MIN_VALUE))
    }

    @Test fun captureTimeComesFirst() {
        assertEquals(1_700_000_000L, mediaTakenAt(1_700_000_000_123L, 5))
        assertEquals(5L, mediaTakenAt(0, 5))
        assertEquals(-86_400L, mediaTakenAt(-1, -86_400))
    }

    @Test fun extremeDatesSortAndGroupTheSameWay() {
        // Crafted times and a real 1970 photo: each is a separate day, in the order of the sort.
        val times = listOf(mediaTakenAt(0, 1L shl 62), mediaTakenAt(0, 0), mediaTakenAt(0, -(1L shl 62)))
        val days = times.map(::mediaDay)
        assertEquals(times.sortedDescending(), times)
        assertEquals(3, days.toSet().size)
        assertEquals(days.sortedDescending(), days)
    }
}
