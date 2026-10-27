package app.megaglass.android.core.security

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReturnGraceTest {
    private var now = 1_000L
    private val grace = ReturnGrace(limitMs = 120_000, clock = { now })

    @Test fun aReturnWithoutAPickerLocks() {
        assertFalse(grace.consume())
    }

    @Test fun theGraceAppliesToOneReturnOnly() {
        grace.expect()
        grace.leave()
        now += 60_000
        assertTrue(grace.consume())
        assertFalse(grace.consume())
    }

    @Test fun anExpiredGraceStillLocks() {
        grace.expect()
        grace.leave()
        now += 120_000
        assertFalse(grace.consume())
    }

    @Test fun aPickerThatDidNotStopTheAppEndsTheGraceAtTheResume() {
        grace.expect()
        grace.leave()
        grace.resume()
        assertFalse(grace.consume())
    }

    @Test fun aPickerStartedAtTheResumeKeepsItsGrace() {
        // A settings page returns, and at the resume the app opens the folder picker.
        grace.expect()
        grace.leave()
        assertTrue(grace.consume())
        grace.expect()
        grace.resume()
        grace.leave()
        assertTrue(grace.consume())
    }
}
