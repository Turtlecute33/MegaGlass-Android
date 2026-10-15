package app.megaglass.android.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

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
