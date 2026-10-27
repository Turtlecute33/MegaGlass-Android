package app.megaglass.android.core.util

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
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
        val emit = invalidationEmitter(scope, flow) { _, _ -> -1 }
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

    @Test fun theFullRefreshKeepsDetailsOfDroppedValues() = runBlocking {
        val owner = Job()
        val scope = CoroutineScope(coroutineContext + owner)
        val flow = MutableSharedFlow<Set<Int>>(extraBufferCapacity = 1)
        val emit = invalidationEmitter(scope, flow) { merged, dropped -> merged.orEmpty() + dropped + -1 }
        val gate = CompletableDeferred<Unit>()
        val refreshed = CompletableDeferred<Set<Int>>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            flow.collect {
                if (1 in it) gate.await()
                if (-1 in it) refreshed.complete(it)
            }
        }
        try {
            emit(setOf(1))
            yield()
            emit(setOf(2))
            emit(setOf(3))
            emit(setOf(4))
            gate.complete(Unit)
            val refresh = withTimeout(2_000) { refreshed.await() }
            assertTrue(refresh.containsAll(setOf(3, 4)))
        } finally {
            collector.cancelAndJoin()
            owner.cancelAndJoin()
        }
    }

    @Test fun changesWaitUntilAScreenObservesAndThenGiveOneReload() = runBlocking {
        val changes = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val observers = MutableStateFlow(0)
        val reloads = Channel<Unit>(Channel.UNLIMITED)
        suspend fun settle() = repeat(20) { yield() }
        val collector = launch { changes.whileObserved(observers).collect { reloads.send(Unit) } }
        try {
            settle()
            // Nobody looks: no reload.
            changes.emit(1)
            changes.emit(2)
            settle()
            assertTrue(reloads.tryReceive().isFailure)
            // A screen comes back: one reload for both changes.
            observers.value = 1
            settle()
            assertTrue(reloads.tryReceive().isSuccess)
            assertTrue(reloads.tryReceive().isFailure)
            // While it looks, a change reloads.
            changes.emit(3)
            settle()
            assertTrue(reloads.tryReceive().isSuccess)
            // Back again without a change: no reload.
            observers.value = 0
            settle()
            observers.value = 1
            settle()
            assertTrue(reloads.tryReceive().isFailure)
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test fun aScreenThatLooksFromTheStartReloadsOnlyOnChanges() = runBlocking {
        val changes = MutableSharedFlow<Int>(extraBufferCapacity = 16)
        val observers = MutableStateFlow(1)
        val reloads = Channel<Unit>(Channel.UNLIMITED)
        suspend fun settle() = repeat(20) { yield() }
        val collector = launch { changes.whileObserved(observers).collect { reloads.send(Unit) } }
        try {
            settle()
            assertTrue(reloads.tryReceive().isFailure)
            changes.emit(1)
            settle()
            assertTrue(reloads.tryReceive().isSuccess)
            assertTrue(reloads.tryReceive().isFailure)
        } finally {
            collector.cancelAndJoin()
        }
    }

    @Test fun theCacheKeepsAValueUntilAChange() = runBlocking {
        val cache = GenerationCache<String, Int>()
        var loads = 0
        suspend fun get(key: String) = cache.get(key) { ++loads }
        assertEquals(1, get("a"))
        assertEquals(1, get("a"))
        // Another key loads again and replaces the value.
        assertEquals(2, get("b"))
        assertEquals(3, get("a"))
        cache.invalidate()
        assertEquals(4, get("a"))
        assertEquals(4, get("a"))
        cache.clear()
        assertEquals(5, get("a"))
    }

    @Test fun aLoadThatAChangeOverlapsIsNotKept() = runBlocking {
        val cache = GenerationCache<String, Int>()
        var loads = 0
        // The change arrives while the first load runs: its result is from before the change.
        assertEquals(1, cache.get("a") { cache.invalidate(); ++loads })
        assertEquals(2, cache.get("a") { ++loads })
        assertEquals(2, cache.get("a") { ++loads })
        assertEquals(3, cache.get("b") { cache.clear(); ++loads })
        assertEquals(4, cache.get("b") { ++loads })
    }
}
