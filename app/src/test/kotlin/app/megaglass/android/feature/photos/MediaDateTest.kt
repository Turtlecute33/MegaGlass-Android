package app.megaglass.android.feature.photos

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MediaDateTest {
    @Test fun invalidMetadataDoesNotCrashTheTimeline() {
        assertEquals(LocalDate.ofEpochDay(0), mediaDay(Long.MAX_VALUE))
        assertEquals(LocalDate.ofEpochDay(0), mediaDay(Long.MIN_VALUE))
    }
}
