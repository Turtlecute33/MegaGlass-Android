package app.megaglass.android.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan
import kotlinx.coroutines.launch

/**
 * The changes, held back while [observers] is 0: no screen shows the data (a hidden tab or back
 * stack entry, or the app in the background), so a reload is wasted work. When a screen observes
 * again, the changes that it missed give one value. [observers] is the subscriptionCount of the
 * state flow that the screen collects with collectAsStateWithLifecycle.
 */
fun Flow<*>.whileObserved(observers: StateFlow<Int>): Flow<Unit> =
    combine(scan(0L) { n, _ -> n + 1 }, observers) { changes, count -> changes.takeIf { count > 0 } }
        .filterNotNull()
        .distinctUntilChanged()
        .filter { it > 0 }
        .map { }

/**
 * SDK callbacks cannot suspend. Coalesce buffer overflows into a guaranteed full refresh.
 * [fullRefresh] merges each dropped value into the refresh made so far (null for the first one),
 * so that details a full refresh cannot recover (changed thumbnails) are not lost.
 */
fun <T : Any> invalidationEmitter(scope: CoroutineScope, flow: MutableSharedFlow<T>, fullRefresh: (merged: T?, dropped: T) -> T): (T) -> Unit {
    val overflow = Channel<Unit>(Channel.CONFLATED)
    val lock = Any()
    var dropped: T? = null
    scope.launch {
        for (ignored in overflow) {
            val refresh = synchronized(lock) { dropped.also { dropped = null } } ?: continue
            flow.emit(refresh)
        }
    }
    return { value ->
        if (!flow.tryEmit(value)) {
            synchronized(lock) { dropped = fullRefresh(dropped, value) }
            overflow.trySend(Unit)
        }
    }
}

/**
 * The last value of a slow load, kept until the next [invalidate]. A load that runs while a
 * change arrives does not store its value, so the next [get] loads again.
 */
class GenerationCache<K : Any, V : Any> {
    private class Entry<K, V>(val generation: Long, val key: K, val value: V)

    private val lock = Any()
    private var generation = 0L
    private var entry: Entry<K, V>? = null

    /** The cached value is stale: the next [get] loads again. */
    fun invalidate() {
        synchronized(lock) { generation++ }
    }

    /** Removes the cached value from memory (for example, at logout). */
    fun clear() {
        synchronized(lock) { generation++; entry = null }
    }

    /** The cached value for [key], or else the result of [load]. */
    suspend fun get(key: K, load: suspend () -> V): V {
        val (current, cached) = synchronized(lock) { generation to entry }
        if (cached != null && cached.generation == current && cached.key == key) return cached.value
        val value = load()
        synchronized(lock) { if (generation == current) entry = Entry(current, key, value) }
        return value
    }
}
