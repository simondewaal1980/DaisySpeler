package nl.simon.daisyspeler.data.parser

import kotlin.math.roundToLong

object TimeParser {
  /**
   * Parses DAISY/SMIL npt times such as `npt=12.345s`, `npt=01:02.345` and
   * `npt=01:02:03.456`. Returns milliseconds.
   */
  fun parseNpt(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    var value = raw.trim()
    if (value.startsWith("npt=", ignoreCase = true)) value = value.substring(4).trim()
    if (value.endsWith("ms", ignoreCase = true)) {
      return value.dropLast(2).trim().replace(',', '.').toDoubleOrNull()?.roundToLong()
    }
    if (value.endsWith("s", ignoreCase = true)) value = value.dropLast(1).trim()
    value = value.replace(',', '.')
    if (value.isEmpty()) return null

    val parts = value.split(':')
    return when (parts.size) {
      1 -> seconds(parts[0])
      2 -> {
        val minutes = parts[0].toLongOrNull() ?: return null
        val sec = seconds(parts[1]) ?: return null
        minutes * 60_000L + sec
      }
      3 -> {
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val sec = seconds(parts[2]) ?: return null
        (hours * 3600L + minutes * 60L) * 1000L + sec
      }
      else -> null
    }
  }

  private fun seconds(value: String): Long? {
    if (value.isBlank()) return null
    val parsed = value.toDoubleOrNull() ?: return null
    if (parsed < 0) return null
    return (parsed * 1000.0).roundToLong()
  }

  fun parseClock(raw: String?): Long? {
    if (raw.isNullOrBlank()) return null
    val parts = raw.trim().replace(',', '.').split(':')
    return when (parts.size) {
      2 -> {
        val minutes = parts[0].toLongOrNull() ?: return null
        val sec = seconds(parts[1]) ?: return null
        minutes * 60_000L + sec
      }
      3 -> {
        val hours = parts[0].toLongOrNull() ?: return null
        val minutes = parts[1].toLongOrNull() ?: return null
        val sec = seconds(parts[2]) ?: return null
        (hours * 3600L + minutes * 60L) * 1000L + sec
      }
      else -> null
    }
  }
}
