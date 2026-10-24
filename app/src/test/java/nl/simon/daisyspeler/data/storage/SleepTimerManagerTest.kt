package nl.simon.daisyspeler.data.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SleepTimerManagerTest {
  @Test
  fun `options are fifteen minute steps up to two hours`() {
    assertEquals(
      listOf(15, 30, 45, 60, 75, 90, 105, 120),
      SleepTimerOptions.MINUTES,
    )
  }

  @Test
  fun `timer counts down persists and cancels`() {
    val file = Files.createTempDirectory("timer").resolve("timer.json").toFile()
    val manager = SleepTimerManager(file)
    val startedAt = System.currentTimeMillis()
    manager.start("book", 15 * 60_000L, now = startedAt)

    val state = manager.state.value
    assertNotNull(state)
    assertEquals(startedAt + 900_000L, state?.deadlineEpochMs)
    assertEquals(600_000L, manager.remainingMs(now = startedAt + 300_000L))
    assertFalse(manager.isExpired(now = startedAt + 899_999L))
    assertTrue(manager.isExpired(now = startedAt + 900_000L))

    val reloaded = SleepTimerManager(file)
    assertEquals("book", reloaded.state.value?.bookId)

    manager.cancel()
    assertNull(manager.state.value)
    assertNull(SleepTimerManager(file).state.value)
  }
}
