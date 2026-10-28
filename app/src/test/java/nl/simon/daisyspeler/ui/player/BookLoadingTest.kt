package nl.simon.daisyspeler.ui.player

import nl.simon.daisyspeler.playback.PlaybackLoadState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BookLoadingTest {
  @Test
  fun `shows loading while connecting or parsing without media for this book`() {
    assertTrue(isBookLoading(PlaybackLoadState.IDLE, hasBookMedia = false))
    assertTrue(isBookLoading(PlaybackLoadState.LOADING, hasBookMedia = false))
  }

  @Test
  fun `keeps loading while the service is preparing the book even with media present`() {
    assertTrue(isBookLoading(PlaybackLoadState.LOADING, hasBookMedia = true))
  }

  @Test
  fun `stops loading when this book is ready`() {
    assertFalse(isBookLoading(PlaybackLoadState.READY, hasBookMedia = true))
    assertFalse(isBookLoading(PlaybackLoadState.IDLE, hasBookMedia = true))
  }

  @Test
  fun `media from another book does not finish loading`() {
    assertTrue(isBookLoading(PlaybackLoadState.READY, hasBookMedia = false))
  }

  @Test
  fun `errors stop loading regardless of existing media`() {
    assertFalse(isBookLoading(PlaybackLoadState.ERROR, hasBookMedia = false))
    assertFalse(isBookLoading(PlaybackLoadState.ERROR, hasBookMedia = true))
  }
}
