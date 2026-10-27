package app.megaglass.android.core.util

import android.content.res.Resources
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.StringRes
import app.megaglass.android.R
import app.megaglass.android.core.mega.MegaException
import app.megaglass.android.core.mega.TwoFactorRequired
import app.megaglass.android.core.storage.NoSpaceException
import app.megaglass.android.core.transfers.AlreadyPreparingException
import nz.mega.sdk.MegaError
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The one mapping from an error to user text (MEGAGLASS-UX-008). The text of an app error stays;
 * SDK, system and library errors get a text from the resources. Never empty: an error without a
 * better text gets [fallback].
 */
fun Resources.userMessage(e: Throwable, @StringRes fallback: Int = R.string.error_title): String =
    appText(e) ?: getString(errorText(e) ?: fallback)

/**
 * The text of an error that the app makes, or null. SDK, system and library texts are for
 * developers: for example "Error -17" or "open failed: EACCES".
 */
internal fun appText(e: Throwable): String? {
    val own = when (e) {
        is MegaException -> !e.fromSdk
        is NoSpaceException -> false
        is AlreadyPreparingException, is TwoFactorRequired -> true
        // The app throws these with no cause. The system adds a cause, for example the errno.
        else -> e.cause == null && (e.javaClass == IOException::class.java || e.javaClass == IllegalStateException::class.java)
    }
    return e.message?.takeIf { own && it.isNotBlank() }
}

/** The string for an SDK, system or library error, or null when no string is better than the fallback. */
@StringRes
internal fun errorText(e: Throwable): Int? = when (e) {
    is MegaException -> megaErrorText(e.code)
    is NoSpaceException -> R.string.error_no_space
    is UnknownHostException, is SocketException, is SocketTimeoutException -> R.string.error_network
    is IOException -> if (e.isNoSpace()) R.string.error_no_space else R.string.error_io
    else -> null
}

/** The string for a MegaError.API_* code, or null for a code with no own text. */
@StringRes
internal fun megaErrorText(code: Int): Int? = when (code) {
    MegaError.API_EOVERQUOTA, MegaError.API_EGOINGOVERQUOTA -> R.string.error_quota
    MegaError.API_EACCESS -> R.string.error_access
    MegaError.API_ENOENT -> R.string.error_not_found
    MegaError.API_EEXIST -> R.string.error_exists
    MegaError.API_ETOOMANY, MegaError.API_ERATELIMIT, MegaError.API_ETOOMANYCONNECTIONS -> R.string.error_too_many
    MegaError.API_EAGAIN, MegaError.API_ETEMPUNAVAIL -> R.string.error_network
    MegaError.API_ESSL -> R.string.error_ssl
    MegaError.API_EREAD, MegaError.API_EWRITE -> R.string.error_io
    else -> null
}

private fun IOException.isNoSpace() = (cause as? ErrnoException)?.let { it.errno == OsConstants.ENOSPC } == true
