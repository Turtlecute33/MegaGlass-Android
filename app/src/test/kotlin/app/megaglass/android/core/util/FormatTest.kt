package app.megaglass.android.core.util

import org.junit.Assert.*
import org.junit.Test

class FormatTest {
    @Test fun largeProgressDoesNotOverflow() {
        assertEquals(100, Format.percent(Long.MAX_VALUE, Long.MAX_VALUE))
        assertEquals(50, Format.percent(Long.MAX_VALUE / 2, Long.MAX_VALUE))
        assertEquals(0, Format.percent(1, 0))
        assertEquals(100, Format.percent(200, 100))
        assertEquals(0, Format.percent(-1, 100))
    }

    @Test fun remainingTimeIsNeverNegative() {
        assertEquals("0 s", Format.eta(-1, 10))
        assertEquals("", Format.eta(100, 0))
        assertEquals("2 min", Format.eta(1200, 10))
    }
}
