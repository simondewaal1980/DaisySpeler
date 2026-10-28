package nl.simon.daisyspeler.data.download

import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DownloadImportMonitorTest {
  @Test
  fun `check imports completed books and reports the result`() = runTest {
    val imported = AudiobookImportResult.Imported("Boek")
    val updates = mutableListOf<Result<List<AudiobookImportResult>>>()
    val monitor = DownloadImportMonitor(backgroundScope) { listOf(imported) }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      monitor.results.collect { updates += it }
    }

    monitor.check()
    runCurrent()

    assertEquals(listOf(imported), updates.single().getOrThrow())
    assertFalse(monitor.checking.value)
  }

  @Test
  fun `completion received during an import is checked immediately afterwards`() = runTest {
    val firstImport = CompletableDeferred<Unit>()
    var checks = 0
    val monitor = DownloadImportMonitor(backgroundScope) {
      checks++
      if (checks == 1) firstImport.await()
      emptyList()
    }

    monitor.check()
    runCurrent()
    assertTrue(monitor.checking.value)

    monitor.check()
    runCurrent()
    assertEquals(1, checks)

    firstImport.complete(Unit)
    runCurrent()
    assertEquals(2, checks)
    assertFalse(monitor.checking.value)
  }

  @Test
  fun `burst of completion broadcasts queues one extra pass without parallel imports`() = runTest {
    val firstImport = CompletableDeferred<Unit>()
    var checks = 0
    val monitor = DownloadImportMonitor(backgroundScope) {
      checks++
      if (checks == 1) firstImport.await()
      emptyList()
    }

    monitor.check()
    runCurrent()
    repeat(20) { monitor.check() }
    runCurrent()
    assertEquals(1, checks)

    firstImport.complete(Unit)
    runCurrent()
    assertEquals(2, checks)
  }

  @Test
  fun `leaving the browser does not cancel an import`() = runTest {
    val browserScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
    val importFinished = CompletableDeferred<Unit>()
    var imported = false
    val monitor = DownloadImportMonitor(backgroundScope) {
      importFinished.await()
      imported = true
      emptyList()
    }
    try {
      browserScope.launch { monitor.check() }
      runCurrent()
      browserScope.cancel()
      assertTrue(monitor.checking.value)

      importFinished.complete(Unit)
      runCurrent()
      assertTrue(imported)
      assertFalse(monitor.checking.value)
    } finally {
      browserScope.cancel()
    }
  }

  @Test
  fun `a failed check reports an error and does not prevent the next check`() = runTest {
    var checks = 0
    val updates = mutableListOf<Result<List<AudiobookImportResult>>>()
    val monitor = DownloadImportMonitor(backgroundScope) {
      checks++
      if (checks == 1) throw IOException("Opslag niet beschikbaar")
      listOf(AudiobookImportResult.Imported("Boek"))
    }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      monitor.results.collect { updates += it }
    }

    monitor.check()
    runCurrent()
    assertTrue(updates.single().exceptionOrNull() is IOException)
    assertFalse(monitor.checking.value)

    monitor.check()
    runCurrent()
    assertEquals(2, checks)
    assertTrue(updates.last().isSuccess)
  }

  @Test
  fun `cancelling the app scope clears checking without reporting an import failure`() = runTest {
    val appScope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))
    val updates = mutableListOf<Result<List<AudiobookImportResult>>>()
    val monitor = DownloadImportMonitor(appScope) {
      CompletableDeferred<Unit>().await()
      emptyList()
    }
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      monitor.results.collect { updates += it }
    }
    try {
      monitor.check()
      runCurrent()
      assertTrue(monitor.checking.value)

      appScope.cancel()
      runCurrent()
      assertFalse(monitor.checking.value)
      assertTrue(updates.isEmpty())
    } finally {
      appScope.cancel()
    }
  }
}
