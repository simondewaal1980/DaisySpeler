package nl.simon.daisyspeler.data.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** App-scoped import checks. A completion received during a check schedules another pass. */
internal class DownloadImportMonitor(
  scope: CoroutineScope,
  importCompleted: suspend () -> List<AudiobookImportResult>,
) {
  private val requests = Channel<Unit>(Channel.CONFLATED)
  private val _checking = MutableStateFlow(false)
  val checking = _checking.asStateFlow()
  private val _results =
    MutableSharedFlow<Result<List<AudiobookImportResult>>>(
      extraBufferCapacity = 1,
      onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
  val results = _results.asSharedFlow()

  init {
    scope.launch {
      for (request in requests) {
        _checking.value = true
        try {
          val imported = importCompleted()
          if (imported.isNotEmpty()) _results.emit(Result.success(imported))
        } catch (cancelled: CancellationException) {
          throw cancelled
        } catch (error: Exception) {
          _results.emit(Result.failure(error))
        } finally {
          _checking.value = false
        }
      }
    }
  }

  fun check() {
    requests.trySend(Unit)
  }
}
