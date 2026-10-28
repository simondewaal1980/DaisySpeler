package nl.simon.daisyspeler.playback

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Synchronous disk operations run in request order, without blocking playback callbacks. */
@OptIn(ExperimentalCoroutinesApi::class)
internal class PlaybackStorage(
  dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
  private val onError: (String, Throwable) -> Unit,
) {
  private val scope = CoroutineScope(SupervisorJob() + dispatcher)
  private val pending = ConcurrentHashMap.newKeySet<Job>()

  fun enqueue(description: String, block: () -> Unit) {
    val job = scope.launch(start = CoroutineStart.LAZY) {
      runCatching(block).onFailure { onError(description, it) }
    }
    pending += job
    job.invokeOnCompletion { pending.remove(job) }
    job.start()
  }

  /** The caller stops producers before draining, and supplies a final position snapshot. */
  suspend fun finish(description: String, finalWrite: () -> Unit) {
    // A synchronous final write could overtake an older queued write (or even its start
    // bookmark). Queue the final snapshot on the same serial dispatcher instead.
    enqueue(description, finalWrite)
    pending.toList().forEach { it.join() }
  }

  fun cancel() = scope.cancel()
}
