package app.megaglass.android.core.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ResultsTest {
    @Test fun operationFailuresCanBeReported() {
        val error = IOException("Disk full")
        assertSame(error, catching<Unit> { throw error }.exceptionOrNull())
    }

    @Test fun cancellationDoesNotPublishAStaleScreenError() = runBlocking {
        var published = false
        val job = launch {
            catching { delay(10_000) }.onFailure { published = true }
        }
        kotlinx.coroutines.yield()
        job.cancelAndJoin()
        assertFalse(published)
    }

    @Test(expected = CancellationException::class)
    fun cancellationIsRethrown() {
        catching<Unit> { throw CancellationException("View was destroyed") }
    }

    @Test(expected = AssertionError::class)
    fun fatalErrorsAreNotDisguisedAsRequestFailures() {
        catching<Unit> { throw AssertionError("Fatal failure") }
    }
}
