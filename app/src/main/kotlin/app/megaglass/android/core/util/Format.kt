package app.megaglass.android.core.util

import android.text.format.DateUtils
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.pow

object Format {

    private val units = arrayOf("B", "KB", "MB", "GB", "TB", "PB")

    /** 1536 -> "1.5 KB". Uses 1024 steps, as MEGA does. */
    fun size(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val exp = (ln(bytes.toDouble()) / ln(1024.0)).toInt().coerceAtMost(units.size - 1)
        val value = bytes / 1024.0.pow(exp)
        return if (value >= 100) String.format(Locale.getDefault(), "%.0f %s", value, units[exp])
        else String.format(Locale.getDefault(), "%.1f %s", value, units[exp])
    }

    fun speed(bytesPerSecond: Long): String = size(bytesPerSecond) + "/s"

    /** Seconds since epoch -> "Today, 14:02", "Yesterday", "3 Oct 2026". */
    fun date(epochSeconds: Long): String {
        if (epochSeconds <= 0) return ""
        val ms = epochSeconds * 1000
        val now = System.currentTimeMillis()
        return when {
            DateUtils.isToday(ms) -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(ms))
            abs(now - ms) < DateUtils.WEEK_IN_MILLIS ->
                DateUtils.getRelativeTimeSpanString(ms, now, DateUtils.DAY_IN_MILLIS, DateUtils.FORMAT_ABBREV_ALL).toString()
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(ms))
        }
    }

    fun dateTime(epochSeconds: Long): String =
        if (epochSeconds <= 0) "" else DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(epochSeconds * 1000))

    /** 3725 -> "1:02:05", 65 -> "1:05". */
    fun duration(seconds: Long): String {
        if (seconds < 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.ROOT, "%d:%02d:%02d", h, m, s) else String.format(Locale.ROOT, "%d:%02d", m, s)
    }

    /** Remaining time for a transfer: "2 min", "45 s". */
    fun eta(remainingBytes: Long, bytesPerSecond: Long): String {
        if (bytesPerSecond <= 0) return ""
        val s = remainingBytes.coerceAtLeast(0) / bytesPerSecond
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} h ${(s % 3600) / 60} min"
        }
    }
}
