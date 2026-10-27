package nl.simon.daisyspeler.data.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

object SleepTimerOptions {
  val MINUTES = listOf(15, 30, 45, 60, 75, 90, 105, 120)
  const val DEBUG_DURATION_MS = 10_000L
}

class SleepTimerManager(file: File) {
  private val store = JsonFileStore(file, SleepTimerData.serializer(), SleepTimerData())
  private val initial = store.read().state?.takeIf { it.deadlineEpochMs > System.currentTimeMillis() }
  private val _state = MutableStateFlow(initial)
  val state: StateFlow<SleepTimerState?> = _state.asStateFlow()

  @Synchronized
  fun start(bookId: String, durationMs: Long, now: Long = System.currentTimeMillis()) {
    val value =
      SleepTimerState(
        bookId = bookId,
        deadlineEpochMs = now + durationMs,
        durationMs = durationMs,
        startedAtEpochMs = now,
      )
    _state.value = value
    persist(SleepTimerData(value))
  }

  @Synchronized
  fun cancel() {
    _state.value = null
    persist(SleepTimerData(null))
  }

  /**
   * Writes the timer in the background. The state flow is updated immediately, so the UI and the
   * playback loop never wait for disk I/O. The single-thread executor keeps writes in order.
   */
  private fun persist(data: SleepTimerData) {
    writeExecutor.execute {
      runCatching { store.write(data) }
    }
  }

  /** Waits until all queued timer writes are done, for tests and controlled shutdown paths. */
  fun flush() {
    val latch = CountDownLatch(1)
    writeExecutor.execute { latch.countDown() }
    try {
      latch.await()
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
    }
  }

  fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
    val timer = _state.value ?: return false
    return now >= timer.deadlineEpochMs
  }

  fun remainingMs(now: Long = System.currentTimeMillis()): Long? {
    val timer = _state.value ?: return null
    return (timer.deadlineEpochMs - now).coerceAtLeast(0L)
  }

  private companion object {
    val writeExecutor =
      Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "sleep-timer-write").apply { isDaemon = true }
      }
  }
}
