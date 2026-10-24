package nl.simon.daisyspeler.playback

import kotlin.math.roundToInt

/**
 * Available playback speeds, from twice as slow (0.5x) to twice as fast (2.0x).
 * The list intentionally uses steps of 0.1x so the speed can be tuned per book
 * or per chapter without making the choice overwhelming.
 */
object PlaybackSpeedOptions {
  const val MIN_SPEED = 0.5f
  const val MAX_SPEED = 2.0f
  const val DEFAULT_SPEED = 1.0f

  val PRESETS: List<Float> =
    listOf(
      0.5f,
      0.6f,
      0.7f,
      0.8f,
      0.9f,
      1.0f,
      1.1f,
      1.2f,
      1.3f,
      1.4f,
      1.5f,
      1.6f,
      1.7f,
      1.8f,
      1.9f,
      2.0f,
    )

  fun sanitize(speed: Float): Float {
    if (speed.isNaN()) return DEFAULT_SPEED
    return speed.coerceIn(MIN_SPEED, MAX_SPEED)
  }

  /** Formats a speed as "0,5×" (Dutch decimal comma). */
  fun format(speed: Float): String {
    val tenths = (sanitize(speed) * 10).roundToInt()
    return "${tenths / 10},${tenths % 10}×"
  }
}
