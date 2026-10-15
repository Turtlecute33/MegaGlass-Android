package app.megaglass.android.core.util

import kotlinx.coroutines.CancellationException

/** A cancelled operation must not publish a result or an error into a newer screen. */
inline fun <T> catching(block: () -> T): Result<T> = try {
    Result.success(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Result.failure(e)
}
