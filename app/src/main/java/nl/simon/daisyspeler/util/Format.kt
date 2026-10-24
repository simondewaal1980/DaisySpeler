package nl.simon.daisyspeler.util

import java.util.Locale
import java.util.concurrent.TimeUnit

fun formatDuration(ms: Long): String {
  val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
  val hours = totalSeconds / 3600
  val minutes = (totalSeconds % 3600) / 60
  val seconds = totalSeconds % 60
  return if (hours > 0) {
    String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
  }
}

fun formatTimer(ms: Long): String {
  val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(ms.coerceAtLeast(0L))
  val minutes = totalSeconds / 60
  val seconds = totalSeconds % 60
  return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
}

fun formatDate(epochMs: Long): String {
  val formatter = java.text.SimpleDateFormat("d MMM HH:mm", Locale.getDefault())
  return formatter.format(java.util.Date(epochMs))
}
