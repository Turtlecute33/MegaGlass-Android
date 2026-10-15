package app.megaglass.android.core.util

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

/** SDK callbacks cannot suspend. Coalesce buffer overflows into a guaranteed full refresh. */
fun <T> invalidationEmitter(scope: CoroutineScope, flow: MutableSharedFlow<T>, fullRefresh: () -> T): (T) -> Unit {
    val overflow = Channel<Unit>(Channel.CONFLATED)
    scope.launch { for (ignored in overflow) flow.emit(fullRefresh()) }
    return { value -> if (!flow.tryEmit(value)) overflow.trySend(Unit); Unit }
}
