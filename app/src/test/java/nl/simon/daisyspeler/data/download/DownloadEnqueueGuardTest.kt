package nl.simon.daisyspeler.data.download

import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class DownloadEnqueueGuardTest {
  private val book = DownloadIdentity("https://example.com/book.zip", "boek.zip")

  @Test
  fun `repeated clicks start the same book only once`() {
    val pending = mutableListOf<DownloadIdentity>()
    val guard = DownloadEnqueueGuard { pending.toList() }
    var starts = 0

    repeat(5) { click ->
      val result = guard.enqueue(book) {
        starts++
        pending += book
        42L
      }
      assertEquals(
        if (click == 0) DownloadEnqueueResult.Started(42L)
        else DownloadEnqueueResult.AlreadyDownloading,
        result,
      )
    }
    assertEquals(1, starts)
  }

  @Test
  fun `same filename with a new download token is blocked`() {
    assertBlocked(book.copy(url = "https://example.com/book.zip?token=new"))
  }

  @Test
  fun `same url with a different filename is blocked`() {
    assertBlocked(book.copy(fileName = "andere-naam.zip"))
  }

  @Test
  fun `another book can start while the first is pending`() {
    val guard = DownloadEnqueueGuard { listOf(book) }
    val other = DownloadIdentity("https://example.com/other.zip", "ander-boek.zip")

    assertEquals(DownloadEnqueueResult.Started(43L), guard.enqueue(other) { 43L })
  }

  @Test
  fun `failed start can be retried`() {
    val guard = DownloadEnqueueGuard { emptyList() }

    assertEquals(DownloadEnqueueResult.Failed, guard.enqueue(book) { null })
    assertEquals(DownloadEnqueueResult.Started(42L), guard.enqueue(book) { 42L })
  }

  @Test
  fun `removed transfer no longer blocks a retry`() {
    val pending = mutableListOf(book)
    val guard = DownloadEnqueueGuard { pending.toList() }

    assertEquals(DownloadEnqueueResult.AlreadyDownloading, guard.enqueue(book) { 42L })
    pending.clear()
    assertEquals(DownloadEnqueueResult.Started(42L), guard.enqueue(book) { 42L })
  }

  @Test
  fun `recreated guard checks previously registered transfers`() {
    val pending = mutableListOf<DownloadIdentity>()
    val firstGuard = DownloadEnqueueGuard { pending.toList() }
    firstGuard.enqueue(book) {
      pending += book
      42L
    }
    val recreatedGuard = DownloadEnqueueGuard { pending.toList() }

    assertEquals(
      DownloadEnqueueResult.AlreadyDownloading,
      recreatedGuard.enqueue(book) { error("Must not start a duplicate") },
    )
  }

  @Test(timeout = 10_000)
  fun `simultaneous callbacks start only one transfer`() {
    val pending = mutableListOf<DownloadIdentity>()
    val guard = DownloadEnqueueGuard { pending.toList() }
    val executor = Executors.newFixedThreadPool(8)
    val ready = CountDownLatch(1)
    var starts = 0
    try {
      val results = (1..24).map {
        executor.submit(Callable {
          ready.await()
          guard.enqueue(book) {
            starts++
            pending += book
            42L
          }
        })
      }
      ready.countDown()
      val completed = results.map { it.get(5, TimeUnit.SECONDS) }

      assertEquals(1, starts)
      assertEquals(1, completed.count { it is DownloadEnqueueResult.Started })
      assertEquals(23, completed.count { it == DownloadEnqueueResult.AlreadyDownloading })
    } finally {
      executor.shutdownNow()
    }
  }

  private fun assertBlocked(request: DownloadIdentity) {
    val guard = DownloadEnqueueGuard { listOf(book) }
    var started = false

    assertEquals(
      DownloadEnqueueResult.AlreadyDownloading,
      guard.enqueue(request) {
        started = true
        42L
      },
    )
    assertFalse(started)
  }
}
