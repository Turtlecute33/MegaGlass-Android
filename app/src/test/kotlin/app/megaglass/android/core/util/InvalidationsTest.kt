package app.megaglass.android.core.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class InvalidationsTest {
    @Test fun aSlowSubscriberGetsAFullRefreshAfterOverflow() = runBlocking {
        val owner = Job()
        val scope = CoroutineScope(coroutineContext + owner)
        val flow = MutableSharedFlow<Int>(extraBufferCapacity = 1)
        val emit = invalidationEmitter(scope, flow) { -1 }
        val gate = CompletableDeferred<Unit>()
        val refreshed = CompletableDeferred<Unit>()
        val received = mutableListOf<Int>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            flow.collect {
                received += it
                if (it == 1) gate.await()
                if (it == -1) refreshed.complete(Unit)
            }
        }
        try {
            emit(1)
            yield()
            emit(2)
            repeat(100) { emit(3) }
            gate.complete(Unit)
            withTimeout(2_000) { refreshed.await() }
            assertTrue(received.contains(-1))
        } finally {
            collector.cancelAndJoin()
            owner.cancelAndJoin()
        }
    }
}
