package app.megaglass.android.core.util

import org.junit.Assert.*
import org.junit.Test

class FormatTest {
    @Test fun remainingTimeIsNeverNegative() {
        assertEquals("0 s", Format.eta(-1, 10))
        assertEquals("", Format.eta(100, 0))
        assertEquals("2 min", Format.eta(1200, 10))
    }
}
