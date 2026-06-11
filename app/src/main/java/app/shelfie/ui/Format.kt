package app.shelfie.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatDuration(totalSeconds: Long): String {
    if (totalSeconds <= 0) return ""
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.US, "%d:%02d", minutes, seconds)
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000 -> String.format(Locale.US, "%.2f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
    bytes >= 1_000 -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}

/** Formats a listening-time total like "12h 34m" or "45m". */
fun formatListeningTime(totalSeconds: Double): String {
    val minutes = (totalSeconds / 60).toLong()
    val hours = minutes / 60
    return if (hours > 0) "${hours}h ${minutes % 60}m" else "${minutes}m"
}

fun formatDate(epochMs: Long?): String {
    if (epochMs == null || epochMs <= 0) return ""
    return SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(Date(epochMs))
}

/** Episode publish date with a fallback to the RSS pubDate string. */
fun formatEpisodeDate(publishedAt: Long?, pubDate: String?): String {
    formatDate(publishedAt).takeIf { it.isNotBlank() }?.let { return it }
    if (pubDate.isNullOrBlank()) return ""
    return runCatching {
        SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss Z", Locale.US).parse(pubDate)
            ?.let { SimpleDateFormat("MMM d, yyyy", Locale.getDefault()).format(it) }
    }.getOrNull() ?: pubDate
}

fun stripHtml(html: String?): String =
    html.orEmpty()
        .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
        .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
        .replace(Regex("<[^>]*>"), "")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&nbsp;", " ")
        .trim()
