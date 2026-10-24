package nl.simon.daisyspeler.data.storage

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

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
    store.write(SleepTimerData(value))
  }

  @Synchronized
  fun cancel() {
    _state.value = null
    store.write(SleepTimerData(null))
  }

  fun isExpired(now: Long = System.currentTimeMillis()): Boolean {
    val timer = _state.value ?: return false
    return now >= timer.deadlineEpochMs
  }

  fun remainingMs(now: Long = System.currentTimeMillis()): Long? {
    val timer = _state.value ?: return null
    return (timer.deadlineEpochMs - now).coerceAtLeast(0L)
  }
}
