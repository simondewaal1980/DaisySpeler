package nl.simon.daisyspeler.ui.player

import nl.simon.daisyspeler.playback.PlaybackLoadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BookLoadingTest {
  private fun machine(timeoutMs: Long = 1_000L, graceMs: Long = 100L) =
    PlayerLoadStateMachine(timeoutMs, graceMs)

  @Test
  fun `shows loading while connecting reading or preparing`() {
    val machine = machine()
    machine.begin("book", nowMs = 0L)
    assertTrue(machine.state.isLoading)
    assertTrue(isBookLoading(machine.state, hasBookMedia = false))

    machine.markReading()
    assertEquals(PlayerLoadPhase.READING_BOOK, machine.state.phase)
    assertTrue(isBookLoading(machine.state, hasBookMedia = false))

    machine.markPreparing()
    assertEquals(PlayerLoadPhase.PREPARING_PLAYER, machine.state.phase)
    assertTrue(isBookLoading(machine.state, hasBookMedia = false))
  }

  @Test
  fun `ready with media stops loading`() {
    val machine = machine()
    machine.begin("book", nowMs = 0L)
    machine.onServiceStatus(
      requestId = machine.state.requestId,
      loadState = PlaybackLoadState.READY,
      statusBookId = "book",
      hasBookMedia = true,
      error = null,
      nowMs = 10L,
    )

    assertTrue(machine.state.isReady)
    assertFalse(isBookLoading(machine.state, hasBookMedia = true))
  }

  @Test
  fun `ready without media is repaired once and then becomes an error`() {
    val machine = machine(timeoutMs = 10_000L, graceMs = 100L)
    machine.begin("book", nowMs = 0L)
    val requestId = machine.state.requestId

    machine.onServiceStatus(
      requestId = requestId,
      loadState = PlaybackLoadState.READY,
      statusBookId = "book",
      hasBookMedia = false,
      error = null,
      nowMs = 10L,
    )
    assertTrue("The spinner must remain visible during the grace period", machine.state.isLoading)
    assertEquals(PlayerLoadEffect.NONE, machine.tick(nowMs = 50L))

    assertEquals(PlayerLoadEffect.RELOAD_READY_BOOK, machine.tick(nowMs = 120L))
    assertTrue(machine.state.isLoading)

    machine.markPreparing()
    machine.onServiceStatus(
      requestId = requestId,
      loadState = PlaybackLoadState.READY,
      statusBookId = "book",
      hasBookMedia = false,
      error = null,
      nowMs = 200L,
    )
    assertEquals(PlayerLoadEffect.NONE, machine.tick(nowMs = 310L))
    assertTrue(machine.state.isError)
    assertFalse(machine.state.isLoading)
    assertFalse(isBookLoading(machine.state, hasBookMedia = false))
  }

  @Test
  fun `timeout ends the loading screen with a clear error`() {
    val machine = machine(timeoutMs = 1_000L, graceMs = 100L)
    machine.begin("book", nowMs = 0L)

    assertEquals(PlayerLoadEffect.NONE, machine.tick(nowMs = 999L))
    assertTrue(machine.state.isLoading)

    machine.tick(nowMs = 1_000L)
    assertTrue(machine.state.isError)
    assertFalse(machine.state.isLoading)
    assertTrue(machine.state.error!!.contains("niet op tijd"))
  }

  @Test
  fun `retry starts a new request and ignores the old answer`() {
    val machine = machine(timeoutMs = 1_000L, graceMs = 100L)
    machine.begin("book", nowMs = 0L)
    machine.tick(nowMs = 1_000L)
    val oldRequestId = machine.state.requestId
    assertTrue(machine.state.isError)

    machine.retry(nowMs = 1_100L)
    val newRequestId = machine.state.requestId
    assertTrue(newRequestId > oldRequestId)
    assertTrue(machine.state.isLoading)

    val stale =
      machine.onServiceStatus(
        requestId = oldRequestId,
        loadState = PlaybackLoadState.ERROR,
        statusBookId = "book",
        hasBookMedia = false,
        error = "oude fout",
        nowMs = 1_200L,
      )
    assertNull(stale)
    assertTrue(machine.state.isLoading)
    assertNull(machine.state.error)
  }

  @Test
  fun `status for another book is ignored`() {
    val machine = machine()
    machine.begin("book-a", nowMs = 0L)
    val requestId = machine.state.requestId

    val result =
      machine.onServiceStatus(
        requestId = requestId,
        loadState = PlaybackLoadState.READY,
        statusBookId = "book-b",
        hasBookMedia = true,
        error = null,
        nowMs = 10L,
      )

    assertNull(result)
    assertFalse(machine.state.isReady)
  }

  @Test
  fun `media availability reconciles a late ready status`() {
    val machine = machine()
    machine.begin("book", nowMs = 0L)
    assertTrue(machine.state.isLoading)

    machine.onMediaAvailability(hasBookMedia = true, nowMs = 20L)

    assertTrue(machine.state.isReady)
    assertFalse(isBookLoading(machine.state, hasBookMedia = true))
  }
}
