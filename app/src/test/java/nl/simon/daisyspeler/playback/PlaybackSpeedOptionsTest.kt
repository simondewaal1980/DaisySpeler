package nl.simon.daisyspeler.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSpeedOptionsTest {
  @Test
  fun `presets run from half to double speed in tenths`() {
    assertEquals(0.5f, PlaybackSpeedOptions.PRESETS.first(), 0.0001f)
    assertEquals(2.0f, PlaybackSpeedOptions.PRESETS.last(), 0.0001f)
    assertEquals(16, PlaybackSpeedOptions.PRESETS.size)
    assertEquals(
      PlaybackSpeedOptions.PRESETS.sorted(),
      PlaybackSpeedOptions.PRESETS,
    )
    assertTrue(PlaybackSpeedOptions.PRESETS.contains(1.0f))
  }

  @Test
  fun `sanitize clamps to the supported range`() {
    assertEquals(0.5f, PlaybackSpeedOptions.sanitize(0.1f), 0.0001f)
    assertEquals(2.0f, PlaybackSpeedOptions.sanitize(3.0f), 0.0001f)
    assertEquals(1.3f, PlaybackSpeedOptions.sanitize(1.3f), 0.0001f)
    assertEquals(
      PlaybackSpeedOptions.DEFAULT_SPEED,
      PlaybackSpeedOptions.sanitize(Float.NaN),
      0.0001f,
    )
  }

  @Test
  fun `format uses a dutch decimal comma`() {
    assertEquals("0,5×", PlaybackSpeedOptions.format(0.5f))
    assertEquals("1,0×", PlaybackSpeedOptions.format(1.0f))
    assertEquals("1,3×", PlaybackSpeedOptions.format(1.3f))
    assertEquals("2,0×", PlaybackSpeedOptions.format(2.0f))
  }
}
